# 在 Android 上跑 AI Agent（DSH）：从 Termux 到自包含 App 的经验

> 一台普通 Android 手机（arm64 / Android 16），目标是让 AI Agent（Node + Python 工具链 + QQ 机器人）
> 像普通 App 一样运行：点开即用、开机自启、熄屏不掉线、**不依赖 Termux**。
> 下面是最终跑通后回看，真正值得记下来的坑和结论。

---

## 一、两条路线

| 路线 | 做法 | 成本 | 结果 |
| --- | --- | --- | --- |
| A. Termux + WebView 壳 | Termux 里跑服务，自研壳 App 加载 `127.0.0.1:3080` | 1 周 | 可用，但依赖 Termux 存活 |
| B. 自包含 App | proot + 完整 rootfs + Node + Python 全部放进 APK | 数周 | 跑通，Termux 可完全关闭 |

A 是"能用"，B 才是"普通 App"。B 的核心不是写代码，而是**一堆平台约束**。

---

## 二、硬结论（每条都是踩出来的）

### 1. `targetSdk` 必须 ≤ 28，否则私有目录里的程序不可执行

Android 10（API 29）起，`targetSdk ≥ 29` 的 App 不能 `execve()` 自己私有目录里的文件
（W^X / SELinux）。而 rootfs 里的 bash/node/proot 全在私有目录。
症状：`EACCES`，且与文件权限无关（`ls -l` 显示 755 也没用）。
代价：targetSdk 28 只能侧载，不能上架应用商店。

### 2. proot 在 App 沙箱里必须显式提供 `PROOT_LOADER`

Termux 的 proot 会去编译期写死的路径找自己的 loader；跨 App 沙箱读不到 → 任何 guest 程序
exec 都 `EACCES`（连 `/system/bin/sh` 都拒）。
解法：把 `loader` 一起打进 APK，设 `PROOT_LOADER`，另配 `PROOT_TMP_DIR` / `PROOT_L2S_DIR`。

### 3. 不要给 proot 加 `--link2symlink`（`-L`）

它会用"符号链接 + 副本"模拟硬链接，副本被清理后，文件**看着在、打开却 ENOENT**。
我们遇到的直接后果：AI Agent 保存会话时报
`ENOENT ... session.v4.jsonl.zstd`，新建会话永远失败。

### 4. f2fs 上真实的 `link()` 也可能被拒 → 需要 copy 兜底

去掉 `--link2symlink` 后，真 `link()` 在部分机型（f2fs + 沙箱）仍然 `EACCES`。
而 dsh 的**会话发布**和**附件发布**都走 `link(tmp, final)`。
解法：改存储层的 4 处 `link()` 为统一兜底——先 `link`，`EEXIST` 原样抛，
其它错误用 `copyFile(a, b, COPYFILE_EXCL)`，保持"目标不存在才发布"的语义。
（这是数据层改动，不需要重打包。）

### 5. proot 里没有 `/dev/shm`，Chromium 系程序会直接崩

Linux QQ 启动即 `FATAL: Unable to access /dev/shm`。
解法二选一：proot 加 `-b <dir>:/dev/shm`，或给 QQ 加 `--disable-dev-shm-usage`。

### 6. Termux 的 `RUN_COMMAND` 需要两个前提

`allow-external-apps=true`（改 `~/.termux/termux.properties`）**且**要在 Termux 前台执行过
`termux-reload-settings`；否则属性不生效，调用方只看到一句服务端报错。
另：从 proot 里调 Termux 的 `am`，必须带 `--user 0`，否则
`INTERACT_ACROSS_USERS`。

### 7. Android 10+ 禁止后台应用启动 Activity

Termux 在后台时 `am start` 会被系统静默丢弃（命令"成功"但什么也没发生）。
跨 App 通信改用**广播**或**本地 HTTP**；我们最终用后者（`127.0.0.1:3081` 提供登录地址）。

### 8. Web UI 的 token 是"一次性兑换券"

`?token=...` 每次进程启动都变，但它只是用来换一个 **30 天有效的签名 cookie**；
cookie 的签名密钥持久化在 `~/.dsh/.credentials.yaml`，重启服务不影响。
结论：token 只需用一次，别把带 token 的 URL 写死；换浏览器/换地址才需要重新兑换。

### 9. Termux bootstrap 的 zip 不含符号链接

Termux 官方 bootstrap 里大量 `.so` 是符号链接（`libreadline.so.8 → libreadline.so.8.3`），
单独放在 `SYMLINKS.txt` 里（格式 `目标←链接`）。只解普通文件会得到
`library "libreadline.so.8" not found`。必须按清单重建。

### 10. tar 解析的三个坑

- GNU longname（`L`）与 pax（`x`）头的 **512 字节对齐**，漏了会头部错位，解出的"文件"指向目录 → `EISDIR`；
- 归档开头的 `./` 条目剥前缀后为空名，必须跳过，否则对根目录开写；
- 硬链接（type `1`）要处理，否则大依赖树会缺文件。

### 11. 包名长度 15 字符（仅当复用 Termux 预编译产物）

Termux 二进制把 `/data/data/com.termux/files`（27 字节）烤进了 `.dynstr`，
替换串 `/data/data/<包名>/` 必须等长（12 + 15 = 27）。长度不等时是**静默失败**。

### 12. WebView 壳的几个必要处理

`onShowFileChooser`（否则附件按钮无反应）、cookie 手动 `flush()`、JS 的
`alert/confirm/prompt` 不接管会没有弹窗、`usesCleartextTraffic` 才能访问 `http://127.0.0.1`。

### 13. 保活 = 前台服务 + 唤醒锁 + 厂商白名单

缺一不可：`startForeground` 常驻通知、`PARTIAL_WAKE_LOCK`、电池优化白名单、
厂商自启动管理放行。熄屏后 Doze 会限制 CPU/网络，只有持锁的前台服务不受影响。

### 14. 开机自启

`BOOT_COMPLETED` → `startForegroundService`。目标 App 也要在厂商的自启动白名单里，
否则广播收不到。

### 15. 体积与首启

完整 rootfs（Node + Python + 依赖 + 数据）**约 5GB**，压缩后也要 1–2GB。
首启解压是分钟级，必须做"已就绪"标记 + 内容校验，否则每次启动都重解压。
注意：**用标记文件判断是否已解压时，标记本身也可能被上述硬链接问题坑掉**，
内容校验（关键文件存在性）比单标记可靠。

---

## 三、改动边界：什么需要重新打包

| 改动类型 | 例子 | 要重打包吗 |
| --- | --- | --- |
| 数据层 | 话术、prompt、Python 工具、插件、配置、数据文件 | ❌ 改完重启服务即可 |
| 原生层 | 服务逻辑、权限、开机自启、保活、内置第三方客户端 | ✅ |

这也是这套方案的实用之处：**功能迭代走数据层，壳一次做好就不用反复打包**。

---

## 四、构建链（没有 Android Studio 也能出包）

```
aapt2（资源/清单）→ javac（Java）→ d8（dex）→ zip 合并 → apksigner（签名）
```
- Termux 里有 aarch64 的 `aapt2`/`d8.jar`/`apksigner.jar`，可直接用；
- 若只有 arm64 Linux 环境，可用 `qemu-user` 跑 x86_64 的 `aapt2`（我们验证过）；
- **keystore 一定要备份**：丢了就只能卸载重装，App 数据全丢。

---

## 五、取舍建议

- 只想"在手机上用"：Termux + WebView 壳，一周内可用，别折腾自包含；
- 要"像普通 App、开机自启、不怕 Termux 被清"：接受 targetSdk 28 + 数 GB 体积 + 数周调试；
- **不要**随包分发第三方闭源客户端（如 Linux QQ）；rootfs 用脚本现场构建；
- 优先把踩坑记录写下来——同类尝试的大部分时间都花在这些"看不见的约束"上。

---

## 六、参考

- 同类开源实现（MIT）：`kai6666666/deepseek-harness`（Android host，Termux bootstrap 方案）
- 另一份 Android QQ 机器人实现（MIT）：`DeepShawn/QQBot`（内置 proot，含 QQ/NapCat）
- Termux proot（GPL-2.0）、NapCat、DSH 本体各自遵循其许可证

> 本文只记录方法与环境约束，不包含任何账号、令牌、聊天记录或第三方二进制。
