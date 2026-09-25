# 全双工语音（`:voice` 模块）方案说明

## 1. 目标

- TTS 播报时麦克风不关，用户随时说话即可打断，且打断那句话不丢字。
- 不把自己的播报声识别成用户说话。
- "嗯""哦"这类口头禅不压低、不打断播报。
- 本地（sherpa-onnx）与云端 API 可切换，由一层路由统一分发。

## 2. 架构

```
DuplexVoiceSession ── 麦克风常开，串行处理每帧音频
  ├─ AudioCapture / AudioPlayer      平台实现（Android、iOS），带系统回声消除
  ├─ VoiceActivityDetector           Silero VAD（模型未就绪时用能量 VAD 兜底）
  ├─ SpeechRecognizer ─ EngineRouter ─ 本地 SherpaLocalAsrEngine / 云端 RemoteAsrEngine
  └─ SpeechSynthesizer ─ EngineRouter ─ 本地 SherpaTtsEngine    / 云端 RemoteTtsEngine
```

- 路由策略 `RoutingPolicy`：`LocalOnly` / `RemoteOnly` / `PreferLocal` / `PreferRemote`。识别在会话中途切换引擎时，会补发自上次最终结果以来的音频；播报只在第一段音频出来前才允许切换引擎。
- 不丢字：`PcmRingBuffer` 保留最近 800 ms 音频，VAD 判定起音时连同这段预录一起送给识别，开头那个字不会被截掉。

## 3. 回声与打断：三层防护

| 层 | 做法 | 作用 |
|---|---|---|
| 系统回声消除（AEC） | Android：`MODE_IN_COMMUNICATION` + `VOICE_COMMUNICATION` 录音源 + `AcousticEchoCanceler`/`NoiseSuppressor`，播放用 `USAGE_VOICE_COMMUNICATION`。iOS：`playAndRecord`/`voiceChat` 模式 + `AVAudioEngine` 输入节点开启 voice processing（回声消除 + 降噪 + 自动增益），播放走同一个 engine | 从声学上消掉播报声 |
| 播报期间更严格的 VAD | 概率阈值 0.7，需持续 250 ms 才确认起音；播报结束后仍有 400 ms 回声尾期 | 残余回声不会触发起音 |
| 响度下限 + 播报后保护期 | 低于 −60 dBFS（`minSpeechLevelDb`）的帧，VAD 概率一律按 0 处理：Silero 只看频谱不看音量，回声消除/降噪后的 −70～−90 dBFS 残音也可能给出 0.3～0.5。播报结束 1.5 s 内（`echoGuardMs`）起音但未被确认为说话的片段直接丢弃，不送识别 | 防止播报刚结束时残音被识别成"好的"等幻觉文字 |
| 文本层过滤 | `EchoTextFilter`：识别文本与播报文本做二字组覆盖率比对，判定为回声的丢弃 | 兜底，拦下漏过的回声 |

- 打断确认 `VadAndUserText`：必须有 VAD 起音，并且识别出一段非回声的文字，才停止播报。
- 口头禅：`BackchannelFilter` 把 ≤4 个字、且全由语气字（嗯/哦/啊/呃…）或"是吗/这样啊"组成的结果判为附和，只上报 `VoiceEvent.Backchannel`，不压低音量、不打断。压低音量的策略默认 `DuckPolicy.OnUserText`：识别出第一段非口头禅文字时才压低。不在播报时说"嗯"，照常作为回答输出。

## 4. 为什么选 Silero VAD + X-ASR + Matcha

三个模型都跑在同一个 sherpa-onnx 运行时上（1.13.6，只需一套 onnxruntime 原生库）。识别与播报是 AVAssistance 项目 2026-09-25 在 x86-64 / onnxruntime CPU 上实测过的组合（离线识别 RTF≈0.020，TTS 三段 RTF 0.021–0.027，中英混读全对），模型目录布局与 AVAssistance 一致，可直接复用已下载的模型。

| 用途 | 选型 | 大小 | 理由 | 放弃的备选 |
|---|---|---|---|---|
| VAD | Silero VAD | 0.6 MB | 每 32 ms 窗口出一个语音概率，单窗口 CPU 耗时远小于 1 ms；抗噪、抗残余回声能力明显强于能量 VAD。起音检测和打断确认依赖这个逐窗口概率 | WebRTC VAD（噪声下误报多）；能量 VAD（保留作兜底） |
| 识别 | X-ASR 480ms 流式 zipformer transducer int8（2026-06-05） | 128 MB | 真流式识别，边说边出字（480ms chunk），AVAssistance 实测流式 RTF≈0.049，中英混读；encoder/joiner 是 int8、decoder 是 fp32，文件名不统一 | 离线 X-ASR（131 MB，保留为回退）；流式 Paraformer（999 MB）：下载大；Paraformer-zh int8（218 MB）：仅中文 |
| 播报 | Matcha-icefall-zh-en + Vocos 16kHz 声码器 | 76 + 51 MB | 中英混读 16kHz 女声，RTF≈0.02（全批 TTS 候选最快）；逐句回调输出，首包快；回调返回 0 可立即中止生成，打断时马上停。英文音素化依赖包内 `espeak-ng-data/`，缺目录引擎会加载失败 | zh-baker（71 MB，仅中文，配 22kHz 声码器）；VITS-Melo（159 MB，中英混读）；Kokoro（126 MB，多语种） |

识别默认走真流式（X-ASR 480ms，`SherpaLocalAsrEngine` 优先用它，partial 随解码即时产出）；流式模型缺失时回退离线 X-ASR / Paraformer，那种情况下中间结果靠每 300 ms 对整句重解码得到，超过 8 秒的句子只在说完后出最终结果。
- TTS 文本前端：合成时加载模型包内**全部** `*.fst` 规则文件（`date-zh`/`number-zh`/`phone-zh`，负责中文数字、日期、小数、号码的正则化——此前按 `date.fst` 等名字硬编码，与包内实际文件名不符，规则从未生效）。`TtsTextNormalizer` 在送入合成前再做两层模型覆盖不到的处理：连续 ≥7 位数字逐位念（手机号/卡号；年份与金额等短数字仍走规则 FST），以及确证的多音字易错词纠正（用 lexicon 里存在读音的同音字替换，如「姓重」→「姓崇」，替换表刻意保持极小避免引入新错误）。此外「开始回声」这类跨词边界会被前端读成乱音（同音字替换无效），实测插入停顿（逗号）后完整可辨，已作为替换表条目固化。

## 5. 模型首次下载

- 进入语音页时由 `ModelManager.ensureModels()` 自动检查，缺什么下什么，共约 256 MB（VAD 1 + 流式识别 128 + 播报 76 + 声码器 51）。下载在 app 级作用域中进行，离开页面不会中断。下载完成后调用 `SherpaRuntime.reload()` 热加载，无需重启 app；下载完成前，识别和播报走云端（如已配置）。
- 换模型说明（2026-09）：默认下载与加载为流式 X-ASR（`stt_x_asr_zh_en_streaming`，识别）和 Matcha zh-en + Vocos 16kHz（播报）。回退链（不默认下载，装了才用）：离线 X-ASR（`stt_x_asr_zh_en`）→ Paraformer-zh int8（`stt_paraformer_zh_int8`）负责识别；Matcha zh-baker + Vocos 22kHz 负责播报。所有 id 与 AVAssistance 一致，模型目录可直接拷贝复用；声码器与 matcha 严格配对（16kHz ↔ zh-en，22kHz ↔ zh-baker），不可互换。
- 存放位置：Android 为 `files/models/<id>/`，目录布局和 `.dl`/`.source` 标记与 AVAssistance 一致，从 AVAssistance 拷过来的模型可直接识别。
  - `.dl`：下载未完成标记；中断后下次用 HTTP Range 断点续传。
  - `.source`：版本标记；与期望版本不一致时重新下载。
- 解压：用纯 Kotlin 实现的 bzip2 + tar 解码器（`model/archive/`），带块级和流级 CRC 校验、路径穿越防护。Android 与 iOS 共用一套，不依赖 commons-compress。
- 下载源：`VoiceConfig.modelBaseUrls`，按顺序尝试：
  1. 官方：`https://github.com/k2-fsa/sherpa-onnx/releases/download`
  2. 【非官方】`https://ghproxy.net/…`、`https://gh-proxy.com/…`（第三方 GitHub 代理，仅在官方地址失败时使用；如不允许可在配置中去掉，或换成内网镜像）

## 6. 实测（OPPO PJZ110，Android 16）

在约 28 秒的长文本播报期间保持安静：

| 指标 | 系统回声消除开 | 系统回声消除关 |
|---|---|---|
| 播报期间 VAD 超过阈值的比例 | 0% | 92% |
| 播报期间检测到的说话起点 | 0 | 1 |
| 被文本层判为回声、已丢弃的结果 | 0 | 21 |
| 误打断 / 误识别 | 0 / 0 | 0 / 0 |

播报结束后：修复前，末尾残音（峰值约 −70 dBFS）曾让 VAD 升到 0.4～0.5，被识别成"好的"。加入响度下限后连续 3 次回声测试，播报结束后 3 秒内 VAD 均为 0，残音峰值 −68～−71 dBFS，没有任何识别结果。

模型首次下载（Silero + Matcha，共约 72 MB）加解压约 30 秒，完成后自动热加载，回声测试结果与上表一致。

## 7. 验证方法（语音页）

1. **回声**：保持安静，点"回声测试"。期望：回声泄漏显示"正常"，VAD 超阈接近 0%，打断和识别都是 0。
2. **对比**：关闭"系统回声消除"后重测，数值会变差，但打断仍应为 0。
3. **口头禅**：播报中说"嗯""哦"。期望：日志出现"口头禅, 不打断"，音量不变、播报继续。再说一句完整的话，期望立即停播，识别出完整句子，面板显示打断延迟。
4. **录音**：点"录制调试音频"，文件在 `/sdcard/Android/data/us.wangxy.voicebook/files/voice_debug/`。左声道是处理后的麦克风声音，右声道是播报原声；播报期间左声道越安静越好。同名 `.txt` 是逐帧时间线（检测器看到的 VAD 概率、电平 dB、是否在播报）和会话事件，可用来核对自己正常说话的电平是否高于 −60 dBFS 下限。

## 8. 现状与限制

- iOS：已实现与 Android 对应的降噪/回声消除模式（`iosMain/audio/IosAudioEngine.kt`、`IosAudioIo.kt`），Info.plist 已加麦克风权限说明。当前 Windows 环境无法编译 iOS（Kotlin/Native 的 Apple 目标需要 macOS），需要在 Mac 上编译并真机验证。iOS 尚未接入 sherpa-onnx 的 iOS 框架，因此不下载本地模型，识别和播报走云端。
- 系统回声消除效果与机型有关：AVAssistance 曾发现 OnePlus 9 上系统回声消除无效。如遇此类机型，可通过 `EchoCanceller` 接口接入软件回声消除（参考 AVAssistance 的 NLMS 实现）。
- 桌面端（Linux x64 JVM）：与 Android 共用同一套模型下载、目录解析和后端构建代码（`voice/src/jvmShared/`）， sherpa-onnx 原生库来自官方 v1.13.6 linux-x64-jni 发布包，解压到 `desktopApp/native/sherpa-onnx-linux-x64/lib`（已 gitignore），模型根目录为 `~/.voicebook/models`（`-Dvoicebook.models` 可覆盖，目录布局与 Android 一致）。桌面端验收：`DesktopLocalVoiceTest`（jvmTest）——真实下载模型后跑 TTS→VAD→STT 闭环；`DesktopAudioIoTest` ——真实麦克风录音 + 扬声器放音。音频采集/播放用 `javax.sound.sampled` 实现（`JvmAudioCapture/JvmAudioPlayer`），桌面端已可端到端语音对话。桌面没有系统 AEC（Linux 可启用 PipeWire 的 module-echo-cancel 虚拟采集源，`JvmAudioCapture.hasPlatformEchoCancellation` 会自动探测），外放场景建议戴耳机或开启该模块；软件 NLMS AEC 仍为 TODO。`desktopApp` 的 run 任务已带 `-Djava.library.path` 参数。
- iOS 与 Web：模型下载代码本身是共通的（commonMain），但这两端还没有 sherpa-onnx 运行时（iOS 需 cinterop 接 C API；Web 需要解决浏览器端文件系统），`LocalModelSupport.modelsDir` 传 null，不下载、不加载，语音走云端。

## 9. 代码索引（`voice/src/`）

| 路径 | 内容 |
|---|---|
| `commonMain/.../duplex/` | `DuplexVoiceSession`、`EchoTextFilter`、`BackchannelFilter`、`DuplexProbe`（互扰诊断） |
| `commonMain/.../routing/` | `EngineRouter` 与带故障切换的识别器、合成器 |
| `commonMain/.../local/sherpa/` | sherpa 引擎适配、`SherpaRuntime`（热加载） |
| `commonMain/.../model/` | 模型清单、下载仓库、`ModelManager`、bzip2/tar 解码 |
| `jvmShared/.../local/sherpa/SherpaModelLoad.kt` | Android/桌面共用的模型目录解析与后端构建（新旧模型配对与回退） |
| `androidMain/.../audio/` | 音频路由、采集、播放、调试录音 |
| `jvmMain/.../audio/JvmAudioCapture|Player.kt` | 桌面端 javax.sound 采集与播放（含 PipeWire 回声消除源探测） |
| `androidMain/.../local/sherpa/AndroidSherpa.kt` | Android 侧：JNI 库加载 + 模型根目录 |
| `jvmMain/.../local/sherpa/JvmSherpa.kt` | 桌面侧：JNI 库加载 + 模型根目录（Linux x64） |
| `jvmTest/.../local/sherpa/DesktopLocalVoiceTest.kt` | 桌面端下载→加载→TTS/VAD/STT 闭环验收 |
| `iosMain/.../audio/` | `AVAudioEngine` 采集/播放 + voice processing |
| `jvmTest/.../model/` | 解码器与下载仓库测试（本地 HTTP 服务器，含断点续传） |
