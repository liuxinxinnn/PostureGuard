# 体态守护 PostureGuard

Android Studio 可直接打开的演示项目，已接入 MediaPipe 姿态检测与相机预览。

已实现内容：
- 体态监测入口页（可选择弯腰驼背、低头过久、久未喝水）。
- 沉浸式番茄钟页面，仅显示已进行时间，底层相机预览用于姿态检测。
- 长按屏幕退出沉浸模式。
- 震动提醒（用于体态/喝水提醒）。
- 快速测试按钮会缩短提醒间隔，方便演示。

MediaPipe 模型：
- 构建时会自动下载 `pose_landmarker_lite.task` 到 `app/src/main/assets`。
