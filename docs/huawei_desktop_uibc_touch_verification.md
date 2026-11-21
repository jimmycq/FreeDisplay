# 华为电脑模式副屏触控（UIBC）验证记录

- 日期：2026-11-16
- 设备：Nokia N1（Android 5.1），ADB `192.168.1.101:5555`
- 源端：华为 nova 12 活力版，电脑模式 / Miracast P2P
- 目标：华为手机电脑模式投屏到 N1 后，直接在 N1 副屏点击、移动光标，达到“点哪里点哪里”

## 1. 最终结论

> **副屏触控目标尚未达成。** 当前只是 UIBC 链路层稳定，华为端没有注入输入反馈，不能视为触控已修好。

当前证据表明：

1. **N1 侧 UIBC 连接、RTSP 协商、TCP 写入链路已经打通。**
2. **HIDC 与 GENERIC 两大类报文都做过真机验证，报文能被持续写入 TCP 连接，不再因格式被立即拒绝。**
3. **华为电脑模式没有把 N1 送来的 UIBC 输入事件注入到系统。**

最决定性现象：

- 使用华为手机自身触控板时，电脑模式界面会出现并可移动圆点光标；
- 在 N1 副屏点击时，日志显示 DOWN/UP 报文正确发出、连接稳定，但华为端的圆点光标不移动、也没有点击反馈。

因此当前问题不应继续按“IP/端口/校验和/包长/坐标缩放”方向盲改，优先判断为 **华为电脑模式 Source 侧输入注入策略/固件限制**。

## 2. 真机协商信息

华为 M4 中实际出现的 UIBC 能力：

```text
wfd_uibc_capability:
  input_category_list=HIDC;
  generic_cap_list=Keyboard, Mouse;
  hidc_cap_list=Keyboard/USB, Mouse/USB;
  port=xxxxx
```

解读：

- `input_category_list=HIDC` 是严格声明的输入类别；
- `generic_cap_list=Keyboard, Mouse` 表示具备 GENERIC 鼠标能力；
- 但在实践中，Sink 主动选择 GENERIC Mouse 后，虽然 TCP 连接保持，华为仍未注入事件；
- HIDC 相对鼠标曾按规范格式稳定发送，同样没有移动光标。

P2P 地址：

```text
手机 / GO: 192.168.49.1
N1 / GC:   192.168.49.213
```

`192.168.49.1` 是从 Socket 对端/M4 得到的真实地址，不是导致触控失败的原因。

## 3. 已修复的非触控问题

### 3.1 切换设备时旧连接未断开

已处理：

- `MiracastRtspServer` 维护所有 RTSP client socket，TEARDOWN 时全部发送并关闭；
- AirPlay `ControlServer` 使用 `ChannelGroup` 跟踪并强制关闭所有 Mac 客户端连接；
- 切换/拒绝时都有 5 秒抑制窗口，防止旧源自动重连抢画面；
- Miracast 侧同时停止 UIBC、丢弃残留 UDP 包、移除 P2P group。

涉及文件：

- `app/src/main/java/com/n1tools/box/ui/AirPlayDisplayActivity.kt`
- `app/src/main/java/com/n1tools/box/miracast/MiracastServer.kt`
- `app/src/main/java/com/n1tools/box/miracast/rtsp/MiracastRtspServer.kt`
- `app/src/main/java/com/n1tools/box/miracast/rtp/MpegTsReceiver.kt`
- `app/src/main/java/com/n1tools/box/miracast/p2p/MiracastP2pManager.kt`
- `app/src/main/java/com/github/serezhka/jap2server/internal/ControlServer.java`

### 3.2 切换设备时 FC

崩溃栈：

```text
Fatal signal 4 (SIGILL)
MediaCodec.cpp:156 CHECK_EQ(mState, UNINITIALIZED) failed
android::MediaCodec::~MediaCodec()
```

原因：UI 线程 `reset()` 与解码线程同时操作/释放 MediaCodec。

修复：

- `VideoPlayer.java` 中所有 MediaCodec 操作与 `reset()` / `releaseDecoder()` 串行化；
- `stop()` 抛异常时仍调用 `release()`；
- 增加 `mConfigGeneration`，reset 后丢弃旧流残留包；
- `stopVideoPlay()` 同步释放解码器。

### 3.3 画面拉伸

按用户确认，已恢复最初的 `onMeasure` 拉伸方案：

- `SCALE_FIT`：保持比例；
- `SCALE_ZOOM`：等比居中放大/裁切；
- `SCALE_FILL`：拉伸铺满；
- `SCALE_ORIGINAL`：1:1。

不要再改回 `SurfaceView.setScaleX/Y` 或 `setFixedSize` 方案：

- `setFixedSize` 会导致 Surface 销毁后不恢复；
- 视图变换虽能改变 SurfaceFlinger layer 矩阵，但用户确认原始方案更合适。

## 4. UIBC 实验矩阵

所有实验开关集中在：

```kotlin
// app/src/main/java/com/n1tools/box/miracast/uibc/UibcClient.kt
object UibcExperiment { ... }
```

### 4.1 基础链路修复（有效）

已做：

- M4/M14 的 SET_PARAMETER 响应原样回显 `wfd_uibc_capability` / `wfd_uibc_setting`；
- 发送 `wfd_uibc_setting: enable` 后等待 RTSP `200 OK` 再连接 UIBC；
- UIBC 断开后通过 RTSP 重发 enable，并限制最小间隔 1.5 秒；
- 只允许一个 UIBC client，旧 client 停止后等待最多 500ms；
- 每包 UIBC 数据打印 hex；
- 断链日志打印 `lastHex`。

验证结果：

```text
Source confirmed UIBC enable (CSeq: 4)
UIBC enable confirmed=true
UIBC TCP connected successfully ...
```

结论：IP、端口、时序、连接建立不是问题。

### 4.2 HIDC 相对鼠标（可发送，无注入）

曾按已知实现使用：

```text
描述符：00 01 00 3E 01 01 01 00 34 [52B descriptor] [00/FF]
报告：  00 01 00 0E 01 01 00 00 04 [buttons][dx][dy][wheel] 00
```

结果：

- 描述符发送成功；
- 报文可连续写入；
- 华为圆点光标不移动、点击无反馈；
- 部分情况下首次连接会出现 `ECONNRESET`，重连后稳定。

结论：HIDC 相对鼠标不是当前主路径。

### 4.3 HIDC 绝对鼠标（失败）

使用 16 位绝对 X/Y 描述符，报告：

```text
00 01 00 10 01 01 00 00 06 [buttons] [XH XL] [YH YL] [wheel] 00
```

日志：

```text
HIDC descriptor hex: 00 01 00 49 01 01 01 00 3F ...
UIBC TX: 00 01 00 10 01 01 00 00 06 00 29 EA 2D 9F 00 00
ECONNRESET
```

之后端口持续 `ECONNREFUSED`。

结论：华为不接受该自定义绝对 HIDC 描述符/报告，不要重复尝试。

### 4.4 GENERIC Touch（可发送，无注入）

报文：

```text
00 00 00 0E [00/01/02] 00 06 01 00 [XH XL] [YH YL] 00
```

结果：

- 像素坐标时可连续发送，无稳定闭环反馈；
- 归一化到 `0~65535` 后出现 `EPIPE`，后续 `ECONNREFUSED`；
- 手机端无点击反馈。

结论：坐标归一化方向错误；Generic Touch 类型也不是华为声明的 Mouse 能力。

### 4.5 GENERIC Mouse（当前默认实验路径，可发送但未注入）

规范格式：

```text
00 00 00 0C [05/06/07] 00 05 [buttons] [XH XL] [YH YL]
```

其中：

- `05` = Mouse Down
- `06` = Mouse Up
- `07` = Mouse Move
- body length = `05`
- 总长 = `12`
- 无 pointer count / pointer id

验证结果：

```text
UIBC TX: 00 00 00 0C 05 00 05 01 04 3C 02 98
UIBC TX: 00 00 00 0C 06 00 05 00 04 3C 02 98
```

连续多组 `05/06` 可稳定发送，无 `RESET`。

但：

- 华为端没有点击反馈；
- 使用手机自身触控板能移动圆点光标，说明不是显示端/系统输入系统整体故障；
- N1 发送的 GENERIC Mouse 没有让圆点光标移动到对应位置。

结论：GENERIC Mouse 报文格式正确，但华为电脑模式未注入该输入。

## 5. 当前默认配置

当前代码默认进入“GENERIC Mouse 仅点击模式”。这是稳定性降级模式，**不代表点击已经被华为注入**：

```kotlin
object UibcExperiment {
    const val DESCRIPTOR_TRAILING_BYTE: Byte = 0x00
    const val HID_TYPE: Byte = 0x01
    const val HID_USAGE_REPORT: Byte = 0x00
    const val HID_USAGE_DESCRIPTOR: Byte = 0x01
    const val KEEPALIVE_ENABLED = false
    const val CLICK_GAP_MS = 20L
    const val LOG_PACKET_HEX = true
    const val ABSOLUTE_MOUSE = false
    const val FORCE_GENERIC = true
    const val GENERIC_NORMALIZED = false
    const val GENERIC_MOUSE = true
}
```

注意：

- `ACTION_MOVE` 在 GENERIC 模式下被直接丢弃，避免 `07` 触发 UIBC reset；
- 只发 `05 DOWN` / `06 UP`；
- 不支持拖动；
- 首次连接若空闲约 5 秒，首击可能丢失，但自动 re-enable + reconnect 后后续点击链路稳定。

## 6. 复现与抓日志

严格按以下顺序操作：

```bash
# 1. 清空日志
adb -s 192.168.1.101:5555 logcat -c

# 2. 重新发起华为电脑模式投屏，并在 N1 副屏点击多次
#    注意：首次连接空闲后首击可能丢失，不要只点一次

# 3. 导出日志
adb -s 192.168.1.101:5555 logcat -d -v time -s UibcClient MiracastRtspServer MiracastServer AirPlayDisplay
```

链路层成功日志（**不代表华为已注入输入**）：

```text
Source confirmed UIBC enable (CSeq: N)
UIBC enable confirmed=true
UIBC TCP connected successfully ... (mode=GENERIC)
UIBC TX: 00 00 00 0C 05 00 05 01 ...
UIBC TX: 00 00 00 0C 06 00 05 00 ...
```

功能成功还必须同时观察华为端：

- 圆点光标移动到 N1 副屏点击位置；
- 华为电脑模式界面出现点击反馈。

如果只有链路层日志、华为端无反馈，仍然判定为触控失败。

不应再出现：

```text
UIBC TX: 00 00 00 0C 07 ...
EPIPE
ECONNRESET
ECONNREFUSED
```

## 7. 明确不要再重复的方向

1. 不要再怀疑 `192.168.49.1`，它是华为 GO 的真实地址。
2. 不要再改 RTSP 端口 7236 或 UIBC 动态端口。
3. 不要再用 `SurfaceView.setFixedSize`，会导致 Surface 不恢复。
4. 不要再尝试 HIDC 绝对鼠标报告，真机已明确 RST。
5. 不要把 GENERIC 坐标归一化到 `0~65535`，真机已明确失败。
6. 不要在 GENERIC Mouse 中发送 `07 MOVE`，会触发华为 UIBC reset。
7. 不要认为“TCP 写入成功”等于“华为已注入输入”；本次验证证明二者可以分离。

## 8. 后续建议

若要继续推进“华为电脑模式副屏直接触控”，只剩几条现实路径：

1. **换标准 Miracast Source 验证**  
   用其他 Android 手机/Windows Miracast 源验证当前 UIBC 代码，确认Sink实现是否通用可用。

2. **逆向华为 CastEngine / UIBC Receiver**  
   研究华为私有 HIDC 描述符或输入注入条件。成本高、风险大。

3. **手机端辅助输入通道**  
   若必须在华为电脑上实现反向控制，需要手机端 App / ADB / HID 方案，不属于 N1 投屏接收端可独立解决范围。

4. **维持当前稳定状态**  
   投屏主功能稳定、无 FC，画面方案符合用户确认，UIBC 链路干净；但副屏触控未打通，华为端不注入输入。

## 9. ADB 输入注入方案

由于 UIBC 无法让华为电脑模式注入输入，已新增 ADB 输入注入作为替代路径。

实现：

- 新增 `app/src/main/java/com/n1tools/box/control/AdbInputController.kt`
- N1 自带 `/system/bin/adb` 1.0.32，且支持 `adb keygen`
- 首次运行在 app files 目录生成 `.android/adbkey` / `.pub`
- UIBC 协商拿到华为源端 IP 后，异步执行：

```bash
adb connect <sourceHost>:5555
```

- 连接成功后，用 `dumpsys display` 找到 `type WIFI` 的外投屏（日志中为 `Free Display`），只用于识别电脑模式和记录 displayId / `1280x800` 分辨率。
- 同时用 `wm size` 读取华为手机主屏（实测 `1084x2412`），作为“触控板”坐标基准。
- **关键结论**：华为手机在电脑模式下作为触控板时，屏幕坐标与外投屏桌面坐标**不是一一映射**；在手机屏幕上滑动是通过相对位移持续移动光标。
- 但用户的点击手势期望“所见即所得”：点到副屏哪个位置，就应直接点到桌面哪个位置。
- 最终采用**混合模式**：
  - **轻点**：映射成外投屏 `1280x800` 绝对坐标，用 mouse source 直接点击对应位置；
  - **长按**：在外投屏目标坐标单击选中，再发送 `KEYCODE_ENTER` 打开；
  - **滑动**：仍按手机屏幕触控板相对位移注入，持续移动光标。

注入命令：

```bash
# 点击：外投屏绝对坐标
adb -s <sourceHost>:5555 shell input mouse -d <displayId> tap X Y

# 长按打开：单击选中 + Enter 打开
adb -s <sourceHost>:5555 shell "input mouse -d <displayId> motionevent MOVE X Y; input mouse -d <displayId> tap X Y; sleep 0.12; input keyevent 66"

# 移动：手机主屏触控板相对滑动，可批量执行
adb -s <sourceHost>:5555 shell "input swipe <cx> <cy> <cx+dx1> <cy+dy1> <duration>; input swipe <cx> <cy> <cx+dx2> <cy+dy2> <duration>"
```

- ADB 未连接或授权失败时，自动回退到 UIBC 路径。

### 手机侧前提

华为手机必须：

1. 开启开发者选项；
2. 开启 USB 调试 / 无线调试；
3. 通过 USB 执行一次 `adb tcpip 5555`，或系统无线调试等效开启；
4. 首次 N1 连接时，在手机上确认 ADB RSA 授权弹窗。

如果手机未授权，日志会出现：

```text
ADB connect result: unauthorized
```

### 当前行为

- `ACTION_DOWN` 记录起点并启动 `500ms` 长按计时；
- `ACTION_MOVE` 位移超过 `24px` 时取消长按，后续按滑动处理；
- 单击：按下时长 `<350ms` 且位移 `<24px`，`ACTION_UP` 立即调用 `mouseTap(sx, sy)`，不再引入延迟；
- 打开：改为**长按 `500ms` 触发**，调用 `longPressDoubleTap(sx, sy)`：先 `input mouse -d <displayId> motionevent MOVE` 把外投光标移到目标坐标，再单击选中并发送 `input keyevent 66`（Enter）。实测合成 mouse tap 两次仍被华为当成两次单击，不能可靠触发双击打开；
- 外投 displayId 不可用时，tap 回退为手机屏幕中心短按；
- swipe 调用 `trackpadMove(dx, dy)`：按手机主屏触控板相对位移发送 `input swipe`；
- 位移过大时会自动拆成多段 swipe，但合并到一次 `adb shell` 中执行，避免每段都启动 adb 进程造成卡顿；
- 每段 swipe 时长压到 `60~140ms`，并把 `TRACKPAD_SCALE` 调到 `2.5f` 提升光标速度。

相关日志：

```text
ADB input controller created for Huawei source 192.168.49.1
ADB connect result: connected to 192.168.49.1:5555
ADB external display: id=92 name=Free Display size=1280x800
ADB phone trackpad size: 1084x2412 (physical)
ADB input basis: display=92 source=1280x800 trackpad=1084x2412 view=1920x1080
ADB mouse tap: (640,400) display=92
ADB long press -> double tap: (640,400)
ADB long press double tap done
ADB trackpad move: dx=180 dy=-40 duration=230
```

如果 `ADB external display` 未出现，点击会回退为手机屏幕中心短按；如果 `ADB phone trackpad size` 未出现，滑动相对移动会被跳过。

### 实体触控板双击抓包结论

用 `getevent -lt /dev/input/event3` 抓取实体双击，关键时序为：

```text
第一次 DOWN 235077.119678
第一次 UP   235077.211661   # 约 92ms
第二次 DOWN 235077.464573   # 距第一次 UP 约 253ms
第二次 UP   235077.541038   # 约 76ms
```

两次坐标非常接近，双击主要由“短时间内两次几乎同位置的 tap”构成。  
`adb shell` 属于 `input` 组，能读 `/dev/input/event3`，但写 `sendevent` 会 `Permission denied`，且手机无 `su`，所以不能直接回放原始事件。

抓屏对比进一步证明：手机主屏上的 `input tap 542 1206` 会把外投光标从 `(640,400)` 跳到另一个外投位置，说明它被华为当成绝对坐标映射，而不是纯相对触控板。外投屏 mouse source 同坐标连点两次虽然不会让光标跳开，但华为仍按两次单击处理；因此长按打开最终改为“单击选中 + Enter”。

### 手机切 Mac 黑屏修复

从华为手机投屏切到 Mac 时，旧实现会在确认切换后调用 `mVideoPlayer.reset()` 重建硬件解码器；但 AirPlay 的 SPS/PPS 只在流开始时下发一次，且在华为画面期间会被 `onVideo()` 丢弃，导致新解码器等不到关键帧而黑屏。

当前实现：

- 在 `AirplayDataConsumer.onVideo()` 中，无论当前源是谁，只要收到配置包（SPS/PPS）就缓存；
- 切到 Mac 后 `reset()` 解码器，立即回灌缓存的 SPS/PPS；
- 新建播放器时也先回灌 SPS/PPS 与缓存帧，再启动解码线程；
- `onDisconnected()` 增加 Mac 会话代际判断，旧会话的延迟断线回调不会误清新 Mac 画面。

## 10. 关键源码位置

- UIBC 实验开关与报文构造：  
  `app/src/main/java/com/n1tools/box/miracast/uibc/UibcClient.kt`
- UIBC 协商与 RTSP：  
  `app/src/main/java/com/n1tools/box/miracast/rtsp/MiracastRtspServer.kt`
- UIBC 生命周期：  
  `app/src/main/java/com/n1tools/box/miracast/MiracastServer.kt`
- 触控入口：  
  `app/src/main/java/com/n1tools/box/ui/AirPlayDisplayActivity.kt`
- 画面缩放：  
  `app/src/main/java/com/n1tools/box/airplay/view/PlaySurfaceView.java`
- 解码器并发修复：  
  `app/src/main/java/com/n1tools/box/airplay/player/VideoPlayer.java`
