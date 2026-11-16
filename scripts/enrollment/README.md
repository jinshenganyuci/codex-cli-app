# SSH 首次激活

此目录只提供固定公钥注册器和管理员工具，不修改 Codex 模型配置、既有 SSH 公钥或已有聊天。专用安装包含一个仅能注册一台设备的一次性邀请；手机生成私钥并本地加密保存。普通 APK 不含邀请。

## 服务端安装

以下工作由任务执行者在核验服务器身份后完成。`enroll.py` 复制为 root 拥有、普通用户不可写的 `/usr/local/libexec/codex-mobile-enroll`，权限 `0755`。`admin.py` 与同版本 `enroll.py` 放在同一个 root 拥有的受限管理目录中。

每份邀请建立独立、最初锁定密码的非特权用户 `codex-join-<4至20位小写字母或数字>`。不加入任何额外组，shell 为 `/bin/sh`，home 为 root 拥有、不可由该用户写入的目录。它仅负责注册，不能浏览项目或上传文件。

逐账号配置和核验 sudoers，只有无参数执行注册器的权限：

```sudoers
codex-join-EXAMPLE ALL=(root) NOPASSWD: /usr/local/libexec/codex-mobile-enroll ""
```

`EXAMPLE` 替换成实际邀请用户的有效后缀。`sudoers` 用 `visudo -cf` 校验。不要授予 shell、解释器或带参数调用程序的额外 sudo 权限。

针对邀请账号配置 SSH：

```sshdconfig
Match User codex-join-*
    AuthenticationMethods password
    PasswordAuthentication yes
    PubkeyAuthentication no
    KbdInteractiveAuthentication no
    DisableForwarding yes
    PermitTTY no
    PermitUserRC no
    MaxSessions 1
    ForceCommand sudo -n /usr/local/libexec/codex-mobile-enroll
Match all
```

为目标账号保留原 `AuthorizedKeysFile` 条目，再增加 `/etc/ssh/codex-mobile-keys/%u`。使用针对目标账号的 `Match User codex`，避免改变其他账号。部署前读取既有有效配置，不直接假定原配置只有 `.ssh/authorized_keys`。`sshd -t` 通过后，分别核验邀请账号、目标账号及管理账号的 `sshd -T -C` 输出，再 reload SSH。持久设备公钥用 `restrict` 禁止转发等附加能力，但仍允许现有 App 所需的 exec 和 SFTP；它拥有目标 Codex 账号的聊天和文件权限。

管理员工具 `init` 创建 root 管理的状态与公钥目录；`issue` 在检查既有账号和有效 SSH 限制后，通过 stdin 为该邀请账号设置随机密码，并将邀请写入 `--output` 指定的新 `0600` JSON 文件。输出目录必须由 root 拥有且权限为 `0700`。命令 stdout 只有无秘密摘要。`issue --help` 列出公网地址、已核验指纹等参数；密码不通过参数传递，也不写入状态记录。安装文件应直接交给指定接收人，不能发布到普通更新目录或 Git。

`status --user USER` 查看无密码状态，`revoke --user USER` 先记录撤销并移除该邀请的授权公钥，再锁定激活账号。既有 SSH 会话不会因公钥撤销自动断开；不要把撤销登录资格描述为终止所有已经建立的会话。

## 协议与恢复

客户端通过固定指纹的 SSH 密码认证连接邀请账号，以任意 exec 命令触发固定注册器，发送一行 JSON 后关闭写端（EOF）：

```json
{"schemaVersion":1,"inviteId":"INVITATION_ID","publicKey":"ssh-rsa BASE64"}
```

输入最多 16 KiB，15 秒内必须结束；拒绝多行、重复字段、未知字段、弱 RSA、公钥选项及注入文本。仅接受 exponent 65537、3072 至 8192 位 RSA 的标准 SSH wire 格式。

成功返回 `{"ok":true,"inviteId":"..."}`；失败返回 `{"ok":false,"error":"invalid|claimed|expired|revoked|unavailable"}`。绑定信息在授权公钥之前持久化。不同公钥不能争用已绑定邀请，同公钥可恢复任意写入阶段；已绑定公钥的恢复不受原邀请到期影响，未绑定邀请到期后拒绝激活。撤销状态先于移除公钥持久化，重试不会复活撤销权限。

首次启动必须先保存手机生成的私钥再注册。专用包包含的是可被持有人兑换的邀请，因此转发未使用的安装包可能使其他人抢先激活；同一安装包不支持第二台手机重新绑定。

## 验证

在 Debian 编译虚拟机运行：

```sh
python3 -m unittest discover -s scripts/enrollment -v
```

单测覆盖并发抢注、幂等、断点恢复、撤销、过期和输入边界。部署还须检查真实密码认证、强制命令、转发禁用、设备新公钥连接、既有账号连接及公网更新读取；单测不代替这些真实连接检查。
