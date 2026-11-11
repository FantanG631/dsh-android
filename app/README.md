# App 源码

本目录只放**与 DSH 相关**的壳代码，不包含任何第三方聊天客户端。

## 已提供

- `src/com/dsh/phone/ProotService.java` — 前台服务：解压 rootfs、启动 proot+dsh、健康检查、token 镜像、唤醒锁
- `src/com/dsh/phone/BootReceiver.java` — 开机自启

## 需要从现有工程拷入（或自行实现）

- `MainActivity.java` — WebView 壳：加载 `127.0.0.1:3080`，401 时读取 `rootfs/tmp/dsh.log` 兑换 cookie，文件选择器，JS 对话框
- `TarGz.java` — tar/tar.gz 解压（需支持 GNU longname、pax 512 对齐、硬链接、符号链接）
- `AndroidManifest.xml` — `targetSdk=28`、`INTERNET`、`FOREGROUND_SERVICE`、`RECEIVE_BOOT_COMPLETED`、`WAKE_LOCK`、`READ_EXTERNAL_STORAGE`
- `res/` — 图标与字符串
- `assets/` — `proot.bin`、`loader`、`libtalloc.so.2`、`libandroid-shmem.so`（构建时下载，不入库）
- `build.sh` — aapt2 → javac → d8 → apksigner，**keystore 从环境变量读取**
