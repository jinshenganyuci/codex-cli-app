# GitHub 自动构建与发布

工作流：`.github/workflows/android-release.yml`（Android build and release）。

推送 `v0.2.9` 这类版本标签后，GitHub 的 Ubuntu 24.04 runner 使用 Java 21、Android SDK 35 和 Gradle 缓存构建正式 APK、运行 Android lint，并验证包名、原有签名、版本号与标签、非 Debug/测试包及不含专用邀请。成功后才发布 GitHub Release，附带 APK、SHA256SUMS 和签名/源码提交等公开元数据。先创建草稿，附件全部上传后公开；已公开的 Release 不会被重跑覆盖。

后续发布步骤：

1. 修改 `app/build.gradle.kts` 的 `versionName` 并递增 `versionCode`，完成所需真机验证。
2. 将源码提交同步至 Debian，由 Debian 推送主分支和匹配的版本标签，例如 `v0.2.10`。
3. 在仓库 Actions 中查看运行结果；通过后可在 Releases 下载。手动 Run workflow 仅允许主分支构建验证，保留 7 天 Actions artifact，不发布 Release。

仓库需要两个 Actions secrets：`ANDROID_KEYSTORE_BASE64`（现有正式 PKCS#12 的标准 Base64）和 `ANDROID_KEYSTORE_PASSWORD`。签名别名为 `codex-mobile`。工作流把签名文件写入 runner 临时目录，构建后和失败时清理；不会放入源码、Gradle 缓存或附件。修改签名必须视作迁移，不能替换成临时签名，否则无法覆盖安装已有 App。

0.2.9 从源码在 GitHub 重新构建，APK 字节校验值可能与 Debian 交付包不同；同包名、同版本号、同签名。现有 App 的 SSH/SFTP 在线更新通道仍使用 `scripts/publish-release.py` 从 Debian 验证后发布，本工作流不持有服务器 SSH 凭据，也不自动覆盖该更新索引。GitHub CI 构建与静态检查不替代真机功能验证。

Actions 固定使用已核对的上游 commit SHA。配置参考：[GitHub Actions 发布权限](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#permissions)、[Gradle setup-gradle](https://github.com/gradle/actions/blob/main/setup-gradle/README.md)。
