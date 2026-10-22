import io
import json
import tempfile
import threading
import unittest
import wave
from contextlib import nullcontext
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from service import AdapterService, CosyVoiceEngine, ServiceError, Voice, load_voices, wav_from_pcm


class FakeOutOfMemoryError(RuntimeError):
    pass


class ThreadedModel:
    def __init__(self):
        self.fail_worker = True
        self.fail_iterator = False
        self.worker_calls = 0
        self.model = SimpleNamespace(llm_job=self.llm_job)

    def llm_job(self):
        self.worker_calls += 1
        if self.fail_worker:
            raise RuntimeError("private-model-worker-detail")

    def inference_zero_shot(self, *_args, **_kwargs):
        worker = threading.Thread(target=self.model.llm_job)
        worker.start()
        worker.join()
        if self.fail_iterator:
            raise ValueError("cannot synthesize incomplete tokens")
        samples = MagicMock()
        samples.ndim = 1
        for name in ("detach", "float", "cpu", "reshape", "clamp", "to"):
            getattr(samples, name).return_value = samples
        samples.__mul__.return_value = samples
        samples.numpy.return_value.tobytes.return_value = b"\x00\x00\x01\x00" * 120
        yield {"tts_speech": samples}


def threaded_engine():
    engine = CosyVoiceEngine.__new__(CosyVoiceEngine)
    engine.model = ThreadedModel()
    finite = SimpleNamespace(all=lambda: SimpleNamespace(item=lambda: True))
    engine.torch = SimpleNamespace(
        inference_mode=nullcontext,
        isfinite=lambda _samples: finite,
        int16="int16",
        cuda=SimpleNamespace(OutOfMemoryError=FakeOutOfMemoryError, empty_cache=lambda: None),
    )
    engine.sample_rate = 24000
    engine._install_worker_guard()
    return engine


class WorkerFailureTests(unittest.TestCase):
    def test_spoken_copy_accepts_english_paragraphs_and_crlf_without_joining_words(self):
        engine = threaded_engine()
        engine.model.fail_worker = False
        voice = Voice("demo", "Demo", ("zh", "en"), Path("reference.wav"), "reference")
        for source in ("Hello\n\nworld. It's 2026!", "Hello\r\nworld. It's 2026!",
                       "  Hello\t \nworld. It's 2026!  "):
            with self.subTest(source=source):
                with patch.object(engine.model, "inference_zero_shot", wraps=engine.model.inference_zero_shot) as inference:
                    audio = engine.synthesize(source, voice, "en")
                    self.assertTrue(audio.startswith(b"RIFF"))
                    self.assertEqual("Hello world. It's 2026!", inference.call_args.args[0])

    def test_worker_failure_cannot_publish_partial_audio_and_does_not_poison_next_request(self):
        engine = threaded_engine()
        voice = Voice("demo", "Demo", ("zh", "en"), Path("reference.wav"), "reference")
        with patch("service.wav_from_pcm", wraps=wav_from_pcm) as encode:
            with self.assertRaises(ServiceError) as raised:
                engine.synthesize("hello", voice, "en")
            self.assertEqual(503, raised.exception.status)
            self.assertNotIn("private-model-worker-detail", raised.exception.detail)
            encode.assert_not_called()
            self.assertIsNone(engine._worker_failure)
            engine.model.fail_worker = False
            audio = engine.synthesize("hello again", voice, "en")
            self.assertTrue(audio.startswith(b"RIFF"))
            encode.assert_called_once()
            self.assertEqual(2, engine.model.worker_calls)
            self.assertIsNone(engine._worker_failure)

    def test_worker_failure_remains_a_service_error_when_partial_tokens_break_the_iterator(self):
        engine = threaded_engine()
        engine.model.fail_iterator = True
        voice = Voice("demo", "Demo", ("zh", "en"), Path("reference.wav"), "reference")
        with self.assertRaises(ServiceError) as raised:
            engine.synthesize("hello", voice, "en")
        self.assertEqual(503, raised.exception.status)
        self.assertIsInstance(raised.exception.__cause__, RuntimeError)
        self.assertIsNone(engine._worker_failure)


class FakeEngine:
    sample_rate = 24000
    device = "cuda"
    model_name = "Fun-CosyVoice3-0.5B"
    voices = (Voice("cosyvoice-official-demo", "官方示例音色", ("zh", "en"), Path("reference.wav"), "reference words"),)

    def __init__(self):
        self.calls = []

    def synthesize(self, text, voice, language):
        self.calls.append((text, voice.name, language))
        return wav_from_pcm(b"\x00\x00\x01\x00" * 240, self.sample_rate)


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.service = AdapterService(max_chars=1000)

    def ready(self):
        self.service.engine = FakeEngine()
        return self.service.engine

    def test_loading_model_never_advertises_available_voices(self):
        self.assertFalse(self.service.health()["ready"])
        for operation in (self.service.voices, lambda: self.service.synthesize({"text": "hello"})):
            with self.assertRaises(ServiceError) as raised:
                operation()
            self.assertEqual(503, raised.exception.status)
        self.service.initialization_error = True
        self.assertTrue(self.service.health()["initialization_failed"])

    def test_registered_voice_supports_chinese_english_and_bilingual_without_switching_speakers(self):
        engine = self.ready()
        for language, text in (("zh-CN", "你好"), ("en-US", "Hello"), ("bilingual", "你好，hello")):
            audio = self.service.synthesize({"text": text, "voice": "cosyvoice-official-demo", "language": language})
            with wave.open(io.BytesIO(audio), "rb") as decoded:
                self.assertEqual(24000, decoded.getframerate())
                self.assertEqual(1, decoded.getnchannels())
                self.assertEqual(2, decoded.getsampwidth())
                self.assertGreater(decoded.getnframes(), 0)
        self.assertEqual(3, len(engine.calls))
        self.assertEqual({"cosyvoice-official-demo"}, {call[1] for call in engine.calls})
        voice = self.service.voices()["voices"][0]
        self.assertEqual("bilingual", voice["locale"])
        self.assertEqual(["zh", "en"], voice["languages"])
        self.assertNotIn("prompt_text", voice)
        self.assertNotIn("prompt_wav", voice)

    def test_request_cannot_supply_a_reference_file_or_url_or_unregistered_voice(self):
        engine = self.ready()
        for request in ({"text": "hello", "prompt_wav": "https://example.test/voice.wav"},
                        {"text": "hello", "voice": "https://example.test/voice.wav"},
                        {"text": "hello", "language": "fr"}, {"text": " "}, {"text": "a" * 1001}):
            with self.assertRaises(ServiceError) as raised:
                self.service.synthesize(request)
            self.assertEqual(422, raised.exception.status)
        self.assertEqual([], engine.calls)

    def test_busy_gpu_returns_service_unavailable_without_running_another_synthesis(self):
        engine = self.ready()
        self.service.lock.acquire()
        try:
            with self.assertRaises(ServiceError) as raised:
                self.service.synthesize({"text": "hello"})
            self.assertEqual(503, raised.exception.status)
            self.assertEqual([], engine.calls)
        finally:
            self.service.lock.release()

    def test_failed_synthesis_releases_single_task_lock(self):
        engine = self.ready()
        def failed(*args):
            raise RuntimeError("failed model")
        engine.synthesize = failed
        with self.assertRaises(RuntimeError):
            self.service.synthesize({"text": "hello"})
        self.assertFalse(self.service.lock.locked())

    def test_reference_registry_is_confined_to_local_deployment_files(self):
        with tempfile.TemporaryDirectory(prefix="voice-test-", dir=Path(__file__).parent) as temporary:
            root = Path(temporary).resolve()
            (root / "asset").mkdir()
            (root / "asset/reference.wav").write_bytes(wav_from_pcm(b"\x00\x00" * 8, 16000))
            manifest = root / "voices.json"
            entry = {"name": "demo", "display_name": "Demo", "languages": ["zh", "en"],
                     "prompt_wav": "asset/reference.wav", "prompt_text": "reference words"}
            manifest.write_text(json.dumps({"voices": [entry]}), encoding="utf-8")
            self.assertEqual("demo", load_voices(manifest, root)[0].name)
            for invalid in ("https://example.test/reference.wav", "../reference.wav", str(root / "asset/reference.wav")):
                entry["prompt_wav"] = invalid
                manifest.write_text(json.dumps({"voices": [entry]}), encoding="utf-8")
                with self.assertRaises((ValueError, FileNotFoundError)):
                    load_voices(manifest, root)


def tone_wav(seconds, rate=44100, channels=2, edge=0.4):
    """生成一段两侧静音、中间有声音的 WAV，用来验证重采样与静音裁剪。"""
    frames = int(rate * seconds)
    start, end = int(rate * edge), frames - int(rate * edge)
    data = bytearray()
    for index in range(frames):
        value = 0
        if start <= index < end and (index // 40) % 2 == 0:
            value = 9000
        data += (value.to_bytes(2, "little", signed=True)) * channels
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav:
        wav.setnchannels(channels)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        wav.writeframes(bytes(data))
    return buffer.getvalue()


class SampleNormalizationTests(unittest.TestCase):
    def test_it_downsamples_stereo_and_trims_silence(self):
        from service import normalize_sample
        with wave.open(io.BytesIO(normalize_sample(tone_wav(5.0))), "rb") as decoded:
            self.assertEqual(16000, decoded.getframerate())
            self.assertEqual(1, decoded.getnchannels())
            self.assertEqual(2, decoded.getsampwidth())
            seconds = decoded.getnframes() / 16000
        self.assertGreater(seconds, 3.5)
        self.assertLess(seconds, 4.6)

    def test_too_short_or_broken_samples_are_rejected(self):
        from service import normalize_sample
        with self.assertRaises(ServiceError) as short:
            normalize_sample(tone_wav(2.0))
        self.assertEqual(422, short.exception.status)
        with self.assertRaises(ServiceError) as broken:
            normalize_sample(b"not a wav")
        self.assertEqual(422, broken.exception.status)


class VoiceImportTests(unittest.TestCase):
    def manifest(self, root):
        path = root / "voices.json"
        path.write_text(json.dumps({"voices": [{
            "name": "demo", "display_name": "示例", "languages": ["zh"],
            "prompt_wav": "asset/demo.wav", "prompt_text": "示例文字"}]}, ensure_ascii=False), encoding="utf-8")
        (root / "asset").mkdir()
        (root / "asset" / "demo.wav").write_bytes(wav_from_pcm(b"\x00\x00" * 16000, 16000))
        return path

    def service(self, root, manifest):
        from service import Voice
        service = AdapterService(voices_file=manifest, repo_root=root)
        engine = FakeEngine()
        # 让假引擎的音色与 manifest 一致，否则重复名与内置音色判断都会测错对象。
        engine.voices = (Voice("demo", "示例", ("zh",), root / "asset" / "demo.wav", "示例文字"),)
        engine.model = MagicMock()
        engine.model.add_zero_shot_spk.return_value = True
        engine.model.frontend.spk2info = {}
        service.engine = engine
        return service, engine

    def payload(self, **overrides):
        import base64
        body = {"name": "my-voice", "display_name": "我的声音", "languages": ["zh"],
                "prompt_text": "这是一段样例文字", "wav_base64": base64.b64encode(tone_wav(5.0)).decode()}
        body.update(overrides)
        return body

    def test_prompt_text_gets_the_cosyvoice3_end_of_prompt_marker(self):
        from service import PROMPT_MARKER, prompt_with_marker
        composed = prompt_with_marker("希望你以后能够做的比我还好哇。")
        self.assertIn(PROMPT_MARKER, composed)
        self.assertTrue(composed.endswith("希望你以后能够做的比我还好哇。"))
        # 已经带标记的配置原样保留，不会重复添加。
        already = "You are a helpful assistant.<|endofprompt|>你好"
        self.assertEqual(already, prompt_with_marker(already))

    def test_import_stores_the_marker_so_synthesis_can_run_later(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            service, engine = self.service(root, self.manifest(root))
            service.import_voice(self.payload())
            entries = json.loads((root / "voices.json").read_text(encoding="utf-8"))["voices"]
            stored = next(entry for entry in entries if entry["name"] == "my-voice")
            self.assertIn("<|endofprompt|>", stored["prompt_text"])
            registered = engine.model.add_zero_shot_spk.call_args[0]
            self.assertIn("<|endofprompt|>", registered[0])

    def test_written_manifest_keeps_the_compact_style(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            service, _ = self.service(root, self.manifest(root))
            service.import_voice(self.payload())
            text = (root / "voices.json").read_text(encoding="utf-8")
            # languages 保持单行，避免每次录入都把整份配置重新排版。
            self.assertIn('"languages": ["zh"]', text)
            self.assertIn('"prompt_wav": "asset/voices/my-voice.wav"', text)
            self.assertNotIn('\n      "zh",\n', text)

    def test_import_writes_normalized_sample_registers_speaker_and_updates_manifest(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            service, engine = self.service(root, self.manifest(root))

            result = service.import_voice(self.payload())

            self.assertEqual("my-voice", result["name"])
            saved = root / "asset" / "voices" / "my-voice.wav"
            self.assertTrue(saved.is_file())
            with wave.open(str(saved), "rb") as decoded:
                self.assertEqual(16000, decoded.getframerate())
                self.assertEqual(1, decoded.getnchannels())
            entries = json.loads((root / "voices.json").read_text(encoding="utf-8"))["voices"]
            self.assertEqual({"demo", "my-voice"}, {entry["name"] for entry in entries})
            engine.model.add_zero_shot_spk.assert_called_once()
            self.assertIn("my-voice", {voice.name for voice in engine.voices})

    def test_duplicate_or_invalid_imports_are_rejected_without_touching_state(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            service, engine = self.service(root, self.manifest(root))

            for body, status in ((self.payload(name="demo"), 409),
                                 (self.payload(prompt_text=""), 422),
                                 (self.payload(languages=["fr"]), 422),
                                 (self.payload(name="bad name"), 422)):
                with self.assertRaises(ServiceError) as raised:
                    service.import_voice(body)
                self.assertEqual(status, raised.exception.status)

            engine.model.add_zero_shot_spk.assert_not_called()
            self.assertFalse((root / "asset" / "voices").exists())

    def test_only_imported_voices_can_be_deleted(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            service, _ = self.service(root, self.manifest(root))
            service.import_voice(self.payload())

            service.delete_voice("my-voice")

            entries = json.loads((root / "voices.json").read_text(encoding="utf-8"))["voices"]
            self.assertEqual({"demo"}, {entry["name"] for entry in entries})
            self.assertFalse((root / "asset" / "voices" / "my-voice.wav").exists())
            with self.assertRaises(ServiceError) as builtin:
                service.delete_voice("demo")
            self.assertEqual(409, builtin.exception.status)


if __name__ == "__main__":
    unittest.main()
