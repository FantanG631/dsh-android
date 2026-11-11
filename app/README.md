# App 源码

Android 壳层：WebView 承载 DSH 的 Web UI，前台服务里跑 proot + Linux 用户空间。
不包含任何第三方聊天客户端或其协议实现。

## 文件

| 文件 | 作用 |
| --- | --- |
| `src/com/dsh/phone/MainActivity.java` | WebView 壳：等待端口就绪、token 自动登录、文件选择器、电池白名单申请 |
| `src/com/dsh/phone/ProotService.java` | 前台服务：下载/校验/解压 rootfs，启动 proot + dsh，健康检查与自愈，token 镜像 |
| `src/com/dsh/phone/BootReceiver.java` | 开机自启 |
| `src/com/dsh/phone/TarGz.java` | tar / tar.gz 解压（GNU longname、PAX 头、符号链接、硬链接失败回退 copy） |
| `AndroidManifest.xml` | `targetSdk 28`、权限、前台服务与开机广播 |
| `res/` | 图标与字符串 |
| `assets/` | `proot.bin`、`loader`、`libtalloc.so.2`、`libandroid-shmem.so`（arm64，随仓库提供） |
| `build.sh` | aapt2 → javac → d8 → apksigner；keystore 与口令只通过环境变量传入 |

## 构建

见 [../docs/BUILD.md](../docs/BUILD.md)。

## 两个不能动的地方

1. **`targetSdk` 必须保持 28。** Android 10+ 禁止从应用私有目录执行文件，
   升到 29+ 会让 `proot.bin` / `loader` 直接起不来，表现为服务反复重启。同理，
   `targetSdk 28` 也让前台服务免于 `foregroundServiceType`、通知免于
   `POST_NOTIFICATIONS` 运行时授权。
2. **不要使用 `--link2symlink`。** 它会把硬链接改写成符号链接，破坏 dsh 会话
   持久化的硬链接语义。`PROOT_L2S_DIR` 可以设置，但不要加 `--link2symlink`。
