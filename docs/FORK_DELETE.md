# 分叉历史引用与永久删除

0.2.12 修复删除确认窗口未处理外部分叉依赖的问题。截图中的目标聊天被两条已归档的分叉引用；归档不会解除历史引用。Codex CLI 0.169.0 的原生删除会移除目标及其 spawn 子代理，但会拒绝破坏删除范围外的分叉历史。

客户端先完整分页读取普通及已归档聊天，显式包含所有来源和模型提供方，再用 `thread/read` 的 `includeTurns:false` 补齐分叉关系。不能直接相信 `thread/list.forkedFromId`：0.169.0 的列表转换明确将其置空。附属聊天按受影响父 ID 单独分页读取。

确认窗口列出会额外删除的分叉、子代理和附属聊天；用户明确确认全部后，客户端再次读取范围，范围有变化即停止并要求重新确认。通过原生 `thread/delete` 按依赖叶子到父节点依次删除，任何失败立即停止并报告已经完成的数量，不直接修改服务器数据库或历史文件。连接变化、循环引用、分页异常或无法完整读取关系均阻止执行。

如果服务器仍报告未能通过公开关系字段发现的历史引用，保留原生拒绝并显示中文原因。公开接口没有 force、detach 或 materialize 操作；本修复不声称能绕过所有历史引用保护，也不把归档当成删除。

## 原生与服务端证据

按 [AGENTS.md](../AGENTS.md) 先复核既有 ChatGPT Android 1.2026.307 定向解包资料：原生 Codex 菜单只有归档，没有对应的永久删除与分叉冲突实现。通用 Android `DeleteConfirmationDialogImpl` 只提供删除确认样式；本次依赖检查与明确列出额外聊天属于客户端补充行为。

原 APK 证据入口：`docs/APK_REFERENCE.md` 所述旧工作区 `work/delete-composer-20261116/delete-evidence.txt`，以及其中的 `delete-native/itf1.java`、`x3f1.java` 和 `r7x.java`。没有重做全量解包。

目标服务器 v0.169.0 官方源码已缓存于 Debian `20261116-android-v02/tmp/permissions/codex-rust-v0.169.0/codex-rs/`：

- `thread-store/src/local/delete_thread.rs`：删除集合外的历史引用保护。
- `app-server/src/request_processors/thread_delete.rs`：原生目标与 spawn 子代理范围。
- `app-server/src/request_processors/thread_summary.rs`：列表的分叉来源为空。
- `app-server-protocol/src/protocol/v2/thread.rs`：删除参数只有 threadId，列表分页与附属聊天过滤。

本轮只读核对目标元数据及两个归档分叉的关系，没有删除用户聊天。按用户明确要求，不运行自动化测试或真机安装测试；仅编译并做签名、版本、传输校验后发布。
