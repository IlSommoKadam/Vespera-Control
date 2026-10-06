# -*- coding: utf-8 -*-
"""Vespera Win Helper: avvia scrcpy su un Android a IP definito e mostra l'ultimo fotogramma."""

from __future__ import annotations

import base64
import calendar
import ctypes
import ftplib
import hashlib
import io
import json
import os
import queue
import re
import shutil
import struct
import subprocess
import sys
import threading
import time
import tkinter as tk
import traceback
import webbrowser
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

BG = "#12141a"
PANEL = "#1b2030"
ENTRY = "#0e1118"
FG = "#e7eaf2"
MUTED = "#9aa3b8"
ACCENT = "#8eb6ff"
OK = "#3dd68c"
WARN = "#ffb020"
ERR = "#ff6b6b"
PRIMARY = "#2f6fe0"

CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
APP_USER_MODEL_ID = "IlSommoKadam.VesperaWinHelper"

try:
    ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(APP_USER_MODEL_ID)
except Exception:
    pass

FALLBACK_HELP = (
    "--tcpip --serial --max-size --video-bit-rate --video-codec --video-encoder "
    "--always-on-top --turn-screen-off --stay-awake --no-audio --window-title"
)
SOFTWARE_ENCODER = "c2.android.avc.encoder"
SCRCPY_RELEASES = "https://github.com/Genymobile/scrcpy/releases/latest"


class HelperError(Exception):
    """Errore atteso, da mostrare all'utente senza traceback."""


def enable_dpi() -> None:
    try:
        shcore = ctypes.WinDLL("shcore")
        shcore.SetProcessDpiAwareness.argtypes = [ctypes.c_int]
        shcore.SetProcessDpiAwareness.restype = ctypes.c_long
        shcore.SetProcessDpiAwareness(1)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass


def acquire_mutex():
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_bool, ctypes.c_wchar_p]
    kernel32.CreateMutexW.restype = ctypes.c_void_p
    kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
    kernel32.CloseHandle.restype = ctypes.c_bool
    handle = kernel32.CreateMutexW(None, False, "Local\\VesperaWinHelperMutex")
    if not handle:
        return None
    if ctypes.get_last_error() == 183:
        kernel32.CloseHandle(handle)
        return False
    return handle


def release_mutex(handle) -> None:
    if not handle:
        return
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
        kernel32.CloseHandle.restype = ctypes.c_bool
        kernel32.CloseHandle(handle)
    except Exception:
        pass


def appdata_dir() -> Path:
    root = os.environ.get("LOCALAPPDATA")
    path = Path(root) / "VesperaWinHelper" if root else Path(__file__).resolve().parent
    path.mkdir(parents=True, exist_ok=True)
    return path


def settings_path() -> Path:
    return appdata_dir() / "settings.json"


def last_frame_path() -> Path:
    return appdata_dir() / "ultimo.png"


def atomic_write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(path)


PNG_SIG = b"\x89PNG\r\n\x1a\n"


def normalize_png(data: bytes) -> bytes:
    """Accetta il PNG di `adb exec-out screencap` e ripara il CRLF di adb su Windows."""
    if not data:
        raise ValueError("adb non ha restituito dati")
    start = data.find(PNG_SIG)
    if start >= 0:
        return data[start:]
    fixed = data.replace(b"\r\n", b"\n")
    start = fixed.find(PNG_SIG)
    if start >= 0:
        return fixed[start:]
    raise ValueError("la risposta di adb non è un'immagine PNG")


def png_size(data: bytes) -> tuple[int, int]:
    if len(data) >= 24 and data.startswith(b"\x89PNG"):
        width, height = struct.unpack(">II", data[16:24])
        if 0 < width < 20000 and 0 < height < 20000:
            return int(width), int(height)
    return 0, 0


def parse_endpoint(ip_text: str, port_text: str) -> tuple[str, int]:
    raw = (ip_text or "").strip()
    if not raw:
        raise HelperError("Inserisci l'IP del dispositivo Android.")
    port_raw = (port_text or "").strip() or "5555"
    host = raw
    if raw.count(":") == 1:
        maybe_host, maybe_port = raw.rsplit(":", 1)
        if maybe_port.isdigit() and maybe_host:
            host = maybe_host
            if port_raw == "5555":
                port_raw = maybe_port
    if not host or any(ch.isspace() for ch in host) or "/" in host:
        raise HelperError("Indirizzo non valido.")
    try:
        port = int(port_raw)
    except ValueError as exc:
        raise HelperError("Porta non valida.") from exc
    if not 1 <= port <= 65535:
        raise HelperError("La porta deve essere tra 1 e 65535.")
    return host, port


def has_opt(help_text: str, option: str) -> bool:
    return bool(re.search(rf"(?:^|\s){re.escape(option)}(?=$|\s|,|=|\[)", help_text))


def build_scrcpy_command(
    exe: str,
    serial: str,
    *,
    max_size: str,
    bitrate: str,
    always_on_top: bool,
    turn_screen_off: bool,
    stay_awake: bool,
    no_audio: bool,
    title: str,
    help_text: str,
    video_encoder: str = "",
) -> list[str]:
    cmd = [exe]
    if has_opt(help_text, "--tcpip"):
        cmd.append(f"--tcpip={serial}")
    elif has_opt(help_text, "--serial"):
        cmd.extend(["--serial", serial])
    else:
        cmd.extend(["-s", serial])

    size = (max_size or "").strip()
    if size and size != "0":
        if not re.fullmatch(r"\d{2,5}", size):
            raise HelperError("Il lato massimo deve essere un numero di pixel, per esempio 1920.")
        if has_opt(help_text, "--max-size"):
            cmd.append(f"--max-size={size}")
        else:
            cmd.extend(["-m", size])

    rate = (bitrate or "").strip()
    if rate:
        if not re.fullmatch(r"\d+(?:\.\d+)?[KMG]?", rate, flags=re.IGNORECASE):
            raise HelperError("Bitrate non valido. Esempi: 8M, 2M, 8000000.")
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


def locate_scrcpy() -> Path | None:
    found = shutil.which("scrcpy")
    if found:
        return Path(found)

    here = Path(__file__).resolve().parent
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


def locate_adb(scrcpy_exe: Path | None) -> Path | None:
    if scrcpy_exe is not None:
        sibling = scrcpy_exe.parent / "adb.exe"
        if sibling.is_file():
            return sibling
    found = shutil.which("adb")
    return Path(found) if found else None


def run_cmd(args: list[str], timeout: float, *, text: bool = False) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(
            args,
            capture_output=True,
            timeout=timeout,
            text=text,
            encoding="utf-8" if text else None,
            errors="replace" if text else None,
            creationflags=CREATE_NO_WINDOW,
        )
    except FileNotFoundError as exc:
        raise HelperError(f"Programma non trovato: {args[0]}") from exc
    except subprocess.TimeoutExpired as exc:
        raise HelperError("Tempo scaduto in attesa di adb.") from exc


def list_adb_devices(adb: Path) -> list[tuple[str, str]]:
    proc = run_cmd([str(adb), "devices"], 15, text=True)
    devices: list[tuple[str, str]] = []
    for line in (proc.stdout or "").splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            devices.append((parts[0], parts[1]))
    return devices


def adb_connect(adb: Path, serial: str) -> str:
    proc = run_cmd([str(adb), "connect", serial], 20, text=True)
    message = ((proc.stdout or "") + "\n" + (proc.stderr or "")).strip()
    lowered = message.lower()
    if "connected to" in lowered or "already connected" in lowered:
        return message
    raise HelperError(message or f"Connessione a {serial} non riuscita.")


def adb_screencap(adb: Path, serial: str) -> bytes:
    proc = run_cmd([str(adb), "-s", serial, "exec-out", "screencap", "-p"], 25)
    stderr = (proc.stderr or b"").decode("utf-8", "replace").strip()
    if proc.returncode == 0 and proc.stdout:
        try:
            return normalize_png(proc.stdout)
        except ValueError:
            pass
    detail = stderr
    if not detail and proc.stdout and len(proc.stdout) < 400:
        detail = proc.stdout.decode("utf-8", "replace").strip()
    raise HelperError(detail or "Acquisizione dello schermo non riuscita.")


def detect_wifi_ip(adb: Path, usb_serial: str) -> str:
    attempts = (
        ["shell", "ip", "route"],
        ["shell", "ip", "-f", "inet", "addr", "show", "wlan0"],
        ["shell", "ip", "-f", "inet", "addr"],
    )
    for args in attempts:
        proc = run_cmd([str(adb), "-s", usb_serial, *args], 12, text=True)
        text = proc.stdout or ""
        source = re.search(r"\bsrc\s+(\d+\.\d+\.\d+\.\d+)\b", text)
        if source and not source.group(1).startswith("127."):
            return source.group(1)
        inet = re.search(r"\binet\s+(\d+\.\d+\.\d+\.\d+)\b", text)
        if inet and not inet.group(1).startswith("127."):
            return inet.group(1)
    raise HelperError("Non riesco a leggere l'IP Wi-Fi dal telefono. Inseriscilo a mano.")


STACK_EXT = {".jpg", ".jpeg", ".png", ".tif", ".tiff"}
PREVIEW_EXT = {".jpg", ".jpeg", ".png"}


def stack_cache_dir() -> Path:
    path = appdata_dir() / "stacks"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _ftp_entries(ftp: ftplib.FTP) -> list[tuple[str, str, float, int]]:
    try:
        found: list[tuple[str, str, float, int]] = []
        for name, facts in ftp.mlsd():
            if name in {".", ".."}:
                continue
            kind = facts.get("type", "file")
            if kind in {"cdir", "pdir"}:
                continue
            modify = facts.get("modify") or ""
            mtime = 0.0
            if len(modify) >= 14 and modify[:14].isdigit():
                mtime = calendar.timegm(time.strptime(modify[:14], "%Y%m%d%H%M%S"))
            size = int(facts.get("size") or 0)
            found.append((name, "dir" if kind == "dir" else "file", mtime, size))
        return found
    except Exception:
        lines: list[str] = []
        try:
            ftp.retrlines("LIST", lines.append)
        except ftplib.Error:
            lines = []
        parsed: list[tuple[str, str, float, int]] = []
        for line in lines:
            parts = line.split(maxsplit=8)
            if len(parts) < 9 or parts[8] in {".", ".."}:
                continue
            size = int(parts[4]) if parts[4].isdigit() else 0
            parsed.append((parts[8], "dir" if parts[0].startswith("d") else "file", 0.0, size))
        return parsed


def _is_stack_dir(name: str) -> bool:
    lowered = name.lower()
    return "image" in lowered or "stack" in lowered


def choose_stack_files(groups: list[list[dict]]) -> list[dict]:
    """Una cartella = un'osservazione: progressivi solo nella prima, altrove solo l'ultimo stack."""
    chosen: list[dict] = []
    seen: set[str] = set()

    def add(item: dict) -> None:
        if item["path"] in seen:
            return
        seen.add(item["path"])
        chosen.append(item)

    for index, group in enumerate(groups[:8]):
        if not group:
            continue
        if index == 0:
            for item in group[:6]:
                add(item)
        else:
            add(group[0])
    return chosen


def frames_for_observation(group: list[dict], *, live: bool) -> list[dict]:
    """Quanti frame tenere dentro una cartella osservazione."""
    if not group:
        return []
    return group[:6] if live else group[:1]


def _prefer_preview_files(files: list[dict]) -> list[dict]:
    files = sorted(files, key=lambda item: (item["mtime"], item["name"]), reverse=True)
    outputs = [
        item
        for item in files
        if "output" in item["name"].lower() and item["ext"] in PREVIEW_EXT
    ]
    if outputs:
        return outputs
    previews = [item for item in files if item["ext"] in PREVIEW_EXT]
    return previews or files


def _frame_label(name: str) -> str:
    match = re.search(r"img-(\d+)", name, re.I)
    if match:
        return f"Frame {int(match.group(1))}"
    return name


def _open_vespera_ftp(host: str) -> ftplib.FTP:
    host = (host or "").strip() or "192.168.1.4"
    last_error: Exception | None = None
    for port in (2121, 21):
        ftp = ftplib.FTP()
        try:
            ftp.connect(host, port, timeout=8)
        except OSError as exc:
            last_error = exc
            continue
        try:
            try:
                ftp.login()
            except ftplib.error_perm:
                ftp.login("anonymous", "anonymous@")
        except ftplib.all_errors as exc:
            last_error = exc
            try:
                ftp.close()
            except Exception:
                pass
            continue
        ftp.set_pasv(True)
        try:
            ftp.encoding = "utf-8"
        except Exception:
            pass
        return ftp
    raise HelperError(
        f"Non raggiungo le foto su {host}. Il disco del Vespera è sul Raspberry Pi, porta 2121."
    ) from last_error


def _list_observation_groups(ftp: ftplib.FTP, obs_limit: int = 8) -> list[list[dict]]:
    ftp.cwd("/")
    root = _ftp_entries(ftp)
    user = next((name for name, kind, _mtime, _size in root if kind == "dir" and name.lower() == "user"), "")
    if not user:
        raise HelperError("Sul Vespera non trovo la cartella USER.")
    ftp.cwd(user)
    observations = [
        item
        for item in _ftp_entries(ftp)
        if item[1] == "dir" and "expert" not in item[0].lower() and "dark" not in item[0].lower()
    ]
    observations.sort(key=lambda item: (item[2], item[0]), reverse=True)
    groups: list[list[dict]] = []
    for obs_name, _kind, _mtime, _size in observations[:obs_limit]:
        ftp.cwd("/")
        ftp.cwd(user)
        ftp.cwd(obs_name)
        here = _ftp_entries(ftp)
        folders = [name for name, kind, _mtime, _size in here if kind == "dir" and _is_stack_dir(name)]
        if not folders:
            folders = ["."]
        files: list[dict] = []
        for folder in folders:
            if folder != ".":
                ftp.cwd("/")
                ftp.cwd(user)
                ftp.cwd(obs_name)
                ftp.cwd(folder)
            for filename, kind, mtime, size in _ftp_entries(ftp):
                ext = Path(filename).suffix.lower()
                if kind != "file" or ext not in STACK_EXT or size > 40_000_000:
                    continue
                remote = "/".join(part for part in (user, obs_name, "" if folder == "." else folder, filename) if part)
                files.append(
                    {
                        "path": remote,
                        "name": filename,
                        "ext": ext,
                        "observation": obs_name,
                        "folder": folder,
                        "mtime": mtime,
                        "size": size,
                    }
                )
        preferred = _prefer_preview_files(files)
        if preferred:
            groups.append(preferred)
    return groups


def _list_observation_stacks(ftp: ftplib.FTP) -> list[dict]:
    return choose_stack_files(_list_observation_groups(ftp))


def _download_ftp_file(ftp: ftplib.FTP, remote: str) -> bytes:
    directory, _, filename = remote.rpartition("/")
    ftp.cwd("/")
    for part in directory.split("/"):
        if part:
            ftp.cwd(part)
    buffer = io.BytesIO()
    ftp.retrbinary(f"RETR {filename}", buffer.write)
    data = buffer.getvalue()
    if not data:
        raise HelperError(f"File vuoto: {filename}")
    return data


def _preview_pngs(data: bytes, full_edge: int = 1600, thumb_edge: int = 180) -> tuple[bytes, bytes]:
    from PIL import Image

    image = Image.open(io.BytesIO(data))
    image = image.convert("RGB")
    image.thumbnail((full_edge, full_edge), Image.Resampling.LANCZOS)
    full = io.BytesIO()
    image.save(full, format="PNG")
    thumb = image.copy()
    thumb.thumbnail((thumb_edge, thumb_edge), Image.Resampling.LANCZOS)
    thumb_bytes = io.BytesIO()
    thumb.save(thumb_bytes, format="PNG")
    return full.getvalue(), thumb_bytes.getvalue()


def _observation_parts(name: str) -> tuple[str, str]:
    match = re.fullmatch(
        r"(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-\d{2}_observation_(.+)",
        name,
    )
    if not match:
        return name, ""
    year, month, day, hour, minute, target = match.groups()
    return target, f"{day}/{month}/{year} {hour}:{minute}"


def _observation_label(name: str) -> str:
    target, when = _observation_parts(name)
    return f"{target} · {when}" if when else target


def _materialize_shot(ftp: ftplib.FTP, host: str, item: dict, cache: Path) -> dict:
    key = hashlib.sha1(f"{host}|{item['path']}|{item['size']}|{int(item['mtime'])}".encode()).hexdigest()[:16]
    full_path = cache / f"{key}.png"
    thumb_path = cache / f"{key}_t.png"
    if not full_path.is_file() or not thumb_path.is_file():
        raw = _download_ftp_file(ftp, item["path"])
        full_bytes, thumb_bytes = _preview_pngs(raw)
        full_path.write_bytes(full_bytes)
        thumb_path.write_bytes(thumb_bytes)
    target, when_obs = _observation_parts(item["observation"])
    # Ora = mtime dell'ultimo stack/output (come in anteprima), non creazione cartella.
    when = (
        time.strftime("%d/%m/%Y %H:%M", time.localtime(item["mtime"]))
        if item.get("mtime")
        else when_obs
    )
    return {
        "label": target,
        "detail": item["name"],
        "frame": _frame_label(item["name"]),
        "when": when,
        "full": str(full_path),
        "thumb": str(thumb_path),
        "mtime": item["mtime"],
        "observation": item["observation"],
    }


def load_vespera_folders(host: str, obs_limit: int = 8) -> list[dict]:
    host = (host or "").strip() or "192.168.1.4"
    try:
        ftp = _open_vespera_ftp(host)
    except ftplib.all_errors as exc:
        raise HelperError(
            f"Non raggiungo le foto su {host}. Il disco del Vespera è sul Raspberry Pi, porta 2121."
        ) from exc
    try:
        groups = _list_observation_groups(ftp, obs_limit=obs_limit)
        if not groups:
            raise HelperError("Nessuno stack JPEG o TIFF nella cartella USER del Vespera.")
        cache = stack_cache_dir()
        folders: list[dict] = []
        for index, group in enumerate(groups):
            selected = frames_for_observation(group, live=index == 0)
            shots = [_materialize_shot(ftp, host, item, cache) for item in selected]
            if not shots:
                continue
            target, when_obs = _observation_parts(group[0]["observation"])
            when = shots[0].get("when", "") or when_obs
            folders.append(
                {
                    "observation": group[0]["observation"],
                    "label": target,
                    "when": when,
                    "count": len(group),
                    "shots": shots,
                    "thumb": shots[0]["thumb"],
                    "full": shots[0]["full"],
                }
            )
        return folders
    finally:
        try:
            ftp.quit()
        except Exception:
            ftp.close()


def load_vespera_stacks(host: str, limit: int = 8) -> list[dict]:
    """Compatibilità: restituisce i frame scelti in elenco piatto."""
    folders = load_vespera_folders(host, obs_limit=limit)
    shots: list[dict] = []
    for folder in folders:
        shots.extend(folder["shots"])
    return shots


class ZoomViewer(tk.Toplevel):
    """Finestra a tutto schermo ridotto con zoom rotella e trascinamento."""

    def __init__(self, master: tk.Misc, path: Path, title: str = "Vista allargata") -> None:
        super().__init__(master)
        self.title(title or "Vista allargata")
        self.configure(bg="#0b0d12")
        self.transient(master)
        self.grab_set()
        self.geometry("1100x780")
        self.minsize(640, 480)
        self._path = path
        self._scale = 1.0
        self._offset_x = 0.0
        self._offset_y = 0.0
        self._drag: tuple[int, int] | None = None
        self._photo = None
        self._pil = None
        self._resize_after: str | None = None

        bar = ttk.Frame(self, padding=(12, 8))
        bar.pack(fill="x")
        ttk.Label(bar, text=title, style="Hero.TLabel").pack(side="left")
        ttk.Label(bar, text="Rotella = zoom  ·  trascina = sposta  ·  Esc = chiudi", style="Sub.TLabel").pack(
            side="left", padx=(12, 0)
        )
        ttk.Button(bar, text="Apri file", command=self._open_file).pack(side="right", padx=(8, 0))
        ttk.Button(bar, text="Chiudi", command=self.destroy).pack(side="right")

        self.canvas = tk.Canvas(self, bg="#0b0d12", highlightthickness=0, bd=0, cursor="fleur")
        self.canvas.pack(fill="both", expand=True)
        self.canvas.bind("<Configure>", self._on_configure)
        self.canvas.bind("<MouseWheel>", self._on_wheel)
        self.canvas.bind("<ButtonPress-1>", self._on_press)
        self.canvas.bind("<B1-Motion>", self._on_drag)
        self.canvas.bind("<ButtonRelease-1>", self._on_release)
        self.bind("<Escape>", lambda _event: self.destroy())
        self.bind("<plus>", lambda _event: self._zoom_at(1.15))
        self.bind("<minus>", lambda _event: self._zoom_at(1 / 1.15))
        self.bind("<KP_Add>", lambda _event: self._zoom_at(1.15))
        self.bind("<KP_Subtract>", lambda _event: self._zoom_at(1 / 1.15))
        self.protocol("WM_DELETE_WINDOW", self.destroy)
        self.after(80, self._load)

    def _open_file(self) -> None:
        if self._path.is_file():
            os.startfile(self._path)  # type: ignore[attr-defined]

    def _load(self) -> None:
        try:
            from PIL import Image

            self._pil = Image.open(self._path).convert("RGB")
        except Exception as exc:
            self.canvas.create_text(
                self.canvas.winfo_width() // 2,
                self.canvas.winfo_height() // 2,
                text=f"Immagine non apribile:\n{exc}",
                fill=ERR,
                font=("Segoe UI", 12),
                justify="center",
            )
            return
        self._fit()
        self._redraw()

    def _fit(self) -> None:
        if self._pil is None:
            return
        cw = max(self.canvas.winfo_width(), 2)
        ch = max(self.canvas.winfo_height(), 2)
        iw, ih = self._pil.size
        self._scale = min(cw / iw, ch / ih, 1.0)
        self._offset_x = (cw - iw * self._scale) / 2
        self._offset_y = (ch - ih * self._scale) / 2

    def _on_configure(self, event: tk.Event) -> None:
        if event.width < 40 or event.height < 40:
            return
        if self._resize_after is not None:
            self.after_cancel(self._resize_after)
        self._resize_after = self.after(80, self._redraw)

    def _on_wheel(self, event: tk.Event) -> None:
        factor = 1.15 if event.delta > 0 else 1 / 1.15
        self._zoom_at(factor, event.x, event.y)

    def _zoom_at(self, factor: float, x: int | None = None, y: int | None = None) -> None:
        if self._pil is None:
            return
        if x is None:
            x = max(self.canvas.winfo_width(), 2) // 2
        if y is None:
            y = max(self.canvas.winfo_height(), 2) // 2
        old = self._scale
        self._scale = max(0.1, min(8.0, self._scale * factor))
        ratio = self._scale / old
        self._offset_x = x - (x - self._offset_x) * ratio
        self._offset_y = y - (y - self._offset_y) * ratio
        self._redraw()

    def _on_press(self, event: tk.Event) -> None:
        self._drag = (event.x, event.y)

    def _on_drag(self, event: tk.Event) -> None:
        if self._drag is None:
            return
        dx = event.x - self._drag[0]
        dy = event.y - self._drag[1]
        self._drag = (event.x, event.y)
        self._offset_x += dx
        self._offset_y += dy
        self._redraw()

    def _on_release(self, _event: tk.Event) -> None:
        self._drag = None

    def _redraw(self) -> None:
        self._resize_after = None
        if self._pil is None:
            return
        try:
            from PIL import Image, ImageTk
        except Exception:
            return
        iw, ih = self._pil.size
        w = max(1, int(iw * self._scale))
        h = max(1, int(ih * self._scale))
        if w * h > 40_000_000:
            return
        resample = Image.Resampling.BILINEAR if self._scale >= 1 else Image.Resampling.LANCZOS
        resized = self._pil.resize((w, h), resample)
        self._photo = ImageTk.PhotoImage(resized)
        self.canvas.delete("all")
        self.canvas.create_image(int(self._offset_x), int(self._offset_y), image=self._photo, anchor="nw")
        pct = int(round(self._scale * 100))
        self.canvas.create_text(
            12,
            max(self.canvas.winfo_height(), 2) - 12,
            text=f"{pct}%",
            fill=MUTED,
            font=("Segoe UI", 10),
            anchor="sw",
        )


class App(tk.Tk):
    def __init__(self, mutex=None) -> None:
        super().__init__()
        self.mutex = mutex
        self.title("Vespera Win Helper")
        self.geometry("1180x820")
        self.minsize(1020, 700)
        self.configure(bg=BG)
        # Icona telescopio in titlebar/taskbar (non quella di pythonw).
        try:
            ico = Path(__file__).resolve().parent / "vespera.ico"
            png = Path(__file__).resolve().parent / "vespera_launcher_icon.png"
            if ico.is_file():
                ico_path = str(ico.resolve())
                self.iconbitmap(ico_path)
                self.iconbitmap(default=ico_path)
            if png.is_file():
                from PIL import Image, ImageTk

                image = Image.open(png).convert("RGBA")
                image.thumbnail((64, 64), Image.Resampling.LANCZOS)
                self._app_icon = ImageTk.PhotoImage(image)
                self.iconphoto(True, self._app_icon)
        except Exception:
            pass

        self.queue: queue.Queue = queue.Queue()
        self.proc: subprocess.Popen | None = None
        self.devices: list[dict] = []
        self._png_bytes: bytes | None = None
        self._photo = None
        self._closing = False
        self._loading = False
        self._capture_busy = False
        self._capture_pending = False
        self._preview_after: str | None = None
        self._resize_after: str | None = None
        self._save_after: str | None = None
        self._job_lock = threading.Lock()
        self._help_cache: tuple[str, float, str] | None = None
        self._last_error = ""
        self._retried = False
        self._user_stop = False
        self._session_log: list[str] = []
        self._stack_busy = False
        self._stack_pending = False
        self._folders: list[dict] = []
        self._folder_index: int | None = None
        self._shots: list[dict] = []
        self._stack_index = 0
        self._list_photos: list = []
        self._list_rows: list[tuple[int, int, str, int]] = []
        self._list_width = 0
        self._zoom_win: tk.Toplevel | None = None

        self.name = tk.StringVar(value="Android")
        self.ip = tk.StringVar()
        self.port = tk.StringVar(value="5555")
        self.scrcpy_path = tk.StringVar()
        self.max_size = tk.StringVar(value="1920")
        self.bitrate = tk.StringVar(value="8M")
        self.always_on_top = tk.BooleanVar(value=False)
        self.turn_screen_off = tk.BooleanVar(value=False)
        self.stay_awake = tk.BooleanVar(value=True)
        self.no_audio = tk.BooleanVar(value=True)
        self.software_encoder = tk.BooleanVar(value=False)
        self.auto_preview = tk.BooleanVar(value=True)
        self.interval = tk.StringVar(value="15")
        self.vespera_host = tk.StringVar(value="192.168.1.4")
        self.close_with_app = tk.BooleanVar(value=False)
        self.meta = tk.StringVar(value="Nessun frame ancora")

        self._build_style()
        self._build()
        self._load_settings()
        self._load_cached_stacks()
        if not self._shots:
            self._load_saved_frame()
        self._autodetect_scrcpy()
        self._bind_persistence()
        self.refresh_buttons()
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self.bind("<F5>", lambda _event: self.request_stacks())
        self.after(120, self._poll)
        self.after(900, self._startup_preview)

    def _build_style(self) -> None:
        style = ttk.Style(self)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure(".", background=BG, foreground=FG, font=("Segoe UI", 10))
        style.configure("TFrame", background=BG)
        style.configure("Card.TFrame", background=PANEL)
        style.configure("TLabel", background=BG, foreground=FG)
        style.configure("Card.TLabel", background=PANEL, foreground=FG)
        style.configure("Title.TLabel", background=BG, foreground=FG, font=("Segoe UI", 18, "bold"))
        style.configure("Sub.TLabel", background=BG, foreground=MUTED, font=("Segoe UI", 10))
        style.configure("Section.TLabel", background=PANEL, foreground=ACCENT, font=("Segoe UI", 11, "bold"))
        style.configure("Hero.TLabel", background=BG, foreground=ACCENT, font=("Segoe UI", 11, "bold"))
        style.configure("Bar.TCheckbutton", background=BG, foreground=FG)
        style.map(
            "Bar.TCheckbutton",
            background=[("active", BG), ("!active", BG)],
            foreground=[("disabled", MUTED), ("!disabled", FG)],
        )
        style.configure("Muted.TLabel", background=PANEL, foreground=MUTED, font=("Segoe UI", 9))
        style.configure("Meta.TLabel", background=BG, foreground=FG, font=("Segoe UI", 10))
        style.configure("Status.TLabel", background=BG, foreground=MUTED, font=("Segoe UI", 10))
        style.configure(
            "TEntry",
            fieldbackground=ENTRY,
            foreground=FG,
            insertcolor=FG,
            bordercolor="#2c3348",
            lightcolor="#2c3348",
            darkcolor="#2c3348",
            padding=4,
        )
        style.configure(
            "TSpinbox",
            fieldbackground=ENTRY,
            foreground=FG,
            background=PANEL,
            arrowcolor=FG,
            bordercolor="#2c3348",
            padding=2,
        )
        style.configure("TButton", background="#2a3148", foreground=FG, borderwidth=0, padding=(10, 7), focusthickness=1)
        style.map(
            "TButton",
            background=[("pressed", "#1a2030"), ("active", "#36405c"), ("disabled", "#232838")],
            foreground=[("disabled", "#6d7588")],
        )
        style.configure(
            "Primary.TButton",
            background=PRIMARY,
            foreground="#ffffff",
            borderwidth=0,
            padding=(10, 9),
            font=("Segoe UI", 10, "bold"),
        )
        style.map(
            "Primary.TButton",
            background=[("pressed", "#1f5ec4"), ("active", "#3d7ee8"), ("disabled", "#2a3144")],
            foreground=[("disabled", "#8b93a7"), ("!disabled", "#ffffff")],
        )
        style.configure("TCheckbutton", background=PANEL, foreground=FG)
        style.map(
            "TCheckbutton",
            background=[("active", PANEL), ("!active", PANEL)],
            foreground=[("disabled", MUTED), ("!disabled", FG)],
        )
        style.configure("Vertical.TScrollbar", background="#2a3148", troughcolor=BG, arrowcolor=FG, borderwidth=0)

    def _build(self) -> None:
        header = ttk.Frame(self, padding=(16, 12, 16, 4))
        header.grid(row=0, column=0, columnspan=2, sticky="ew")
        header.columnconfigure(0, weight=1)
        ttk.Label(header, text="Vespera Win Helper", style="Title.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Button(header, text="Pagina di scrcpy", command=lambda: webbrowser.open(SCRCPY_RELEASES)).grid(
            row=0, column=1, rowspan=2, sticky="e"
        )
        ttk.Label(
            header,
            text="Avvia scrcpy sul Raspberry Pi e mostra gli ultimi stack del Vespera.",
            style="Sub.TLabel",
        ).grid(row=1, column=0, sticky="w", pady=(2, 0))

        left = ttk.Frame(self, padding=(16, 6, 8, 6))
        left.grid(row=1, column=0, sticky="nsew")
        right = ttk.Frame(self, padding=(8, 6, 16, 6))
        right.grid(row=1, column=1, sticky="nsew")
        self.columnconfigure(0, minsize=460)
        self.columnconfigure(1, weight=1)
        self.rowconfigure(1, weight=1)
        left.columnconfigure(0, weight=1)
        left.rowconfigure(1, weight=1)
        right.columnconfigure(0, weight=1)
        right.rowconfigure(2, weight=1)

        self._build_actions(left)
        form = self._make_scroller(left)
        self._build_device_card(form)
        self._build_options_card(form)
        self._build_log(left)
        self._build_preview(right)

        footer = ttk.Frame(self, padding=(16, 0, 16, 12))
        footer.grid(row=2, column=0, columnspan=2, sticky="ew")
        self.status = ttk.Label(footer, text="Pronto. Indica l'IP e avvia scrcpy.", style="Status.TLabel")
        self.status.grid(row=0, column=0, sticky="w")

    def _make_scroller(self, parent: ttk.Frame) -> ttk.Frame:
        holder = ttk.Frame(parent)
        holder.grid(row=1, column=0, sticky="nsew", pady=(0, 8))
        holder.rowconfigure(0, weight=1)
        holder.columnconfigure(0, weight=1)
        canvas = tk.Canvas(holder, bg=BG, height=240, width=400, highlightthickness=0, bd=0)
        scroll = ttk.Scrollbar(holder, orient="vertical", command=canvas.yview)
        canvas.grid(row=0, column=0, sticky="nsew")
        scroll.grid(row=0, column=1, sticky="ns")
        inner = ttk.Frame(canvas)
        window_id = canvas.create_window((0, 0), window=inner, anchor="nw")

        def sync_region(_event: tk.Event | None = None) -> None:
            canvas.configure(scrollregion=canvas.bbox("all"))

        def sync_width(event: tk.Event) -> None:
            canvas.itemconfigure(window_id, width=event.width)

        def on_wheel(event: tk.Event) -> None:
            if isinstance(event.widget, (tk.Listbox, tk.Text)):
                return
            canvas.yview_scroll(int(-event.delta / 120), "units")

        inner.bind("<Configure>", sync_region)
        canvas.bind("<Configure>", sync_width)
        holder.bind("<Enter>", lambda _event: canvas.bind_all("<MouseWheel>", on_wheel))
        holder.bind("<Leave>", lambda _event: canvas.unbind_all("<MouseWheel>"))
        inner.columnconfigure(0, weight=1)
        return inner

    def _card(self, parent: ttk.Frame, title: str, row: int) -> ttk.Frame:
        card = ttk.Frame(parent, style="Card.TFrame", padding=(10, 8))
        card.grid(row=row, column=0, sticky="ew", pady=(0, 8))
        card.columnconfigure(1, weight=1)
        ttk.Label(card, text=title, style="Section.TLabel").grid(row=0, column=0, columnspan=3, sticky="w", pady=(0, 8))
        return card

    def _build_device_card(self, parent: ttk.Frame) -> None:
        card = self._card(parent, "Dispositivo", 0)
        ttk.Label(card, text="Nome", style="Card.TLabel").grid(row=1, column=0, sticky="w", pady=3)
        ttk.Entry(card, textvariable=self.name, width=12).grid(row=1, column=1, columnspan=2, sticky="ew", pady=3)
        ttk.Label(card, text="IP", style="Card.TLabel").grid(row=2, column=0, sticky="w", pady=3)
        self.ip_entry = ttk.Entry(card, textvariable=self.ip, width=12)
        self.ip_entry.grid(row=2, column=1, columnspan=2, sticky="ew", pady=3)
        self.ip_entry.bind("<Return>", lambda _event: self.start_scrcpy())
        ttk.Label(card, text="Porta", style="Card.TLabel").grid(row=3, column=0, sticky="w", pady=3)
        ttk.Entry(card, textvariable=self.port, width=8).grid(row=3, column=1, sticky="w", pady=3)

        list_row = ttk.Frame(card, style="Card.TFrame")
        list_row.grid(row=5, column=0, columnspan=3, sticky="ew", pady=(8, 0))
        list_row.columnconfigure(0, weight=1)
        self.listbox = tk.Listbox(
            list_row,
            height=2,
            activestyle="none",
            exportselection=False,
            bg=ENTRY,
            fg=FG,
            selectbackground=PRIMARY,
            selectforeground="#ffffff",
            highlightthickness=0,
            relief="flat",
            font=("Segoe UI", 10),
        )
        self.listbox.grid(row=0, column=0, sticky="ew")
        scroll = ttk.Scrollbar(list_row, orient="vertical", command=self.listbox.yview)
        scroll.grid(row=0, column=1, sticky="ns")
        self.listbox.configure(yscrollcommand=scroll.set)
        self.listbox.bind("<<ListboxSelect>>", self.on_list_select)

        buttons = ttk.Frame(card, style="Card.TFrame")
        buttons.grid(row=6, column=0, columnspan=3, sticky="ew", pady=(8, 0))
        ttk.Button(buttons, text="Salva", command=self.save_device).pack(side="left")
        ttk.Button(buttons, text="Nuovo", command=self.new_device).pack(side="left", padx=6)
        ttk.Button(buttons, text="Elimina", command=self.delete_device).pack(side="left")

    def _build_options_card(self, parent: ttk.Frame) -> None:
        card = self._card(parent, "scrcpy", 1)
        ttk.Label(card, text="Programma", style="Card.TLabel").grid(row=1, column=0, sticky="w", pady=3)
        ttk.Entry(card, textvariable=self.scrcpy_path, width=12).grid(row=1, column=1, sticky="ew", pady=3, padx=(6, 6))
        ttk.Button(card, text="Sfoglia", command=self.browse_scrcpy).grid(row=1, column=2, sticky="e", pady=3)

        ttk.Label(card, text="Lato max", style="Card.TLabel").grid(row=2, column=0, sticky="w", pady=3)
        ttk.Entry(card, textvariable=self.max_size, width=10).grid(row=2, column=1, sticky="w", pady=3, padx=(6, 0))
        ttk.Label(card, text="Bitrate", style="Card.TLabel").grid(row=3, column=0, sticky="w", pady=3)
        ttk.Entry(card, textvariable=self.bitrate, width=10).grid(row=3, column=1, sticky="w", pady=3, padx=(6, 0))
        ttk.Label(card, text="Vuoto = valore predefinito di scrcpy", style="Muted.TLabel").grid(
            row=4, column=0, columnspan=3, sticky="w"
        )

        checks = ttk.Frame(card, style="Card.TFrame")
        checks.grid(row=5, column=0, columnspan=3, sticky="ew", pady=(6, 0))
        checks.columnconfigure(0, weight=1)
        checks.columnconfigure(1, weight=1)
        ttk.Checkbutton(checks, text="Sempre in primo piano", variable=self.always_on_top).grid(
            row=0, column=0, sticky="w"
        )
        ttk.Checkbutton(checks, text="Senza audio", variable=self.no_audio).grid(row=0, column=1, sticky="w")
        ttk.Checkbutton(
            checks,
            text="Spegni lo schermo del telefono",
            variable=self.turn_screen_off,
            command=self._toggle_screen_hint,
        ).grid(row=1, column=0, columnspan=2, sticky="w")
        self.screen_hint = ttk.Label(
            card,
            text="Con lo schermo spento questa anteprima può diventare nera. La finestra di scrcpy resta attiva.",
            style="Muted.TLabel",
            wraplength=400,
        )
        self.screen_hint.grid(row=6, column=0, columnspan=3, sticky="w")
        self.screen_hint.grid_remove()
        ttk.Checkbutton(checks, text="Tieni sveglio", variable=self.stay_awake).grid(row=2, column=0, sticky="w")
        ttk.Checkbutton(checks, text="Chiudi scrcpy con questa finestra", variable=self.close_with_app).grid(
            row=2, column=1, sticky="w"
        )
        ttk.Checkbutton(
            checks,
            text="Encoder software (Raspberry Pi)",
            variable=self.software_encoder,
        ).grid(row=3, column=0, columnspan=2, sticky="w")

    def _build_actions(self, parent: ttk.Frame) -> None:
        box = ttk.Frame(parent)
        box.grid(row=0, column=0, sticky="ew", pady=(0, 8))
        box.columnconfigure(0, weight=1)
        box.columnconfigure(1, weight=1)
        self.start_btn = ttk.Button(box, text="Avvia scrcpy", style="Primary.TButton", command=self.start_scrcpy)
        self.start_btn.grid(row=0, column=0, columnspan=2, sticky="ew", pady=(0, 6))
        self.stop_btn = ttk.Button(box, text="Chiudi scrcpy", command=self.stop_scrcpy)
        self.stop_btn.grid(row=1, column=0, columnspan=2, sticky="ew", pady=(0, 6))
        ttk.Button(box, text="Connetti", command=self.connect_only).grid(row=2, column=0, sticky="ew", padx=(0, 4))
        ttk.Button(box, text="Attiva Wi-Fi da USB", command=self.enable_wifi_from_usb).grid(
            row=2, column=1, sticky="ew", padx=(4, 0)
        )

    def _build_log(self, parent: ttk.Frame) -> None:
        card = ttk.Frame(parent, style="Card.TFrame", padding=12)
        card.grid(row=2, column=0, sticky="nsew")
        card.columnconfigure(0, weight=1)
        card.rowconfigure(1, weight=1)
        ttk.Label(card, text="Registro", style="Section.TLabel").grid(row=0, column=0, sticky="w", pady=(0, 8))
        self.logbox = tk.Text(
            card,
            height=3,
            width=28,
            wrap="word",
            bg=ENTRY,
            fg="#d5dbe8",
            insertbackground=FG,
            relief="flat",
            highlightthickness=0,
            font=("Consolas", 9),
            padx=8,
            pady=6,
        )
        self.logbox.grid(row=1, column=0, sticky="nsew")
        self.logbox.bind("<Key>", self._log_key)

    def _build_preview(self, parent: ttk.Frame) -> None:
        head = ttk.Frame(parent)
        head.grid(row=0, column=0, sticky="ew", pady=(0, 8))
        head.columnconfigure(0, weight=1)
        ttk.Label(head, text="Osservazioni del Vespera", style="Hero.TLabel").grid(row=0, column=0, sticky="w")
        self.preview_copy = ttk.Label(
            head,
            text="Prima le cartelle. Apri un'osservazione per i suoi stack, poi clic sull'immagine per zoom.",
            style="Sub.TLabel",
            wraplength=480,
        )
        self.preview_copy.grid(row=1, column=0, sticky="ew", pady=(2, 0))
        host_row = ttk.Frame(head)
        host_row.grid(row=2, column=0, sticky="ew", pady=(6, 0))
        ttk.Label(host_row, text="Pi", style="Sub.TLabel").pack(side="left")
        ttk.Entry(host_row, textvariable=self.vespera_host, width=18).pack(side="left", padx=(8, 0))
        parent.bind("<Configure>", self._fit_preview_copy, add="+")

        list_holder = ttk.Frame(parent)
        list_holder.grid(row=1, column=0, sticky="ew", pady=(0, 8))
        list_holder.columnconfigure(0, weight=1)
        self.list_canvas = tk.Canvas(list_holder, bg=ENTRY, height=168, highlightthickness=0, bd=0)
        list_scroll = ttk.Scrollbar(list_holder, orient="vertical", command=self.list_canvas.yview)
        self.list_canvas.configure(yscrollcommand=list_scroll.set)
        self.list_canvas.grid(row=0, column=0, sticky="ew")
        list_scroll.grid(row=0, column=1, sticky="ns")
        self.list_canvas.bind("<Button-1>", self._on_list_click)
        self.list_canvas.bind("<Configure>", self._on_list_configure)
        self.list_canvas.bind("<Enter>", lambda _event: self.list_canvas.bind_all("<MouseWheel>", self._on_list_wheel))
        self.list_canvas.bind("<Leave>", lambda _event: self.list_canvas.unbind_all("<MouseWheel>"))

        self.canvas = tk.Canvas(parent, bg="#0b0d12", highlightthickness=0, bd=0, cursor="hand2")
        self.canvas.grid(row=2, column=0, sticky="nsew")
        self.canvas.bind("<Configure>", self._on_canvas_configure)
        self.canvas.bind("<Button-1>", lambda _event: self.open_zoom_view())
        self.canvas.bind("<Double-Button-1>", lambda _event: self.open_zoom_view())

        bar = ttk.Frame(parent)
        bar.grid(row=3, column=0, sticky="ew", pady=(8, 0))
        bar.columnconfigure(0, weight=1)
        ttk.Label(bar, textvariable=self.meta, style="Meta.TLabel").grid(row=0, column=0, columnspan=6, sticky="w", pady=(0, 6))
        ttk.Checkbutton(
            bar,
            text="Automatica",
            style="Bar.TCheckbutton",
            variable=self.auto_preview,
            command=self._on_auto_toggle,
        ).grid(row=1, column=1, sticky="e")
        ttk.Label(bar, text="ogni", style="Sub.TLabel").grid(row=1, column=2, sticky="e", padx=(8, 4))
        ttk.Spinbox(
            bar,
            from_=10,
            to=120,
            textvariable=self.interval,
            width=4,
            command=self._on_interval_change,
        ).grid(row=1, column=3, sticky="e")
        ttk.Label(bar, text="s", style="Sub.TLabel").grid(row=1, column=4, sticky="w", padx=(4, 8))
        ttk.Button(bar, text="Aggiorna", command=self.request_stacks).grid(row=1, column=5, sticky="e")

    def _log_key(self, event: tk.Event) -> str | None:
        if event.state & 0x4 and event.keysym.lower() in {"c", "a", "insert"}:
            return None
        return "break"

    def _toggle_screen_hint(self) -> None:
        if self.turn_screen_off.get():
            self.screen_hint.grid()
        else:
            self.screen_hint.grid_remove()

    def set_status(self, text: str, kind: str = "info") -> None:
        colors = {"info": MUTED, "ok": OK, "warn": WARN, "err": ERR}
        self.status.configure(text=text, foreground=colors.get(kind, FG))

    def append_log(self, text: str) -> None:
        line = text.strip()
        if not line:
            return
        stamp = time.strftime("%H:%M:%S")
        self.logbox.insert("end", f"{stamp}  {line}\n")
        row_count = int(self.logbox.index("end-1c").split(".")[0])
        if row_count > 400:
            self.logbox.delete("1.0", f"{row_count - 300}.0")
        self.logbox.see("end")

    def refresh_buttons(self) -> None:
        running = self.proc is not None and self.proc.poll() is None
        self.start_btn.configure(state="disabled" if running else "normal")
        self.stop_btn.configure(state="normal" if running else "disabled")

    def current_endpoint(self) -> tuple[str, int, str]:
        host, port = parse_endpoint(self.ip.get(), self.port.get())
        self.ip.set(host)
        self.port.set(str(port))
        name = self.name.get().strip() or "Android"
        return host, port, name

    def scrcpy_exe(self) -> Path:
        raw = self.scrcpy_path.get().strip().strip('"')
        path = Path(raw) if raw else locate_scrcpy()
        if path is None or not path.is_file():
            raise HelperError("Non trovo scrcpy.exe. Indicalo con Sfoglia, oppure scompatta scrcpy in questa cartella.")
        if path.suffix.lower() != ".exe" and not path.name.lower().startswith("scrcpy"):
            raise HelperError("Il file indicato non sembra scrcpy.")
        self.scrcpy_path.set(str(path))
        return path

    def adb_exe(self) -> Path:
        try:
            scrcpy = self.scrcpy_exe()
        except HelperError:
            scrcpy = None
        adb = locate_adb(scrcpy if scrcpy is not None and scrcpy.is_file() else None)
        if adb is None:
            raise HelperError("Non trovo adb.exe. È incluso nello zip di scrcpy, accanto a scrcpy.exe.")
        return adb

    def _help_text(self, exe: Path) -> str:
        try:
            stamp = exe.stat().st_mtime
        except OSError:
            stamp = 0
        if self._help_cache and self._help_cache[0] == str(exe) and self._help_cache[1] == stamp:
            return self._help_cache[2]
        try:
            proc = run_cmd([str(exe), "--help"], 12, text=True)
            text = ((proc.stdout or "") + "\n" + (proc.stderr or "")).strip()
        except HelperError:
            text = ""
        if "--tcpip" not in text and "--serial" not in text:
            text = FALLBACK_HELP
        self._help_cache = (str(exe), stamp, text)
        return text

    def _env_for(self, exe: Path, adb: Path) -> dict[str, str]:
        env = os.environ.copy()
        bindir = str(exe.parent)
        env["PATH"] = bindir + os.pathsep + env.get("PATH", "")
        env["ADB"] = str(adb)
        return env

    def refresh_list(self, select: int | None = None) -> None:
        self.listbox.delete(0, "end")
        for device in self.devices:
            self.listbox.insert("end", f"{device['name']}    {device['ip']}:{device['port']}")
        if select is not None and 0 <= select < len(self.devices):
            self.listbox.selection_set(select)
            self.listbox.activate(select)
            self.listbox.see(select)

    def on_list_select(self, _event=None) -> None:
        selection = self.listbox.curselection()
        if not selection:
            return
        device = self.devices[selection[0]]
        self._loading = True
        self.name.set(device["name"])
        self.ip.set(device["ip"])
        self.port.set(str(device["port"]))
        self._loading = False

    def save_device(self) -> None:
        try:
            host, port, name = self.current_endpoint()
        except HelperError as exc:
            self.set_status(str(exc), "warn")
            return
        entry = {"name": name, "ip": host, "port": port}
        selection = self.listbox.curselection()
        if selection:
            index = selection[0]
            self.devices[index] = entry
        else:
            index = next((i for i, item in enumerate(self.devices) if item["ip"] == host and int(item["port"]) == port), -1)
            if index >= 0:
                self.devices[index] = entry
            else:
                self.devices.append(entry)
                index = len(self.devices) - 1
        self.refresh_list(index)
        self._save_settings()
        self.set_status(f"Salvato {name} ({host}:{port}).", "ok")

    def new_device(self) -> None:
        self.listbox.selection_clear(0, "end")
        self.name.set("Android")
        self.ip.set("")
        self.port.set("5555")
        self.ip_entry.focus_set()

    def delete_device(self) -> None:
        selection = self.listbox.curselection()
        if not selection:
            self.set_status("Seleziona un dispositivo salvato da eliminare.", "warn")
            return
        index = selection[0]
        removed = self.devices.pop(index)
        self.refresh_list(min(index, len(self.devices) - 1) if self.devices else None)
        self._save_settings()
        self.set_status(f"Eliminato {removed['name']}.", "info")

    def browse_scrcpy(self) -> None:
        initial = self.scrcpy_path.get().strip()
        folder = str(Path(initial).parent) if initial else str(Path(__file__).resolve().parent)
        picked = filedialog.askopenfilename(
            title="Seleziona scrcpy.exe",
            initialdir=folder,
            filetypes=[("scrcpy", "scrcpy.exe"), ("Programmi", "*.exe")],
        )
        if picked:
            self.scrcpy_path.set(picked)
            self._help_cache = None
            self.append_log(f"scrcpy: {picked}")
            self._save_settings()

    def _autodetect_scrcpy(self) -> None:
        if self.scrcpy_path.get().strip():
            return
        found = locate_scrcpy()
        if found:
            self.scrcpy_path.set(str(found))
            self.append_log(f"Trovato scrcpy: {found}")

    def run_async(self, job) -> None:
        if not self._job_lock.acquire(blocking=False):
            self.set_status("C'è già un'operazione in corso.", "warn")
            return

        def wrap() -> None:
            try:
                job()
            except HelperError as exc:
                self.queue.put(("error", str(exc)))
            except Exception:
                detail = traceback.format_exc()
                self.queue.put(("error", "Errore imprevisto. Dettaglio nel registro."))
                self.queue.put(("log", detail))
            finally:
                self._job_lock.release()
                self.queue.put(("buttons",))

        threading.Thread(target=wrap, daemon=True).start()

    def connect_only(self) -> None:
        try:
            host, port, _name = self.current_endpoint()
            adb = self.adb_exe()
        except HelperError as exc:
            self._missing_tool(exc)
            return
        serial = f"{host}:{port}"

        def job() -> None:
            self.queue.put(("status", f"Connessione a {serial}…", "info"))
            message = adb_connect(adb, serial)
            self.queue.put(("log", message))
            self.queue.put(("status", f"Connesso a {serial}.", "ok"))
            self.queue.put(("capture",))

        self.run_async(job)

    def enable_wifi_from_usb(self) -> None:
        try:
            adb = self.adb_exe()
            if self.ip.get().strip():
                host, port, _name = self.current_endpoint()
            else:
                host, port = "", 5555
        except HelperError as exc:
            self._missing_tool(exc)
            return

        def job() -> None:
            self.queue.put(("status", "Cerco un Android collegato via USB…", "info"))
            usb = [serial for serial, state in list_adb_devices(adb) if state == "device" and ":" not in serial]
            if not usb:
                raise HelperError(
                    "Nessun Android via USB. Collega il cavo, accetta «Consenti debug USB» e riprova."
                )
            usb_serial = usb[0]
            self.queue.put(("log", f"USB: {usb_serial}"))
            use_host, use_port = host, port
            if not use_host:
                use_host = detect_wifi_ip(adb, usb_serial)
                use_port = 5555
            self.queue.put(("endpoint", use_host, use_port))
            self.queue.put(("status", f"Attivo adb in ascolto sulla porta {use_port}…", "info"))
            proc = run_cmd([str(adb), "-s", usb_serial, "tcpip", str(use_port)], 20, text=True)
            message = ((proc.stdout or "") + "\n" + (proc.stderr or "")).strip()
            if proc.returncode != 0:
                raise HelperError(message or "adb tcpip non riuscito.")
            if message:
                self.queue.put(("log", message))
            time.sleep(1.2)
            serial = f"{use_host}:{use_port}"
            connected = adb_connect(adb, serial)
            self.queue.put(("log", connected))
            self.queue.put(("status", f"Wi-Fi attivo su {serial}. Puoi avviare scrcpy.", "ok"))
            self.queue.put(("capture",))

        self.run_async(job)

    def start_scrcpy(self, retry: bool = False) -> None:
        if self.proc is not None and self.proc.poll() is None:
            self.set_status("scrcpy è già aperto.", "warn")
            return
        if not retry:
            self._retried = False
        self._user_stop = False
        self._session_log = []
        try:
            host, port, name = self.current_endpoint()
            exe = self.scrcpy_exe()
            adb = self.adb_exe()
        except HelperError as exc:
            self._missing_tool(exc)
            return
        options = {
            "max_size": self.max_size.get(),
            "bitrate": self.bitrate.get(),
            "always_on_top": self.always_on_top.get(),
            "turn_screen_off": self.turn_screen_off.get(),
            "stay_awake": self.stay_awake.get(),
            "no_audio": self.no_audio.get(),
            "video_encoder": SOFTWARE_ENCODER if self.software_encoder.get() else "",
            "title": name,
        }

        def job() -> None:
            serial = f"{host}:{port}"
            command = build_scrcpy_command(str(exe), serial, help_text=self._help_text(exe), **options)
            self.queue.put(("status", f"Connessione a {serial}…", "info"))
            try:
                message = adb_connect(adb, serial)
                self.queue.put(("log", message))
            except HelperError as exc:
                self.queue.put(("log", f"adb connect: {exc}"))
                self.queue.put(("log", "Provo comunque con scrcpy --tcpip."))
            self.queue.put(("log", " ".join(command)))
            self.queue.put(("spawn", command, str(exe.parent), self._env_for(exe, adb)))

        self.run_async(job)

    def _spawn_scrcpy(self, command: list[str], workdir: str, env: dict[str, str]) -> None:
        if self.proc is not None and self.proc.poll() is None:
            return
        try:
            self.proc = subprocess.Popen(
                command,
                cwd=workdir,
                env=env,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                creationflags=CREATE_NO_WINDOW,
            )
        except OSError as exc:
            self.set_status(f"Avvio di scrcpy non riuscito: {exc}", "err")
            self.append_log(str(exc))
            return
        self.append_log(f"scrcpy avviato (pid {self.proc.pid}).")
        self.set_status("scrcpy è aperto.", "ok")
        self.refresh_buttons()
        threading.Thread(target=self._read_scrcpy, args=(self.proc,), daemon=True).start()

    def _read_scrcpy(self, proc: subprocess.Popen) -> None:
        try:
            assert proc.stdout is not None
            for raw in iter(proc.stdout.readline, b""):
                line = raw.decode("utf-8", "replace").rstrip()
                if line:
                    self.queue.put(("log", line))
        except Exception as exc:
            self.queue.put(("log", f"Lettura output scrcpy interrotta: {exc}"))
        code = proc.wait()
        self.queue.put(("exit", code, proc.pid))

    def stop_scrcpy(self) -> None:
        proc = self.proc
        if proc is None or proc.poll() is not None:
            self.set_status("scrcpy non è in esecuzione.", "info")
            self.refresh_buttons()
            return
        self._user_stop = True
        proc.terminate()
        self.append_log("Chiusura di scrcpy…")
        self.set_status("Chiudo scrcpy…", "info")

    def request_capture(self) -> None:
        if self._closing:
            return
        if self._capture_busy:
            self._capture_pending = True
            return
        try:
            host, port, _name = self.current_endpoint()
            adb = self.adb_exe()
        except HelperError as exc:
            self.set_status(str(exc), "warn")
            return
        serial = f"{host}:{port}"
        self._capture_busy = True

        def work() -> None:
            try:
                try:
                    data = adb_screencap(adb, serial)
                except HelperError:
                    adb_connect(adb, serial)
                    data = adb_screencap(adb, serial)
                self.queue.put(("frame", data))
            except HelperError as exc:
                self.queue.put(("frame_error", str(exc)))
            except Exception:
                self.queue.put(("frame_error", "Anteprima non riuscita."))
                self.queue.put(("log", traceback.format_exc()))
            finally:
                self.queue.put(("capture_idle",))

        threading.Thread(target=work, daemon=True).start()

    def _on_frame(self, data: bytes) -> None:
        self._png_bytes = data
        self._last_error = ""
        try:
            last_frame_path().write_bytes(data)
        except OSError as exc:
            self.append_log(f"Non riesco a salvare l'ultimo frame: {exc}")
        width, height = png_size(data)
        stamp = time.strftime("%d/%m/%Y %H:%M:%S")
        size = f"{width}×{height}" if width and height else "PNG"
        self.meta.set(f"Ultimo frame {stamp}  ·  {size}")
        self.redraw()
        if self.proc is not None and self.proc.poll() is None:
            self.set_status("scrcpy aperto. Anteprima aggiornata.", "ok")
        else:
            self.set_status("Anteprima aggiornata.", "ok")

    def _on_frame_error(self, message: str) -> None:
        if not self._png_bytes:
            self.meta.set("Ultimo tentativo non riuscito")
        self.set_status(message, "err")
        if message != self._last_error:
            self.append_log(message)
            self._last_error = message

    def request_stacks(self) -> None:
        if self._closing:
            return
        if self._stack_busy:
            self._stack_pending = True
            return
        host = self.vespera_host.get().strip() or "192.168.1.4"
        self._stack_busy = True
        self.set_status(f"Cerco gli stack su {host}…", "info")

        def work() -> None:
            try:
                folders = load_vespera_folders(host)
                self.queue.put(("folders", folders))
            except HelperError as exc:
                self.queue.put(("stack_error", str(exc)))
            except Exception:
                self.queue.put(("stack_error", "Lettura degli stack non riuscita."))
                self.queue.put(("log", traceback.format_exc()))
            finally:
                self.queue.put(("stack_idle",))

        threading.Thread(target=work, daemon=True).start()

    def _on_folders(self, folders: list[dict]) -> None:
        prev_obs = ""
        if self._folder_index is not None and 0 <= self._folder_index < len(self._folders):
            prev_obs = str(self._folders[self._folder_index].get("observation") or "")
        prev_detail = ""
        if self._shots and 0 <= self._stack_index < len(self._shots):
            prev_detail = str(self._shots[self._stack_index].get("detail") or "")
        self._folders = folders
        self._write_folder_index(folders)
        self._last_error = ""
        total = sum(len(folder.get("shots") or []) for folder in folders)
        self.set_status(f"{len(folders)} cartelle · {total} stack.", "ok")
        if prev_obs:
            for index, folder in enumerate(folders):
                if folder.get("observation") == prev_obs:
                    self._open_folder(index, select_detail=prev_detail)
                    return
        self._show_folder_browser()

    def _show_folder_browser(self) -> None:
        self._folder_index = None
        self._shots = []
        self._stack_index = 0
        self._png_bytes = None
        self.meta.set("Scegli una cartella osservazione")
        self.redraw()
        self._draw_stack_list()

    def _open_folder(self, index: int, select_detail: str = "") -> None:
        if not self._folders:
            return
        index = max(0, min(index, len(self._folders) - 1))
        folder = self._folders[index]
        self._folder_index = index
        self._shots = list(folder.get("shots") or [])
        shot_index = 0
        if select_detail:
            for pos, shot in enumerate(self._shots):
                if shot.get("detail") == select_detail:
                    shot_index = pos
                    break
        if self._shots:
            self._show_stack(shot_index)
        else:
            self._png_bytes = None
            self.meta.set(f"{folder.get('label') or 'Osservazione'}  ·  nessun frame")
            self.redraw()
            self._draw_stack_list()

    def _show_stack(self, index: int) -> None:
        if not self._shots:
            return
        index = max(0, min(index, len(self._shots) - 1))
        self._stack_index = index
        shot = self._shots[index]
        try:
            self._png_bytes = Path(shot["full"]).read_bytes()
        except OSError as exc:
            self.set_status(f"Immagine non leggibile: {exc}", "err")
            return
        folder = self._folders[self._folder_index] if self._folder_index is not None else None
        label = (folder or {}).get("label") or shot.get("label") or "Stack"
        when = (folder or {}).get("when") or shot.get("when") or ""
        frame = shot.get("frame") or shot.get("detail") or ""
        parts = [label]
        if when:
            parts.append(when)
        parts.append(frame)
        parts.append(f"{index + 1}/{len(self._shots)}")
        self.meta.set("  ·  ".join(part for part in parts if part))
        self.redraw()
        self._draw_stack_list()

    def _draw_stack_list(self) -> None:
        canvas = self.list_canvas
        canvas.delete("all")
        self._list_photos = []
        self._list_rows = []
        width = max(canvas.winfo_width(), 240)
        row_h = 54
        y = 4

        def draw_row(
            title: str,
            subtitle: str,
            *,
            kind: str,
            index: int,
            selected: bool = False,
            thumb_path: str = "",
            accent: bool = False,
        ) -> None:
            nonlocal y
            top = y
            bottom = y + row_h
            bg = "#1a2740" if selected or accent else ("#151922" if index % 2 == 0 else ENTRY)
            outline = ACCENT if selected or accent else "#2c3348"
            canvas.create_rectangle(2, top, width - 2, bottom - 2, fill=bg, outline=outline, width=2 if selected or accent else 1)
            thumb_x = 10
            text_x = thumb_x
            if thumb_path:
                try:
                    data = Path(thumb_path).read_bytes()
                    photo = self._make_photo(data, 72, 42)
                    self._list_photos.append(photo)
                    ty = top + max(4, (row_h - 4 - photo.height()) // 2)
                    canvas.create_image(thumb_x, ty, image=photo, anchor="nw")
                    text_x = thumb_x + photo.width() + 12
                except (OSError, tk.TclError):
                    pass
            canvas.create_text(text_x, top + 12, text=title, fill=FG, font=("Segoe UI", 10, "bold"), anchor="nw")
            canvas.create_text(text_x, top + 30, text=subtitle, fill=MUTED, font=("Segoe UI", 9), anchor="nw")
            self._list_rows.append((top, bottom - 2, kind, index))
            y = bottom

        if self._folder_index is None:
            if not self._folders:
                canvas.create_text(
                    width // 2,
                    40,
                    text="Nessuna cartella in elenco.\nPremi Aggiorna o F5.",
                    fill=MUTED,
                    font=("Segoe UI", 10),
                    justify="center",
                )
                canvas.configure(scrollregion=(0, 0, width, 80))
                return
            for index, folder in enumerate(self._folders):
                count = int(folder.get("count") or len(folder.get("shots") or []))
                when = folder.get("when") or "—"
                count_txt = "1 stack" if count == 1 else f"{count} stack"
                draw_row(
                    folder.get("label") or "Osservazione",
                    f"{when}  ·  {count_txt}",
                    kind="folder",
                    index=index,
                    thumb_path=str(folder.get("thumb") or ""),
                )
        else:
            folder = self._folders[self._folder_index]
            draw_row(
                "← Cartelle",
                f"{folder.get('label') or 'Osservazione'}  ·  {folder.get('when') or ''}".strip(" ·"),
                kind="back",
                index=-1,
                accent=True,
            )
            if not self._shots:
                canvas.create_text(
                    width // 2,
                    y + 28,
                    text="Nessuno stack in questa cartella.",
                    fill=MUTED,
                    font=("Segoe UI", 10),
                    justify="center",
                )
                y += 56
            for index, shot in enumerate(self._shots):
                frame = shot.get("frame") or shot.get("detail") or "Stack"
                detail = shot.get("detail") or ""
                draw_row(
                    frame,
                    detail,
                    kind="shot",
                    index=index,
                    selected=index == self._stack_index,
                    thumb_path=str(shot.get("thumb") or ""),
                )
        canvas.configure(scrollregion=(0, 0, width, max(y + 4, 1)))

    def _on_list_click(self, event: tk.Event) -> None:
        y = self.list_canvas.canvasy(event.y)
        for top, bottom, kind, index in self._list_rows:
            if top <= y <= bottom:
                if kind == "folder":
                    self._open_folder(index)
                elif kind == "back":
                    self._show_folder_browser()
                elif kind == "shot":
                    self._show_stack(index)
                return

    def _on_list_wheel(self, event: tk.Event) -> None:
        self.list_canvas.yview_scroll(int(-event.delta / 120), "units")

    def _on_list_configure(self, event: tk.Event) -> None:
        if event.width < 40 or abs(event.width - self._list_width) < 8:
            return
        self._list_width = event.width
        self._draw_stack_list()

    def _write_folder_index(self, folders: list[dict]) -> None:
        payload = {"version": 2, "folders": []}
        for folder in folders:
            shots = []
            for shot in folder.get("shots") or []:
                shots.append(
                    {
                        "label": shot.get("label", ""),
                        "detail": shot.get("detail", ""),
                        "frame": shot.get("frame", ""),
                        "when": shot.get("when", ""),
                        "full": Path(shot["full"]).name,
                        "thumb": Path(shot["thumb"]).name,
                        "mtime": shot.get("mtime", 0),
                        "observation": shot.get("observation", folder.get("observation", "")),
                    }
                )
            payload["folders"].append(
                {
                    "observation": folder.get("observation", ""),
                    "label": folder.get("label", ""),
                    "when": folder.get("when", ""),
                    "count": folder.get("count", len(shots)),
                    "shots": shots,
                }
            )
        try:
            atomic_write_text(stack_cache_dir() / "index.json", json.dumps(payload, ensure_ascii=False, indent=2))
        except OSError:
            pass

    def _load_cached_stacks(self) -> None:
        path = stack_cache_dir() / "index.json"
        if not path.is_file():
            return
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return
        cache = stack_cache_dir()
        folders: list[dict] = []
        if isinstance(raw, dict) and raw.get("version") == 2 and isinstance(raw.get("folders"), list):
            source = raw["folders"]
        elif isinstance(raw, list):
            # cache vecchia piatta: una sola cartella fittizia
            source = [
                {
                    "observation": "cached",
                    "label": "Cache locale",
                    "when": "",
                    "count": len(raw),
                    "shots": raw,
                }
            ]
        else:
            return
        for item in source:
            if not isinstance(item, dict):
                continue
            shots: list[dict] = []
            for shot in item.get("shots") or []:
                if not isinstance(shot, dict):
                    continue
                full = cache / str(shot.get("full", ""))
                thumb = cache / str(shot.get("thumb", ""))
                if not full.is_file() or not thumb.is_file():
                    continue
                shots.append(
                    {
                        "label": str(shot.get("label") or item.get("label") or "Stack"),
                        "detail": str(shot.get("detail") or full.name),
                        "frame": str(shot.get("frame") or _frame_label(str(shot.get("detail") or full.name))),
                        "when": str(shot.get("when") or item.get("when") or ""),
                        "full": str(full),
                        "thumb": str(thumb),
                        "mtime": shot.get("mtime") or 0,
                        "observation": str(shot.get("observation") or item.get("observation") or ""),
                    }
                )
            if not shots:
                continue
            folders.append(
                {
                    "observation": str(item.get("observation") or shots[0].get("observation") or ""),
                    "label": str(item.get("label") or shots[0].get("label") or "Osservazione"),
                    "when": str(item.get("when") or shots[0].get("when") or ""),
                    "count": int(item.get("count") or len(shots)),
                    "shots": shots,
                    "thumb": shots[0]["thumb"],
                    "full": shots[0]["full"],
                }
            )
        if not folders:
            return
        self._folders = folders
        self._folder_index = None
        self._shots = []
        self.after(250, self._show_folder_browser)

    def _startup_preview(self) -> None:
        if self.auto_preview.get():
            self.request_stacks()

    def _on_auto_toggle(self) -> None:
        if self.auto_preview.get():
            self.request_stacks()
        else:
            self._disarm_preview()

    def _on_interval_change(self) -> None:
        if self.auto_preview.get() and not self._stack_busy:
            self._arm_preview()

    def _arm_preview(self) -> None:
        self._disarm_preview()
        if self._closing or not self.auto_preview.get():
            return
        self._preview_after = self.after(self._interval_ms(), self.request_stacks)

    def _disarm_preview(self) -> None:
        if self._preview_after is not None:
            try:
                self.after_cancel(self._preview_after)
            except tk.TclError:
                pass
            self._preview_after = None

    def _interval_ms(self) -> int:
        try:
            seconds = int(float(self.interval.get().replace(",", ".")))
        except ValueError:
            seconds = 3
        return max(10, min(120, seconds)) * 1000

    def _fit_preview_copy(self, event: tk.Event) -> None:
        if event.widget is not self.preview_copy.master or event.width < 80:
            return
        self.preview_copy.configure(wraplength=max(160, event.width - 8))

    def _on_canvas_configure(self, event: tk.Event) -> None:
        if event.width < 30 or event.height < 30:
            return
        if self._resize_after is not None:
            self.after_cancel(self._resize_after)
        self._resize_after = self.after(120, self.redraw)

    def redraw(self) -> None:
        self._resize_after = None
        if self._closing:
            return
        width = max(self.canvas.winfo_width(), 2)
        height = max(self.canvas.winfo_height(), 2)
        self.canvas.delete("all")
        if not self._png_bytes:
            self.canvas.create_text(
                width // 2,
                height // 2,
                width=max(width - 48, 120),
                text="Apri una cartella dall'elenco sopra.\nPoi clic sull'immagine per la vista allargata con zoom.",
                fill=MUTED,
                font=("Segoe UI", 12),
                justify="center",
            )
            return
        try:
            photo = self._make_photo(self._png_bytes, max(width - 12, 1), max(height - 12, 1))
        except tk.TclError as exc:
            self.canvas.create_text(width // 2, height // 2, text=f"Immagine non mostrabile:\n{exc}", fill=ERR)
            return
        self._photo = photo
        self.canvas.create_image(width // 2, height // 2, image=photo, anchor="center")
        self.canvas.create_text(
            width - 12,
            height - 10,
            text="clic = zoom",
            fill=MUTED,
            font=("Segoe UI", 9),
            anchor="se",
        )

    def _make_photo(self, data: bytes, max_w: int, max_h: int):
        try:
            from PIL import Image, ImageTk

            image = Image.open(__import__("io").BytesIO(data))
            image.thumbnail((max_w, max_h), Image.Resampling.LANCZOS)
            return ImageTk.PhotoImage(image)
        except Exception:
            raw = tk.PhotoImage(data=base64.b64encode(data).decode("ascii"))
            factor_w = (raw.width() + max_w - 1) // max_w if max_w else 1
            factor_h = (raw.height() + max_h - 1) // max_h if max_h else 1
            factor = max(1, factor_w, factor_h)
            return raw if factor == 1 else raw.subsample(factor, factor)

    def open_last_frame(self) -> None:
        self.open_zoom_view()

    def open_zoom_view(self) -> None:
        path: Path | None = None
        title = "Vista allargata"
        if self._shots:
            shot = self._shots[self._stack_index]
            candidate = Path(shot["full"])
            if candidate.is_file():
                path = candidate
                when = shot.get("when") or ""
                title = "  ·  ".join(part for part in (shot.get("label"), when, shot.get("detail")) if part)
        if path is None:
            candidate = last_frame_path()
            if candidate.is_file():
                path = candidate
        if path is None:
            self.set_status("Non c'è ancora uno stack da aprire.", "warn")
            return
        if self._zoom_win is not None and self._zoom_win.winfo_exists():
            self._zoom_win.destroy()
        self._zoom_win = ZoomViewer(self, path, title=title)

    def _load_saved_frame(self) -> None:
        path = last_frame_path()
        if not path.is_file():
            return
        try:
            data = normalize_png(path.read_bytes())
        except Exception:
            return
        self._png_bytes = data
        width, height = png_size(data)
        stamp = time.strftime("%d/%m/%Y %H:%M:%S", time.localtime(path.stat().st_mtime))
        size = f"{width}×{height}" if width and height else "PNG"
        self.meta.set(f"Ultimo frame del {stamp}  ·  {size}")
        self.after(200, self.redraw)

    def _missing_tool(self, exc: HelperError) -> None:
        self.set_status(str(exc), "err")
        self.append_log(str(exc))
        if "scrcpy" in str(exc).lower():
            if messagebox.askyesno("scrcpy", f"{exc}\n\nVuoi indicare scrcpy.exe adesso?"):
                self.browse_scrcpy()

    def _bind_persistence(self) -> None:
        for variable in (
            self.name,
            self.ip,
            self.port,
            self.scrcpy_path,
            self.max_size,
            self.bitrate,
            self.always_on_top,
            self.turn_screen_off,
            self.stay_awake,
            self.no_audio,
            self.software_encoder,
            self.auto_preview,
            self.interval,
            self.vespera_host,
            self.close_with_app,
        ):
            variable.trace_add("write", lambda *_args: self._touch_save())

    def _touch_save(self) -> None:
        if self._loading or self._closing:
            return
        if self._save_after is not None:
            self.after_cancel(self._save_after)
        self._save_after = self.after(500, self._save_settings)

    def _load_settings(self) -> None:
        path = settings_path()
        raw: dict = {}
        if path.is_file():
            try:
                loaded = json.loads(path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    raw = loaded
            except (OSError, json.JSONDecodeError):
                raw = {}
        self._loading = True
        self.devices = _clean_devices(raw.get("devices"))
        draft = raw.get("draft") if isinstance(raw.get("draft"), dict) else {}
        self.name.set(str(draft.get("name") or "Android"))
        self.ip.set(str(draft.get("ip") or ""))
        self.port.set(str(draft.get("port") or "5555"))
        self.scrcpy_path.set(str(raw.get("scrcpy_path") or ""))
        self.max_size.set(str(raw.get("max_size") if raw.get("max_size") is not None else "1920"))
        self.bitrate.set(str(raw.get("bitrate") if raw.get("bitrate") is not None else "8M"))
        self.always_on_top.set(bool(raw.get("always_on_top", False)))
        self.turn_screen_off.set(bool(raw.get("turn_screen_off", False)))
        self.stay_awake.set(bool(raw.get("stay_awake", True)))
        self.no_audio.set(bool(raw.get("no_audio", True)))
        self.software_encoder.set(bool(raw.get("software_encoder", False)))
        self.auto_preview.set(bool(raw.get("auto_preview", True)))
        interval = str(raw.get("interval") or "15")
        try:
            if float(interval.replace(",", ".")) < 10:
                interval = "15"
        except ValueError:
            interval = "15"
        self.interval.set(interval)
        host = str(raw.get("vespera_host") or "192.168.1.4")
        if host == "10.0.0.1":
            host = "192.168.1.4"
        self.vespera_host.set(host)
        self.close_with_app.set(bool(raw.get("close_with_app", False)))
        geometry = str(raw.get("geometry") or "")
        match = re.match(r"(\d+)x(\d+)", geometry)
        if match and int(match.group(1)) >= 900 and int(match.group(2)) >= 560:
            try:
                self.geometry(geometry)
            except tk.TclError:
                pass
        self._loading = False
        self.refresh_list()
        self._toggle_screen_hint()
        if not self.ip.get().strip() and self.devices:
            self.refresh_list(0)
            self.on_list_select()

    def _save_settings(self) -> None:
        self._save_after = None
        if self._closing and not self.winfo_exists():
            return
        payload = {
            "scrcpy_path": self.scrcpy_path.get().strip(),
            "max_size": self.max_size.get().strip(),
            "bitrate": self.bitrate.get().strip(),
            "always_on_top": self.always_on_top.get(),
            "turn_screen_off": self.turn_screen_off.get(),
            "stay_awake": self.stay_awake.get(),
            "no_audio": self.no_audio.get(),
            "software_encoder": self.software_encoder.get(),
            "auto_preview": self.auto_preview.get(),
            "interval": self.interval.get().strip() or "15",
            "vespera_host": self.vespera_host.get().strip() or "192.168.1.4",
            "close_with_app": self.close_with_app.get(),
            "geometry": self.geometry(),
            "draft": {
                "name": self.name.get().strip(),
                "ip": self.ip.get().strip(),
                "port": self.port.get().strip() or "5555",
            },
            "devices": self.devices,
        }
        try:
            atomic_write_text(settings_path(), json.dumps(payload, ensure_ascii=False, indent=2))
        except OSError as exc:
            self.append_log(f"Salvataggio impostazioni non riuscito: {exc}")

    def _recover_pi(self) -> bool:
        if self._retried:
            return False
        text = "\n".join(self._session_log)
        audio_broken = "Cannot create AudioRecord" in text or "AudioDirectCapture" in text
        video_broken = (
            "CodecException" in text
            or "Capture/encoding error" in text
            or "dequeueOutputBuffer" in text
        )
        if not audio_broken and not video_broken:
            return False
        turn_off_audio = audio_broken and not self.no_audio.get()
        use_software = (video_broken or audio_broken) and not self.software_encoder.get()
        if not turn_off_audio and not use_software:
            return False
        self._retried = True
        self.no_audio.set(True)
        if use_software:
            self.software_encoder.set(True)
        self.append_log(
            "Il Raspberry Pi non apre l'audio di scrcpy e il codec hardware va in errore. "
            "Riprovo senza audio, con l'encoder software."
        )
        self.set_status("Riprovo senza audio, con l'encoder software…", "warn")
        self.after(400, lambda: self.start_scrcpy(retry=True))
        return True

    def _poll(self) -> None:
        if self._closing:
            return
        try:
            while True:
                item = self.queue.get_nowait()
                kind = item[0]
                if kind == "log":
                    self.append_log(item[1])
                    self._session_log.append(item[1])
                    if len(self._session_log) > 300:
                        del self._session_log[:-200]
                elif kind == "status":
                    self.set_status(item[1], item[2] if len(item) > 2 else "info")
                elif kind == "error":
                    self.set_status(item[1], "err")
                    self.append_log(item[1])
                elif kind == "buttons":
                    self.refresh_buttons()
                elif kind == "endpoint":
                    self.ip.set(item[1])
                    self.port.set(str(item[2]))
                elif kind == "capture":
                    self.request_stacks()
                elif kind == "folders":
                    self._on_folders(item[1])
                elif kind == "stacks":
                    # compat eventuali messaggi vecchi in coda
                    self._on_folders(
                        [
                            {
                                "observation": "legacy",
                                "label": "Stack",
                                "when": "",
                                "count": len(item[1]),
                                "shots": item[1],
                                "thumb": (item[1][0].get("thumb") if item[1] else ""),
                                "full": (item[1][0].get("full") if item[1] else ""),
                            }
                        ]
                        if item[1]
                        else []
                    )
                elif kind == "stack_error":
                    self._on_frame_error(item[1])
                elif kind == "stack_idle":
                    self._stack_busy = False
                    if self._stack_pending:
                        self._stack_pending = False
                        self.request_stacks()
                    elif self.auto_preview.get():
                        self._arm_preview()
                elif kind == "spawn":
                    self._spawn_scrcpy(item[1], item[2], item[3])
                elif kind == "exit":
                    code, pid = item[1], item[2]
                    if self.proc is not None and self.proc.poll() is None and self.proc.pid != pid:
                        continue
                    if self.proc is not None and self.proc.pid == pid:
                        self.proc = None
                    stopped = self._user_stop
                    self._user_stop = False
                    self.refresh_buttons()
                    if not stopped and code != 0 and self._recover_pi():
                        continue
                    self.append_log(f"scrcpy terminato (codice {code}). L'ultimo frame resta in anteprima.")
                    if code == 0:
                        self.set_status("scrcpy chiuso. L'ultimo fotogramma resta visibile.", "info")
                    else:
                        self.set_status(f"scrcpy chiuso con codice {code}. Controlla il registro.", "warn")
                elif kind == "frame":
                    self._on_frame(item[1])
                elif kind == "frame_error":
                    self._on_frame_error(item[1])
                elif kind == "capture_idle":
                    self._capture_busy = False
                    if self._capture_pending:
                        self._capture_pending = False
                        self.request_capture()
                    elif self.auto_preview.get():
                        self._arm_preview()
        except queue.Empty:
            pass
        except Exception:
            self.append_log(traceback.format_exc())
        if not self._closing:
            self.after(80, self._poll)

    def on_close(self) -> None:
        if self._closing:
            return
        self._closing = True
        self._disarm_preview()
        if self._zoom_win is not None and self._zoom_win.winfo_exists():
            try:
                self._zoom_win.destroy()
            except tk.TclError:
                pass
        try:
            self._save_settings()
        except Exception:
            pass
        if self.close_with_app.get():
            proc = self.proc
            if proc is not None and proc.poll() is None:
                self._user_stop = True
                proc.terminate()
        release_mutex(self.mutex)
        self.mutex = None
        self.destroy()


def _clean_devices(raw) -> list[dict]:
    devices: list[dict] = []
    if not isinstance(raw, list):
        return devices
    for item in raw:
        if not isinstance(item, dict):
            continue
        ip = str(item.get("ip", "")).strip()
        name = str(item.get("name", "")).strip() or "Android"
        try:
            port = int(item.get("port", 5555))
        except (TypeError, ValueError):
            continue
        if ip and 1 <= port <= 65535:
            devices.append({"name": name, "ip": ip, "port": port})
    return devices


def _self_check() -> None:
    signature = PNG_SIG + b"rest"
    assert normalize_png(signature) == signature
    assert normalize_png(b"\r\n" + signature) == signature
    corrupted = b"\x89PNG\r\r\n\x1a\r\nrest"
    assert normalize_png(corrupted).startswith(PNG_SIG)
    sample_help = "--tcpip[=ip[:port]]\n    -m, --max-size=value\n    --video-bit-rate=value\n"
    assert has_opt(sample_help, "--tcpip")
    assert has_opt(sample_help, "--max-size")
    assert has_opt(sample_help, "--video-bit-rate")
    host, port = parse_endpoint("192.168.1.20:4321", "5555")
    assert (host, port) == ("192.168.1.20", 4321)
    command = build_scrcpy_command(
        "scrcpy.exe",
        "192.168.1.20:5555",
        max_size="1920",
        bitrate="8M",
        always_on_top=True,
        turn_screen_off=False,
        stay_awake=True,
        no_audio=True,
        title="Vespera",
        help_text=FALLBACK_HELP,
    )
    assert command[0] == "scrcpy.exe"
    assert "--tcpip=192.168.1.20:5555" in command
    assert "--max-size=1920" in command
    assert "--video-bit-rate=8M" in command
    assert "--no-audio" in command
    assert "--video-encoder" not in " ".join(command)
    pi = build_scrcpy_command(
        "scrcpy.exe",
        "192.168.1.4:5555",
        max_size="1920",
        bitrate="8M",
        always_on_top=False,
        turn_screen_off=False,
        stay_awake=True,
        no_audio=True,
        title="Raspberry Pi",
        help_text=FALLBACK_HELP,
        video_encoder=SOFTWARE_ENCODER,
    )
    assert "--video-codec=h264" in pi
    assert f"--video-encoder={SOFTWARE_ENCODER}" in pi
    assert "--no-audio" in pi
    chosen = choose_stack_files(
        [
            [{"path": "a"}, {"path": "b"}, {"path": "c"}, {"path": "d"}, {"path": "e"}, {"path": "g"}, {"path": "h"}],
            [{"path": "f"}, {"path": "z"}],
            [{"path": "x"}],
        ]
    )
    assert [item["path"] for item in chosen] == ["a", "b", "c", "d", "e", "g", "f", "x"]
    assert [item["path"] for item in frames_for_observation([{"path": "a"}, {"path": "b"}], live=False)] == ["a"]
    assert len(frames_for_observation([{"path": str(i)} for i in range(10)], live=True)) == 6
    target, when = _observation_parts("2026-10-03_22-16-00_observation_IC1396")
    assert target == "IC1396"
    assert when == "03/10/2026 22:16"
    assert _frame_label("img-0885-output.jpg") == "Frame 885"
    try:
        parse_endpoint("", "5555")
        raise AssertionError("IP vuoto doveva fallire")
    except HelperError:
        pass


def main() -> None:
    enable_dpi()
    if "--check" in sys.argv:
        _self_check()
        print("ok")
        return
    held = acquire_mutex()
    if held is False:
        root = tk.Tk()
        root.withdraw()
        messagebox.showinfo("Vespera Win Helper", "L'app è già aperta.")
        root.destroy()
        return
    app = App(mutex=held)
    app.mainloop()


if __name__ == "__main__":
    main()
