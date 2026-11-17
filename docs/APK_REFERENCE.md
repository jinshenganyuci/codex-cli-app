# 界面参考与证据位置

执行界面适配前遵循根目录 `agents.me`：先核对原 Android 安装包对应组件，再结合用户截图和录屏。仓库保存我们编写的客户端及验证说明。

参考安装包：`ChatGPT_1.2026.307.apks`。原始录屏、截图、安装包和反编译文件保留在本机受控参考目录中，不作为本仓库内容分发。具体机器路径记录在已忽略、不得提交的 `work/local-environment.md`。

原始适配工作区的实际根目录见本机私有记录。其中相对路径 `outputs/两段录屏与安卓APK核对报告.txt`、`outputs/APK组件与协议核对附录.txt`、`work/image-fix-20261116/apk-image-audit.txt` 和 `work/image-apk-audit/` 是既有适配资料。移动工作区后应先核对根目录是否仍有效；这些相对路径不属于当前仓库。

0.2.7 完整构建、协议与手机证据永久保存在 Debian 受控产物区，以下为相对于该产物区根目录的归档标识：

- `codex-mobile/20261116-android-v02/build-24/`
- `codex-mobile/20261116-android-v02/phone-verification-0.2.7/`
- `codex-mobile/20261116-android-v02/composer-open-resume-20261116/`
- 逐帧覆盖与排除说明：上述最后目录的 `video-final/ARCHIVE-README.txt`。

仓库内的 `docs/releases/0.2.7-verification.txt` 是便于恢复工作的摘要。完整视频/截图含设备与聊天画面，不上传到 GitHub。早期多 Activity 测试录像明确排除为普通重连问题的因果证据。

远程文件下载的原生调用链、尺寸和行为见 [远程文件下载](REMOTE_FILES.md)。本次定向解包与两段录屏核对归档于 Debian `codex-mobile/20261116-file-download` 产物目录。
