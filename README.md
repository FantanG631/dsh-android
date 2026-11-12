# DSH on Android —— 手机上的通用 Agent 平台

把 [DeepSeek Harness (DSH)](https://github.com/deepseek-ai) 做成一个**普通 Android App**：
内置 proot + Linux 用户空间 + Node 运行时，点开即用、开机自启、熄屏保活，**不需要 Termux**。

定位：**手机版的 DSH 桌面端**——一个通用的 Agent 运行平台，可以装任意 DSH 插件、
MCP 工具、技能与预设；不绑定任何特定业务或聊天客户端。

- 想直接用：下载 Release 里的 APK + 运行环境包；
- 想自己构建：见 [docs/BUILD.md](docs/BUILD.md)，rootfs 可用 `scripts/build-rootfs.sh` 从零生成；
- 想了解踩过的坑：[docs/DSH-on-Android-经验与教训.md](docs/DSH-on-Android-经验与教训.md)。

## 版本

| 版本 | 内容 | 说明 |
| --- | --- | --- |
| [**v1.0.2**](../../releases/tag/v1.0.2) | APK | **当前最新**，修复文件上传与 JS 弹窗，新增 `/sdcard` 挂载 |
| [v1.0.0](../../releases/tag/v1.0.0) | APK + rootfs + 校验文件 | 首个公开版；运行环境 `rootfs-clean.tar.gz` **只在这里**，之后各版本复用 |

- **升级**：直接用新版 APK 覆盖安装即可，签名不变、数据保留，无需重新下载运行环境；
- **运行环境**只发布一次（v1.0.0），App 首次启动会自动按固定 SHA256 校验下载，
  因此后续版本的 Release 页面里看不到它，属于预期行为。


## 能力

- 前台服务内运行 proot 容器，容器里跑 `dsh --profile phone --port 3080`
- WebView 壳加载 `127.0.0.1:3080`，自动完成 token → cookie 登录
- 开机自启（BOOT_COMPLETED）、唤醒锁、崩溃自愈
- 首次启动下载约 695 MiB 并解压约 2.5 GB，之后秒开

## 已知限制（务必先读）

- **targetSdk 必须是 28**，否则 App 私有目录里的程序无法执行；因此**无法上架应用商店**，只能侧载；
- 首次启动需下载约 695 MiB（Wi-Fi 建议），解压后约 2.5 GB，建议留出 ≥4 GB 空闲空间；
- 仅支持 arm64（aarch64）设备；
- 不同厂商的省电策略不同，需要手动把 App 加入“自启动/后台白名单”。

## 仓库结构

```
app/        App 壳源码（WebView + 前台服务 + proot 启动 + TarGz 解压）
docs/       构建说明与踩坑记录
scripts/    rootfs 构建脚本与发布前敏感信息自检
```

## 快速开始

见 [docs/BUILD.md](docs/BUILD.md)。简要流程：

1. 准备 rootfs（Ubuntu arm64 + Node + DSH），打包成 `rootfs.tar` 放到 `/sdcard/Download/DSH/`；
2. 用 `app/build.sh` 编译 APK（需要 JDK、aapt2、d8、apksigner，以及你自己的 keystore）；
3. 安装 APK，首次启动自动解压并拉起服务。

## 踩坑记录

[`docs/DSH-on-Android-经验与教训.md`](docs/DSH-on-Android-经验与教训.md) 记录了 15 条硬结论
（targetSdk、PROOT_LOADER、硬链接、/dev/shm、保活等），建议先读一遍再动手。

## 不包含什么

- 任何第三方聊天客户端或其协议实现（Linux QQ、NapCat、OneBot 等）；
- 任何账号凭据、登录态、聊天记录、rootfs 压缩包、签名密钥。

## 许可证

MIT。第三方组件（proot 等）遵循其各自许可证，详见 `docs/BUILD.md` 的“许可证”一节。
