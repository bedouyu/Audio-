"""Start or reuse the local CosyVoice service, then wait for model readiness."""

import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import time
from datetime import datetime
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, build_opener


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--wait-seconds", type=int, default=180)
    args = parser.parse_args()
    if not 1 <= args.wait_seconds <= 1800:
        parser.error("wait-seconds must be between 1 and 1800")
    repo = args.repo_root.resolve(strict=True)
    project = Path(__file__).resolve().parents[2]
    endpoint = urlsplit(os.environ.get("AUDIORAG_COSYVOICE_URL", "http://127.0.0.1:50000"))
    if (endpoint.scheme != "http" or endpoint.hostname not in ("127.0.0.1", "localhost", "::1")
            or endpoint.path not in ("", "/") or endpoint.username or endpoint.password
            or endpoint.query or endpoint.fragment):
        parser.error("CosyVoice must use a local HTTP endpoint")
    port = endpoint.port or 80
    opener = build_opener(ProxyHandler({}))
    health_url = f"http://{endpoint.netloc}/health"

    def health():
        try:
            try:
                response = opener.open(health_url, timeout=2)
            except HTTPError as failure:
                response = failure
            with response:
                data = json.loads(response.read(65537))
            return data if isinstance(data, dict) else None
        except (OSError, URLError, ValueError):
            return None

    def ready(state):
        return state and state.get("ready") and state.get("model") == "Fun-CosyVoice3-0.5B"

    state = health()
    if ready(state):
        print("Local CosyVoice is ready.", flush=True)
        return
    process = None
    if state is None:
        try:
            connection = socket.create_connection((endpoint.hostname, port), timeout=0.5)
        except OSError:
            pass
        else:
            connection.close()
            raise RuntimeError("The CosyVoice port is occupied by another service.")
        if endpoint.hostname == "::1":
            raise RuntimeError("The local service binds to 127.0.0.1; use that address to start it.")
        python = repo / ".venv/Scripts/python.exe"
        if not python.is_file() or not (repo / "pretrained_models/Fun-CosyVoice3-0.5B/cosyvoice3.yaml").is_file():
            raise RuntimeError("CosyVoice Python environment or model is missing.")
        runtime = project / "runtime"
        runtime.mkdir(exist_ok=True)
        prefix = runtime / ("cosyvoice-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
        env = os.environ.copy()
        env.update(PYTHONUTF8="1", OMP_NUM_THREADS="4", MKL_NUM_THREADS="4",
                   HF_HOME=str(repo / ".cache/huggingface"),
                   MODELSCOPE_CACHE=str(repo / ".cache/modelscope"))
        with Path(str(prefix) + ".out.log").open("wb") as output, Path(str(prefix) + ".err.log").open("wb") as errors:
            process = subprocess.Popen(
                [str(python), "-u", str(Path(__file__).with_name("service.py")), "--repo-root", str(repo), "--port", str(port)],
                cwd=repo, env=env, stdout=output, stderr=errors,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        (runtime / "cosyvoice.pid").write_text(str(process.pid), encoding="ascii")
        print(f"Loading local CosyVoice. Log: {prefix}", flush=True)
    deadline = time.monotonic() + args.wait_seconds
    while time.monotonic() < deadline:
        state = health()
        if ready(state):
            print("Local CosyVoice is ready.", flush=True)
            return
        if state and state.get("initialization_failed"):
            raise RuntimeError("CosyVoice model initialization failed. Check runtime/cosyvoice logs.")
        if process and process.poll() is not None:
            raise RuntimeError("CosyVoice exited during startup. Check runtime/cosyvoice logs.")
        time.sleep(2)
    raise RuntimeError("CosyVoice did not become ready within the startup timeout.")


if __name__ == "__main__":
    main()
