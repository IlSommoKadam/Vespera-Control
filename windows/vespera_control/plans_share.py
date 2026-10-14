"""Piani salvati condivisi fra Vespera Control Windows e APK.

Il file comune è shared.plans.json nella cartella dell'Helper sul Pi (letto e scritto via ADB,
come remote.req): {"version": 1, "plans": [...], "deleted": [{"created", "at"}]}.
Ogni client unisce i suoi piani con quelli del file: per lo stesso "created" vince il
"modified" più recente; un piano cancellato (in "deleted" con "at") sparisce ovunque, a meno
che non venga risalvato dopo. Come Favorites.mergeShared dell'APK.
"""

from __future__ import annotations

import json
import time

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
    try:
        remote = json.loads(raw) if raw.strip() else {}
    except json.JSONDecodeError:
        remote = {}
    if not isinstance(remote, dict):
        remote = {}
    merged, gone = merge((plans, deleted), (remote.get("plans") or [], remote.get("deleted") or []))
    body = {"version": 1, "plans": merged, "deleted": gone}
    bridge.push_text(SHARED_PLANS, json.dumps(body, ensure_ascii=False, separators=(",", ":")))
    return merged, gone
