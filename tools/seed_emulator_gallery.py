#!/usr/bin/env python3
"""Download reproducible gallery fixtures and import them into an Android emulator."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import math
from pathlib import Path
import random
import shlex
import shutil
import struct
import subprocess
import time
import zlib


ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "build" / "emulator-gallery"
ALBUM = "/sdcard/Pictures/SwipeCleanDemo"
RELATIVE_PATH = "Pictures/SwipeCleanDemo/"

# Public Unsplash image URLs. License: https://unsplash.com/license
SOURCES = [
    "photo-1470770841072-f978cf4d019e",
    "photo-1500530855697-b586d89ba3ee",
    "photo-1441974231531-c6227db76b6e",
    "photo-1518837695005-2083093ee35b",
    "photo-1464822759023-fed622ff2c3b",
    "photo-1501785888041-af3ef285b470",
    "photo-1469474968028-56623f02e42e",
    "photo-1472396961693-142e6e269027",
    "photo-1519681393784-d120267933ba",
    "photo-1447752875215-b2761acb3c5d",
    "photo-1511497584788-876760111969",
    "photo-1506744038136-46273834b3fb",
    "photo-1473448912268-2022ce9509d8",
    "photo-1433086966358-54859d0ed716",
    "photo-1500534314209-a25ddb2bd429",
    "photo-1507525428034-b723cf961d3e",
    "photo-1552053831-71594a27632d",
    "photo-1517849845537-4d257902454a",
    "photo-1514888286974-6c03e2ca1dba",
    "photo-1518791841217-8f162f1e1131",
    "photo-1511818966892-d7d671e672a2",
    "photo-1449824913935-59a10b8d2000",
    "photo-1442512595331-e89e73853f31",
    "photo-1504674900247-0877df9cc836",
]


def find_adb():
    installed = shutil.which("adb")
    if installed:
        return installed
    properties = ROOT / "local.properties"
    if properties.exists():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")
                candidate = Path(sdk) / "platform-tools" / "adb"
                if candidate.exists():
                    return str(candidate)
    raise RuntimeError("adb не найден. Добавьте platform-tools в PATH или передайте --adb.")


def download(entry):
    target = CACHE / "images" / entry["filename"]
    if target.exists() and target.read_bytes().startswith(b"\xff\xd8"):
        return
    temporary = target.with_suffix(".part")
    try:
        subprocess.run([
            "curl", "--fail", "--location", "--silent", "--show-error",
            "--retry", "2", "--connect-timeout", "10", "--max-time", "30",
            "--output", str(temporary), entry["url"],
        ], check=True)
        if not temporary.read_bytes().startswith(b"\xff\xd8"):
            raise RuntimeError(f"Сервер вернул не JPEG: {entry['url']}")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def write_pattern(path, kind):
    """Exact synthetic luminance fixtures for exposure, detail and smooth-edge checks."""
    width, height = 960, 720

    def chunk(name, payload):
        return (struct.pack(">I", len(payload)) + name + payload +
                struct.pack(">I", zlib.crc32(name + payload)))

    rows = []
    for y in range(height):
        row = bytearray([0])  # PNG scanline filter: None
        for x in range(width):
            if kind == "dark":
                value = 7 + x * 8 // width
            elif kind == "black":
                value = 0
            elif kind == "bright":
                value = 249 + x * 6 // width
            elif kind == "white":
                value = 255
            elif kind == "flat":
                value = 120
            elif kind == "low_detail":
                value = 115 + x * 6 // width + y * 3 // height
            elif kind == "soft_horizontal":
                value = round(40 + 170 / (1 + math.exp(-(x - width / 2) / 80)))
            else:
                value = round(40 + 170 / (1 + math.exp(-(y - height / 2) / 60)))
            row.extend((value, value, value))
        rows.append(row)
    png = (b"\x89PNG\r\n\x1a\n" +
           chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) +
           chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b""))
    if not path.exists() or path.read_bytes() != png:
        path.write_bytes(png)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554", help="ADB serial эмулятора")
    parser.add_argument("--adb", help="Путь к adb, если его нет в PATH или local.properties")
    args = parser.parse_args()
    adb = [args.adb or find_adb(), "-s", args.serial]

    def shell(*arguments):
        return subprocess.check_output(adb + ["shell", shlex.join(arguments)], text=True).strip()

    if shell("getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Скрипт предназначен только для эмулятора Android.")
    if not shutil.which("curl"):
        raise RuntimeError("Для скачивания нужен curl.")

    entries = []
    for source in SOURCES:
        entries.append({
            "category": "photo", "source": f"https://images.unsplash.com/{source}",
            "url": f"https://images.unsplash.com/{source}?fm=jpg&w=1440&fit=max&q=85",
        })
    for source in SOURCES[:8]:
        entries.append({
            "category": "blur", "source": f"https://images.unsplash.com/{source}",
            "url": f"https://images.unsplash.com/{source}?fm=jpg&w=800&fit=max&q=85&blur=100",
        })
    for kind in ("dark", "black", "bright", "white", "flat", "low_detail", "soft_horizontal", "soft_vertical"):
        entries.append({"category": kind, "source": "synthetic test pattern"})

    random.Random(20260927).shuffle(entries)
    images = CACHE / "images"
    images.mkdir(parents=True, exist_ok=True)
    for index, entry in enumerate(entries, 1):
        extension = "jpg" if "url" in entry else "png"
        entry["filename"] = f"swipe_demo_{index:02d}_{entry['category']}.{extension}"

    print("Скачиваем 24 фото и 8 размытых вариантов из Unsplash…", flush=True)
    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(download, entry) for entry in entries if "url" in entry]
        for completed, future in enumerate(as_completed(futures), 1):
            future.result()
            if completed % 8 == 0:
                print(f"Фото готовы: {completed}/{len(futures)}", flush=True)
    for entry in entries:
        if "url" not in entry:
            write_pattern(images / entry["filename"], entry["category"])
    (CACHE / "sources.json").write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n")

    print(f"Загружаем {len(entries)} изображений в {args.serial}…", flush=True)
    shell("mkdir", "-p", ALBUM)
    # Push only known fixtures. Stable filenames let subsequent runs update the same album.
    for entry in entries:
        subprocess.run(adb + ["push", "--sync", str(images / entry["filename"]), ALBUM + "/"],
                       check=True, capture_output=True, text=True)
    for entry in entries:
        shell("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
              "-d", f"file://{ALBUM}/{entry['filename']}")

    deadline = time.monotonic() + 20
    while True:
        indexed = shell("content", "query", "--uri", "content://media/external/images/media",
                        "--projection", "_display_name", "--where", f"relative_path='{RELATIVE_PATH}'")
        if all(entry["filename"] in indexed for entry in entries):
            break
        if time.monotonic() >= deadline:
            raise RuntimeError("Файлы загружены, но MediaStore ещё не добавил всю подборку. Повторите команду.")
        time.sleep(0.5)
    total_mb = sum((images / entry["filename"]).stat().st_size for entry in entries) / 1024**2
    print(f"Готово: {len(entries)} изображений ({total_mb:.1f} МБ), альбом SwipeCleanDemo.")
    print(f"Источники: {CACHE / 'sources.json'}")
    print("Перезапустите SwipeClean, чтобы перечитать галерею. Анализ запускается кнопкой в настройках.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, OSError) as error:
        raise SystemExit(f"Ошибка: {error}") from error
