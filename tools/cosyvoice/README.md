# CosyVoice 本地语音适配器

本适配器只在 `127.0.0.1` 提供接口，启动后加载一次 CosyVoice 3 基础模型和参考音色。默认要求 CUDA，使用半精度基础推理，不启用 vLLM/TensorRT。只有显式指定 `--allow-cpu` 才允许 CPU 推理。

在部署好的 CosyVoice 独立 Python 环境中启动：

```powershell
D:\desktop\AI\CosyVoice\.venv\Scripts\python.exe -u D:\desktop\AI\AI音频\audiorag-java\tools\cosyvoice\service.py --repo-root D:\desktop\AI\CosyVoice --port 50000
```

默认模型目录为 CosyVoice 源码下的 `pretrained_models/Fun-CosyVoice3-0.5B`，可使用 `--model-dir` 指定其他 CosyVoice 3 模型目录。`--voices-file` 可指定音色清单；`--max-chars` 控制每次请求的文字上限，默认为 20000。

- `GET /health`：模型加载中或失败时 HTTP 503，成功后 HTTP 200；返回 `ready`、`model`、`sample_rate`、`device`、`busy` 等。
- `GET /voices`：只在模型及参考音色加载成功后返回 `{voices:[...]}`。当前仅注册一个真实存在的“官方示例音色”，ID 为 `cosyvoice-official-demo`，支持中文、英文和中英双语。它是示例参考录音，不代表完整的商业音色库。
- `POST /synthesize`：JSON 正文 `{text,voice,language}`，`voice` 留空时选择首个注册音色；语言接受 `zh`、`en`、`bilingual`、`auto` 和相应区域写法。成功返回完整的 `audio/wav`，单声道、16 位，采样率来自实际模型。

服务同一时间只执行一个合成任务，繁忙时返回 HTTP 503，不自动排队或重复生成。请求不能传参考录音路径、URL 或额外代码。只有操作人员在 `voices.json` 中预先配置的本地录音可使用；录音路径必须位于 CosyVoice 部署目录内。音色参考特征在模型启动时缓存，后续请求按稳定音色 ID 复用。

官方样本及对应文字来自 CosyVoice `example.py` 的 CosyVoice 3 示例。后续添加自己或已获授权的录音时，同时填写准确的录音文字；模型对参考录音长度、采样率等校验仍然有效。修改音色清单后重启语音服务。

轻量协议测试不加载模型、不需要安装 Torch：

```powershell
python -m unittest discover -s tools/cosyvoice -p test_service.py
```
