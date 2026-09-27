#!/usr/bin/env python3
"""Fetch an optional ONNX fixture for instrumented tests. App builds need no model file."""
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = json.loads((ROOT / "app/src/main/assets/models/embedding-model.json").read_text())
SHA256 = MANIFEST["sha256"]
URL = MANIFEST["url"]
DESTINATION = ROOT / "build/model-fixtures/siglip2-base-patch16-224-int8.onnx"


def digest(path: Path) -> str:
    with path.open("rb") as source:
        result = hashlib.sha256()
        while chunk := source.read(1024 * 1024):
            result.update(chunk)
        return result.hexdigest()


def main() -> None:
    if (DESTINATION.exists() and DESTINATION.stat().st_size == MANIFEST["sizeBytes"]
            and digest(DESTINATION) == SHA256):
        print("Model already present; SHA-256 verified.")
        return
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    temporary = DESTINATION.with_suffix(".download")
    try:
        with urllib.request.urlopen(URL, timeout=120) as response, temporary.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        if temporary.stat().st_size != MANIFEST["sizeBytes"] or digest(temporary) != SHA256:
            raise RuntimeError("Model checksum mismatch; existing test fixture was not changed")
        temporary.replace(DESTINATION)
        print(f"Model downloaded and verified: {DESTINATION}")
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
