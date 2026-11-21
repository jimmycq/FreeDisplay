# Wi-Fi Display (Miracast / WFD) 接收端与鸿蒙 4.2 电脑模式协议深度分析

## 1. 核心结论与可行性裁决 (Executive Verdict)

1. **协议兼容性裁定：完全可行 (Fully Feasible)**
   - 鸿蒙系统（HarmonyOS 4.2 / EMUI）的「电脑模式」（Desktop Mode）**底层完全支持标准 Wi-Fi Display (Miracast / WFD)**。
   - 华为手机在发起“无线投屏”扫描时，若未发现专有 Cast+ 接收端，会自动降级为标准 WFD 协议。连接建立后，鸿蒙系统提供“手机模式”与“电脑模式”自由切换开关。
   - 切换至“电脑模式”后，手机端渲染独立的多窗口桌面（1080P/2K 16:9），经硬件编码后通过标准 WFD 传输通道推流。

2. **HDCP 内容保护规避：无障碍 (Zero-Blocker)**
   - 鸿蒙电脑模式**不强制要求 HDCP**。在 RTSP M3/M4 能力协商中，Sink 声明 `wfd_content_protection: none`，手机端会以纯明文 H.264 码流传输。
   - 仅当手机前台播放 Widevine L1 / 商业版权 DRM 视频时，该视频窗口会受 DRM 保护黑屏，但系统桌面、办公软件、浏览器、IDE 及常规视频（B站/YouTube/网页）100% 正常显示。

3. **Nokia N1 硬件与驱动支持：完全满足**
   - 硬件配备 **Broadcom BCM4354**（2x2 MIMO 802.11ac 双频），支持 Wi-Fi Direct P2P 及 STA+P2P 并发。
   - Android 5.1 (Lollipop API 22) 下，可通过 Java 反射调用 `WifiP2pManager.setWfdInfo`，或利用 N1-Box 已具备的 Root 权限直接通过 `wpa_cli` 向 `wpa_supplicant` 下发 WFD Subelement 与自动 PBC 授权，实现完全免弹窗一键连接。

---

## 2. Wi-Fi Display (Miracast) 接收端协议栈深度剖析

```mermaid
graph TD
    A[Wi-Fi Direct P2P 发现与组网] --> B[RTSP M1~M7 信令与能力协商]
    B --> C[RTP / UDP 媒体流传输]
    C --> D[MPEG-2 TS 解复用]
    D --> E[H.264 视频流 / AAC 音频流]
    E --> F[MediaCodec + SurfaceView 硬件渲染]
```

### 2.1 Wi-Fi Direct (P2P) 与 WFD IE 广播
Miracast 发现不依赖路由器 mDNS，而是基于 802.11 协议帧中的 P2P IE (Information Element) 与 WFD IE：
- **广播方式**：接收端在 Beacon 帧、Probe Request 和 Probe Response 帧中附带厂商私有 IE（OUI: `50-6F-9A`, Type: `0x0A`）。
- **WFD Subelement 0 (WFD Device Info)**：
  - 长度：6 字节
  - 核心内容：
    - `0x0111`：声明设备角色为 **Primary Sink**（接收端）、WFD 会话可用（Session Available）、不支持/无需 CP（Content Protection）。
    - `0x1C44`：RTSP 会话管理控制端口，即 TCP **`7236`**。
    - `0x0032`：最大吞吐量声明（50 Mbps）。
  - 十六进制序列：`000600111c440032`。

### 2.2 RTSP M1~M7 信令握手时序 (State Machine)
P2P 建组并完成 DHCP 分配 IP 后，手机作为 RTSP Client 连接 N1 监听的 TCP 7236 端口：

```mermaid
sequenceDiagram
    autonumber
    participant Source as 鸿蒙手机 (Source)
    participant Sink as N1 Box (Sink: TCP 7236)

    Note over Source,Sink: 阶段一：能力探索与支持确认
    Source->>Sink: M1: OPTIONS * RTSP/1.0 (Require: org.wfa.wfd1.0)
    Sink-->>Source: RTSP 200 OK (Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER)
    Sink->>Source: M2: OPTIONS * RTSP/1.0 (Require: org.wfa.wfd1.0)
    Source-->>Sink: RTSP 200 OK (Public: org.wfa.wfd1.0, ...)

    Note over Source,Sink: 阶段二：音视频格式与能力查询
    Source->>Sink: M3: GET_PARAMETER (wfd_video_formats, wfd_audio_codecs, wfd_content_protection, wfd_client_rtp_ports)
    Note over Sink: 声明支持 1080p60/2K, LPCM/AAC, content_protection: none, RTP 端口 19000
    Sink-->>Source: RTSP 200 OK (wfd_content_protection: none, wfd_video_formats: 00 00 02 02 ..., wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play)

    Note over Source,Sink: 阶段三：参数敲定与触发
    Source->>Sink: M4: SET_PARAMETER (确定 video/audio 协商参数)
    Sink-->>Source: RTSP 200 OK
    Source->>Sink: M5: SET_PARAMETER (wfd_trigger_method: SETUP)
    Sink-->>Source: RTSP 200 OK

    Note over Source,Sink: 阶段四：流媒体通道建立与播放
    Sink->>Source: M6: SETUP rtsp://source/wfd1.0/streamid=0 RTSP/1.0 (Transport: RTP/AVP/UDP;unicast;client_port=19000)
    Source-->>Sink: RTSP 200 OK (server_port=19002, session=...)
    Sink->>Source: M7: PLAY rtsp://source/wfd1.0/streamid=0 RTSP/1.0
    Source-->>Sink: RTSP 200 OK
    Note over Source,Sink: 手机开始向 N1 端口 19000 推送 UDP RTP (MPEG-TS) 数据流
```

### 2.3 媒体数据流格式 (RTP / MPEG-TS)
- **底层传输**：UDP 单播，目标端口为 M6 协商的本地端口（如 `19000`）。
- **封装层级**：
  - 每个 UDP 包通常承载 7 个标准 188 字节 MPEG-TS 包（$7 \times 188 = 1316$ 字节，规避 1500 字节 MTU 分片）。
  - **PAT (Program Association Table, PID 0x0000)** -> 指向 **PMT (Program Map Table, PID 0x0100)**。
  - **PMT** 描述两个流：
    - Stream 1: H.264 Video PES (Stream Type `0x1B`, PID 常用 `0x1011`)
    - Stream 2: AAC Audio PES (Stream Type `0x0F`, PID 常用 `0x1100`)
- **零开销 Annex B 直通**：
  PES 载荷中的 H.264 视频流本身即为原生带 `00 00 00 01` 起始码的 Annex B 格式，SPS/PPS 作为内联 NALU 周期性发送。解复用器提取 PES 载荷与 PTS 后，**无需任何内存变换**即可直接送入 `MediaCodec`。

---

## 3. 鸿蒙 4.2 / 华为 Cast+ 与标准 Miracast 兼容矩阵

| 维度 | 华为 Cast+ 专有协议 | 标准 Wi-Fi Display (Miracast) |
| :--- | :--- | :--- |
| **底层发现** | 蓝牙 BLE 广播 / mDNS 私有服务 | 802.11 P2P IE 广播 (WFD IE) |
| **握手协议** | 华为自研通道 / 证书鉴权 | 标准 RTSP M1~M7 文本信令 |
| **内容加密** | 华为设备端云双向证书绑定 | 可选 HDCP 2.x；支持 `none` 明文协商 |
| **手机模式支持**| 完美支持 | **完美支持** |
| **电脑模式支持**| 完美支持 (华为智慧屏/MatePad) | **完美支持** (第三方显示器/电视通用) |
| **开源可控性** | 闭源私有，需华为 MFi 级生态认证 | **100% 开源公开标准**，社区方案成熟 |
| **落地结论** | 无法在第三方非认证设备独立实现 | **N1 Box 最佳、唯一正道落地选型** |

### 鸿蒙“电脑模式”在标准 Miracast 下的工作逻辑：
1. 鸿蒙手机检测到 Miracast Sink 成功建立连接后，系统 `DisplayManager` 注册一个虚拟屏幕（VirtualDisplay）。
2. 下拉通知栏弹出投屏通知卡片，默认提示“已连接至无线显示”，点击可自由选择：“手机模式”或“电脑模式”。
3. 选定“电脑模式”后，手机端 SurfaceFlinger 将桌面系统（Desktop Launcher, 支持多窗口拖拽、任务栏、应用窗口化、鼠标指针）渲染到虚拟屏幕，分辨率协商为 1920x1080@60fps。
4. 手机端通过硬件 MediaCodec H.264 编码器推流，N1 接收端仅负责标准解码显示。

---

## 4. Nokia N1 (Broadcom BCM4354 + Android 5.1) 驱动与网络可行性

### 4.1 硬件与固件支持
- **Wi-Fi 模组**：Broadcom BCM4354 支持 2x2 MIMO 802.11ac，最大物理速率可达 867 Mbps。
- **内核驱动**：Broadcom `bcmdhd` 驱动在 Android 5.0/5.1 内核中原生实现 `cfg80211` / `nl80211` 接口，完整支持 Wi-Fi Direct (P2P)、P2P Listen、P2P GO/Client 模式。
- **并发能力**：BCM4354 硬件支持 STA + P2P 并发（Concurrent Mode）。N1 在连接家庭 5GHz Wi-Fi 路由器的同时，仍可开启 P2P 监听并与鸿蒙手机点对点直连。

### 4.2 软件实现路径与权限规避方案

#### 路径 A：纯 Java 反射（无 Root 依赖）
在 Android 5.1 (API 22) 下，系统尚未引入 Android 9+ 的非 SDK 接口隐藏限制（Hidden API Greylist），可通过反射直接调用：
```java
WifiP2pManager manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
WifiP2pManager.Channel channel = manager.initialize(this, getMainLooper(), null);

WifiP2pWfdInfo wfdInfo = new WifiP2pWfdInfo();
wfdInfo.setWfdEnabled(true);
wfdInfo.setDeviceType(WifiP2pWfdInfo.PRIMARY_SINK);
wfdInfo.setSessionAvailable(true);
wfdInfo.setControlPort(7236);
wfdInfo.setMaxThroughput(50);

Method setWfdInfo = manager.getClass().getMethod("setWfdInfo", 
    WifiP2pManager.Channel.class, WifiP2pWfdInfo.class, WifiP2pManager.ActionListener.class);
setWfdInfo.invoke(manager, channel, wfdInfo, listener);
```

#### 路径 B：Root 注入 wpa_cli（高稳定性、自动化 PBC 推荐方案）
鉴于 N1-Box 运行环境已取得 Root 权限，为了彻底规避 Android 5.1 原生系统弹窗需用户手动点“接受”的体验断层，可直接通过 Native/Root 脚本向 `wpa_supplicant` 注入控制指令：
1. **注入 Sink 描述**：
   ```bash
   wpa_cli -i wlan0 SET wifi_display 1
   wpa_cli -i wlan0 WFD_SUBELEM_SET 0 000600111c440032
   wpa_cli -i wlan0 SET config_methods 0x0080
   wpa_cli -i wlan0 SET device_type 7-0050F200-0
   wpa_cli -i wlan0 P2P_SET listen_channel 6
   wpa_cli -i wlan0 P2P_LISTEN
   ```
2. **免确认自动握手（Auto-Accept PBC）**：
   通过后台守护线程监控 `wpa_cli -i wlan0` 事件流，一旦监听到 `P2P-INVITATION-RECEIVED` 或 `P2P-GO-NEG-REQUEST`，立即下发：
   ```bash
   wpa_cli -i wlan0 P2P_CONNECT <peer_mac> pbc auto
   ```
   实现手机点击投屏后，N1 零人工干预、瞬时自动组网入流！

---

## 5. 对 @codec_perf 与 @arch_lead 的对接建议

1. **流水线复用**：
   - 完全赞同 @codec_perf 的结论，MPEG-TS 解复用后直接输出 Annex B，零拷贝直喂既有 `VideoPlayer`。
2. **黑边变宝藏（4:3 适配 16:9 电脑模式）**：
   - 居中渲染 2048x1152，上下空余 192px 黑边打造「副屏快捷控制坞」：
     - 上部 192px：显示手机电量、P2P 实时信号强度、传输码率、端到端延迟（~24ms 监控）、断开按钮。
     - 下部 192px：提供虚拟手势触控区或快捷功能切换键，大幅提升商务/极客交互质感。
3. **架构整合**：
   - 建议在 N1-Box 打造统一的 `CastEngineService`，统一管理 AirPlay 2（苹果生态）与 Miracast（鸿蒙/安卓/Windows 生态）的端口与后台监听。
