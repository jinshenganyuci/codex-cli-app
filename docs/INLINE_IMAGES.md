# 聊天中的 SVG 与 PNG 图片

本次按 [AGENTS.md](../AGENTS.md) 先核对 ChatGPT Android 1.2026.307 的 Android 原生图片链，再处理用户提供的 SVG/PNG 失败录屏。用户的小猫截图是本客户端已有生成图片的成功状态，用于确认本次应在聊天正文里直接展示图片，而非要求另造文件卡或新的图片外观。

## Android 原生证据

复用既有图片审计，并在 Debian 定向反编译少数新类。以下行号指保存的 JADX simple 输出，不是原 Kotlin 源文件；没有用 Valdi/iOS 实现推断 Android 数值。

### 生成图和普通图片消息

- `vdo` 的 `CodexRemoteStandardMessageContent → irf1.E → kx61 MessageImageContent → m791.c` 是已经核验的共享图片链。
- `ta11.b` 为 `280dp × 350dp`；`g5f1.l` 根据解码尺寸或元数据计算比例，`w381.g` 在最大边界内布局。普通方图/竖图保留比例，不一律裁成方形；未知尺寸、缺少有效元数据时使用方形占位。
- `m791.c` 使用 Coil `AsyncImagePainter` 的加载、成功状态，并接收尺寸回调；图像生成中的占位和成功图片处于同一图片容器。
- 图片使用 `Shapes.extraLarge` token，默认 provider 的值为 `28dp`。这证明默认 token，未证明官方 App 运行时主题没有覆盖。

以上详细调用位置见既有受控归档 `image-apk-audit/apk-image-audit.txt`，以及仓库的 [参考资料入口](APK_REFERENCE.md)。

### Markdown 图片

本次新增的 Android 反编译证据：

- `p3h0.java:1320` 的 `b(...)` 接收 source、alt、消息上下文和引用映射；`:1389` 构造 `MessageMarkdownImage`，由 `i3h0` 提供实际图片内容。
- `i3h0.java:52–69` 明确最大宽 `280dp`、最大高 `ta11.a = 350dp`，再用 `g5f1.l` 根据解码尺寸计算适配；`:95–102` 通过 `n5w.b` 接入 Coil 图片，并传入点击回调和尺寸成功回调。
- source 尚未解析完成的分支在 `p3h0.java:1449–1466` 留出占位；`p3h0.d` 在静态初始化中为 `128dp`。它与生成图未知尺寸时的方形占位不是同一个分支，不混称同一原生常量。
- 另一个共享 `btf1.g`（`:925–1058`）带 `MarkdownImage` 标记，接受 source/alt，支持 `data:image...base64` 转为字节，并通过 `AsyncImagePainter` 加载。
- 这些证据证明 Markdown 图片拥有图片渲染路径，并不是只显示超链接。尚未证明普通 `[文件名](file.png)` 必须自动变成图片；普通文件链接与 `![说明](image.png)` 的语义应分别处理。

### SVG 解码

- `wzd1.java:378–426` 的 `AsyncInlineImage` 通过 `n5w.b` 使用共享图片加载器；内联来源由 `gxv0.D` 判断，再进入 `wzd1.i`。
- `wzd1.java:773–811` 明确识别 `data:image/svg+xml`，区分 base64 与文本字节；SVG 分支为请求指定 `e241` 解码工厂。
- `e241.java:28–39` 根据 MIME `image/svg+xml` 或流内容中的 `<svg` 特征选择 `f241` 解码器。
- `f241.a → bqo0` 的解码分支（`bqo0.java:146–257`）读取 SVG、提取 viewBox/文档尺寸并按请求尺寸适配，最后建立 `j241` 图片结果；其中直接使用 `com.caverock.androidsvg` 的 CSS 处理类型。SVG 并非只能作为文字或网页呈现。
- APK 的 Android DEX 同时包含 `com.caverock.androidsvg` 和其 SVG/CSS 解析异常类型。这些是 Android 解码证据，不能与另外存在的 Valdi SVG 组件混为一谈。

## 实施与验证边界

本客户端应把正文中的 Markdown 图片接到已有图片卡与查看器，PNG 继续按位图解码，SVG 使用专用矢量解析并绘制为受尺寸限制的位图；不能要求用户先手工转换成 PNG 才可在聊天中看到。实际远程读取仍通过当前连接身份对应的 SSH/SFTP，不复用其他空间的内容。

外部文件引用、脚本或不受限的资源读取不是显示 SVG 所必需的能力。解析限制、文件/像素上限与错误处理由本项目实现明确约束，不能仅因包中有 SVG 解析库就宣称所有任意 SVG 内容均能支持。

本次未运行官方 App，也未对其所有 SVG/Markdown 分支做实际设备测试。原生源码证据不能替代本客户端在目标手机上的 SVG、PNG、点击放大、重新进入聊天与错误状态验证。实施结果与实际验证见对应版本发布记录。

## 受控证据位置

- 既有本机图片证据：原始工作区 `work/image-apk-audit/`，绝对根目录见 `APK_REFERENCE.md`。
- 新定向资料：Debian 本次任务 `20261117-inline-svg-png/tmp/native-svg/`；交付时归档到同任务永久产物区，不把原包或反编译全文随公开源码分发。
- `native-svg-audit` 首轮 6 个目标中 5 个成功，`i3h0` 因 JADX 默认匿名类内联而没有单独输出；`native-svg-decode-02` 关闭匿名内联后成功取得该类及 `f241`。此工具设置问题不是 App 构建失败，也不影响已取得类的有效证据。`native-svg-final-03` 又成功取得 `bqo0/o36`，核对了 SVG 解码尺寸处理。
