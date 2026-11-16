# 备份与恢复

仓库：`https://github.com/jinshenganyuci/codex-cli-app`，当前为私有仓库。

## 已备份的范围

- Android Kotlin/Compose 源码、Gradle wrapper、固定依赖和构建/发布脚本。
- 项目专属 `AGENTS.md`、`agents.me` 与验证说明。
- 标签 `v0.2.7` 精确对应已经构建验证的 build24 原始 68 个源码文件；清单为 `docs/releases/0.2.7-source-manifest.json`。后续仅补充仓库文档与忽略规则。
- `v0.2.7` Release 保存已验证的正式 APK、原源码 ZIP、验证说明与 SHA-256 清单。APK 不放进 Git 源码历史。

GitHub 备份不包含 SSH 私钥、API Key、签名密钥、手机连接偏好、服务器聊天记录、用户项目文件或原 ChatGPT 安装包。完整测试证据的位置见 `APK_REFERENCE.md`。不要把整个旧工作目录或 `outputs` 打包推送；旧目录中存在私有连接材料。

## 恢复代码

在 Debian 编译机执行（GitHub 认证使用凭据库临时授权，不把令牌写入远端 URL）：

```sh
git clone https://github.com/jinshenganyuci/codex-cli-app.git
cd codex-cli-app
git checkout v0.2.7
```

继续开发使用 `main`。Mac 当前工作区为 `/Users/nanci/Documents/ChatGPT/codex cli app`，源码在 Mac 编辑后单向同步 Debian；构建、测试和 GitHub 推送仍由 Debian 完成。备份不会自动运行，后续每个阶段提交后从 Debian 推送并核对远端提交。

## 恢复构建

`./scripts/build.sh` 依赖现有 BuildLab 环境：JDK 21、Android SDK 35、Gradle 缓存及 `/srv/buildlab/control/cache-env.sh`。原工程固定 AGP 8.7.3、Gradle 8.9、Kotlin 2.2.10、Compose BOM 2024.12.01。编译目标为 Java/Kotlin 17。

在其他 Debian 上配置相同 JDK/SDK 后，可直接运行 `./gradlew :app:assembleDebug` 做开发检查。正式覆盖升级必须使用原签名配置，以环境变量 `CODEX_MOBILE_SIGNING_KEYSTORE`、`CODEX_MOBILE_SIGNING_PASSWORD` 提供；正式发版使用项目构建/发布脚本并递增 versionCode。

现有签名文件独立保存在 Debian 受限产物目录 `/srv/buildlab/artifacts/codex-mobile/20261115-android-v01/signing/`。签名凭据不会上传到 GitHub；丢失原签名后，重新生成的签名无法覆盖已安装的正式包。源码备份与原签名的受限备份需要分别保管。

## 0.2.7 产物核对

- 包名：`com.nanci.codexremote`
- versionCode：`10`
- Release APK：7,270,599 字节
- APK SHA-256：`a114b5d64b11c1b9d1c41d31ed5df6f5f5aba9e84972e17b6c3fec63f4d0b029`
- 原源码 ZIP SHA-256：`933c15d83c996c009756c30328fec8526a7a4d0a30e9472d583569d31c1035d9`

恢复 APK 后先用 Release 提供的校验清单验证文件，再安装。具体已测范围与已知限制见 `releases/0.2.7-verification.txt`。
