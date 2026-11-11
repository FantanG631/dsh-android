# 构建说明

## 一、App（壳）

### 依赖

- JDK 17（`javac`）
- `aapt2`、`d8.jar`、`apksigner.jar`（Android build-tools 34+）
- 你自己的 keystore

> 只有 arm64 Linux 环境时，可用 `qemu-user` 运行 x86_64 版的 `aapt2`（本项目验证过可行）。

### 生成签名密钥

```bash
keytool -genkeypair -v -keystore my.keystore -alias dsh -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass '<your-pass>' -keypass '<your-pass>' \
  -dname "CN=DSH Android, O=DSH, C=CN"
```

**务必备份**：换签名只能卸载重装，App 数据（rootfs、登录态）会全部丢失。

### 编译

```bash
cd app
KS="$PWD/my.keystore" KS_PASS='<your-pass>' ./build.sh
# 产物：app/dsh.apk
```

`AndroidManifest.xml` 中 `targetSdkVersion` **必须保持 28**。

## 二、rootfs（Linux 用户空间）

目标：一个 arm64 Ubuntu rootfs，内含 Node ≥ 24.9、Python3、DSH 及其 profile。

1. 用 `proot-distro` 或 debootstrap 建一个 Ubuntu arm64 rootfs；
2. 安装依赖：`nodejs`（≥24.9）、`python3`、`ca-certificates`、`curl`；
3. 安装 DSH 并创建 profile（例如 `phone`），确认 `dsh --profile phone --port 3080 --no-open` 能启动；
4. 清理缓存后打包：

```bash
cd /path/to/rootfs
tar cf /sdcard/Download/DSH/rootfs.tar \
  --exclude=./proc --exclude=./sys --exclude=./dev --exclude=./tmp --exclude=./run \
  --exclude=./mnt --exclude=./sdcard --exclude=./storage --exclude=./media \
  --exclude=./system --exclude=./system_vendor --exclude=./vendor --exclude=./product \
  --exclude=./odm --exclude=./apex --exclude=./data --exclude=./.l2s \
  --exclude=./var/cache ./root/.cache .
```

> 不要打成 `.gz` 再解：同一台手机上压缩只是徒增耗时；App 同时支持 tar 与 tar.gz。

## 三、首次启动

1. 把 `rootfs.tar` 放到 `/sdcard/Download/DSH/rootfs.tar`；
2. 安装 APK，打开 App；
3. 服务会解压 rootfs → 启动 dsh → WebView 自动登录；
4. 之后每次启动只需几秒（有就绪检测）。

## 四、许可证与合规

- **proot**：GPL-2.0，分发二进制需提供源码获取方式；
- **Termux bootstrap / 相关二进制**：遵循其各自许可证；
- **DSH 本体**：遵循其仓库许可证；
- 本仓库不包含、也不应包含任何第三方聊天客户端或其协议实现。

## 五、排错

| 现象 | 排查 |
| --- | --- |
| `dsh: ENOENT ... session.v4.jsonl.zstd` | proot 是否带了 `--link2symlink`；或文件系统拒绝硬链接 |
| 启动卡在“准备运行环境” | rootfs 包路径、存储权限、tar 是否完整 |
| 401 反复出现 | 服务是否把 token 写进了 `rootfs/tmp/dsh.log` |
| 熄屏后掉线 | 前台服务、唤醒锁、厂商后台白名单 |

## 六、权限与环境检查清单（发布前逐条核对）

### App 清单权限

| 权限 | 用途 | 必需 |
| --- | --- | --- |
| `INTERNET` | 下载 rootfs、访问模型 API | ✅ |
| `FOREGROUND_SERVICE` | 常驻服务跑 proot | ✅ |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 | ✅ |
| `WAKE_LOCK` | 熄屏保持运行 | ✅ |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 申请电池白名单 | ✅ |
| `READ/WRITE_EXTERNAL_STORAGE` | **不需要**：rootfs 下载到私有目录；本地导入走 SAF | ❌ |

- `targetSdkVersion` 必须保持 **28**（否则无法执行私有目录内的 proot/node）；
- 清单需 `android:usesCleartextTraffic="true"`（访问 `http://127.0.0.1`）；
- Android 14+ 对 targetSdk 28 不强制 `foregroundServiceType`，但不建议升级 targetSdk。

### 运行时环境

- 首次启动前检查空闲空间（解压后约 2.5GB，下载包约 0.7GB，建议要求 ≥4GB）；
- rootfs 内 `/etc/resolv.conf` 要写好可用 DNS（Android 不会把系统 DNS 透传给 proot）；
- rootfs 内设置 `/etc/timezone` 与 `/etc/localtime`，否则服务端时间显示为 UTC；
- **f2fs 的关键坑**：dsh 保存会话/附件使用 `link()`，在 f2fs 上会被拒（`EACCES`），
  表现为新建会话报 `ENOENT ... session.v4.jsonl.zstd`。
  必须给 `dsh-session-persistence-jsonl` 与 `dsh-attachment-local` 的四处 `link()`
  加 `copyFile(EXCL)` 兜底，或把 rootfs 放在支持硬链接的文件系统上。

### 用户侧设置（写进 README，无法代码绕过）

1. 允许安装未知来源；
2. 电池优化白名单（App 会主动弹窗申请）；
3. 厂商“自启动管理”里放行；
4. 若系统清理后台较强，把 App 锁在最近任务里。
