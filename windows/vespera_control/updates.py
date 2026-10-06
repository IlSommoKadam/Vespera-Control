# -*- coding: utf-8 -*-
"""Controllo aggiornamenti Windows via cartella Mega pubblica (come StarStacKadam)."""

from __future__ import annotations

import json
import os
import tempfile
import threading
import urllib.request
import webbrowser
from pathlib import Path

MEGA_FOLDER = "https://mega.nz/folder/6xgAFYqY#fvY3ii_8ytQtujuGIMATmQ"
VERSION_FILE = "vespera-control-win-version.json"
LOCAL_VERSION = "0.2.44"
LOCAL_CODE = 47


def compare_version(left: str, right: str) -> int:
    a = [int(x) if x.isdigit() else 0 for x in (left or "0").split(".")]
    b = [int(x) if x.isdigit() else 0 for x in (right or "0").split(".")]
    size = max(3, len(a), len(b))
    a += [0] * (size - len(a))
    b += [0] * (size - len(b))
    for i in range(size):
        if a[i] != b[i]:
            return 1 if a[i] > b[i] else -1
    return 0


def fetch_manifest_from_share() -> dict | None:
    """Prima prova C:\\WORK\\ESA\\Share\\pub\\Pubblici (cartella pubblica Mega), poi solo metadati."""
    local = Path(r"C:\WORK\ESA\Share\pub\Pubblici") / VERSION_FILE
    if local.is_file():
        try:
            return json.loads(local.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            pass
    return None


def is_newer(remote: dict) -> bool:
    ver = str(remote.get("version") or "")
    code = int(remote.get("versionCode") or 0)
    by_name = compare_version(ver, LOCAL_VERSION)
    if by_name != 0:
        return by_name > 0
    return code > LOCAL_CODE


def check_async(callback) -> None:
    def work() -> None:
        try:
            remote = fetch_manifest_from_share()
            if remote is None:
                callback(None, None)
                return
            if is_newer(remote):
                callback(remote, None)
            else:
                callback(None, None)
        except Exception:
            callback(None, None)

    threading.Thread(target=work, daemon=True).start()


def open_download_page() -> None:
    webbrowser.open(MEGA_FOLDER)
