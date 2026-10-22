"""Measure the real speaking rate from synthesized WAVs against their scripts.

The 260 chars/min constant is a prior. This compares it with what the voice
actually produced, using the WAV the app already generated.
"""
import base64
import glob
import json
import os
import re
import sys
import wave

CJK = re.compile(r"[\u4e00-\u9fff]")


def main(tsv_path, audio_dir):
    rows = []
    with open(tsv_path, encoding="utf-8-sig") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            audio_id, status, encoded = parts[0], parts[1], parts[2]
            if not audio_id or status != "READY":
                continue
            try:
                script = base64.b64decode(encoded).decode("utf-8")
            except (ValueError, UnicodeDecodeError):
                continue
            path = os.path.join(audio_dir, audio_id + ".wav")
            if not os.path.exists(path):
                continue
            try:
                with wave.open(path, "rb") as wav:
                    seconds = wav.getnframes() / float(wav.getframerate())
            except wave.Error:
                continue
            cjk = len(CJK.findall(script))
            if cjk < 20 or seconds <= 0:
                continue
            rows.append({"audio_id": audio_id, "cjk": cjk, "seconds": round(seconds, 1),
                         "chars_per_minute": round(cjk / seconds * 60, 1)})
    if not rows:
        print("no usable audio; nothing measured")
        return 1
    rates = [row["chars_per_minute"] for row in rows]
    total_cjk = sum(row["cjk"] for row in rows)
    total_seconds = sum(row["seconds"] for row in rows)
    print(json.dumps(rows, ensure_ascii=False, indent=2))
    print("\nMEASURED")
    print(f"  segments            = {len(rows)}")
    print(f"  pooled chars/minute = {total_cjk / total_seconds * 60:.1f}")
    print(f"  mean of per-segment = {sum(rates) / len(rates):.1f}")
    print(f"  min / max           = {min(rates):.1f} / {max(rates):.1f}")
    print(f"  constant in code    = 260.0")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
