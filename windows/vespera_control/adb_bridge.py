# -*- coding: utf-8 -*-
"""Bridge ADB verso RemoteBridge di VesperaHelper sul Pi."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import time
from pathlib import Path

CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

HELPER_FILES = "/sdcard/Android/data/com.vaonis.vesperahelper/files"
REMOTE_REQ = f"{HELPER_FILES}/remote.req"
REMOTE_ACK = f"{HELPER_FILES}/remote.ack"
REMOTE_STATE = f"{HELPER_FILES}/remote.state.json"


class AdbError(Exception):
    """Errore atteso del bridge ADB."""


def locate_adb(scrcpy_exe: Path | None = None) -> Path | None:
    if scrcpy_exe is not None:
        sibling = scrcpy_exe.parent / "adb.exe"
        if sibling.is_file():
            return sibling
    found = shutil.which("adb")
    return Path(found) if found else None


def run_adb(adb: Path, args: list[str], timeout: float = 25, text: bool = True) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(
            [str(adb), *args],
            capture_output=True,
            timeout=timeout,
            text=text,
            encoding="utf-8" if text else None,
            errors="replace" if text else None,
            creationflags=CREATE_NO_WINDOW,
        )
    except FileNotFoundError as exc:
        raise AdbError(f"adb non trovato: {adb}") from exc
    except subprocess.TimeoutExpired as exc:
        raise AdbError("Timeout adb.") from exc


class AdbBridge:
    def __init__(self, adb: Path, serial: str) -> None:
        self.adb = Path(adb)
        self.serial = serial.strip()
        if not self.serial:
            raise AdbError("Serial ADB vuoto (es. 192.168.1.4:5555).")

    def _s(self, *args: str, timeout: float = 25, text: bool = True) -> subprocess.CompletedProcess:
        return run_adb(self.adb, ["-s", self.serial, *args], timeout=timeout, text=text)

    def connect(self) -> str:
        proc = run_adb(self.adb, ["connect", self.serial], timeout=20)
        message = ((proc.stdout or "") + "\n" + (proc.stderr or "")).strip()
        lowered = message.lower()
        if "connected to" in lowered or "already connected" in lowered:
            return message
        raise AdbError(message or f"Connessione a {self.serial} non riuscita.")

    def shell(self, command: str, timeout: float = 25) -> str:
        proc = self._s("shell", command, timeout=timeout)
        out = ((proc.stdout or "") + (proc.stderr or "")).strip()
        if proc.returncode != 0 and not out:
            raise AdbError(f"shell fallita ({proc.returncode})")
        return out

    def push_text(self, remote: str, text: str) -> None:
        local = Path(os.environ.get("TEMP") or ".") / "vespera_control_req.txt"
        local.write_text(text if text.endswith("\n") else text + "\n", encoding="utf-8")
        proc = self._s("push", str(local), remote, timeout=20)
        if proc.returncode != 0:
            detail = ((proc.stdout or "") + "\n" + (proc.stderr or "")).strip()
            raise AdbError(detail or "push remote.req fallito")

    def pull_text(self, remote: str) -> str:
        local = Path(os.environ.get("TEMP") or ".") / "vespera_control_pull.txt"
        if local.exists():
            local.unlink()
        proc = self._s("pull", remote, str(local), timeout=20)
        if proc.returncode != 0 or not local.is_file():
            detail = ((proc.stdout or "") + "\n" + (proc.stderr or "")).strip()
            raise AdbError(detail or f"pull {remote} fallito")
        return local.read_text(encoding="utf-8", errors="replace")

    def send_command(self, line: str, wait_ack: float = 90.0) -> str:
        """Scrive remote.req e attende un nuovo remote.ack."""
        line = (line or "").strip()
        if not line:
            raise AdbError("Comando vuoto")
        # Cancella ack precedente se possibile.
        self.shell(f"rm -f '{REMOTE_ACK}'", timeout=10)
        before = time.time()
        self.push_text(REMOTE_REQ, line)
        deadline = time.time() + max(5.0, wait_ack)
        last_err = ""
        while time.time() < deadline:
            time.sleep(0.35)
            try:
                ack = self.pull_text(REMOTE_ACK).strip()
            except AdbError as exc:
                last_err = str(exc)
                continue
            if not ack:
                continue
            # Evita ack stantio se il pull arriva prima della scrittura.
            if time.time() < before + 0.2:
                continue
            return ack
        raise AdbError(last_err or "Nessun ack da RemoteBridge (Helper avviato sul Pi?)")

    def get_state(self) -> dict:
        try:
            raw = self.pull_text(REMOTE_STATE)
            data = json.loads(raw)
            return data if isinstance(data, dict) else {}
        except (AdbError, json.JSONDecodeError, OSError):
            # Forza refresh lato Pi.
            try:
                self.send_command("get|state", wait_ack=20)
                raw = self.pull_text(REMOTE_STATE)
                data = json.loads(raw)
                return data if isinstance(data, dict) else {}
            except Exception as exc:
                raise AdbError(f"Stato non disponibile: {exc}") from exc
