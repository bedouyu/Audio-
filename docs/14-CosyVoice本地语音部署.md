# CosyVoice 本地自然语音

Audio智能助手支持将语音合成切换为本机 CosyVoice 3。原有文字生成、音频队列、播放器、历史和收听进度继续使用；生成后的音频仍保存为标准 WAV。

## 部署布局

- 源码：`D:\desktop\AI\CosyVoice`，当前固定提交 `074ca6dc9e80a2f424f1f74b48bdd7d3fea531cc`。
- 独立环境：源码下 `.venv`，Python 3.10。
- 模型：`pretrained_models/Fun-CosyVoice3-0.5B`，模型仓库 `FunAudioLLM/Fun-CosyVoice3-0.5B-2512`，固定版本 `29e01c4e8d000f4bcd70751be16fa94bf3d85a18`。
- 服务适配器：`tools/cosyvoice/service.py`，仅监听 `127.0.0.1:50000`。
- 音色清单：`tools/cosyvoice/voices.json`。

基础推理依赖见 `tools/cosyvoice/requirements-windows.txt`。Torch 2.3.1 与 Torchaudio 2.3.1 使用 CUDA 12.1 对应的 Windows 版本；不需要为此替换系统 CUDA 驱动。暂不启用 vLLM、TensorRT 或训练组件。

旧版 Whisper 构建需要 `pkg_resources`。本部署将 Setuptools 固定为 `80.9.0`，安装其余依赖时使用 `tools/cosyvoice/build-constraints.txt` 约束隔离构建环境，避免新版 Setuptools 导致安装失败。

文本归一化使用自带离线资源的 `wetext==0.1.8`。上游旧版 `0.0.4` 每次初始化会访问已不兼容的 ModelScope 资源接口，导致日期、数字等朗读处理被静默跳过；本部署直接使用新版随包资源，启动时无需为该组件登录或联网。

## 启动与切换

单独启动语音服务：

```powershell
.\start-cosyvoice-local.ps1
```

`GET http://127.0.0.1:50000/health` 在加载中返回 503，就绪后返回 200、`ready=true`、真实设备和采样率。只有模型和参考音色均初始化完成后才可合成。

本机部署验证完成后，`runtime/speech-provider.txt` 保存 `cosyvoice`。原有 `start-backend-local.cmd` 会读取该配置、启动或复用本地语音服务、等待就绪，再启动 Java 后端。日志与启动进程编号保存于 `runtime/`。

可用环境变量覆盖选择：

```powershell
$env:AUDIORAG_SPEECH_PROVIDER = 'windows'
.\start-backend-local.ps1
```

选择 `cosyvoice` 后服务失败会明确报错，不会自动改用 Windows 音色。切换语音服务后重新打开桌面端，以刷新音色列表。

配置项：

| 环境变量 | 默认值与作用 |
| --- | --- |
| `AUDIORAG_SPEECH_PROVIDER` | `windows`；本机部署标记可改为 `cosyvoice` |
| `AUDIORAG_COSYVOICE_ROOT` | 项目上两级目录中的 `CosyVoice` |
| `AUDIORAG_COSYVOICE_URL` | `http://127.0.0.1:50000`，仅允许本机 HTTP |
| `AUDIORAG_COSYVOICE_TIMEOUT_SECONDS` | `600`，单次合成等待上限 |
| `AUDIORAG_SYNTHESIS_LEASE_SECONDS` | Windows 为 `300`；本机 CosyVoice 启动脚本设置 `1500`，涵盖两个队列工作线程的串行等待 |

## 音色与格式

目前只预注册一个真实的“官方示例音色”，ID `cosyvoice-official-demo`。参考录音来自源码的 `asset/zero_shot_prompt.wav`，支持中文、英文和双语。它用于本地体验，不代表内置多角色音色库。

新增自己或已获授权的音色时，将参考 WAV 放入 CosyVoice 部署目录，修改 `voices.json` 并填写准确的参考录音文字。参考文件必须使用部署目录内的相对路径；服务请求不能指定任意文件或外部 URL。修改后重启语音服务。

服务启动时缓存参考音色，后续按 ID 复用。同一时间只合成一个任务，Java 客户端同步串行调用，避免 GPU 争抢。音频按模型真实采样率包装为单声道 16 位 WAV；Java 再验证文件完整性后发布到现有 `/media/{UUID}.wav` 地址。后台生成线程失败时不会发布可能截断的音频。

## 验证

不加载模型的协议测试：

```powershell
python -m unittest discover -s tools/cosyvoice -p test_service.py
```

2026-10-21 已在本机 RTX 4060 Laptop 8GB 上完成真实验证：Python 3.10.22、Torch/Torchaudio 2.3.1+cu121，CUDA 矩阵运算、模型加载和 Java 调用全部通过。三个音频均为 24000 Hz、单声道、16 位 PCM WAV，Java 已读取并校验完整文件。

| 试音 | 音频长度 | 合成耗时 |
| --- | --- | --- |
| 中文 | 7.92 秒 | 5.58 秒 |
| 英语 | 6.72 秒 | 4.06 秒 |
| 中英双语 | 8.48 秒 | 4.76 秒 |

试听文件位于 `runtime/cosyvoice-smoke/cosyvoice-{zh,en,bilingual}.wav`。以上为短文本实测，长篇生成速度会随分段、文本和机器负载变化。

Java 请求预先编码 UTF-8 JSON 并设置准确的 `Content-Length`，避免默认分块正文与本地接口不兼容；已有真实本机 HTTP 回归测试覆盖中文传输长度。

自动启动检查由 `tools/cosyvoice/ensure_service.py` 执行，PowerShell 入口仅调用独立 Python 环境。服务只绑定本机地址；已就绪的模型会直接复用。

英文段落进入模型前会将连续空白合并为单个空格，兼容 WeText 英文处理对空段落和 CRLF 的限制。该处理只作用于朗读副本，保留数据库中的原始讲稿、标点与内容。已用真实的 9 段英文故事复现并验证文本处理，并增加空段落、CRLF 和混合空白的回归测试。
