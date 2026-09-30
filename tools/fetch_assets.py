#!/usr/bin/env python3
"""Retrieve pinned build assets; binaries stay out of Git and remain bundled in the APK."""
import hashlib
from pathlib import Path
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MODEL = "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-streaming-1120ms"

def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def fetch(url, path, expected):
    if path.exists() and sha(path) == expected:
        print("Verified", path.name)
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    staging = path.with_suffix(path.suffix + ".part")
    try:
        print("Downloading", path.name, flush=True)
        with urllib.request.urlopen(url, timeout=60) as source, staging.open("wb") as target:
            while block := source.read(1024 * 1024):
                target.write(block)
        if sha(staging) != expected:
            raise RuntimeError("Checksum mismatch for " + path.name)
        staging.replace(path)
    finally:
        staging.unlink(missing_ok=True)

def main():
    model_zip = ROOT / "app/src/main/assets/models" / (MODEL + ".zip")
    expected_zip = "ab5d28779f17ec0ce60ec537adc33f7fd5730d0adc68db0ab58ad5596b84eb4e"
    if not model_zip.exists() or sha(model_zip) != expected_zip:
        archive = ROOT / ".tools/downloads" / (MODEL + ".tar.bz2")
        fetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" + archive.name,
              archive, "4788229a6dd03be33f8243ccee48e33a8d15df7b448cb99150b0ccddd1b02d74")
        subprocess.run([sys.executable, str(ROOT / "tools/package_parakeet.py"), str(archive)], check=True)
        if sha(model_zip) != expected_zip:
            raise RuntimeError("Repackaged model checksum mismatch")
    fetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar",
          ROOT / "app/libs/sherpa-onnx-1.13.8.aar",
          "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96")
    print("Build assets verified. The APK includes the model for offline first use.")

if __name__ == "__main__":
    main()
