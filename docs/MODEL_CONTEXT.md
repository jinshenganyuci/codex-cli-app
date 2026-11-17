# 模型上下文配置记录

核对日期：2026-11-17（北京时间）。服务端及本机客户端版本：Codex CLI 0.169.0。

## 当前可选模型

以下 9 个模型来自运行中服务的 `model/list`，查询参数为 `includeHidden=false`，与 Android 客户端的查询方式一致。K 按 1,000 tokens 计算。

| 模型 ID | 原始上下文窗口 | 可用上下文窗口 |
| --- | ---: | ---: |
| `gpt-6.1-sol` | 500K | 475K |
| `gpt-6.1-terra` | 500K | 475K |
| `gpt-6-astra` | 272K | 258.4K |
| `gpt-6-sol` | 272K | 258.4K |
| `gpt-6-luna` | 272K | 258.4K |
| `gpt-5.6-sol` | 272K | 258.4K |
| `gpt-5.6-terra` | 272K | 258.4K |
| `gpt-5.6-luna` | 272K | 258.4K |
| `gpt-5.5` | 272K | 258.4K |

这些模型的有效比例均为 95%，因此 `500,000 × 95% = 475,000`，`272,000 × 95% = 258,400`。Codex 保留其余空间用于系统提示、工具开销及模型输出；运行时上报的窗口使用可用值。未另设阈值时，自动压缩阈值为原始窗口的 90%，分别为 450K 和 244.8K。[计算规则源码](https://github.com/openai/codex/blob/rust-v0.169.0/codex-rs/protocol/src/openai_models.rs#L567)

## 核对依据与差异原因

- [官方模型文档](https://learn.chatgpt.com/docs/models)明确说明：GPT-6.1 Sol 在 Codex 和 ChatGPT Work 中默认使用 500,000 tokens。
- 本机官方客户端的 `~/.codex/models_cache.json` 是另一个实测证据，最后核对的缓存时间为 `2026-11-16T16:23:25.755499Z`，客户端版本为 `0.169.0`。其中 Sol、Terra 为 500K，其余表内模型除 5.5 外均为 272K，有效比例均为 95%。这是本机收到的模型元数据，不表示官方网页逐一公布了这些数值，也不保证所有账号、服务商或后续版本相同。
- 5.5 未出现在该本机缓存中，使用 [0.169.0 官方随包目录](https://raw.githubusercontent.com/openai/codex/rust-v0.169.0/codex-rs/models-manager/models.json)核对其 272K 默认值。

本次查询时，最新正式版为 0.169.0，其随包目录以及 GitHub `main` 仍将 **Sol** 写为 272K，Terra 已为 500K。在线下发的模型元数据与客户端随包目录存在差异，因此仅升级到同一正式版本不能修正 Sol 的窗口。

第三方 provider 不会仅因为兼容 Responses API 就自动获取官方在线模型目录。0.169.0 的目录发现取决于 provider 的目录地址、认证方式及相关能力；使用 API key 的自定义 provider 若未提供 `model_catalog_url`，通常回退到随包目录。不能把这个回退值直接解释为服务商后端的实际最大能力。[目录发现条件源码](https://github.com/openai/codex/blob/rust-v0.169.0/codex-rs/model-provider/src/models_endpoint.rs#L317)

## 本次配置

使用完整的 0.169.0 官方模型目录生成 `models-current.json`，仅将 `gpt-6.1-sol.context_window` 从 `272000` 修改为 `500000`。Terra 的 500K、Astra 及其他模型的既有值保持原样。

服务端顶层配置采用以下结构；目录路径按部署位置填写：

```toml
model = "gpt-6.1-terra"
model_reasoning_effort = "xhigh"
model_catalog_json = "/absolute/path/to/models-current.json"
```

未设置全局 `model_context_window` 或 `model_auto_compact_token_limit`，以便切换模型时分别使用对应的目录数值。`max_context_window` 是配置覆盖上限，不等于默认窗口，不能据此把所有模型统一扩大。

`model_catalog_json` 在服务启动时加载，并使用静态目录；修改文件后需要协调重启相关服务才会应用。它不会自动追随官方后端的目录更新。[配置说明](https://learn.chatgpt.com/docs/config-file/config-reference)、[静态目录选择源码](https://github.com/openai/codex/blob/rust-v0.169.0/codex-rs/model-provider/src/provider.rs#L714)

## 后续升级时重新核对

1. 查询实际运行服务的完整可选模型列表，分别核对模型 ID、默认窗口、有效比例和自动压缩阈值；不要仅凭模型代际或某个模型的截图推断其他模型。
2. 对照当时的官方文档、最新版本随包目录，以及可核验的官方客户端模型缓存或 provider 原生目录。记录各证据的日期和适用范围，确认第三方 provider 对应后端的支持情况。
3. 若官方随包目录已修正 Sol，或 provider 已提供正确且完整的动态目录，评估删除这份静态覆盖；仍需静态目录时，从新版完整目录重新生成，只保留仍有依据的必要修正，避免继续使用旧模型清单和旧能力字段。
4. 修改前备份配置，保留用户指定的默认模型与推理强度；在适当时机重启服务，再核对实际模型列表和运行时窗口。不要把本记录中的数值永久写死为模型能力。
