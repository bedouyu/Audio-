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


if __name__ == "__main__":
    unittest.main()
