#!/usr/bin/env python3
"""Repackage the pinned upstream model into a deterministic, Android-readable ZIP.

No network calls. No model conversion/weight modification. Run from any directory:
python3 tools/package_parakeet.py path/to/upstream.tar.bz2
Only the four inference files are included; upstream test recordings are excluded.
"""
import hashlib
from pathlib import Path
import sys
import tarfile
import zipfile

MODEL = "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-streaming-1120ms"
ARCHIVE_SHA256 = "4788229a6dd03be33f8243ccee48e33a8d15df7b448cb99150b0ccddd1b02d74"
FILES = {"encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"}
ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    archive = Path(sys.argv[1])
    if digest(archive) != ARCHIVE_SHA256:
        raise SystemExit("Wrong or damaged upstream model archive")
    output = ROOT / "app/src/main/assets/models" / f"{MODEL}.zip"
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = output.with_suffix(".zip.part")
    found = set()
    try:
        with tarfile.open(archive, "r|bz2") as source, zipfile.ZipFile(staging, "w", compression=zipfile.ZIP_STORED) as dest:
            for member in source:
                prefix = MODEL + "/"
                name = member.name.removeprefix(prefix)
                if not member.name.startswith(prefix) or name not in FILES:
                    continue
                if name in found or not member.isfile() or member.size <= 0 or member.size > 800_000_000:
                    raise ValueError("Unexpected model archive member")
                found.add(name)
                info = zipfile.ZipInfo(member.name, date_time=(2026, 4, 7, 0, 0, 0))
                info.compress_type = zipfile.ZIP_STORED
                info.external_attr = 0o100644 << 16
                info.file_size = member.size
                file_hash = hashlib.sha256()
                with source.extractfile(member) as data, dest.open(info, "w") as target:
                    while block := data.read(1024 * 1024):
                        target.write(block)
                        file_hash.update(block)
                print(f"{name}: {member.size} bytes, sha256 {file_hash.hexdigest()}", flush=True)
        if found != FILES:
            raise ValueError("Model archive is missing inference files")
        staging.replace(output)
        print(f"Bundle: {output.stat().st_size} bytes, sha256 {digest(output)}", flush=True)
    finally:
        staging.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
