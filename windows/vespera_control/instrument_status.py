# -*- coding: utf-8 -*-
"""Barre di stato Wi-Fi Vespera e Singularity, come nella UI di Vespera Helper.

Stessi testi e colori di MainActivity.updateSingularityStatusBar / bindDeviceRow
dell'Helper (gemello Android: InstrumentStatus.java).
"""

from __future__ import annotations

STEEL = "#8A97A3"       # offline / non verificabile
AMBER = "#C9A227"       # rilevato ma non connesso
STEEL_BLUE = "#5A7A92"  # in corso
GREEN = "#3B7F55"       # connesso


def text_on(color: str) -> str:
    r, g, b = (int(color[i:i + 2], 16) for i in (1, 3, 5))
    lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return "#212121" if lum > 0.62 else "#FFFFFF"


def vespera_bar(wifi: dict) -> tuple[str, str]:
    """(testo, colore) della barra dello strumento Wi-Fi."""
    wifi = wifi or {}
    status = str(wifi.get("status") or "")
    model = wifi.get("model") or "Vespera"
    ssid = wifi.get("ssid") or ""
    bssid = wifi.get("bssid") or ""
    freq = wifi.get("scanFreq") or wifi.get("freq") or 0
    connected = status == "CONNECTED" and bool(wifi.get("hasNetwork", True))
    if status.startswith("REQUESTING"):
        return f"Connessione…\nRichiesta rete {model} / {ssid}", STEEL_BLUE
    if not wifi.get("configured", bool(ssid)):
        return "○ Nessuno strumento salvato\nSceglilo dalla scansione sul Pi", STEEL
    signal = ""
    if "level" in wifi:
        signal = f"{wifi.get('level')} dBm ({wifi.get('bars', '?')}/5) · "
    tail = f"{bssid} · {signal}{freq} MHz" if freq else f"{bssid} · {signal}".rstrip(" ·")
    if connected:
        return f"✓ {model}\n{ssid}\n{tail}", GREEN
    if wifi.get("online"):
        return f"● {model}\n{ssid}\n{tail}", AMBER
    return f"○ {model}\n{ssid}\n{bssid} · non online (non in scansione)", STEEL


_SING = {
    "IDLE": ("non verificabile (Wi‑Fi Vespera assente)", "○ Singularity\nConnetti al Vespera per verificare", STEEL),
    "CHECKING": ("controllo in corso", "Controllo…\nRilevazione strumento in Singularity", STEEL_BLUE),
    "RECOVERING": ("recupero in corso", "Recupero…\nAggiorno route / riavvio Singularity", STEEL_BLUE),
    "STARTING": ("avvio in corso", "Avvio…\nApro Singularity", STEEL_BLUE),
    "CONNECTED": ("CONNESSO allo strumento", "✓ Singularity\nConnesso allo strumento", GREEN),
    "NOT_RUNNING": ("non in esecuzione", "● Singularity\nNon in esecuzione", AMBER),
    "API_DOWN": ("API Vespera non raggiungibile", "● Singularity\nAPI Vespera non raggiungibile", AMBER),
    "NO_WIFI": ("Wi‑Fi non associata al Vespera", "○ Singularity\nWi‑Fi non associata al Vespera", STEEL),
    "DAEMON_MISSING": ("daemon vespera-netd assente", "○ Singularity\nDaemon vespera-netd assente", STEEL),
}
_SING_DISCONNECTED = ("non rileva lo strumento", "● Singularity\nNon rileva lo strumento", AMBER)


def singularity(sing: dict | None) -> tuple[str, str, str]:
    """(riga 'Stato Singularity: …', testo barra, colore)."""
    if sing is None:
        return "Stato Singularity: —", "○ Singularity\nStato non disponibile (Helper datato)", STEEL
    code = str(sing.get("status") or "IDLE")
    info, bar, color = _SING.get(code, _SING_DISCONNECTED)
    if code not in _SING and code not in ("DISCONNECTED", "UNKNOWN"):
        info = code
    return f"Stato Singularity: {info}", bar, color
