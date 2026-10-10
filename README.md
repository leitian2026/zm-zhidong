# 手机助手（PhoneAgent）

多模型（Gemini / GPT / Claude）聊天客户端，界面仿 QQ，工具/思考折叠条仿 Claude 网页版。
模型可以通过 root 直接操作这台手机（开关 Wi-Fi/蓝牙、启动应用、执行 shell、读写文件）。

## 构建（GitHub 手动构建发布版）
1. 把整个文件夹上传到你的 GitHub 仓库（建议私有仓库，因为里面带了签名密钥 `app/release.jks`）。
2. 打开仓库 → Actions → **Build Release APK** → **Run workflow**。（只有手动触发，不会自动构建）
3. 构建完成后，在该次运行页面底部的 Artifacts 下载 `PhoneAgent-release`，里面是已签名的 release APK。

R8 目前关闭。稳定后在 `app/build.gradle.kts` 把 `isMinifyEnabled` 和 `isShrinkResources` 改成 `true`。

## 使用
1. 安装后打开 → 左上角菜单 → 设置，填入至少一家的 API Key（模型名可改）。
2. 设置里点「测试 root」，在 root 管理器里授权。
3. 回到聊天页，输入“关闭 Wi-Fi”之类的指令。输入框左边的按钮可切换模型。

## 安全分级
- 只读查询、开关设置、启动应用等：自动执行。
- 其他命令：对话里弹出确认卡片，点“允许”才执行，120 秒无操作视为拒绝。
- 可能破坏系统/不可逆的命令：卡片变红，需**长按 2 秒**才能允许。
- 设置里关闭“自动放行安全命令”后，所有操作都要确认。

## 动画
所有过渡统一 350ms + 同一条曲线（`Motion` 对象在 `UiBits.kt`），流式文字按帧匀速出字。
