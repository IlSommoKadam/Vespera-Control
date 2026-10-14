"""Piani salvati condivisi fra Vespera Control Windows e APK.

Due copie comuni, entrambe {"version": 1, "plans": [...], "deleted": [{"created", "at"}]}:
- shared.plans.json nella cartella dell'Helper sul Pi (letto e scritto via ADB, come remote.req);
- un documento fissato nella chat del bot Telegram dell'Helper, per lavorare anche a Pi spento.
Ogni client unisce i suoi piani con quelli delle copie: per lo stesso "created" vince il
"modified" più recente; un piano cancellato (in "deleted" con "at") sparisce ovunque, a meno
che non venga risalvato dopo. Come Favorites.mergeShared e PlansCloud dell'APK.
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from .adb_bridge import HELPER_FILES, AdbBridge, AdbError

SHARED_PLANS = f"{HELPER_FILES}/shared.plans.json"
MAX_PLANS = 30
KEEP_DELETED_MS = 90 * 24 * 3600 * 1000


def stamp(plan: dict) -> int:
    try:
        return int(plan.get("modified") or plan.get("created") or 0)
    except (TypeError, ValueError):
        return 0


def merge(*sources: tuple[list, list]) -> tuple[list[dict], list[dict]]:
    """Unisce più (piani, cancellati); ritorna i piani vivi (il più recente per primo, max 30)
    e le cancellazioni degli ultimi 90 giorni."""
    plans: dict[int, dict] = {}
    gone: dict[int, int] = {}
    for found, deleted in sources:
        for d in deleted or []:
            try:
                c, at = int(d.get("created")), int(d.get("at"))
            except (AttributeError, TypeError, ValueError):
                continue
            gone[c] = max(gone.get(c, 0), at)
        for p in found or []:
            if not isinstance(p, dict) or not isinstance(p.get("steps"), list):
                continue
            try:
                c = int(p.get("created") or 0)
            except (TypeError, ValueError):
                continue
            if c and (c not in plans or stamp(p) > stamp(plans[c])):
                plans[c] = p
    cutoff = int(time.time() * 1000) - KEEP_DELETED_MS
    gone = {c: at for c, at in gone.items() if at >= cutoff}
    alive = [p for c, p in plans.items() if stamp(p) > gone.get(c, -1)]
    alive.sort(key=stamp, reverse=True)
    return alive[:MAX_PLANS], [{"created": c, "at": at} for c, at in sorted(gone.items())]


def _parse(raw: str) -> dict:
    try:
        data = json.loads(raw) if raw.strip() else {}
    except json.JSONDecodeError:
        return {}
    return data if isinstance(data, dict) else {}


def _body(plans: list, deleted: list) -> str:
    return json.dumps({"version": 1, "plans": plans, "deleted": deleted}, ensure_ascii=False,
                      separators=(",", ":"))


def sync(bridge: AdbBridge, plans: list, deleted: list) -> tuple[list[dict], list[dict]]:
    """Legge il file comune sul Pi, lo unisce ai piani locali e lo riscrive. Se il file non
    c'è ancora parte vuoto; con altri errori di lettura non scrive nulla (AdbError)."""
    try:
        raw = bridge.pull_text(SHARED_PLANS)
    except AdbError as exc:
        text = str(exc).lower()
        if "does not exist" not in text and "no such file" not in text:
            raise
        raw = ""
    remote = _parse(raw)
    merged, gone = merge((plans, deleted), (remote.get("plans") or [], remote.get("deleted") or []))
    bridge.push_text(SHARED_PLANS, _body(merged, gone))
    return merged, gone


# --------------------------------------------------------------------------- bot Telegram
# Copia nel cloud per lavorare anche a Pi spento: il file è un documento fissato (pinned) nella
# chat del bot dell'Helper. Si legge con getChat → pinned_message → getFile; si riscrive
# sostituendo il documento dello stesso messaggio (editMessageMedia), così la chat non si
# riempie; se non si può (primo giro o messaggio non più modificabile) si manda e si fissa.

TG_FILE = "vespera-plans.json"
TG_API = "https://api.telegram.org"
TG_CAPTION = "Piani di Vespera Control (non cancellare e non togliere dai fissati)"
CRLF = "\r\n"


def _tg(token: str, method: str, fields: dict, file_field: str = "", data: bytes = b"") -> dict:
    """Chiamata alla Bot API; con file_field manda anche il file dei piani (multipart)."""
    url = f"{TG_API}/bot{token}/{method}"
    fields = {k: str(v) for k, v in fields.items()}
    if not file_field:
        req = urllib.request.Request(url, data=urllib.parse.urlencode(fields).encode())
    else:
        boundary = uuid.uuid4().hex
        parts = [(f"--{boundary}{CRLF}Content-Disposition: form-data; name=\"{k}\"{CRLF}{CRLF}{v}{CRLF}")
                 .encode() for k, v in fields.items()]
        head = (f"--{boundary}{CRLF}Content-Disposition: form-data; name=\"{file_field}\"; "
                f"filename=\"{TG_FILE}\"{CRLF}Content-Type: application/json{CRLF}{CRLF}")
        parts.append(head.encode() + data + CRLF.encode())
        parts.append(f"--{boundary}--{CRLF}".encode())
        req = urllib.request.Request(url, data=b"".join(parts))
        req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    try:
        with urllib.request.urlopen(req, timeout=25) as resp:
            out = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        try:
            out = json.loads(exc.read().decode("utf-8"))
        except (OSError, ValueError):
            raise AdbError(f"Telegram {method}: HTTP {exc.code}") from exc
    except OSError as exc:
        raise AdbError(f"Telegram non raggiungibile: {exc}") from exc
    if not out.get("ok"):
        raise AdbError(f"Telegram {method}: {out.get('description') or 'errore'}")
    return out.get("result") or {}


def _dump(value) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False)


def tg_sync(token: str, chat: str, plans: list, deleted: list) -> tuple[list[dict], list[dict]]:
    """Unisce i piani locali con il documento fissato nella chat del bot e lo riscrive se cambia."""
    token, chat = (token or "").strip(), str(chat or "").strip()
    if not token or not chat:
        raise AdbError("Bot Telegram per i piani non impostato")
    pinned = _tg(token, "getChat", {"chat_id": chat}).get("pinned_message") or {}
    doc = pinned.get("document") or {}
    remote: dict = {}
    message_id = None
    if doc.get("file_name") == TG_FILE:
        message_id = pinned.get("message_id")
        path = _tg(token, "getFile", {"file_id": doc.get("file_id")}).get("file_path")
        try:
            with urllib.request.urlopen(f"{TG_API}/file/bot{token}/{path}", timeout=25) as resp:
                remote = _parse(resp.read().decode("utf-8"))
        except OSError as exc:
            raise AdbError(f"Lettura dei piani da Telegram non riuscita: {exc}") from exc
    merged, gone = merge((plans, deleted), (remote.get("plans") or [], remote.get("deleted") or []))
    if message_id is not None and _dump(remote.get("plans") or []) == _dump(merged) \
            and _dump(remote.get("deleted") or []) == _dump(gone):
        return merged, gone  # niente di nuovo da scrivere
    data = _body(merged, gone).encode("utf-8")
    if message_id is not None:
        try:
            media = json.dumps({"type": "document", "media": "attach://plans", "caption": TG_CAPTION})
            _tg(token, "editMessageMedia", {"chat_id": chat, "message_id": message_id, "media": media},
                "plans", data)
            return merged, gone
        except AdbError:
            pass  # messaggio non più modificabile: se ne manda uno nuovo
    sent = _tg(token, "sendDocument", {"chat_id": chat, "caption": TG_CAPTION, "disable_notification": "true"},
               "document", data)
    _tg(token, "pinChatMessage", {"chat_id": chat, "message_id": sent.get("message_id"),
                                  "disable_notification": "true"})
    return merged, gone
