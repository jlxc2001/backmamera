# A4L Screen AI Overlay Probe

这是给 JLXC 的第三方安卓车机做的验证 Demo：

- Android 10
- 32 位处理器可用
- 不直接抢 Camera2 / AHD 输入
- 使用 MediaProjection 录屏方式取帧
- 使用悬浮窗叠加模拟 AI 检测框
- 每秒保存一张截图，验证能否抓到前置影像画面

## GitHub 在线编译

仓库根目录应为：

```text
.github/
app/
build.gradle
settings.gradle
README.md
```

进入 GitHub：

```text
Actions → Android CI → Run workflow
```

编译成功后下载：

```text
A4LScreenAIOverlay-debug-apk
```

## 车机测试方法

1. 安装 APK
2. 打开 App
3. 点“开始录屏 + 悬浮窗，然后自动打开前置影像”
4. 等 5-10 秒
5. 查看前置影像界面上是否出现悬浮检测框
6. 用 ADB 拉取截图：

```bash
adb pull /sdcard/Android/data/com.jlxc.a4lscreenai/files/Pictures/
```

如果截图里能看到前置影像画面，下一步就可以接 AI 检测模型。
