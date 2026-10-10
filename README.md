# 手机助手（PhoneAgent）

多模型（Gemini / GPT / Claude）聊天客户端，界面仿 QQ，工具/思考折叠条仿 Claude 网页版。
模型可以通过 root 直接操作这台手机（通过 root shell 执行任意操作）。

## 构建（GitHub 手动构建发布版）
1. 把整个文件夹上传到你的 GitHub 仓库（建议私有仓库，因为里面带了签名密钥 `app/release.jks`）。
2. 打开仓库 → Actions → **Build Release APK** → **Run workflow**。（只有手动触发，不会自动构建）
3. 构建完成后，在该次运行页面底部的 Artifacts 下载 `PhoneAgent-release`，里面是已签名的 release APK。

R8 目前关闭。稳定后在 `app/build.gradle.kts` 把 `isMinifyEnabled` 和 `isShrinkResources` 改成 `true`。

## 使用
1. 安装后打开 → 左上角菜单 → 设置，填入至少一家的 API Key（模型名可改）。
2. 设置里点「测试 root」，在 root 管理器里授权。
3. 回到聊天页，输入“关闭 Wi-Fi”之类的指令。输入框左边的按钮可切换模型。

## 工具与确认
- 只有一个工具 `run_shell`，手机上的所有操作都由模型用 root shell 完成，不再为每个功能单独写工具。
- App 不做任务判断，只有一个兜底：命令里含删除操作（rm / rmdir / unlink / shred / find -delete / pm uninstall / pm clear）时，对话里弹确认卡片，点“允许”才执行（120 秒无操作视为拒绝）。其余命令一律直接执行。
- 设置里关闭“自动放行非删除命令”后，进入严格模式，所有操作都要点确认。

## 动画
所有过渡统一 350ms + 同一条曲线（`Motion` 对象在 `UiBits.kt`），流式文字按帧匀速出字。
