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
    r"(?i)^(\d{4})-(\d{2})-(\d{2})(?:_(\d{2})-(\d{2})-(\d{2}))?"
    r".{0,48}?(?:observation|acquisition)[_\s-]+(.+)$"
)
CATALOG = re.compile(
    r"(?i)\b(M\s*\d{1,3}|NGC\s*\d{1,4}|IC\s*\d{1,4}|SH\s*2\s*-?\s*\d{1,4})\b"
)
OUTPUT_NAME = re.compile(r"(?i).*-output\.jpe?g$")
OUTPUT_INDEX = re.compile(r"(?i)img-(\d+)-output\.jpe?g$")
IMAGE_EXT = {".jpg", ".jpeg", ".png"}
# Il LIST di questo FTP è in inglese (ls), indipendente dalla lingua di Windows.
_LIST_MONTHS = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
}

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
    # Firma dell'ultimo *-output.jpg (path + ora): cambia quando la cartella si aggiorna.
    signature: str = ""


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


def observation_parts(name: str) -> tuple[str, str]:
    """Estrae target e data/ora dal nome cartella Vespera."""
    match = OBS_DIR.match(name or "")
    if not match:
        return "", ""
    year, month, day, hour, minute, _sec, target = match.groups()
    target = (target or "").strip("_- ")
    if hour is not None and minute is not None:
        when = f"{day}/{month}/{year} {hour}:{minute}"
    else:
        when = f"{day}/{month}/{year}"
    return target, when


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


def _output_index(name: str) -> int:
    match = OUTPUT_INDEX.search(name or "")
    return int(match.group(1)) if match else -1


def _list_mtime(month: str, day: str, clock_or_year: str) -> float:
    """Ora locale del LIST Unix (`Oct 04 02:14` oppure `Oct 04 2025`)."""
    mon = _LIST_MONTHS.get((month or "").lower())
    if not mon:
        return 0.0
    try:
        day_n = int(day)
    except ValueError:
        return 0.0
    now = time.time()
    try:
        if ":" in clock_or_year:
            hour_s, minute_s = clock_or_year.split(":", 1)
            year = time.localtime(now).tm_year
            stamp = time.mktime((year, mon, day_n, int(hour_s), int(minute_s), 0, 0, 0, -1))
            # Senza anno: se cade nel futuro, è l'anno precedente.
            if stamp > now + 24 * 3600:
                stamp = time.mktime((year - 1, mon, day_n, int(hour_s), int(minute_s), 0, 0, 0, -1))
            return stamp
        return time.mktime((int(clock_or_year), mon, day_n, 0, 0, 0, 0, 0, -1))
    except (OverflowError, ValueError):
        return 0.0


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
            mtime = _list_mtime(parts[5], parts[6], parts[7])
            parsed.append((parts[8], "dir" if parts[0].startswith("d") else "file", mtime, size))
        return parsed


def _join(*parts: str) -> str:
    bits = []
    for part in parts:
        part = (part or "").strip("/")
        if part:
            bits.append(part)
    return "/" + "/".join(bits)


def _format_when(mtime: float) -> str:
    if not mtime:
        return ""
    return time.strftime("%d/%m/%Y %H:%M", time.localtime(mtime))


def _latest_output(ftp: ftplib.FTP, folder: str, depth: int = 0) -> tuple[str, float]:
    """Path e mtime dell'ultimo *-output.jpg (stesso criterio dell'anteprima)."""
    if depth > 5:
        return "", 0.0
    try:
        ftp.cwd("/")
        for part in folder.strip("/").split("/"):
            if part:
                ftp.cwd(part)
    except ftplib.Error:
        return "", 0.0
    best_path = ""
    best_mtime = -1.0
    best_index = -1
    dirs: list[str] = []
    for name, kind, mtime, _size in _entries(ftp):
        remote = _join(folder, name)
        if kind == "dir":
            lowered = name.lower()
            if "dark" in lowered or "expert" in lowered:
                continue
            dirs.append(remote)
            continue
        if not OUTPUT_NAME.match(name):
            continue
        stamp = float(mtime or 0.0)
        index = _output_index(name)
        if (stamp, index) >= (best_mtime, best_index):
            best_mtime = stamp
            best_index = index
            best_path = remote
    for sub in dirs:
        path, stamp = _latest_output(ftp, sub, depth + 1)
        if not path:
            continue
        index = _output_index(path)
        if (stamp, index) >= (best_mtime, best_index):
            best_mtime = stamp
            best_index = index
            best_path = path
    return best_path, max(best_mtime, 0.0)


def _skip_dir(name: str) -> bool:
    lowered = name.lower()
    return name.startswith(".") or "dark" in lowered or "expert" in lowered


def _looks_like_object(name: str) -> bool:
    return bool(observation_parts(name)[0] or CATALOG.search(name))


def _list_dirs(ftp: ftplib.FTP, folder: str) -> list[tuple[str, float]]:
    try:
        ftp.cwd("/")
        for part in folder.strip("/").split("/"):
            if part:
                ftp.cwd(part)
    except ftplib.Error:
        return []
    return [(n, mt) for n, k, mt, _sz in _entries(ftp) if k == "dir" and not _skip_dir(n)]


def _candidate_folders(ftp: ftplib.FTP, all_roots: bool) -> list[tuple[str, str, float, str]]:
    """(cartella, nome, mtime, gruppo): figli di USER e, con all_roots (HD), le altre cartelle in radice."""
    root = _list_dirs(ftp, "/")
    user = next((n for n, _mt in root if n.lower() == "user"), "")
    if not user:
        return [(_join(n), n, mt, "") for n, mt in root]
    found = [(_join(user, n), n, mt, "") for n, mt in _list_dirs(ftp, _join(user))]
    if not all_roots:
        return found
    for name, mtime in root:
        if name == user:
            continue
        # Cartella in radice dell'HD: contenitore di osservazioni o osservazione essa stessa.
        children = _list_dirs(ftp, _join(name))
        if not _looks_like_object(name) and any(_looks_like_object(c) for c, _mt in children):
            found += [(_join(name, c), c, mt, name) for c, mt in children]
        else:
            found.append((_join(name), name, mtime, name))
    return found


def list_objects(host: str, port: int, limit: int = 80, all_roots: bool = False) -> list[SkyObject]:
    """Elenco oggetti. all_roots=True (HD) include anche le cartelle fuori da USER."""
    ftp = open_ftp(host, port)
    try:
        objects: list[tuple[float, SkyObject]] = []
        folders = _candidate_folders(ftp, all_roots)
        folders.sort(key=lambda item: (item[2], item[1]), reverse=True)
        for folder, name, mtime, group in folders[: limit * 2 if all_roots else limit]:
            target, when_obs = observation_parts(name)
            if not target:
                cat = CATALOG.search(name)
                target = cat.group(1).replace(" ", "") if cat else name
            latest_path, stack_mtime = _latest_output(ftp, folder)
            # Fuori da USER si mostrano solo cartelle con almeno un *-output.jpg.
            if group and not latest_path:
                continue
            signature = f"{latest_path}@{stack_mtime:.0f}" if latest_path else f"dir@{mtime or 0}"
            if stack_mtime:
                when = _format_when(stack_mtime)
                sort_key = stack_mtime
            elif mtime:
                when = _format_when(mtime)
                sort_key = mtime
            else:
                when = when_obs
                sort_key = 0.0
            public = common_name_for(target)
            label = f"{target} · {public}" if public else target
            if group and group != target:
                label = f"{label}  [{group}]"
            objects.append(
                (
                    sort_key,
                    SkyObject(
                        label=label,
                        target=target,
                        public_name=public,
                        when=when,
                        folder=folder,
                        signature=signature,
                    ),
                )
            )
        objects.sort(key=lambda item: (-item[0], item[1].label.lower()))
        return [obj for _key, obj in objects]
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
        images.sort(key=lambda item: (item[1], _output_index(item[0]), item[0]), reverse=True)
        outputs.sort(key=lambda item: (item[1], _output_index(item[0]), item[0]), reverse=True)
        output = outputs[0][0] if outputs else (images[0][0] if images else "")
        stack_mtime = outputs[0][1] if outputs else (images[0][1] if images else 0.0)
        folder_name = Path(folder.rstrip("/")).name
        target, when_obs = observation_parts(folder_name)
        if not target:
            target = folder_name
        when = _format_when(stack_mtime) if stack_mtime else when_obs
        public = common_name_for(target)
        return SkyObject(
            label=f"{target} · {public}" if public else target,
            target=target,
            public_name=public,
            when=when,
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
