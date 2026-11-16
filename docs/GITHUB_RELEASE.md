# GitHub 自动构建、发布与公开版更新

工作流：`.github/workflows/android-release.yml`（Android build and release）。每小时第 17 分钟检查 `main`（UTC 表达式 `17 * * * *`，避开整点；GitHub 调度可能延迟）。发现新的版本号后自动创建/核对版本标签、构建并发布。版本已存在于公开 Release 时，只完成检查，跳过 Android 环境、构建和发布，不会重复发同一版本。

后续发布只需：

1. 修改 `app/build.gradle.kts` 的 `versionName`，递增 `versionCode` 并完成必要的功能验证。
2. 将源码提交同步到 Debian，再由 Debian 推送 `main`。下一次小时检查自动处理新版本。
3. 要立即处理，可在 Actions 中对 `main` 点 **Run workflow**。手推 `v0.2.10` 这样的标签仍可立即触发同一流程。

准备阶段固定本次读取到的源码提交。新标签必须精确指向该提交；旧标签指向不同提交时会停止，不覆盖标签。`versionCode` 必须大于所有已公开版本；重新发布应继续提升版本号。通过 GitHub Token 创建标签后，同一次运行直接执行构建，不依赖标签事件再次触发。整个工作流使用一个并发组，定时检查、手动检查和标签事件不会同时发布。

GitHub Ubuntu 24.04 runner 使用 Java 21、Android SDK 35 和 Gradle 缓存，以 `CODEX_MOBILE_PUBLIC_RELEASE=1` 构建正式 APK、运行 Android lint，并验证包名、原有签名、版本号与标签、源码提交、公开更新通道标记及不含专用邀请/Debug/测试入口。成功后先创建草稿，APK、SHA256SUMS、release-metadata.json 全部上传后才公开。已公开 Release 不会被重跑覆盖；尚未完成的同源码草稿允许重试。构建失败可修复源码后递增版本，或在源码未变时重跑。

公开 APK 从 GitHub Releases 检查和下载更新，无需连接 SSH 服务器。元数据包含 `schemaVersion: 1`、`updateChannel: github`、版本、最低 Android API、SHA-256、大小、签名摘要、源码提交和更新说明；实际下载 URL 来自对应 Release 的附件。0.2.9 历史 Release 保留，不回写。默认私有构建仍走现有 SSH/SFTP 更新通道，由 Debian 的 `scripts/publish-release.py` 验证发布；GitHub 工作流不持有服务器 SSH 凭据，也不修改该索引。

仓库 Actions secrets 为 `ANDROID_KEYSTORE_BASE64`（现有正式 PKCS#12 的标准 Base64）和 `ANDROID_KEYSTORE_PASSWORD`，签名别名 `codex-mobile`。签名文件只写入 runner 临时目录，成功/失败均清理，不进入源码、Gradle缓存或附件。Actions artifact 保留 7 天；GitHub Release 附件持续保留。

CI 构建与静态检查不替代真机功能验证。Actions 固定使用上游 commit SHA。参考：[GitHub 定时工作流](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)、[工作流权限](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#permissions)。
