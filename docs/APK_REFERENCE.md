# 界面参考与证据位置

执行界面适配前遵循根目录 `agents.me`：先核对原 Android 安装包对应组件，再结合用户截图和录屏。仓库保存我们编写的客户端及验证说明。

参考安装包：`/Users/nanci/Desktop/ChatGPT_1.2026.307.apks`。原始录屏、截图、安装包和反编译文件保留在用户机器/受控归档中，不作为本仓库内容分发。

原始工作区：`/Users/nanci/Documents/Codex/2026-11-15/co`。其中 `outputs/两段录屏与安卓APK核对报告.txt`、`outputs/APK组件与协议核对附录.txt`、`work/image-fix-20261116/apk-image-audit.txt` 和 `work/image-apk-audit/` 是既有适配资料。移动工作区后应先核对这些绝对位置是否仍有效。

0.2.7 完整构建、协议与手机证据永久保存在 Debian：

- `/srv/buildlab/artifacts/codex-mobile/20261116-android-v02/build-24/`
- `/srv/buildlab/artifacts/codex-mobile/20261116-android-v02/phone-verification-0.2.7/`
- `/srv/buildlab/artifacts/codex-mobile/20261116-android-v02/composer-open-resume-20261116/`
- 逐帧覆盖与排除说明：上述最后目录的 `video-final/ARCHIVE-README.txt`。

仓库内的 `docs/releases/0.2.7-verification.txt` 是便于恢复工作的摘要。完整视频/截图含设备与聊天画面，不上传到 GitHub。早期多 Activity 测试录像明确排除为普通重连问题的因果证据。
