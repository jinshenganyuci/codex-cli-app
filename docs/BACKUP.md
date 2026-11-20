# 备份与恢复

仓库：`https://github.com/jinshenganyuci/codex-cli-app`，当前为公开仓库。

## 已备份的范围

- Android Kotlin/Compose 源码、Gradle wrapper、固定依赖和构建/发布脚本。
- 项目专属 [AGENTS.md](../AGENTS.md) 与验证说明。
- 标签 `v0.2.7` 精确对应已经构建验证的 build24 原始 68 个源码文件；清单为 `docs/releases/0.2.7-source-manifest.json`。后续仅补充仓库文档与忽略规则。
- `v0.2.7` Release 保存已验证的正式 APK、原源码 ZIP、验证说明与 SHA-256 清单。APK 不放进 Git 源码历史。

GitHub 备份不包含 SSH 私钥、API Key、签名密钥、手机连接偏好、服务器聊天记录、用户项目文件或原 ChatGPT 安装包。完整测试证据的位置见 `APK_REFERENCE.md`。不要把整个旧工作目录或 `outputs` 打包推送；旧目录中存在私有连接材料。

0.2.9 已同步后续的免配置首装、文件下载面板和流式下载源码；GitHub Actions 从版本标签自动构建发布 APK，签名保持一致。

## 恢复代码

在 Debian 编译机执行（GitHub 认证使用凭据库临时授权，不把令牌写入远端 URL）：

```sh
git clone https://github.com/jinshenganyuci/codex-cli-app.git
cd codex-cli-app
git checkout v0.2.7
```

继续开发使用 `main` 和当前仓库工作区，源码在 Mac 编辑后单向同步 Debian；构建、测试和 GitHub 推送仍由 Debian 完成，并核对远端提交。机器上的实际工作区位置见已忽略、不得提交的 `work/local-environment.md`。用户另行配置了 GitHub Actions 自动构建发布：推送匹配版本标签后在 GitHub runner 构建，详见 [GitHub 自动发布](GITHUB_RELEASE.md)。

## 恢复构建

`./scripts/build.sh` 使用环境变量配置构建环境：`JAVA_HOME` 指向 JDK 21，`ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指向包含 Android SDK 35 的 SDK，`GRADLE_USER_HOME` 可选，用于复用 Gradle 缓存。可通过 `CODEX_MOBILE_BUILD_ENV` 载入保存在源码目录之外的私人环境配置；不要将该配置或其中的签名凭据提交到仓库。原工程固定 AGP 8.7.3、Gradle 8.9、Kotlin 2.2.10、Compose BOM 2024.12.01。编译目标为 Java/Kotlin 17。

在其他 Debian 上配置相同 JDK/SDK 后，可直接运行 `./gradlew :app:assembleDebug` 做开发检查。正式覆盖升级必须使用原签名配置：`CODEX_MOBILE_SIGNING_KEYSTORE` 指向受限签名文件，密码由 `CODEX_MOBILE_SIGNING_PASSWORD` 或指向受限密码文件的 `CODEX_MOBILE_SIGNING_PASSWORD_FILE` 提供。正式发版使用项目构建/发布脚本并递增 versionCode。

现有签名文件独立保存在 Debian 受限备份目录，实际位置只记录在本机私有环境说明中。签名凭据不会进入 Git 历史或 Release 附件；自动构建所需的原签名仅存于仓库加密 Actions secrets。丢失原签名后，重新生成的签名无法覆盖已安装的正式包。源码备份与原签名的受限备份需要分别保管。

## 0.2.7 产物核对

- 包名：`com.nanci.codexremote`
- versionCode：`10`
- Release APK：7,270,599 字节
- APK SHA-256：`a114b5d64b11c1b9d1c41d31ed5df6f5f5aba9e84972e17b6c3fec63f4d0b029`
- 原源码 ZIP SHA-256：`933c15d83c996c009756c30328fec8526a7a4d0a30e9472d583569d31c1035d9`

恢复 APK 后先用 Release 提供的校验清单验证文件，再安装。具体已测范围与已知限制见 `releases/0.2.7-verification.txt`。
