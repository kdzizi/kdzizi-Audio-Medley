# Snapnode — 极简 Snapcast 服务端（Windows 可用）

官方 Snapcast 只发布 Windows 版 **client**，没有 server 二进制。
本目录用 Node.js 实现了 Snapcast 二进制协议 v2（`SnapStreamProtocolVersion = 2`）的服务端，
实测兼容官方 **snapdroid 0.29.0.2（安卓）** 和 **snapclient 0.35.0（Windows）**。

## 文件

- `server.js` — 服务端（1704 stream / 1705 control / 1780 网页控制台）
- `control.html` — 浏览器控制台（任何设备的浏览器都能开，见下）
- `mdns.js` — 零依赖 mDNS/DNS-SD 广播（发布 `_snapcast._tcp`，供客户端自动发现）
- `test-mdns.js` — 发现自检：模拟客户端发 PTR/SRV/TXT/A 查询，看服务端答了什么
- `start-server.bat` — 双击启动（默认播 `../ncm.wav`，也可拖一个 wav 到图标上）
- `start-pc-client.bat` — 双击启动 PC 端 snapclient（默认 `--latency 200` 补蓝牙延迟）
- `gen-wav.js` — 生成同步测试音频（48kHz/16bit/立体声，每秒左右交替 click + 每 4 秒强拍 + 和弦垫）
- `sync-test.wav` — 生成的测试音，30 秒循环播放
- `../song.mp3` / `../song.wav` — 真实歌曲测试素材（SoundHelix-Song-1，6 分 12 秒，44.1kHz 立体声）

## 用法

```bash
# 1. 生成测试音（可选，已生成好）
node gen-wav.js

# 2. 启动服务端（默认端口 1704/1705/1780）
node server.js [wav路径] [端口]

# 播放真实歌曲（mp3 先用 ffmpeg 转 wav）：
# ../ffmpeg/ffmpeg.exe -y -i song.mp3 -ar 44100 -ac 2 -sample_fmt s16 -f wav song.wav
node server.js ../song.wav 1704
```

平板装 Snapcast App（snapdroid），设置 → Host 填电脑 IP → 点播放。
PC 装 snapclient.exe：`snapclient.exe tcp://<电脑IP>:1704`

## 自动发现（不用每换一次网络就手填 host）

服务端启动后会**发布 mDNS 服务** `_snapcast._tcp`（+ `_snapcast-jsonrpc` 1705、`_snapcast-http` 1780），
实例名 `Snapcast`——和官方 snapserver 一致。客户端侧的自动查找逻辑（新版 APK）：

1. **mDNS 先搜**（`NsdHelper`，3 秒）
2. 没结果 → **并发扫描局域网**：探测本机子网的 1704 端口，谁开着谁是 Snapserver
   （`/24` 子网 254 个地址、64 并发、400ms 超时，实测 0.4 秒内出结果）
3. 找到后自动写入 host 并连接；**旧 host 连不上**（换热点/服务端没开）也会自动重找

为什么两套都要：

- mDNS 是标准做法，但在**手机热点 / 开了 AP 隔离的路由器**上组播常被禁，扫不到；
- 另外 Windows 上 5353 端口常被 Bonjour、浏览器等多个进程共享占用，
  组播查询包不一定投递到本进程的 socket（实测多次收不到），应答就发不出去。
  所以扫描兜底才是真正稳定的那条路。

自检：`node test-mdns.js` 会模拟客户端发一轮查询并打印服务端应答；
服务端日志里 `[mdns ] query from x.x.x.x` 表示确实收到了查询。

> 未握手的 TCP 连接（扫描探针）不会进在线设备列表——`hello` 之后才算在线。

## 网页控制台（1780）

启动后浏览器打开 `http://<电脑IP>:1780/`（平板上也能开，建议"添加到主屏幕"当 App 用）：

- **正在播放**：歌名 / 歌手（从 `<同名>.json` sidecar 读，如 `{"title":"如果当时2020","artist":"许嵩 / 朱婷婷"}`）、
  可拖动进度条、暂停/继续、±10s 快进快退（`/api/seek?sec=N`，直接移动 PCM 读取位置）
- **在线设备**：名称 / 系统 / 时钟抖动 / 单台音量与静音
- **全局**：音量、全部静音、人为延迟归零
- 实时同步流**没有倍速概念**——所有设备对齐同一条时间轴，等效操作是暂停与 seek

## 服务端角色：同一局域网只能有一个「源」

PC 的 `server.js` 和手机里的 `ServerService` 是**同构**的（都是 1704 音频 + 1780 控制），
两个同时跑 = 局域网出现两个源，客户端会连错人。所以引入了明确的角色模型：

| 角色 | 含义 |
|---|---|
| `server` | 自己在放流，是时钟基准 |
| `follower` | 已把播放权交出去；本机 1780 控制台**透明代理**到真正放流的那台（API 同构，直接反代） |

自动化行为：

- **启动自动退让**：启动时先扫一遍局域网 1704，已有服务端就不抢，自动当 follower
  （`SNAPNODE_NO_FOLLOW=1` 可关掉）
- **一键接管**：新服务端（手机/另一台 PC）调旧服务端的
  `GET /api/handover?to=<新IP>&http=1780` → 拿到当前曲目与进度 → 旧的断开自己的客户端并停流
  → 客户端靠已有的自动发现改连到新的（断流 1~2 秒）→ 旧的转 follower
- **收回**：`GET /api/resume` 本机重新变成 server；`GET /api/follow?to=<ip>` 手动跟随某台

| 接口 | 用途 |
|---|---|
| `GET /api/who` | 本机角色：`{role:'server'|'follower', upstream, host, rev}` |
| `GET /api/events` | **SSE 状态推送**：状态一变推一帧，多端中控实时一致（follower 时自动订阅上游） |
| `GET /api/handover?to=&http=` | 被接管：返回当前 media（曲目+进度）后退位 |
| `GET /api/resume` / `GET /api/follow?to=` | 收回播放权 / 手动跟随 |

多端中控**不需要互斥开关**：它们都只是对同一个服务端发命令，靠 `/api/events` 保持同步即可。
真正要互斥的是"谁是源"，由上面的 handover 流程保证。

## 蓝牙音箱延迟补偿

蓝牙链路自带 150~250ms 缓冲（JBL Flip 7 实测约 200ms），会让接蓝牙的那台设备慢半拍。
这不是同步机制的误差，用 snapclient 的 `--latency` 参数补偿：

```bash
snapclient.exe tcp://<server>:1704 --latency 200   # 告诉客户端输出链路还有200ms，它会提前送数据
```

数值需要耳朵微调：仍慢就加大，快了就减小。平板 App 内对应"设置 → 客户端延迟"。
补偿后如果平板和蓝牙音箱人声重合成一轨，就说明对上了。

## 架构说明：Snapcast 是严格的中心化架构——`snapserver` 是唯一的音频源 + 时钟基准，
所有 client 是"哑终端"（只收流、按统一时间戳播放，无决策权）。
但"中心"只有音频平面这一个；**控制平面是任意的**：控制台就是一个网页，
手机、平板、任何电脑的浏览器都能开，不需要在每台设备上装控制软件。
服务端本身也可以跑在任何一台常开的设备上（PC / 树莓派 / NAS / 安卓 Termux）。

## 实现要点（踩坑记录）

1. **时间戳必须用单调时钟**（进程/开机时间），不能用 Unix 时间。
   客户端算播放时刻 = `chunk时间戳 + 时钟差`，各端 tv.sec 是 int32，
   用 Unix 时间会导致 `±1.79e9 + 1.79e9` 溢出，客户端反复断连。
   协议里 c2s/s2c 各测一次再相减除二，时钟起点任意都自洽。
2. **ServerSettings 的 `refersTo` 必须等于 Hello 请求的 id**，
   客户端按此匹配请求，2 秒匹配不上就 `hello request timed out` 断连重连。
3. 消息 id（uint16）要做 `% 65536` 回绕，否则服务端自己崩。
4. 客户端消息 sent 时间戳是**客户端自己的单调时钟**（Android=开机时间，
   Windows=Unix 时间），服务端回 Time 时 `latency = 本端接收时刻 - 对端 sent` 即可。
5. 协议文档：snapcast 仓库 `doc/binary_protocol.md`（v0.29 与 v0.35 相同的 v2 协议）。

## 如何量化验证"没有延迟"

耳朵只能听个大概，真正的证据在 snapclient 自己的统计里。Snapcast App 跑 native 的
`libsnapclient.so`，每秒打一条 Stats（`adb logcat -s Main | grep Stats`）：

```
Chunk: <A age> <B miniMedian> <C shortMedian> <D median> <E bufferSize> <F dacTime> <G frameDelta>
```

源码位置：`client/stream.cpp`（数值 /100 = ms）。关键列：

- **A 瞬时偏差**：当前音频块相对应播放时刻的偏差
- **D 长窗中位偏差**：**核心同步精度指标**，稳定就等于同步良好
- F DAC 缓冲：播放链路固有延迟（~90ms），**不是同步误差**，所有设备一致所以不影响同步

用 `analyze.py` 可直接统计（2026-10-05 实测，许嵩《如果当时2020》，平板 + 电脑）：

| 指标 | 实测 | 人耳阈值 |
|---|---|---|
| 瞬时偏差 A | 0 ~ 2 ms | >10ms 才发虚 |
| 同步精度 D | 0 ~ 1 ms | >30ms 才出回声 |
| 服务端侧时钟抖动 | 0.1 ~ 1.1 ms | — |

`>10ms` 的采样 0 次 —— 也就是说，实测误差比"能听出来"的门槛低一个数量级以上。

## 同步听诊（对照实验，默认关闭）

控制台每台设备右侧的「+Nms」滑块 = 人为给该设备注入播放延迟。

- 加到 **+30ms** 以上：人耳开始听出重影/拖尾
- 加到 **+80ms**：明显像两个人错开在唱（实测：客户端 stats 末列立刻跳到 -23 表示检测到时间跳变）
- **实测发现**：加了固定偏移后，snapclient 会在 **几十秒内自动追平**（末列 -23 → -7 → 0），
  说明它的时钟同步闭环真的在工作——持续测量偏差并微调播放速率。归零后声音重新合成一个干净人声。
  这是验证"系统确实在同步"最直接的 A/B 对照。

## 安卓 App 二次开发（snapdroid-app/）

`../snapdroid-app/` 是 snapdroid 0.29.0.2 的完整可编译工程，新增**中控台视图**（与 Web 控制台并存）：

- `ControlActivity.java` — 中控：正在播放（歌名/歌手/可拖进度）、暂停/±10s、EQ 预设、
  在线设备（单台音量/静音/声道角色 全频·低音炮·中高频），1s 轮询 `/api/status`
- 入口：主界面菜单 → **中控台**；host 与主界面设置共享，控制台端口固定 1780
- `AndroidManifest.xml` 开了 `usesCleartextTraffic`（明文 HTTP 必需）

### 编译（Android Studio）

1. Studio 打开 `snapdroid-app/` 目录（首次 sync 自动生成 `local.properties`）
2. **GitHub Packages 凭证**：oboe/boost/flac/opus 等 native AAR 托管在
   `maven.pkg.github.com/badaix/snapcast-deps`，需要 PAT（勾 `read:packages`）。
   填在 `gradle.properties` 的 `GITHUB_USER` / `GITHUB_TOKEN`，或环境变量
3. SDK 组件：compileSdk 35 + build-tools 35.0.0 + **NDK 27.2.12479018** + **CMake 3.22.1**
   （工程含 native C++ client，缺什么 Studio 会提示）
4. 构建配置已做国内适配：Gradle wrapper 8.11.1 **-all** 走腾讯云镜像、Maven 走阿里云
5. `Build → Build APK(s)` 产物：`Snapcast/build/outputs/apk/debug/`

### 与原版 snapdroid 的差异（全部可 grep `snapnode` 定位）

| 文件 | 改动 |
|---|---|
| `ControlActivity.java`（新增） | 中控视图本体 |
| `LanScanner.java`（新增） | 局域网扫描兜底：并发探测子网的 1704，mDNS 不通时用它自动找服务端 |
| `MainActivity.java` | 自动发现编排：mDNS 3 秒无果→扫描；旧 host 连不上→自动重找；resolve 用 IP 不用反解主机名 |
| `res/layout/activity_control.xml`、`item_control_client.xml`（新增） | 中控布局 |
| `AndroidManifest.xml` | 注册 ControlActivity + usesCleartextTraffic |
| `menu_snapcast.xml` / `MainActivity.java` | 新增"中控台"菜单入口 |
| `strings.xml` | action_control / title_activity_control |
| `gradle-wrapper.properties` / `build.gradle` / `gradle.properties` | 国内镜像 + 凭证占位 |

## 已知现象

- 蓝牙音箱（如 JBL）链路自身延迟约 200ms，比有线/内置喇叭慢半拍。
  这是蓝牙固有问题，用 snapclient 的 `--latency <ms>` 或 App 里客户端延迟设置补偿。
- 所有设备播放同一立体声流 = 全屋同步背景音乐；要环绕声需分轨音源（见原分析）。
