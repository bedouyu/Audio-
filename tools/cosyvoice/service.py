"""Loopback-only CosyVoice 3 adapter. Torch/model imports occur only during startup."""

from __future__ import annotations

import argparse
import io
import json
import logging
import os
import re
import sys
import threading
import wave
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


LOGGER = logging.getLogger("audio-assistant.cosyvoice")
MAX_BODY_BYTES = 512 * 1024


class ServiceError(Exception):
    def __init__(self, status: int, detail: str):
        super().__init__(detail)
        self.status = status
        self.detail = detail


@dataclass(frozen=True)
class Voice:
    name: str
    display_name: str
    languages: tuple[str, ...]
    prompt_wav: Path
    prompt_text: str

    def public(self) -> dict:
        locale = "bilingual" if set(self.languages) == {"zh", "en"} else self.languages[0]
        return {
            "name": self.name,
            "display_name": self.display_name,
            "locale": locale,
            "culture": locale,
            "languages": list(self.languages),
            "enabled": True,
        }


def load_voices(filename: Path, repo_root: Path) -> tuple[Voice, ...]:
    root = repo_root.resolve(strict=True)
    data = json.loads(filename.read_text(encoding="utf-8-sig"))
    entries = data.get("voices") if isinstance(data, dict) else None
    if not isinstance(entries, list) or not entries:
        raise ValueError("音色配置必须包含非空 voices 列表")
    voices = []
    seen = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError("音色配置项格式错误")
        if entry.get("enabled", True) is False:
            continue
        name = entry.get("name", "")
        display = entry.get("display_name", "")
        languages = entry.get("languages", [])
        prompt_text = entry.get("prompt_text", "")
        wav = entry.get("prompt_wav", "")
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", name) or name in seen:
            raise ValueError("音色名称无效或重复")
        if not isinstance(display, str) or not display.strip() or len(display) > 80:
            raise ValueError("音色显示名称无效")
        if not isinstance(languages, list) or not languages or any(value not in ("zh", "en") for value in languages):
            raise ValueError("当前音色支持的语言必须为 zh、en 或二者")
        if not isinstance(prompt_text, str) or not prompt_text.strip():
            raise ValueError("参考录音需要对应的文字")
        if not isinstance(wav, str) or not wav or "://" in wav:
            raise ValueError("参考音频必须是部署目录中的本地 WAV 文件")
        relative = Path(wav)
        if relative.is_absolute():
            raise ValueError("参考音频必须使用相对部署目录的路径")
        prompt_wav = (root / relative).resolve(strict=True)
        if not prompt_wav.is_relative_to(root) or not prompt_wav.is_file() or prompt_wav.suffix.lower() != ".wav":
            raise ValueError("参考音频必须位于部署目录中")
        seen.add(name)
        voices.append(Voice(name, display.strip(), tuple(dict.fromkeys(languages)), prompt_wav, prompt_text))
    if not voices:
        raise ValueError("没有启用的音色")
    return tuple(voices)


def wav_from_pcm(pcm: bytes, sample_rate: int) -> bytes:
    if not pcm or len(pcm) % 2 or not 8000 <= sample_rate <= 192000:
        raise ValueError("语音模型未返回有效的单声道 PCM")
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(sample_rate)
        audio.writeframes(pcm)
    return output.getvalue()


class CosyVoiceEngine:
    def __init__(self, repo_root: Path, model_dir: Path, voices: tuple[Voice, ...], allow_cpu: bool = False):
        sys.path.insert(0, str(repo_root))
        sys.path.insert(0, str(repo_root / "third_party" / "Matcha-TTS"))
        os.chdir(repo_root)
        import torch
        from cosyvoice.cli.cosyvoice import AutoModel

        gpu = torch.cuda.is_available()
        if not gpu and not allow_cpu:
            raise RuntimeError("未检测到可用 CUDA 显卡；请先检查 GPU 环境，或明确使用 --allow-cpu")
        self.torch = torch
        self.device = "cuda" if gpu else "cpu"
        self.model_name = model_dir.name
        self.voices = voices
        self.model = AutoModel(model_dir=str(model_dir), fp16=gpu, load_trt=False, load_vllm=False)
        self._install_worker_guard()
        self.sample_rate = int(self.model.sample_rate)
        for voice in voices:
            if not self.model.add_zero_shot_spk(voice.prompt_text, str(voice.prompt_wav), voice.name):
                raise RuntimeError("参考音色初始化失败")

    def _install_worker_guard(self):
        self._worker_failure = None
        original = self.model.model.llm_job

        def guarded(*args, **kwargs):
            try:
                original(*args, **kwargs)
            except Exception as failure:
                # Upstream joins this thread without propagating its exception.
                # The adapter's single-task lock keeps this marker bound to one synthesis.
                self._worker_failure = failure
                LOGGER.error("CosyVoice language-model worker failed (%s)", type(failure).__name__)

        self.model.model.llm_job = guarded

    def synthesize(self, text: str, voice: Voice, language: str) -> bytes:
        frames = bytearray()
        self._worker_failure = None
        # The English wetext FST rejects blank paragraphs and CRLF sequences.
        # Normalize only the spoken copy; the application's saved script stays intact.
        spoken_text = re.sub(r"\s+", " ", text).strip()
        # Cached reference audio is encoded once at startup; no audio paths come from HTTP callers.
        try:
            with self.torch.inference_mode():
                chunks = self.model.inference_zero_shot(
                    spoken_text, "", "", zero_shot_spk_id=voice.name, stream=False
                )
                for chunk in chunks:
                    samples = chunk["tts_speech"].detach().float().cpu()
                    if samples.ndim == 2 and samples.shape[0] != 1:
                        raise RuntimeError("语音模型返回了非单声道数据")
                    if not self.torch.isfinite(samples).all().item():
                        raise RuntimeError("语音模型返回了无效采样")
                    pcm = (samples.reshape(-1).clamp(-1, 1) * 32767).to(self.torch.int16).numpy().tobytes()
                    frames.extend(pcm)
            if self._worker_failure is not None:
                raise ServiceError(503, "语音模型后台生成失败，没有发布可能截断的音频，请检查服务日志") from self._worker_failure
            return wav_from_pcm(bytes(frames), self.sample_rate)
        except self.torch.cuda.OutOfMemoryError as failure:
            self.torch.cuda.empty_cache()
            raise ServiceError(503, "语音合成显存不足，请关闭占用显卡的程序并缩短单段内容") from failure
        except Exception:
            if self._worker_failure is not None:
                if isinstance(self._worker_failure, self.torch.cuda.OutOfMemoryError):
                    self.torch.cuda.empty_cache()
                raise ServiceError(503, "语音模型后台生成失败，没有发布可能截断的音频，请检查服务日志") from self._worker_failure
            raise
        finally:
            self._worker_failure = None


class AdapterService:
    def __init__(self, max_chars: int = 20000):
        self.max_chars = max_chars
        self.engine = None
        self.initialization_error = False
        self.lock = threading.Lock()

    def health(self) -> dict:
        engine = self.engine
        return {
            "ready": engine is not None,
            "model": engine.model_name if engine else "Fun-CosyVoice3-0.5B",
            "sample_rate": engine.sample_rate if engine else None,
            "device": engine.device if engine else None,
            "busy": self.lock.locked(),
            "voices": len(engine.voices) if engine else 0,
            "max_chars": self.max_chars,
            "initialization_failed": self.initialization_error,
        }

    def require_engine(self):
        if self.engine is None:
            detail = "语音模型初始化失败，请检查服务日志" if self.initialization_error else "语音模型正在加载，请稍后再试"
            raise ServiceError(503, detail)
        return self.engine

    def voices(self) -> dict:
        return {"voices": [voice.public() for voice in self.require_engine().voices]}

    def synthesize(self, request: dict) -> bytes:
        engine = self.require_engine()
        if not isinstance(request, dict) or set(request) - {"text", "voice", "language"}:
            raise ServiceError(422, "合成请求格式无效")
        text = request.get("text")
        if not isinstance(text, str) or not text.strip() or len(text) > self.max_chars:
            raise ServiceError(422, f"合成文字须为 1 到 {self.max_chars} 个字符")
        requested_voice = request.get("voice", "")
        if requested_voice is None:
            requested_voice = ""
        if not isinstance(requested_voice, str):
            raise ServiceError(422, "音色名称无效")
        voice = next((item for item in engine.voices if item.name == requested_voice), None) if requested_voice else engine.voices[0]
        if voice is None:
            raise ServiceError(422, "所选音色未注册，请重新加载音色列表")
        language = request.get("language", "auto") or "auto"
        if not isinstance(language, str):
            raise ServiceError(422, "语音语言无效")
        language = language.lower().replace("_", "-")
        language = language.split("-", 1)[0] if language not in ("auto", "bilingual") else language
        if language not in ("auto", "zh", "en", "bilingual"):
            raise ServiceError(422, "当前本地音色仅支持中文、英语和中英双语")
        needed = {"zh", "en"} if language == "bilingual" else {language} if language != "auto" else set()
        if not needed.issubset(voice.languages):
            raise ServiceError(422, "所选音色不支持请求的语言")
        if not self.lock.acquire(blocking=False):
            raise ServiceError(503, "本地语音服务正在合成另一段内容，请稍后明确重试")
        try:
            return engine.synthesize(text, voice, language)
        finally:
            self.lock.release()


def handler_for(service: AdapterService):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, message, *args):
            LOGGER.info("HTTP %s %s", self.command, self.path.split("?", 1)[0])

        def reply(self, status: int, body: bytes, content_type: str):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            self.wfile.write(body)

        def json_reply(self, status: int, data: dict):
            self.reply(status, json.dumps(data, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

        def do_GET(self):
            try:
                if self.path == "/health":
                    self.json_reply(200 if service.engine else 503, service.health())
                elif self.path == "/voices":
                    self.json_reply(200, service.voices())
                else:
                    self.json_reply(404, {"detail": "接口不存在"})
            except ServiceError as failure:
                self.json_reply(failure.status, {"detail": failure.detail})
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_POST(self):
            try:
                if self.path != "/synthesize":
                    raise ServiceError(404, "接口不存在")
                if self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() != "application/json":
                    raise ServiceError(415, "合成接口仅接受 JSON")
                if self.headers.get("Transfer-Encoding"):
                    raise ServiceError(400, "不支持分块请求正文")
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError as failure:
                    raise ServiceError(400, "请求长度无效") from failure
                if not 0 < length <= MAX_BODY_BYTES:
                    raise ServiceError(413, "合成请求正文为空或过大")
                self.connection.settimeout(30)
                raw = self.rfile.read(length)
                if len(raw) != length:
                    raise ServiceError(400, "合成请求正文不完整")
                try:
                    request = json.loads(raw.decode("utf-8"))
                except (UnicodeError, ValueError) as failure:
                    raise ServiceError(400, "请求正文不是有效 UTF-8 JSON") from failure
                self.reply(200, service.synthesize(request), "audio/wav")
            except ServiceError as failure:
                self.json_reply(failure.status, {"detail": failure.detail})
            except (BrokenPipeError, ConnectionResetError):
                # A disconnected caller must not trigger another synthesis attempt.
                pass
            except Exception as failure:
                LOGGER.exception("Synthesis failed (%s)", type(failure).__name__)
                self.json_reply(503, {"detail": "本地语音合成失败，请检查服务日志，不会自动重复合成"})

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, default=Path("pretrained_models/Fun-CosyVoice3-0.5B"))
    parser.add_argument("--voices-file", type=Path, default=Path(__file__).with_name("voices.json"))
    parser.add_argument("--port", type=int, default=50000)
    parser.add_argument("--max-chars", type=int, default=20000)
    parser.add_argument("--allow-cpu", action="store_true")
    args = parser.parse_args()
    repo_root = args.repo_root.resolve(strict=True)
    model_dir = (args.model_dir if args.model_dir.is_absolute() else repo_root / args.model_dir).resolve(strict=True)
    voices_file = args.voices_file.resolve(strict=True)
    if not (repo_root / "cosyvoice/cli/cosyvoice.py").is_file() or not (model_dir / "cosyvoice3.yaml").is_file():
        parser.error("请提供完整的 CosyVoice 源码目录及 CosyVoice 3 模型目录")
    if not 1 <= args.port <= 65535 or not 1 <= args.max_chars <= 100000:
        parser.error("端口或文字长度上限无效")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    service = AdapterService(args.max_chars)

    def initialize():
        try:
            LOGGER.info("Loading CosyVoice model and reference voices")
            voices = load_voices(voices_file, repo_root)
            engine = CosyVoiceEngine(repo_root, model_dir, voices, args.allow_cpu)
            service.engine = engine
            LOGGER.info("READY model=%s device=%s sample_rate=%s voices=%s", engine.model_name, engine.device, engine.sample_rate, len(engine.voices))
        except Exception:
            service.initialization_error = True
            LOGGER.exception("CosyVoice initialization failed")

    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler_for(service))
    server.daemon_threads = True
    threading.Thread(target=initialize, name="cosyvoice-initialize", daemon=True).start()
    LOGGER.info("Listening on http://127.0.0.1:%s; readiness is available at /health", args.port)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
