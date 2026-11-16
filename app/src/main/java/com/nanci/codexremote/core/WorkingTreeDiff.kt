package com.nanci.codexremote.core

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.CompletableFuture

enum class WorkingTreeDiffBasis { NativeRemote, Head }
enum class WorkingTreeDiffStatus { AVAILABLE, NOT_REPOSITORY, NO_HEAD, TOO_LARGE, UNAVAILABLE }

data class WorkingTreeDiffResult(
    val status: WorkingTreeDiffStatus,
    val patch: String? = null,
    val basis: WorkingTreeDiffBasis? = null,
    val reason: String? = null,
)

/**
 * One complete snapshot; never aggregate it with turn/item patches.
 * The callback must be a silent, connection-guarded background RPC. Pass a
 * per-request owner guard so fallback cannot run after the host/thread changes.
 */
class WorkingTreeDiffReader(
    private val backgroundRead: (String, JSONObject) -> CompletableFuture<JSONObject>,
    private val isCurrent: () -> Boolean = { true },
) {
    fun read(cwd: String): CompletableFuture<WorkingTreeDiffResult> {
        if (!cwd.startsWith("/") || cwd.contains('\u0000')) return done(unavailable("无法确定工作区目录"))
        if (!isCurrent()) return done(unavailable("连接或聊天已变化"))
        return backgroundRead("gitDiffToRemote", jsonObject("cwd" to cwd)).handle { response, error ->
            if (error != null) null else decodeNative(response)
        }.thenCompose { native ->
            if (!isCurrent()) done(unavailable("连接或聊天已变化"))
            else if (native != null) done(native)
            else readHead(cwd)
        }
    }

    private fun decodeNative(response: JSONObject?): WorkingTreeDiffResult? {
        if (response == null || !response.has("diff") || response.isNull("diff")) return null
        val diff = response.opt("diff") as? String ?: return null
        if (diff.toByteArray(Charsets.UTF_8).size > MAX_PATCH_BYTES)
            return WorkingTreeDiffResult(WorkingTreeDiffStatus.TOO_LARGE, reason = "工作区差异过大，暂不能完整显示")
        return WorkingTreeDiffResult(WorkingTreeDiffStatus.AVAILABLE, diff, WorkingTreeDiffBasis.NativeRemote)
    }

    private fun readHead(cwd: String): CompletableFuture<WorkingTreeDiffResult> {
        if (!isCurrent()) return done(unavailable("连接或聊天已变化"))
        val params = jsonObject(
            "command" to jsonArray(listOf("python3", "-c", HEAD_PROBE)),
            "cwd" to cwd,
            "sandboxPolicy" to jsonObject("type" to "readOnly", "networkAccess" to false),
            "outputBytesCap" to OUTPUT_CAP,
            "timeoutMs" to 30000,
        )
        return backgroundRead("command/exec", params).handle { response, error ->
            if (!isCurrent()) unavailable("连接或聊天已变化")
            else if (error != null || response == null || response.optInt("exitCode", -1) != 0)
                unavailable("无法读取工作区差异")
            else decodeHead(response)
        }
    }

    private fun decodeHead(response: JSONObject): WorkingTreeDiffResult {
      return try {
        val text = response.opt("stdout") as? String ?: return unavailable("无法读取工作区差异")
        if (text.toByteArray(Charsets.UTF_8).size >= OUTPUT_CAP) return unavailable("工作区差异读取不完整")
        val obj = JSONObject(text)
        when (obj.optString("status")) {
            "available" -> {
                val encoded = obj.opt("patch") as? String ?: return unavailable("工作区差异读取不完整")
                val bytes = Base64.getDecoder().decode(encoded)
                if (bytes.size > MAX_PATCH_BYTES) return WorkingTreeDiffResult(
                    WorkingTreeDiffStatus.TOO_LARGE, reason = "工作区差异过大，暂不能完整显示")
                val patch = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                WorkingTreeDiffResult(WorkingTreeDiffStatus.AVAILABLE, patch, WorkingTreeDiffBasis.Head)
            }
            "not_repository" -> WorkingTreeDiffResult(WorkingTreeDiffStatus.NOT_REPOSITORY, reason = "当前目录不是 Git 仓库")
            "no_head" -> WorkingTreeDiffResult(WorkingTreeDiffStatus.NO_HEAD, reason = "仓库尚无可比较的提交")
            "too_large" -> WorkingTreeDiffResult(WorkingTreeDiffStatus.TOO_LARGE, reason = "工作区差异过大，暂不能完整显示")
            else -> unavailable(when (obj.optString("reason")) {
                "工作区包含暂不支持的文件类型或编码" -> "工作区包含暂不支持的文件类型或编码"
                "工作区使用 Git 内容过滤器，暂不能安全读取差异" -> "工作区使用 Git 内容过滤器，暂不能安全读取差异"
                "仓库存在未合并冲突，暂不能完整显示差异" -> "仓库存在未合并冲突，暂不能完整显示差异"
                "读取工作区差异超时" -> "读取工作区差异超时"
                else -> "无法读取工作区差异"
            })
        }
      } catch (_: Exception) {
        unavailable("工作区差异读取不完整")
      }
    }

    private fun unavailable(reason: String) = WorkingTreeDiffResult(WorkingTreeDiffStatus.UNAVAILABLE, reason = reason)
    private fun done(value: WorkingTreeDiffResult) = CompletableFuture.completedFuture(value)

    private companion object {
        const val MAX_PATCH_BYTES = 512 * 1024
        const val OUTPUT_CAP = 1024 * 1024
        // Fixed program only; cwd is a separate JSON-RPC field, never shell text.
        val HEAD_PROBE = """
            import base64, json, os, selectors, stat, subprocess, sys, time

            MAX_PATCH = 512 * 1024
            MAX_NAMES = 1024 * 1024
            MAX_FILES = 8000
            MAX_UNTRACKED = 200
            MAX_SINGLE = 4 * 1024 * 1024
            DEADLINE = time.monotonic() + 22
            GIT = ['git', '--no-optional-locks', '-c', 'core.fsmonitor=false', '--no-pager']
            env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
            env.update(GIT_OPTIONAL_LOCKS='0', GIT_TERMINAL_PROMPT='0', GIT_PAGER='cat', LC_ALL='C')

            class LimitExceeded(Exception): pass
            class DeadlineExceeded(Exception): pass
            class GitError(Exception): pass
            class UnsafeFilter(Exception): pass
            class UnsupportedPath(Exception): pass

            def run(args, limit, acceptable=(0,)):
                remaining = DEADLINE - time.monotonic()
                if remaining <= 0: raise DeadlineExceeded()
                p = subprocess.Popen(GIT + args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
                selector = selectors.DefaultSelector()
                selector.register(p.stdout, selectors.EVENT_READ, 'out')
                selector.register(p.stderr, selectors.EVENT_READ, 'err')
                out = bytearray(); err = bytearray()
                try:
                    while selector.get_map():
                        remaining = DEADLINE - time.monotonic()
                        if remaining <= 0: raise DeadlineExceeded()
                        for key, _ in selector.select(min(remaining, 0.5)):
                            block = os.read(key.fileobj.fileno(), 32768)
                            if not block:
                                selector.unregister(key.fileobj); key.fileobj.close(); continue
                            if key.data == 'out':
                                out.extend(block)
                                if len(out) > limit: raise LimitExceeded()
                            elif len(err) < 4096:
                                err.extend(block[:4096-len(err)])
                    rc = p.wait(timeout=max(0.01, DEADLINE - time.monotonic()))
                    if rc not in acceptable: raise GitError()
                    return rc, bytes(out), bytes(err)
                finally:
                    selector.close()
                    if p.poll() is None:
                        p.terminate()
                        try: p.wait(timeout=1)
                        except subprocess.TimeoutExpired: pass
                    if p.stdout and not p.stdout.closed: p.stdout.close()
                    if p.stderr and not p.stderr.closed: p.stderr.close()

            def result(status, reason=None, patch=None):
                obj={'status':status}
                if reason is not None:obj['reason']=reason
                if patch is not None:obj['patch']=base64.b64encode(patch).decode('ascii')
                print(json.dumps(obj,separators=(',',':')))

            def paths(raw):
                if not raw:return []
                if not raw.endswith(b'\0'):raise GitError()
                result=[]
                for name in raw[:-1].split(b'\0'):
                    try: text=name.decode('utf-8','strict')
                    except UnicodeDecodeError: raise UnsupportedPath()
                    if not text or text.startswith('/') or any(x == '..' for x in text.split('/')):raise UnsupportedPath()
                    result.append(text)
                return result

            def work():
                rc, inside, err = run(['rev-parse','--is-inside-work-tree'], 4096, (0,128))
                if rc!=0:
                    if b'not a git repository' in err:return result('not_repository','当前目录不是 Git 仓库')
                    return result('unavailable','无法读取 Git 工作区')
                if inside.strip()!=b'true':return result('not_repository','当前目录不是 Git 工作区')
                _, root, _ = run(['rev-parse','--show-toplevel'], 32768)
                if not root.endswith(b'\n'):raise GitError()
                try:root_text=root[:-1].decode('utf-8','strict')
                except UnicodeDecodeError:raise UnsupportedPath()
                os.chdir(root_text)
                _, conflict, _ = run(['ls-files','--unmerged','-z'], MAX_NAMES)
                if conflict:return result('unavailable','仓库存在未合并冲突，暂不能完整显示差异')
                # Prevent Git attribute-driven filter commands, which could otherwise run
                # arbitrary repository configuration even with external diffs disabled.
                _, names_raw, _ = run(['ls-files','--cached','--others','--exclude-standard','-z'], MAX_NAMES)
                all_names=paths(names_raw)
                if len(all_names)>MAX_FILES:raise LimitExceeded()
                for start in range(0,len(all_names),64):
                    batch=all_names[start:start+64]
                    _, attrs, _ = run(['check-attr','-z','filter','--']+batch, MAX_NAMES)
                    parts=attrs.split(b'\0')
                    if not parts or parts[-1]!=b'' or (len(parts)-1)%3:raise GitError()
                    for i in range(0,len(parts)-1,3):
                        if parts[i+1]!=b'filter':raise GitError()
                        if parts[i+2] not in (b'unspecified', b'unset'):raise UnsafeFilter()
                head_rc, _, _ = run(['rev-parse','--verify','-q','HEAD'],4096,(0,1))
                if head_rc==0:
                    _, patch, _ = run(['diff','--no-ext-diff','--no-textconv','--no-color','--src-prefix=a/','--dst-prefix=b/','--find-renames','HEAD','--'],MAX_PATCH)
                    _, untracked_raw, _ = run(['ls-files','--others','--exclude-standard','-z'],MAX_NAMES)
                    new_names=paths(untracked_raw)
                else:
                    patch=b'';new_names=all_names
                if len(new_names)>MAX_UNTRACKED:raise LimitExceeded()
                joined=bytearray(patch)
                for path in new_names:
                    try:st=os.lstat(path)
                    except FileNotFoundError:continue
                    if not (stat.S_ISREG(st.st_mode) or stat.S_ISLNK(st.st_mode)):raise UnsupportedPath()
                    if st.st_size>MAX_SINGLE:raise LimitExceeded()
                    _, diff, _ = run(['diff','--no-index','--no-ext-diff','--no-textconv','--no-color','--src-prefix=a/','--dst-prefix=b/','--','/dev/null',path],MAX_PATCH-len(joined),(0,1))
                    joined.extend(diff)
                # A non-UTF-8 patch cannot be displayed faithfully and must not be silently
                # converted to replacement characters or counted as a full snapshot.
                try:bytes(joined).decode('utf-8','strict')
                except UnicodeDecodeError:raise UnsupportedPath()
                return result('available',patch=bytes(joined))

            try:
                work()
            except LimitExceeded:
                result('too_large','工作区差异过大，暂不能完整显示')
            except DeadlineExceeded:
                result('unavailable','读取工作区差异超时')
            except UnsafeFilter:
                result('unavailable','工作区使用 Git 内容过滤器，暂不能安全读取差异')
            except UnsupportedPath:
                result('unavailable','工作区包含暂不支持的文件类型或编码')
            except (GitError, OSError, ValueError, subprocess.SubprocessError):
                result('unavailable','无法读取 Git 工作区差异')
        """.trimIndent()
    }
}
