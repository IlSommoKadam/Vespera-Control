"""Previsioni notturne a fasce di 3 h (Open-Meteo), come il footer di Vespera Helper.

Stessa logica di OpenMeteoClient.fetchNightSlots: notti dal tramonto (fine giornata del sito)
all'alba (inizio giornata, inclusa), una cella ogni 3 h con icona WMO, temperatura e mm sommati.
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

from vespera_control import sky

NIGHT_FORECAST_DAYS = 7
# Default di PhotoSyncStore dell'Helper (dayEndHour / dayStartHour).
DEFAULT_NIGHT_START = 19
DEFAULT_NIGHT_END = 10

MONTHS = ("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic")


@dataclass
class Slot:
    when: datetime          # ora del sito (naive)
    code: int               # WMO weather_code
    mm: float               # pioggia/neve sommata fino alla cella successiva
    temp: float | None


@dataclass
class Night:
    start: datetime
    end: datetime
    slots: list[Slot] = field(default_factory=list)


@dataclass
class Result:
    nights: list[Night]
    now: datetime            # ora del sito quando è stato scaricato
    error: str = ""


def kind(code: int) -> str:
    """Icona per codice WMO (come WeatherIcons.drawableFor)."""
    if code in (0, 1):
        return "clear"
    if code in (2, 3):
        return "cloud"
    if code in (45, 48):
        return "fog"
    if 71 <= code <= 77 or code in (85, 86):
        return "snow"
    if 95 <= code <= 99:
        return "storm"
    if 51 <= code <= 67 or 80 <= code <= 82:
        return "rain"
    return "cloud"


def _night_window_start(now: datetime, start_h: int, end_h: int) -> datetime:
    """La notte in corso, oppure la prossima dopo l'alba."""
    start = now.replace(hour=start_h, minute=0, second=0, microsecond=0)
    end = start.replace(hour=end_h)
    if end <= start:
        end += timedelta(days=1)
    if now < start:
        prev_start, prev_end = start - timedelta(days=1), end - timedelta(days=1)
        if prev_start <= now < prev_end:
            return prev_start
        return start
    if now < end:
        return start
    return start + timedelta(days=1)


def _night_end(night_start: datetime, end_h: int) -> datetime:
    end = night_start.replace(hour=end_h, minute=0, second=0, microsecond=0)
    if end <= night_start:
        end += timedelta(days=1)
    return end


def fetch(lat: float, lon: float, start_h: int = DEFAULT_NIGHT_START,
          end_h: int = DEFAULT_NIGHT_END) -> Result:
    """Scarica e divide in notti. Gli orari sono quelli del sito (timezone=auto)."""
    path = ("/v1/forecast?latitude=%.5f&longitude=%.5f"
            "&hourly=weather_code,precipitation,precipitation_probability,temperature_2m"
            "&forecast_days=%d&timezone=auto" % (lat, lon, NIGHT_FORECAST_DAYS))
    code, body = sky._get_text("api.open-meteo.com", path)
    if code >= 400:
        return Result([], datetime.now(), f"HTTP {code}")
    root = json.loads(body)
    offset = timedelta(seconds=int(root.get("utc_offset_seconds") or 0))
    now = (datetime.now(timezone.utc) + offset).replace(tzinfo=None)
    hourly = root.get("hourly") or {}
    times = [datetime.fromisoformat(t) for t in hourly.get("time") or []]
    codes = hourly.get("weather_code") or []
    precip = hourly.get("precipitation") or []
    temps = hourly.get("temperature_2m") or []
    if not times or not codes:
        return Result([], now, "no_series")

    def is_slot(t: datetime, night_end: datetime) -> bool:
        return t.hour % 3 == 0 or (t.hour == end_h and t == night_end)

    nights: list[Night] = []
    first = _night_window_start(now, start_h, end_h)
    for n in range(NIGHT_FORECAST_DAYS - 1):
        ns = first + timedelta(days=n)
        ne = _night_end(ns, end_h)
        night = Night(ns, ne)
        for i, t in enumerate(times):
            if i >= len(codes) or t < ns or t > ne or not is_slot(t, ne):
                continue
            mm = 0.0
            for h in range(i, len(times)):
                th = times[h]
                if th < ns or th > ne or (h > i and is_slot(th, ne)):
                    break
                if h < len(precip) and precip[h] is not None:
                    mm += float(precip[h])
            temp = temps[i] if i < len(temps) and temps[i] is not None else None
            night.slots.append(Slot(t, int(codes[i] or 0), mm, temp))
        if night.slots:
            nights.append(night)
    if not nights:
        return Result([], now, "no_series")
    return Result(nights, now)


def day_caption(night_start: datetime, now: datetime) -> str:
    """«9 ott · Oggi» per la sera in cui inizia la notte."""
    days = max(0, (night_start.date() - now.date()).days)
    relative = {0: "Oggi", 1: "Domani", 2: "Dopodomani"}.get(days, f"Tra {days} giorni")
    return f"{night_start.day} {MONTHS[night_start.month - 1]} · {relative}"


def slot_meta(slot: Slot) -> str:
    """«12° · 0,4 mm» (i mm solo se è prevista pioggia/neve)."""
    parts = []
    if slot.temp is not None:
        parts.append(f"{round(slot.temp)}°")
    if slot.mm >= 0.05:
        mm = f"{slot.mm:.1f}" if slot.mm < 9.95 else f"{slot.mm:.0f}"
        parts.append(mm.replace(".", ",") + " mm")
    return " · ".join(parts)


def first_slot(result: Result) -> tuple[str, Slot] | None:
    """Prima previsione ancora utile della notte in corso/prossima, con «Stasera»/«Stanotte»."""
    if not result.nights:
        return None
    slots = result.nights[0].slots
    upcoming = [s for s in slots if s.when >= result.now - timedelta(hours=1, minutes=30)]
    slot = upcoming[0] if upcoming else slots[0]
    if slot.when.date() > result.now.date() and slot.when.hour < 12:
        label = "Stanotte" if slot.when.date() == result.now.date() + timedelta(days=1) else "Notte"
    elif slot.when.date() == result.now.date():
        label = "Stasera" if slot.when.hour >= 18 else "Oggi"
    else:
        label = "Domani"
    return label, slot


def town_from_label(label: str) -> str:
    """«Adelfia, Bari, Italia» → «Adelfia» (solo il paese/città)."""
    return (label or "").split(",")[0].strip()


_TOWNS: dict[tuple, str] = {}


def town(lat: float, lon: float) -> str:
    """Nome del paese/città per le coordinate (BigDataCloud, senza chiave); "" se non trovato."""
    key = (round(lat, 3), round(lon, 3))
    if key in _TOWNS:
        return _TOWNS[key]
    path = ("/data/reverse-geocode-client?latitude=%.5f&longitude=%.5f&localityLanguage=it" % (lat, lon))
    code, body = sky._get_text("api.bigdatacloud.net", path)
    name = ""
    if 200 <= code < 300:
        data = json.loads(body)
        name = str(data.get("city") or data.get("locality") or "").strip()
    _TOWNS[key] = name
    return name


def friendly_error(err: str) -> str:
    """Testo breve per il riquadro meteo."""
    low = (err or "").lower()
    if not low:
        return "Nessuna previsione"
    if any(k in low for k in ("unreachable", "no route", "getaddrinfo", "11001", "name or service")):
        return "Meteo: senza Internet"
    if "timed out" in low or "timeout" in low:
        return "Meteo: timeout"
    return "Meteo non raggiungibile"


# --------------------------------------------------------------------------- icone (come ic_wx_* dell'Helper)

def _cloud(d, k, dy: float, color: str) -> None:
    d.ellipse([3.4 * k, (5.6 + dy) * k, 9.6 * k, (11.2 + dy) * k], fill=color)
    d.ellipse([7.4 * k, (1.8 + dy) * k, 15.4 * k, (9.8 + dy) * k], fill=color)
    d.ellipse([13.4 * k, (4.6 + dy) * k, 20.0 * k, (11.2 + dy) * k], fill=color)
    d.rectangle([6.4 * k, (7.4 + dy) * k, 16.8 * k, (11.2 + dy) * k], fill=color)


def icon_image(name: str, size: int = 28):
    """Icona meteo (PIL Image RGBA) disegnata sulla griglia 24×24 delle vector Android."""
    from PIL import Image, ImageDraw

    scale = 4
    s = size * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = s / 24.0
    if name == "clear":
        sun = "#F9A825"
        d.ellipse([7.5 * k, 7.5 * k, 16.5 * k, 16.5 * k], fill=sun)
        for a in range(0, 360, 45):
            r = math.radians(a)
            x0, y0 = 12 + 6.3 * math.cos(r), 12 + 6.3 * math.sin(r)
            x1, y1 = 12 + 7.6 * math.cos(r), 12 + 7.6 * math.sin(r)
            d.line([x0 * k, y0 * k, x1 * k, y1 * k], fill=sun, width=int(2.4 * k))
    elif name == "cloud":
        _cloud(d, k, 4.0, "#90A4AE")
        d.ellipse([6.4 * k, 12.8 * k, 11.0 * k, 17.8 * k], fill="#B0BEC5")
        d.ellipse([9.0 * k, 10.0 * k, 15.4 * k, 16.4 * k], fill="#B0BEC5")
        d.ellipse([13.6 * k, 12.6 * k, 18.2 * k, 17.8 * k], fill="#B0BEC5")
        d.rectangle([8.6 * k, 14.6 * k, 16.0 * k, 17.8 * k], fill="#B0BEC5")
    elif name == "rain":
        _cloud(d, k, 0.0, "#78909C")
        for x in (8.4, 12.4, 16.4):
            d.line([(x + 0.9) * k, 13.8 * k, (x - 0.5) * k, 17.4 * k], fill="#1E88E5", width=int(1.9 * k))
    elif name == "storm":
        _cloud(d, k, -0.6, "#546E7A")
        d.polygon([(11.2 * k, 11.5 * k), (8.4 * k, 16.7 * k), (10.6 * k, 16.7 * k), (9.5 * k, 22.0 * k),
                   (14.9 * k, 15.2 * k), (12.5 * k, 15.2 * k), (14.3 * k, 11.5 * k)], fill="#F9A825")
    elif name == "snow":
        _cloud(d, k, -0.2, "#90A4AE")
        for x, y in ((8.9, 14.6), (12.7, 14.6), (16.5, 14.6), (10.2, 18.2), (14.0, 18.2)):
            d.ellipse([(x - 1.1) * k, (y - 1.1) * k, (x + 1.1) * k, (y + 1.1) * k],
                      fill="#E3F2FD", outline="#90A4AE", width=max(1, int(0.4 * k)))
    elif name == "fog":
        d.rounded_rectangle([4 * k, 8 * k, 20 * k, 17 * k], radius=2 * k, fill="#B0BEC5")
        for x, y, r in ((7, 10.5, 1.2), (11, 9.5, 1.4), (15.5, 11, 1.1), (9.5, 13.5, 1.3), (14, 14, 1.2)):
            d.ellipse([(x - r) * k, (y - r) * k, (x + r) * k, (y + r) * k], fill="#78909C")
    elif name == "pin":
        blue = "#1565C0"
        d.ellipse([5 * k, 2 * k, 19 * k, 16 * k], fill=blue)
        d.polygon([(5.6 * k, 11.5 * k), (18.4 * k, 11.5 * k), (12 * k, 22 * k)], fill=blue)
        d.ellipse([9.5 * k, 6.5 * k, 14.5 * k, 11.5 * k], fill=(0, 0, 0, 0))
    return img.resize((size, size), Image.Resampling.LANCZOS)
