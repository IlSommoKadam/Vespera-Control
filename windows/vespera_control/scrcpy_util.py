# -*- coding: utf-8 -*-
"""Utilità scrcpy riprese da Vespera Win Helper."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
from pathlib import Path

CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
SOFTWARE_ENCODER = "c2.android.avc.encoder"
FALLBACK_HELP = (
    "--tcpip --serial --max-size --video-bit-rate --video-codec --video-encoder "
    "--always-on-top --turn-screen-off --stay-awake --no-audio --window-title"
)


def has_opt(help_text: str, option: str) -> bool:
    return bool(re.search(rf"(?:^|\s){re.escape(option)}(?=$|\s|,|=|\[)", help_text))


def locate_scrcpy() -> Path | None:
    found = shutil.which("scrcpy")
    if found:
        return Path(found)
    here = Path(__file__).resolve().parent.parent
    roots = [
        here,
        here.parent,
        Path.home() / "Downloads",
        Path.home() / "Desktop",
        Path.home() / "scrcpy",
        Path(r"C:\scrcpy"),
        Path(r"C:\Tools"),
    ]
    local = os.environ.get("LOCALAPPDATA")
    if local:
        roots.append(Path(local) / "Programs" / "scrcpy")
        link = Path(local) / "Microsoft" / "WinGet" / "Links" / "scrcpy.exe"
        if link.is_file():
            return link
    seen: set[Path] = set()
    for root in roots:
        if root in seen or not root.exists():
            continue
        seen.add(root)
        direct = root / "scrcpy.exe"
        if direct.is_file():
            return direct
        try:
            children = list(root.iterdir())
        except OSError:
            continue
        for child in children:
            if child.is_dir() and "scrcpy" in child.name.lower():
                exe = child / "scrcpy.exe"
                if exe.is_file():
                    return exe
    return None


def scrcpy_help(exe: Path) -> str:
    try:
        proc = subprocess.run(
            [str(exe), "--help"],
            capture_output=True,
            timeout=12,
            text=True,
            encoding="utf-8",
            errors="replace",
            creationflags=CREATE_NO_WINDOW,
        )
        text = ((proc.stdout or "") + "\n" + (proc.stderr or "")).strip()
    except Exception:
        text = ""
    if "--tcpip" not in text and "--serial" not in text:
        text = FALLBACK_HELP
    return text


def build_scrcpy_command(
    exe: str,
    serial: str,
    *,
    max_size: str = "1920",
    bitrate: str = "8M",
    always_on_top: bool = False,
    turn_screen_off: bool = False,
    stay_awake: bool = True,
    no_audio: bool = True,
    title: str = "Vespera Pi",
    video_encoder: str = "",
    help_text: str = "",
) -> list[str]:
    help_text = help_text or FALLBACK_HELP
    cmd = [exe]
    if has_opt(help_text, "--tcpip"):
        cmd.append(f"--tcpip={serial}")
    elif has_opt(help_text, "--serial"):
        cmd.extend(["--serial", serial])
    else:
        cmd.extend(["-s", serial])
    size = (max_size or "").strip()
    if size and size != "0":
        if has_opt(help_text, "--max-size"):
            cmd.append(f"--max-size={size}")
        else:
            cmd.extend(["-m", size])
    rate = (bitrate or "").strip()
    if rate:
        if has_opt(help_text, "--video-bit-rate"):
            cmd.append(f"--video-bit-rate={rate}")
        elif has_opt(help_text, "--bit-rate"):
            cmd.append(f"--bit-rate={rate}")
        else:
            cmd.extend(["-b", rate])

    def flag(long_name: str, short_name: str | None = None) -> None:
        if has_opt(help_text, long_name):
            cmd.append(long_name)
        elif short_name and has_opt(help_text, short_name):
            cmd.append(short_name)

    if always_on_top:
        flag("--always-on-top")
    if turn_screen_off:
        flag("--turn-screen-off", "-S")
    if stay_awake:
        flag("--stay-awake", "-w")
    if no_audio:
        flag("--no-audio")
    encoder = (video_encoder or "").strip()
    if encoder:
        if has_opt(help_text, "--video-codec"):
            cmd.append("--video-codec=h264")
        if has_opt(help_text, "--video-encoder"):
            cmd.append(f"--video-encoder={encoder}")
    if title and has_opt(help_text, "--window-title"):
        cmd.append(f"--window-title={title}")
    return cmd
