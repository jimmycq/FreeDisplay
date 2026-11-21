from html import escape

out = '/Users/jimmy/工作文档/projects/N1_Tools/docs/n1_secondary_display_principle.svg'
L = []
A = L.append

A('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1600 1240" width="1600" height="1240">')
A('  <style>')
A("    text { font-family: 'Helvetica Neue', Helvetica, Arial, 'PingFang SC', 'Microsoft YaHei', 'Microsoft JhengHei', 'SimHei', sans-serif; }")
A('  </style>')
A('  <defs>')
for name, color in [('blue', '#2563eb'), ('green', '#059669'), ('orange', '#ea580c'), ('purple', '#9333ea'), ('gray', '#6b7280'), ('red', '#dc2626')]:
    A(f'    <marker id="arrow-{name}" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">')
    A(f'      <polygon points="0 0, 10 3.5, 0 7" fill="{color}"/>')
    A('    </marker>')
A('    <filter id="shadow" x="-20%" y="-20%" width="140%" height="140%">')
A('      <feDropShadow dx="0" dy="2" stdDeviation="3" flood-color="#111827" flood-opacity="0.10"/>')
A('    </filter>')
A('  </defs>')
A('  <rect width="1600" height="1240" fill="#ffffff"/>')
A('  <text x="40" y="52" font-size="26" font-weight="700" fill="#111827">N1 副屏功能原理说明图</text>')
A('  <text x="40" y="78" font-size="13" fill="#6b7280">AirPlay 2 / Miracast 双协议接收 · 视频音频播放 · UIBC 与 ADB 反向控制 · UI 状态与生命周期</text>')


def lane(x, y, w, h, label, color):
    A(f'  <rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{color}" fill-opacity="0.045" stroke="{color}" stroke-width="1.2" stroke-dasharray="6,4"/>')
    A(f'  <text x="{x+14}" y="{y+18}" font-size="11" font-weight="700" letter-spacing="0.08em" fill="{color}">{escape(label)}</text>')


def node(x, y, w, h, title, subs, badge, fill='#ffffff', stroke='#d1d5db', accent='#2563eb'):
    A('  <g filter="url(#shadow)">')
    A(f'    <rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{fill}" stroke="{stroke}" stroke-width="1.5"/>')
    A('  </g>')
    A(f'  <rect x="{x}" y="{y}" width="7" height="{h}" rx="3" fill="{accent}"/>')
    A(f'  <circle cx="{x+30}" cy="{y+27}" r="15" fill="{accent}" opacity="0.14"/>')
    A(f'  <text x="{x+30}" y="{y+31}" text-anchor="middle" font-size="10" font-weight="700" fill="{accent}">{escape(badge)}</text>')
    A(f'  <text x="{x+54}" y="{y+27}" font-size="14" font-weight="700" fill="#111827">{escape(title)}</text>')
    for i, s in enumerate(subs):
        A(f'  <text x="{x+18}" y="{y+53+i*17}" font-size="11.5" fill="#4b5563">{escape(s)}</text>')


def path(points, color, marker, dash=None, width=1.8):
    d = 'M ' + ' L '.join(f'{x},{y}' for x, y in points)
    dash_attr = f' stroke-dasharray="{dash}"' if dash else ''
    A(f'  <path d="{d}" fill="none" stroke="{color}" stroke-width="{width}" marker-end="url(#arrow-{marker})"{dash_attr}/>')


def alabel(x, y, text, color='#6b7280'):
    w = max(42, len(text) * 12 + 14)
    A(f'  <rect x="{x-w/2:.1f}" y="{y-12}" width="{w:.1f}" height="19" rx="4" fill="#ffffff" opacity="0.96" stroke="{color}" stroke-opacity="0.25"/>')
    A(f'  <text x="{x}" y="{y+1.5}" text-anchor="middle" font-size="11.5" fill="{color}">{escape(text)}</text>')


lane(40, 100, 1520, 140, '01  接入与入口', '#2563eb')
node(70, 124, 180, 92, 'AirPlay 源', ['Mac / iOS 镜像', '屏幕镜像投送'], 'AP', accent='#2563eb')
node(280, 124, 210, 92, '华为电脑模式', ['Miracast 源', '手机屏幕=触控板'], 'HW', accent='#dc2626')
node(520, 124, 190, 92, '标准 Miracast', ['Android / Windows', 'Win+K'], 'WFD', accent='#dc2626')
node(740, 124, 210, 92, '入口与开关', ['Dashboard', '通知栏 Quick Control'], 'UI', accent='#059669')
node(990, 124, 280, 92, 'AirPlayDisplayActivity', ['统一副屏 Activity', '双协议栈 / 生命周期'], 'APP', accent='#2563eb')
node(1310, 124, 230, 92, '状态仲裁', ['activeSource: MAC / HUAWEI', '5s 切换抑制窗口'], 'ST', accent='#059669')
for x in [160, 385, 615]:
    path([(x, 216), (x, 230)], '#2563eb', 'blue')
path([(160, 230), (1130, 230)], '#2563eb', 'blue')
path([(1130, 230), (1130, 216)], '#2563eb', 'blue')
alabel(640, 222, '启动副屏', '#2563eb')
path([(950, 170), (990, 170)], '#059669', 'green')
path([(1270, 170), (1310, 170)], '#059669', 'green')

lane(40, 270, 1520, 210, '02  协议发现与信令', '#2563eb')
A('  <rect x="60" y="300" width="730" height="160" rx="8" fill="#eff6ff" fill-opacity="0.45" stroke="#bfdbfe" stroke-dasharray="6,4"/>')
A('  <text x="76" y="318" font-size="11" font-weight="700" fill="#2563eb">AIRPLAY 2 STACK</text>')
A('  <rect x="810" y="300" width="730" height="160" rx="8" fill="#fef2f2" fill-opacity="0.42" stroke="#fecaca" stroke-dasharray="6,4"/>')
A('  <text x="826" y="318" font-size="11" font-weight="700" fill="#dc2626">MIRACAST / WFD SINK</text>')
node(80, 326, 160, 104, '发现与控制', ['7000 控制 / 7100 视频', '_airplay / _raop'], 'AP', accent='#2563eb')
node(260, 326, 180, 104, 'ControlServer', ['Pairing / FairPlay', 'RTSP / HeartBeat'], 'CTL', accent='#2563eb')
node(460, 326, 160, 104, '镜像接收', ['TCP 7100', 'MirroringReceiver'], 'MR', accent='#2563eb')
node(640, 326, 130, 104, 'FairPlay', ['视频解密', 'AVCC 载荷'], 'FP', accent='#2563eb')
node(830, 326, 170, 104, 'P2P Manager', ['Primary Sink', '192.168.49.1'], 'P2P', accent='#dc2626')
node(1020, 326, 160, 104, 'RTSP M1–M7', ['7236 信令', 'video / audio / uibc'], 'RTSP', accent='#dc2626')
node(1200, 326, 150, 104, 'MpegTS', ['UDP 19000', 'TS 解复用'], 'TS', accent='#dc2626')
node(1370, 326, 150, 104, 'UIBC 协商', ['enable / port', '触发 TCP 回传'], 'UIBC', accent='#9333ea')
path([(1130, 216), (1130, 252), (160, 252), (160, 300)], '#2563eb', 'blue')
path([(1130, 216), (1130, 260), (1010, 260), (1010, 300)], '#dc2626', 'red')
alabel(640, 247, 'AirPlay 启动', '#2563eb')
alabel(1050, 255, 'WFD 启动', '#dc2626')
for x1, x2 in [(240, 260), (440, 460), (620, 640)]:
    path([(x1, 378), (x2, 378)], '#2563eb', 'blue')
for x1, x2 in [(1000, 1020), (1180, 1200), (1350, 1370)]:
    path([(x1, 378), (x2, 378)], '#dc2626', 'red')

lane(40, 510, 1520, 220, '03  流接收 / 解码 / 播放', '#059669')
A('  <rect x="60" y="536" width="430" height="150" rx="8" fill="#f0fdf4" fill-opacity="0.45" stroke="#bbf7d0" stroke-dasharray="6,4"/>')
A('  <text x="76" y="554" font-size="11" font-weight="700" fill="#059669">PARSE &amp; PACKETIZE</text>')
A('  <rect x="510" y="536" width="530" height="150" rx="8" fill="#eff6ff" fill-opacity="0.42" stroke="#bfdbfe" stroke-dasharray="6,4"/>')
A('  <text x="526" y="554" font-size="11" font-weight="700" fill="#2563eb">DECODE &amp; DISPLAY</text>')
A('  <rect x="1060" y="536" width="480" height="150" rx="8" fill="#faf5ff" fill-opacity="0.42" stroke="#e9d5ff" stroke-dasharray="6,4"/>')
A('  <text x="1076" y="554" font-size="11" font-weight="700" fill="#9333ea">AUDIO ROUTING</text>')
node(80, 568, 190, 100, 'NAL / PCM 封装', ['AVCC → Annex B', 'NALPacket / PCMPacket'], 'PKT', accent='#059669')
node(290, 568, 180, 100, '数据消费者', ['onVideo / onAudio', '帧缓存与首帧'], 'CB', accent='#059669')
node(540, 568, 220, 100, 'VideoPlayer', ['MediaCodec AVC', '120 帧低延迟队列'], 'DEC', accent='#2563eb')
node(780, 568, 230, 100, 'PlaySurfaceView', ['FIT / ZOOM / FILL / 1:1', '硬件叠加 / 旋转'], 'SUR', accent='#2563eb')
node(1080, 568, 190, 100, 'AudioPlayer', ['AAC → AudioTrack', '副屏发声'], 'AUD', accent='#9333ea')
node(1290, 568, 220, 100, '音频路由', ['本机发声 / 副屏发声', 'MiracastAudioPlayer'], 'RT', accent='#9333ea')
path([(540, 430), (540, 488), (175, 488), (175, 568)], '#2563eb', 'blue')
path([(1275, 430), (1275, 500), (650, 500), (650, 568)], '#dc2626', 'red')
path([(1340, 430), (1340, 520), (1400, 520), (1400, 568)], '#9333ea', 'purple')
alabel(340, 482, 'RTSP / 解密', '#2563eb')
alabel(960, 494, 'TS / PES', '#dc2626')
path([(270, 618), (290, 618)], '#059669', 'green')
path([(470, 618), (540, 618)], '#059669', 'green')
path([(760, 618), (780, 618)], '#2563eb', 'blue')
path([(470, 640), (1080, 640), (1080, 638)], '#9333ea', 'purple', dash='5,3')

lane(40, 760, 1520, 230, '04  反向控制：UIBC 与 ADB', '#ea580c')
node(70, 790, 180, 150, 'SurfaceView 触点', ['ACTION_DOWN/UP/MOVE', 'view 2048×1152'], 'IN', accent='#ea580c')
node(280, 790, 220, 150, '手势语义', ['轻点 → 单击', '长按 500ms → 双击', '滑动 → 触控板'], 'GS', accent='#ea580c')
node(530, 790, 200, 150, '坐标映射', ['view → 1280×800', 'displayId 动态识别'], 'MAP', accent='#ea580c')
node(760, 790, 230, 150, 'AdbInputController', ['免 root / adb :5555', 'mouse tap / input tap', '分段 input swipe'], 'ADB', accent='#ea580c')
node(1020, 790, 200, 150, 'UIBC Client', ['HIDC / GENERIC Mouse', 'TCP 05/06 报文'], 'UIBC', accent='#9333ea')
node(1250, 790, 280, 150, '华为源端桌面', ['外投 display 动态 ID', '手机主屏 1084×2412 = 触控板'], 'SRC', accent='#dc2626')
path([(895, 668), (895, 724), (160, 724), (160, 790)], '#ea580c', 'orange')
alabel(520, 718, 'hasReceivedFrame', '#ea580c')
path([(250, 865), (280, 865)], '#ea580c', 'orange')
path([(500, 830), (530, 830)], '#ea580c', 'orange')
path([(500, 900), (760, 900)], '#ea580c', 'orange')
alabel(630, 894, '长按/滑动', '#ea580c')
path([(730, 865), (760, 865)], '#ea580c', 'orange')
alabel(745, 848, '轻点', '#ea580c')
path([(990, 865), (1250, 865)], '#ea580c', 'orange')
alabel(1110, 858, '免 root 注入', '#ea580c')
path([(500, 845), (1020, 845)], '#9333ea', 'purple', dash='6,3')
alabel(760, 838, '实验链路', '#9333ea')
path([(1220, 865), (1250, 865)], '#9333ea', 'purple', dash='6,3')
alabel(1235, 848, '未注入', '#9333ea')

lane(40, 1020, 1520, 130, '05  UI / 状态 / 生命周期', '#6b7280')
node(70, 1046, 210, 86, '等待页 / 状态', ['首帧后隐藏', '网络信息 3s 轮询'], 'UI', accent='#059669')
node(310, 1046, 250, 86, '控制卡片 / 抽屉', ['画质 / 音频 / 缩放 / 旋转', '重命名 / 关于 / 退出'], 'CFG', accent='#2563eb')
node(590, 1046, 250, 86, '设备切换仲裁', ['Mac / 华为冲突', '5s 抑制 + 切断旧会话'], 'SW', accent='#dc2626')
node(870, 1046, 240, 86, '连接生命周期', ['首帧 / 断线 / reset', 'P2P / UIBC / ADB 清理'], 'LC', accent='#6b7280')
node(1140, 1046, 390, 86, '验证结论', ['AirPlay 无标准反向触控；UIBC 华为不注入', 'ADB 免 root 为当前可行路径'], 'DOC', accent='#9333ea')
path([(175, 940), (175, 1046)], '#6b7280', 'gray', dash='4,3')
path([(1130, 940), (1130, 1046)], '#6b7280', 'gray', dash='4,3')
alabel(1125, 995, '状态回写', '#6b7280')

A('  <rect x="40" y="1172" width="1520" height="48" rx="8" fill="#f9fafb" stroke="#e5e7eb"/>')
legend = [
    ('#2563eb', 'blue', '主媒体流 / AirPlay'),
    ('#dc2626', 'red', 'Miracast / 源端'),
    ('#059669', 'green', '状态与数据回调'),
    ('#ea580c', 'orange', 'ADB 反向输入'),
    ('#9333ea', 'purple', 'UIBC 实验 / 音频 / 结论'),
    ('#6b7280', 'gray', '生命周期 / 清理'),
]
x = 62
for color, marker, label in legend:
    A(f'  <line x1="{x}" y1="1196" x2="{x+34}" y2="1196" stroke="{color}" stroke-width="2" marker-end="url(#arrow-{marker})"/>')
    A(f'  <text x="{x+42}" y="1201" font-size="11.5" fill="#4b5563">{escape(label)}</text>')
    x += 250
A('</svg>')

with open(out, 'w', encoding='utf-8') as f:
    f.write('\n'.join(L))
print('SVG generated:', out)
