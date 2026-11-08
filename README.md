# 视频提取 · VideoExtractor

一个把公开分享链接保存为 MP4 的 Android 应用。解析、下载和 B 站音视频合轨都在手机上完成。

## 安装

需要 Android 10 或更新版本。从 [GitHub Releases](https://github.com/HappyGame137/VideoExtractor-Android/releases/latest) 下载 `VideoExtractor-1.0.apk` 并安装。此前安装过内部测试包的设备需要先卸载旧包，才能安装使用发布签名的版本。

## 使用

1. 在原应用复制分享链接或整段分享文案，粘贴到「视频提取」。也可以从系统分享菜单选择「视频提取」。
2. 点「提取并保存」。完成后可以播放或分享，文件也能在系统相册的「视频提取」目录中找到。
3. 单个视频最多 1 GB。支持 B 站普通视频（含短链、BV/AV 号和分 P）与 HTTPS MP4 直链。抖音和小红书的公开分享页也可尝试提取，成功与否取决于平台是否返回播放资源。遇到访问限制时，可从原作品重新复制完整链接。

## 从源码构建

安装 JDK 17 和 Android SDK Platform 35，设置 `ANDROID_HOME` 或用 Android Studio 打开仓库根目录。

```bash
./gradlew :videoextractor:assembleDebug :videoextractor:testDebugUnitTest :videoextractor:lintDebug
```

Windows 使用 `gradlew.bat`。调试 APK 位于 `videoextractor/build/outputs/apk/debug/`。构建未签名的发布 APK 时运行 `./gradlew :videoextractor:assembleRelease`，输出位于 `videoextractor/build/outputs/apk/release/`。
