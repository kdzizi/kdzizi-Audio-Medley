# Snapnode — 极简 Snapcast 服务端（Windows 可用）

官方 Snapcast 只发布 Windows 版 **client**，没有 server 二进制。
本目录用 Node.js 实现了 Snapcast 二进制协议 v2（`SnapStreamProtocolVersion = 2`）的服务端，
实测兼容官方 **snapdroid 0.29.0.2（安卓）** 和 **snapclient 0.35.0（Windows）**。

## 文件

- `server.js` — 服务端（1704 stream / 1705 control / 1780 网页控制台）
- `control.html` — 浏览器控制台（任何设备的浏览器都能开，见下）
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

## 网页控制台（1780）

启动后浏览器打开 `http://<电脑IP>:1780/`（平板上也能开，建议"添加到主屏幕"当 App 用）：

- **正在播放**：歌名 / 歌手（从 `<同名>.json` sidecar 读，如 `{"title":"如果当时2020","artist":"许嵩 / 朱婷婷"}`）、
  可拖动进度条、暂停/继续、±10s 快进快退（`/api/seek?sec=N`，直接移动 PCM 读取位置）
- **在线设备**：名称 / 系统 / 时钟抖动 / 单台音量与静音
- **全局**：音量、全部静音、人为延迟归零
- 实时同步流**没有倍速概念**——所有设备对齐同一条时间轴，等效操作是暂停与 seek

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

## 已知现象

- 蓝牙音箱（如 JBL）链路自身延迟约 200ms，比有线/内置喇叭慢半拍。
  这是蓝牙固有问题，用 snapclient 的 `--latency <ms>` 或 App 里客户端延迟设置补偿。
- 所有设备播放同一立体声流 = 全屋同步背景音乐；要环绕声需分轨音源（见原分析）。
