# -*- coding: utf-8 -*-
"""Anteprima FTP stile StarStacKadam (senza stacking): oggetti + ultimo *-output.jpg."""

from __future__ import annotations

import calendar
import ftplib
import io
import re
import time
from dataclasses import dataclass, field
from pathlib import Path

OBS_DIR = re.compile(
    r"(?i)^(\d{4}-\d{2}-\d{2}).{0,48}?(?:observation|acquisition)[_\s-]+(.+)$"
)
CATALOG = re.compile(
    r"(?i)\b(M\s*\d{1,3}|NGC\s*\d{1,4}|IC\s*\d{1,4}|SH\s*2\s*-?\s*\d{1,4})\b"
)
OUTPUT_NAME = re.compile(r"(?i).*-output\.jpe?g$")
IMAGE_EXT = {".jpg", ".jpeg", ".png"}

# Sottoinsieme dei nomi comuni (StarStacKadam CommonNames).
COMMON_NAME = {
    "m31": "Galassia di Andromeda",
    "m33": "Galassia del Triangolo",
    "m42": "Nebulosa di Orione",
    "m45": "Pleiadi",
    "m51": "Galassia Vorticosa",
    "m57": "Nebulosa Anello",
    "m81": "Galassia di Bode",
    "m82": "Galassia Sigaro",
    "m8": "Nebulosa Laguna",
    "m16": "Nebulosa Aquila",
    "m17": "Nebulosa Omega",
    "m20": "Nebulosa Trifida",
    "m27": "Nebulosa Manubrio",
    "ngc7000": "Nebulosa Nord America",
    "ngc6960": "Nebulosa Velo",
    "ngc6992": "Nebulosa Velo",
    "ic1396": "Nebulosa Proboscide d'Elefante",
}


@dataclass
class SkyObject:
    label: str
    target: str
    public_name: str
    when: str
    folder: str
    output_path: str = ""
    images: list[str] = field(default_factory=list)


class PreviewError(Exception):
    pass


def parse_endpoint(text: str, default_port: int) -> tuple[str, int]:
    raw = (text or "").strip()
    if raw.lower().startswith("ftp://"):
        raw = raw[6:]
    raw = raw.strip().strip("/")
    if not raw:
        raise PreviewError("Endpoint FTP vuoto")
    if raw.count(":") == 1:
        host, port_s = raw.rsplit(":", 1)
        if port_s.isdigit():
            return host, int(port_s)
    return raw, default_port


def common_name_for(target: str) -> str:
    key = re.sub(r"[\s_-]+", "", (target or "").lower())
    return COMMON_NAME.get(key, "")


def open_ftp(host: str, port: int, timeout: float = 12) -> ftplib.FTP:
    ftp = ftplib.FTP()
    try:
        ftp.connect(host, port, timeout=timeout)
        try:
            ftp.login()
        except ftplib.error_perm:
            ftp.login("anonymous", "anonymous@")
        ftp.set_pasv(True)
        try:
            ftp.encoding = "utf-8"
        except Exception:
            pass
        return ftp
    except OSError as exc:
        raise PreviewError(f"FTP {host}:{port} non raggiungibile: {exc}") from exc


def reachable(host: str, port: int, timeout: float = 6) -> bool:
    try:
        ftp = open_ftp(host, port, timeout=timeout)
        try:
            ftp.pwd()
        finally:
            try:
                ftp.quit()
            except Exception:
                ftp.close()
        return True
    except Exception:
        return False


def _entries(ftp: ftplib.FTP) -> list[tuple[str, str, float, int]]:
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
            return []
        parsed: list[tuple[str, str, float, int]] = []
        for line in lines:
            parts = line.split(maxsplit=8)
            if len(parts) < 9 or parts[8] in {".", ".."}:
                continue
            size = int(parts[4]) if parts[4].isdigit() else 0
            parsed.append((parts[8], "dir" if parts[0].startswith("d") else "file", 0.0, size))
        return parsed


def _join(*parts: str) -> str:
    bits = []
    for part in parts:
        part = (part or "").strip("/")
        if part:
            bits.append(part)
    return "/" + "/".join(bits)


def list_objects(host: str, port: int, limit: int = 80) -> list[SkyObject]:
    ftp = open_ftp(host, port)
    try:
        ftp.cwd("/")
        root = _entries(ftp)
        user = next((n for n, k, *_ in root if k == "dir" and n.lower() == "user"), "")
        base = user or ""
        if base:
            ftp.cwd(base)
            dirs = _entries(ftp)
        else:
            dirs = root
        objects: list[SkyObject] = []
        folders = [(n, mt) for n, k, mt, _sz in dirs if k == "dir"]
        folders.sort(key=lambda item: (item[1], item[0]), reverse=True)
        for name, mtime in folders[:limit]:
            match = OBS_DIR.match(name)
            if match:
                day, target = match.group(1), match.group(2).strip("_- ")
            else:
                cat = CATALOG.search(name)
                target = cat.group(1).replace(" ", "") if cat else name
                day = time.strftime("%Y-%m-%d", time.localtime(mtime)) if mtime else ""
            public = common_name_for(target)
            when = ""
            if mtime:
                when = time.strftime("%d/%m/%Y %H:%M", time.localtime(mtime))
            elif day:
                when = day
            objects.append(
                SkyObject(
                    label=f"{target} · {public}" if public else target,
                    target=target,
                    public_name=public,
                    when=when,
                    folder=_join(base, name) if base else f"/{name}",
                )
            )
        return objects
    finally:
        try:
            ftp.quit()
        except Exception:
            ftp.close()


def load_object_preview(host: str, port: int, folder: str) -> SkyObject:
    """Carica dettagli: elenco immagini e ultimo *-output.jpg nella cartella oggetto."""
    ftp = open_ftp(host, port)
    try:
        images: list[tuple[str, float, int]] = []
        outputs: list[tuple[str, float, int]] = []

        def walk(path: str, depth: int) -> None:
            if depth > 5:
                return
            ftp.cwd("/")
            for part in path.strip("/").split("/"):
                if part:
                    ftp.cwd(part)
            for name, kind, mtime, size in _entries(ftp):
                remote = _join(path, name)
                if kind == "dir":
                    lowered = name.lower()
                    if "dark" in lowered or "expert" in lowered:
                        continue
                    walk(remote, depth + 1)
                    continue
                ext = Path(name).suffix.lower()
                if ext not in IMAGE_EXT or size > 40_000_000:
                    continue
                images.append((remote, mtime, size))
                if OUTPUT_NAME.match(name):
                    outputs.append((remote, mtime, size))

        walk(folder, 0)
        images.sort(key=lambda item: (item[1], item[0]), reverse=True)
        outputs.sort(key=lambda item: (item[1], item[0]), reverse=True)
        output = outputs[0][0] if outputs else (images[0][0] if images else "")
        target = Path(folder.rstrip("/")).name
        match = OBS_DIR.match(target)
        if match:
            target = match.group(2).strip("_- ")
        public = common_name_for(target)
        return SkyObject(
            label=f"{target} · {public}" if public else target,
            target=target,
            public_name=public,
            when="",
            folder=folder,
            output_path=output,
            images=[path for path, _m, _s in images[:40]],
        )
    finally:
        try:
            ftp.quit()
        except Exception:
            ftp.close()


def download_bytes(host: str, port: int, remote: str) -> bytes:
    ftp = open_ftp(host, port)
    try:
        directory, _, filename = remote.rpartition("/")
        ftp.cwd("/")
        for part in directory.split("/"):
            if part:
                ftp.cwd(part)
        buffer = io.BytesIO()
        ftp.retrbinary(f"RETR {filename}", buffer.write)
        data = buffer.getvalue()
        if not data:
            raise PreviewError(f"File vuoto: {filename}")
        return data
    except ftplib.all_errors as exc:
        raise PreviewError(str(exc)) from exc
    finally:
        try:
            ftp.quit()
        except Exception:
            ftp.close()
