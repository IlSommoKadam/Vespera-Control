# -*- coding: utf-8 -*-
"""Osserva: ricerca catalogo (CDS SESAME/SIMBAD), anteprima hips2fits e
visibilità della notte. Porting speculare di SkyNames/SkySearch/SkyCatalog/
NightSky dell'APK Android."""

from __future__ import annotations

import json
import math
import re
import time
import unicodedata
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, timedelta

TIMEOUT = 20
MAX_BYTES = 2_500_000
SESAME_HOST = "cds.unistra.fr"
SIMBAD_HOST = "simbad.cds.unistra.fr"
PREVIEW_HOST = "alasky.cds.unistra.fr"
PANSTARRS = "CDS/P/PanSTARRS/DR1/color-z-zg-g"
DSS_COLOR = "CDS/P/DSS2/color"
LIMIT = 12


class SkyError(Exception):
    """Codici: empty, short, not_found, net."""


# --------------------------------------------------------------------------- HTTP

def _get(host: str, path: str, accept: str = "application/json,text/*,*/*") -> tuple[int, bytes]:
    if not path.startswith("/"):
        path = "/" + path
    req = urllib.request.Request(
        f"https://{host}{path}",
        headers={"Accept": accept, "User-Agent": "VesperaControl/1.0"},
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            data = resp.read(MAX_BYTES + 1)
            code = resp.status
    except urllib.error.HTTPError as exc:
        return exc.code, b""
    if len(data) > MAX_BYTES:
        raise SkyError("net")
    return code, data


def _get_text(host: str, path: str) -> tuple[int, str]:
    code, data = _get(host, path)
    return code, data.decode("utf-8", errors="replace")


# --------------------------------------------------------------------------- nomi

PHRASES = [
    ("testa di cavallo", "horsehead"), ("nord america", "north america"),
    ("sette sorelle", "seven sisters"), ("doppio ammasso", "double cluster"),
    ("stella polare", "polaris"), ("nebulosa", "nebula"), ("galassia", "galaxy"),
    ("ammasso", "cluster"), ("stella", "star"), ("pianeta", "planet"),
    ("orione", "orion"), ("pleiadi", "pleiades"), ("granchio", "crab"),
    ("aquila", "eagle"), ("cigno", "swan"), ("laguna", "lagoon"), ("velo", "veil"),
    ("rosetta", "rosette"), ("manubrio", "dumbbell"), ("anello", "ring"),
    ("vortice", "whirlpool"), ("girandola", "pinwheel"), ("ercole", "hercules"),
    ("triangolo", "triangulum"), ("cavallo", "horse"), ("giove", "jupiter"),
    ("saturno", "saturn"), ("marte", "mars"), ("venere", "venus"), ("luna", "moon"),
    ("sirio", "sirius"),
]

STOP = {
    "di", "del", "della", "delle", "dei", "degli", "of", "the", "da", "in",
    "sul", "sulla", "nebula", "nebulosa", "galaxy", "galassia", "cluster",
    "ammasso", "star", "stella", "object", "oggetto", "open", "aperto",
    "globular", "globulare", "planet", "pianeta",
}

_ALIAS_SRC = [
    ("orione", "Orion Nebula"), ("nebulosa di orione", "Orion Nebula"),
    ("nebulosa orione", "Orion Nebula"), ("orion", "Orion Nebula"),
    ("orion nebula", "Orion Nebula"), ("andromeda", "Andromeda"),
    ("galassia di andromeda", "Andromeda"), ("galassia andromeda", "Andromeda"),
    ("andromeda galaxy", "Andromeda"), ("pleiadi", "Pleiades"), ("pleiades", "Pleiades"),
    ("sette sorelle", "Pleiades"), ("seven sisters", "Pleiades"),
    ("nebulosa granchio", "Crab Nebula"), ("granchio", "Crab Nebula"),
    ("crab nebula", "Crab Nebula"), ("nebulosa anello", "Ring Nebula"),
    ("anello", "Ring Nebula"), ("ring nebula", "Ring Nebula"),
    ("nebulosa manubrio", "Dumbbell"), ("manubrio", "Dumbbell"), ("dumbbell", "Dumbbell"),
    ("dumbbell nebula", "Dumbbell"), ("nebulosa laguna", "Lagoon Nebula"),
    ("laguna", "Lagoon Nebula"), ("lagoon nebula", "Lagoon Nebula"),
    ("nebulosa trifida", "Trifid Nebula"), ("trifida", "Trifid Nebula"),
    ("trifid nebula", "Trifid Nebula"), ("nebulosa aquila", "Eagle Nebula"),
    ("aquila", "Eagle Nebula"), ("eagle nebula", "Eagle Nebula"),
    ("nebulosa omega", "Omega Nebula"), ("omega nebula", "Omega Nebula"),
    ("nebulosa cigno", "Omega Nebula"), ("nord america", "North America Nebula"),
    ("nebulosa nord america", "North America Nebula"),
    ("north america nebula", "North America Nebula"), ("nebulosa velo", "Veil Nebula"),
    ("velo", "Veil Nebula"), ("veil nebula", "Veil Nebula"),
    ("nebulosa rosetta", "Rosette Nebula"), ("rosetta", "Rosette Nebula"),
    ("rosette nebula", "Rosette Nebula"), ("testa di cavallo", "Horsehead Nebula"),
    ("horsehead", "Horsehead Nebula"), ("horsehead nebula", "Horsehead Nebula"),
    ("galassia vortice", "Whirlpool Galaxy"), ("vortice", "Whirlpool Galaxy"),
    ("whirlpool", "Whirlpool Galaxy"), ("whirlpool galaxy", "Whirlpool Galaxy"),
    ("galassia sombrero", "Sombrero Galaxy"), ("sombrero", "Sombrero Galaxy"),
    ("sombrero galaxy", "Sombrero Galaxy"), ("galassia girandola", "Pinwheel Galaxy"),
    ("girandola", "Pinwheel Galaxy"), ("pinwheel", "Pinwheel Galaxy"),
    ("pinwheel galaxy", "Pinwheel Galaxy"), ("ammasso di ercole", "Hercules Cluster"),
    ("ercole", "Hercules Cluster"), ("hercules cluster", "Hercules Cluster"),
    ("galassia triangolo", "Triangulum Galaxy"), ("triangolo", "Triangulum Galaxy"),
    ("triangulum", "Triangulum Galaxy"), ("triangulum galaxy", "Triangulum Galaxy"),
    ("doppio ammasso", "Double Cluster"), ("double cluster", "Double Cluster"),
    ("sirio", "Sirius"), ("sirius", "Sirius"), ("betelgeuse", "Betelgeuse"),
    ("aldebaran", "Aldebaran"), ("polare", "Polaris"), ("stella polare", "Polaris"),
    ("polaris", "Polaris"), ("vega", "Vega"), ("giove", "Jupiter"), ("jupiter", "Jupiter"),
    ("saturno", "Saturn"), ("saturn", "Saturn"), ("marte", "Mars"), ("mars", "Mars"),
    ("venere", "Venus"), ("venus", "Venus"), ("luna", "Moon"), ("moon", "Moon"),
]


def normalize(value: str) -> str:
    text = unicodedata.normalize("NFD", value or "")
    text = "".join(c for c in text if unicodedata.category(c) != "Mn")
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


ALIASES: dict[str, str] = {}
for _k, _v in _ALIAS_SRC:
    ALIASES.setdefault(normalize(_k), _v)


def to_english(query: str) -> str:
    text = " " + normalize(query) + " "
    for it, en in PHRASES:
        text = text.replace(" " + it + " ", " " + en + " ")
    return text.strip()


def exact_alias(query: str) -> str | None:
    key = normalize(query)
    if not key:
        return None
    return ALIASES.get(key) or ALIASES.get(normalize(to_english(query)))


def stem(query: str) -> str:
    best = ""
    for token in normalize(to_english(query)).split(" "):
        if len(token) < 3 or token in STOP:
            continue
        if len(token) > len(best):
            best = token
    return best


def alias_targets_starting_with(word: str) -> list[str]:
    needle = normalize(word)
    out: list[str] = []
    if len(needle) < 3:
        return out
    for key, value in ALIASES.items():
        if key.startswith(needle) and value not in out:
            out.append(value)
            if len(out) >= 6:
                break
    return out


def compact(value: str) -> str:
    return "".join(c.upper() for c in (value or "") if c not in " _-")


# --------------------------------------------------------------------------- catalogo

@dataclass
class Hit:
    name: str
    type_code: str
    ra_deg: float
    dec_deg: float

    def choice_label(self) -> str:
        t = type_label(self.type_code) or self.type_code
        head = f"{self.name}  ·  {t}" if t else self.name
        return f"{head}   RA {ra_text(self.ra_deg)}   Dec {dec_text(self.dec_deg)}"


@dataclass
class Target:
    query: str
    name: str
    type_code: str
    ra_deg: float
    dec_deg: float
    preview_jpeg: bytes | None

    def object_id(self) -> str:
        return compact(self.name or self.query)


@dataclass
class Session:
    store_id: str
    object_name: str
    stacks: int


def _tag(xml: str, name: str) -> str:
    m = re.search(rf"<{name}>(.*?)</{name}>", xml, re.S)
    return m.group(1).strip() if m else ""


_DSO_ID = re.compile(r"(?i)^(M|NGC|IC)\s+\d+[A-Z]?$|^Sh\s*2-\d+$|^(LBN|LDN|Ced|vdB|RCW|Gum)\s+\d+$|^Barnard\s+\d+$")
_NEBULA_ID = re.compile(r"(?i)^Sh\s*2-\d+$|^(LBN|LDN|Ced|vdB|RCW|Gum)\s+\d+$")


def clean_id(ident: str) -> str:
    """Toglie gli spazi doppi di SIMBAD: 'NGC  6888' -> 'NGC 6888'."""
    out = re.sub(r"\s+", " ", (ident or "").strip())
    return out[5:] if out.startswith("NAME ") else out


def is_dso_id(ident: str) -> bool:
    return bool(_DSO_ID.match(clean_id(ident)))


def is_star_type(otype: str) -> bool:
    c = (otype or "").strip().upper()
    if c.startswith(("CL", "AS", "C?")):
        return False
    return "*" in c


def _dso_rank(ident: str) -> int:
    u = ident.upper()
    for i, pre in enumerate(("M ", "NGC", "IC", "SH")):
        if u.startswith(pre):
            return i
    return 4


def prefer_dso(query: str, hit: Hit, aliases: list[str]) -> Hit:
    """SIMBAD a volte unisce una nebulosa alla sua stella centrale (NGC 6888 -> HD 192163).
    Se l'oggetto è una stella ma ha una sigla di nebulosa/galassia/ammasso, mostra quella
    (preferendo la sigla cercata). Come SkyCatalog.preferDso dell'APK."""
    if hit is None or not aliases or not is_star_type(hit.type_code):
        return hit
    wanted = compact(clean_id(query))
    chosen = None
    nebula = False
    for alias in aliases:
        ident = clean_id(alias)
        if _NEBULA_ID.match(ident):
            nebula = True
        if not _DSO_ID.match(ident):
            continue
        if chosen is not None and compact(chosen) == wanted:
            continue
        if compact(ident) == wanted or chosen is None or _dso_rank(ident) < _dso_rank(chosen):
            chosen = ident
    if chosen is None:
        return hit
    return Hit(chosen, "Neb" if nebula else hit.type_code, hit.ra_deg, hit.dec_deg)


def sesame_hit(query: str) -> Hit | None:
    name = (query or "").strip()[:80]
    if not name:
        return None
    try:
        code, xml = _get_text(SESAME_HOST, "/cgi-bin/nph-sesame/-oxI/S?" + urllib.parse.quote(name))
        if code < 200 or code >= 300:
            return None
        at = xml.find("<Resolver")
        if at < 0:
            return None
        part = xml[at:]
        ra, dec = _tag(part, "jradeg"), _tag(part, "jdedeg")
        if not ra or not dec:
            return None
        ra_d, dec_d = float(ra), float(dec)
        if not (0 <= ra_d < 360 and -90 <= dec_d <= 90):
            return None
        aliases = [a.strip() for a in re.findall(r"<alias>(.*?)</alias>", part, re.S)]
        return prefer_dso(name, Hit(clean_id(_tag(part, "oname") or name), _tag(part, "otype"), ra_d, dec_d), aliases)
    except Exception:
        return None


_CATALOG = re.compile(r"(?i)^(m|ngc|ic|ugc|pgc)\s*(\d+)\s*$")
_COORD = re.compile(r"([+-]?\d+(?:\.\d+)?)\s+([+-]?\d+(?:\.\d+)?)")


def _tap_like(prefix: str) -> list[Hit]:
    sql = (
        "SELECT TOP 20 basic.main_id, basic.ra, basic.dec, basic.otype, ident.id "
        "FROM basic JOIN ident ON ident.oidref = basic.oid "
        f"WHERE ident.id LIKE '{prefix.replace(chr(39), '')}%'"
    )
    path = ("/simbad/sim-tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=json&MAXREC=20&QUERY="
            + urllib.parse.quote(sql))
    code, body = _get_text(SIMBAD_HOST, path)
    if code < 200 or code >= 300:
        raise SkyError("net")
    out: dict[str, Hit] = {}
    if not body.startswith("{"):
        return []
    for row in (json.loads(body).get("data") or []):
        if not isinstance(row, list) or len(row) < 4:
            continue
        ident, ra, dec, otype = str(row[0] or "").strip(), row[1], row[2], str(row[3] or "")
        matched = clean_id(str(row[4] or "")) if len(row) > 4 else ""
        if not ident or ra is None or dec is None:
            continue
        key = compact(ident)
        if key in out:
            continue
        # Mostra la sigla cercata (M 44, NGC 6888) invece dell'id principale SIMBAD.
        hit = Hit(matched or clean_id(ident), otype.strip(), float(ra), float(dec))
        if is_star_type(otype) and is_dso_id(matched):
            hit = sesame_hit(matched) or hit
        out[key] = hit
        if len(out) >= LIMIT:
            break
    return list(out.values())


def _wildcard(word: str) -> list[Hit]:
    safe = re.sub(r"[^A-Za-z0-9 \-]", "", word).strip()
    if len(safe) < 3:
        return []
    script = (
        "output console=off script=off\n"
        'format object "%MAIN_ID|%COO(d;A D)|%OTYPE(3)|%IDLIST(M,NGC,IC,Sh,LBN,LDN,Ced,vdB,RCW,Gum)"\n'
        "set limit 18\n"
        f"query id wildcard NAME {safe}*\n"
    )
    code, body = _get_text(SIMBAD_HOST, "/simbad/sim-script?script=" + urllib.parse.quote(script))
    if code < 200 or code >= 300:
        raise SkyError("net")
    out: list[Hit] = []
    for line in body.split("\n"):
        row = line.strip()
        if not row or row.startswith(":"):
            continue
        parts = row.split("|")
        if len(parts) < 3 or not parts[0].strip():
            continue
        coord = parts[1].strip()
        if "no coord" in coord.lower():
            continue
        m = _COORD.search(coord)
        if not m:
            continue
        ra, dec = float(m.group(1)), float(m.group(2))
        if not (0 <= ra < 360 and -90 <= dec <= 90):
            continue
        ids = [p.strip() for p in parts[3].split(",") if p.strip()] if len(parts) > 3 else []
        out.append(prefer_dso(parts[0].strip(), Hit(clean_id(parts[0]), parts[2].strip(), ra, dec), ids))
        if len(out) >= LIMIT:
            break
    return out


def find(query: str) -> list[Hit]:
    """Sigla esatta, prefisso (M4 → M4, M40…) o nome parziale IT/EN."""
    name = (query or "").strip()[:80]
    if not name:
        raise SkyError("empty")
    try:
        exact = exact_alias(name)
        if exact:
            hit = sesame_hit(exact)
            if hit:
                return [hit]
        m = _CATALOG.match(name)
        if m:
            tag = m.group(1).upper()
            hits = _tap_like(f"{tag}  {m.group(2)}") or _tap_like(f"{tag} {m.group(2)}")
            if hits:
                return hits
            hit = sesame_hit(name)
            if hit:
                return [hit]
            raise SkyError("not_found")
        word = stem(name)
        if len(word) < 3:
            raise SkyError("short")
        merged: dict[str, Hit] = {}

        def add(hit: Hit | None) -> None:
            if hit is None or len(merged) >= LIMIT:
                return
            key = compact(hit.name)
            if key and key not in merged:
                merged[key] = hit

        add(sesame_hit(to_english(name)))
        add(sesame_hit(name))
        for alias in alias_targets_starting_with(word):
            add(sesame_hit(alias))
        for hit in _wildcard(word):
            add(hit)
        if not merged:
            raise SkyError("not_found")
        return list(merged.values())[:LIMIT]
    except SkyError:
        raise
    except Exception as exc:
        raise SkyError("net") from exc


def _angular_size_arcmin(name: str) -> float:
    if not name:
        return 0.0
    try:
        code, text = _get_text(
            SIMBAD_HOST, "/simbad/sim-id?Ident=" + urllib.parse.quote(name) + "&output.format=ASCII")
        if code < 200 or code >= 300:
            return 0.0
        at = text.find("Angular size:")
        if at < 0:
            return 0.0
        line = text[at:].split("\n", 1)[0]
        nums = [float(x) for x in re.findall(r"\d+(?:\.\d+)?", line)[:2]]
        return max(nums) if nums else 0.0
    except Exception:
        return 0.0


def _fov(major_arcmin: float) -> float:
    if major_arcmin <= 0:
        return 0.5
    return min(10.0, max(0.02, major_arcmin / 60.0 * 2.6))


def _object_image(survey: str, ra: float, dec: float, fov: float) -> bytes | None:
    try:
        path = ("/hips-image-services/hips2fits?hips=%s&ra=%f&dec=%f&fov=%f"
                "&width=1100&height=740&format=jpg") % (urllib.parse.quote(survey, safe=""), ra, dec, fov)
        code, data = _get(PREVIEW_HOST, path, "image/jpeg,image/*,*/*")
        if code < 200 or code >= 300 or len(data) < 32 or data[0] != 0xFF or data[1] != 0xD8:
            return None
        return data
    except Exception:
        return None


def with_preview(hit: Hit) -> Target:
    size = _angular_size_arcmin(hit.name)
    if size <= 0 and hit.type_code == "Neb":
        size = 18.0
    fov = _fov(size)
    jpeg = _object_image(PANSTARRS, hit.ra_deg, hit.dec_deg, fov) or \
        _object_image(DSS_COLOR, hit.ra_deg, hit.dec_deg, fov)
    return Target(hit.name, hit.name, hit.type_code, hit.ra_deg, hit.dec_deg, jpeg)


def type_label(code: str) -> str:
    if not code:
        return ""
    c = code.upper()
    if c.startswith("V*"):
        return "stella variabile"
    if c == "GLC":
        return "ammasso globulare"
    if c == "PN":
        return "nebulosa planetaria"
    if c == "SNR":
        return "resto di supernova"
    if c in ("OPC", "CL*", "AS*"):
        return "ammasso aperto"
    if "NEB" in c or c in ("HII", "ISM", "RFN"):
        return "nebulosa"
    if "*" in c or c in ("HS", "BD"):
        return "stella"
    if c.startswith("G") or "AGN" in c or c in ("LIN", "SY", "EMG", "SB", "SAB"):
        return "galassia"
    return code


def ra_text(ra_deg: float) -> str:
    hours = (ra_deg / 15.0) % 24.0
    h = int(hours)
    mf = (hours - h) * 60
    m = int(mf)
    return f"{h:02d}h {m:02d}m {(mf - m) * 60:04.1f}s"


def dec_text(dec_deg: float) -> str:
    sign = "−" if dec_deg < 0 else "+"
    a = abs(dec_deg)
    d = int(a)
    mf = (a - d) * 60
    m = int(mf)
    return f"{sign}{d:02d}° {m:02d}′ {(mf - m) * 60:04.1f}″"


# --------------------------------------------------------------------------- sessioni

def _capture_store(root: dict) -> dict | None:
    if isinstance(root.get("captureStore"), dict):
        return root["captureStore"]
    tel = root.get("telescope")
    if isinstance(tel, dict) and isinstance(tel.get("captureStore"), dict):
        return tel["captureStore"]
    nested = root.get("result") if isinstance(root.get("result"), dict) else root.get("data")
    if isinstance(nested, dict) and isinstance(nested.get("captureStore"), dict):
        return nested["captureStore"]
    return None


def _resumable(item: dict) -> bool:
    state = str(item.get("storeState") or "")
    store = item.get("store")
    if not state and isinstance(store, dict):
        state = str(store.get("state") or "")
    if not state:
        state = str(item.get("state") or "")
    return "NON_RESUMABLE" not in state.upper()


def _matches(item: dict, object_name: str, query: str) -> bool:
    want_name, want_query = compact(object_name), compact(query)
    target = item.get("target") if isinstance(item.get("target"), dict) else {}
    ident = compact(str(target.get("objectId") or ""))
    name = compact(str(target.get("objectName") or ""))
    store = compact(str(item.get("storeId") or ""))
    if want_name and (want_name in (ident, name) or store.endswith(want_name)):
        return True
    return bool(want_query) and (want_query in (ident, name) or store.endswith(want_query))


def find_session(state: dict, object_name: str, query: str) -> Session | None:
    if not isinstance(state, dict):
        return None
    store = _capture_store(state)
    captures = store.get("storedCaptures") if store else None
    if not isinstance(captures, list):
        return None
    best: Session | None = None
    for item in captures:
        if not isinstance(item, dict) or not _matches(item, object_name, query) or not _resumable(item):
            continue
        sid = str(item.get("storeId") or "")
        if not sid:
            continue
        stacks = int(item.get("totalStackingCount") or 0)
        target = item.get("target") if isinstance(item.get("target"), dict) else {}
        label = str(target.get("objectName") or object_name)
        if best is None or stacks >= best.stacks:
            best = Session(sid, label, max(0, stacks))
    return best


def start_body(target: Target, telescope: dict | None) -> str:
    gain, exposure = 150, 10_000_000
    if isinstance(telescope, dict):
        try:
            g = int(telescope.get("gain") or 0)
            e = int(telescope.get("exposureMicroSec") or 0)
            if g > 0:
                gain = g
            if e > 0:
                exposure = e
        except (TypeError, ValueError):
            pass
    body = {
        "ra": target.ra_deg, "de": target.dec_deg, "dec": target.dec_deg, "rot": 0,
        "type": "CATALOG", "objectType": "ODC", "objectId": target.object_id(),
        "objectName": target.name, "resume": False, "gain": gain,
        "exposureMicroSec": exposure, "histogramEnabled": True, "histogramLow": -0.75,
        "histogramMedium": 5, "histogramHigh": 0, "backgroundEnabled": True,
        "backgroundPolyorder": 2,
    }
    return json.dumps(body, separators=(",", ":"))


# --------------------------------------------------------------------------- sito

def parse_site(telescope: dict | None) -> tuple[float, float] | None:
    """Coordinate dallo stato telescopio dell'Helper (come ObservatorySite)."""
    if not isinstance(telescope, dict):
        return None

    def first(obj: dict, *keys: str) -> float:
        for key in keys:
            if obj.get(key) is None:
                continue
            try:
                return float(str(obj[key]).strip().replace(",", "."))
            except ValueError:
                continue
        return float("nan")

    def from_obj(obj: dict) -> tuple[float, float] | None:
        lat = first(obj, "latitude", "lat", "gpsLatitude")
        lon = first(obj, "longitude", "lon", "lng", "gpsLongitude")
        return _valid(lat, lon)

    direct = from_obj(telescope)
    if direct:
        return direct
    loc = telescope.get("location")
    if isinstance(loc, dict):
        return from_obj(loc)
    if isinstance(loc, str) and "," in loc:
        before, lon_part = loc.rsplit(",", 1)
        lat_part = re.split(r"[ ·]", before.strip())[-1]
        try:
            return _valid(float(lat_part.replace(",", ".")), float(lon_part.strip()))
        except ValueError:
            return None
    return None


def _valid(lat: float, lon: float) -> tuple[float, float] | None:
    if math.isnan(lat) or math.isnan(lon):
        return None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return None
    if abs(lat) < 0.01 and abs(lon) < 0.01:
        return None
    return lat, lon


def site_from_internet() -> tuple[float, float]:
    """Posizione approssimata dalla rete (al posto del GPS del telefono)."""
    code, body = _get_text("ipapi.co", "/json/")
    if 200 <= code < 300:
        data = json.loads(body)
        site = _valid(float(data.get("latitude") or "nan"), float(data.get("longitude") or "nan"))
        if site:
            return site
    code, body = _get_text("ipwho.is", "/")
    data = json.loads(body) if 200 <= code < 300 else {}
    site = _valid(float(data.get("latitude") or "nan"), float(data.get("longitude") or "nan"))
    if not site:
        raise SkyError("net")
    return site


# --------------------------------------------------------------------------- visibilità

NONE, NOT_VISIBLE, POOR, GOOD, GREAT = -1, 0, 1, 2, 3
STEP_MIN = 15
HEADLINE_MIN = 30


def alt_az(lat: float, lon: float, ra: float, dec: float, utc: datetime) -> tuple[float, float]:
    jd = utc.timestamp() / 86400.0 + 2440587.5
    days = jd - 2451545.0
    gmst = (280.46061837 + 360.98564736629 * days) % 360.0
    ha = gmst + lon - ra
    while ha > 180:
        ha -= 360
    while ha < -180:
        ha += 360
    la, de, h = math.radians(lat), math.radians(dec), math.radians(ha)
    s = math.sin(la) * math.sin(de) + math.cos(la) * math.cos(de) * math.cos(h)
    alt = math.degrees(math.asin(max(-1.0, min(1.0, s))))
    az = math.degrees(math.atan2(-math.sin(h) * math.cos(de),
                                 math.sin(de) * math.cos(la) - math.cos(de) * math.sin(la) * math.cos(h)))
    return alt, az % 360.0


def sun_ra_dec(utc: datetime) -> tuple[float, float]:
    jd = utc.timestamp() / 86400.0 + 2440587.5
    d = jd - 2451545.0
    L = (280.460 + 0.9856474 * d) % 360
    g = math.radians((357.528 + 0.9856003 * d) % 360)
    lam = math.radians((L + 1.915 * math.sin(g) + 0.020 * math.sin(2 * g)) % 360)
    eps = math.radians(23.439 - 0.0000004 * d)
    ra = math.degrees(math.atan2(math.cos(eps) * math.sin(lam), math.cos(lam))) % 360
    dec = math.degrees(math.asin(math.sin(eps) * math.sin(lam)))
    return ra, dec


def _weather_level(cloud: int, pop: int) -> int:
    if pop >= 50 or cloud >= 80:
        return NOT_VISIBLE
    if pop >= 30 or cloud >= 50:
        return POOR
    if pop >= 15 or cloud >= 20:
        return GOOD
    return GREAT


def _altitude_level(alt: float) -> int:
    if alt >= 50:
        return GREAT
    if alt >= 30:
        return GOOD
    if alt >= 15:
        return POOR
    return NOT_VISIBLE


def _fetch_weather(lat: float, lon: float) -> dict[str, int]:
    """Livello meteo per ora, chiave 'YYYY-MM-DDTHH:00' in ora locale del PC."""
    path = ("/v1/forecast?latitude=%f&longitude=%f&hourly=cloud_cover,precipitation_probability"
            "&forecast_days=16&timezone=auto" % (lat, lon))
    code, body = _get_text("api.open-meteo.com", path)
    if code < 200 or code >= 300:
        raise SkyError("net")
    root = json.loads(body)
    offset = int(root.get("utc_offset_seconds") or 0)
    hourly = root.get("hourly") or {}
    times = hourly.get("time") or []
    clouds = hourly.get("cloud_cover") or []
    rain = hourly.get("precipitation_probability") or []
    out: dict[str, int] = {}
    raw: dict[str, tuple[int, int]] = {}
    for i, stamp in enumerate(times):
        site_local = datetime.fromisoformat(stamp)
        # ora del sito -> UTC -> ora locale del PC
        utc = (site_local - timedelta(seconds=offset))
        local = datetime.fromtimestamp(utc.replace(tzinfo=_UTC).timestamp())
        cloud = clouds[i] if i < len(clouds) and clouds[i] is not None else 100
        pop = rain[i] if i < len(rain) and rain[i] is not None else 0
        out[local.strftime("%Y-%m-%dT%H")] = _weather_level(int(cloud), int(pop))
        raw[local.strftime("%Y-%m-%dT%H")] = (int(cloud), int(pop))
    _WEATHER_RAW[(round(lat, 2), round(lon, 2))] = raw
    return out


# nubi % e probabilità di pioggia % per ora, chiave come sopra (riempito da _fetch_weather)
_WEATHER_RAW: dict[tuple, dict[str, tuple[int, int]]] = {}


from datetime import timezone as _tz  # noqa: E402
_UTC = _tz.utc


_WEATHER_CACHE: dict[tuple, tuple[float, dict[str, int]]] = {}


def _weather_cached(lat: float, lon: float) -> dict[str, int] | None:
    """Meteo con 2 tentativi e cache di 15 minuti (ogni ricerca non lo riscarica)."""
    key = (round(lat, 2), round(lon, 2))
    hit = _WEATHER_CACHE.get(key)
    if hit and time.time() - hit[0] < 900:
        return hit[1]
    for _ in range(2):
        try:
            data = _fetch_weather(lat, lon)
            _WEATHER_CACHE[key] = (time.time(), data)
            return data
        except Exception:
            time.sleep(1.0)
    return hit[1] if hit else None


def report(lat: float, lon: float, object_name: str | None = None,
           ra: float = 0.0, dec: float = 0.0) -> list[tuple[int, str]]:
    """Righe (livello, testo); livello ≥ 0 → faccina colorata.
    Il livello di ogni quarto d'ora è il peggiore tra meteo e altezza oggetto."""
    has_object = object_name is not None
    weather = _weather_cached(lat, lon)

    now = datetime.now()
    start = now.replace(hour=12, minute=0, second=0, microsecond=0)
    if now < start:
        start -= timedelta(days=1)
    slots: list[tuple[datetime, int, int]] = []
    max_alt = -90.0
    for _attempt in range(2):
        slots, max_alt = [], -90.0
        cursor = start
        while cursor < start + timedelta(days=1):
            utc = datetime.fromtimestamp(cursor.timestamp(), _UTC)
            sra, sdec = sun_ra_dec(utc)
            if alt_az(lat, lon, sra, sdec, utc)[0] < -6:
                sky = GREAT
                if weather is not None:
                    sky = weather.get(cursor.strftime("%Y-%m-%dT%H"), GREAT)
                high = GREAT
                if has_object:
                    alt = alt_az(lat, lon, ra, dec, utc)[0]
                    max_alt = max(max_alt, alt)
                    high = _altitude_level(alt)
                slots.append((cursor, sky, high))
            cursor += timedelta(minutes=STEP_MIN)
        # Notte già finita (mattina): mostra la prossima.
        if slots and slots[-1][0] + timedelta(minutes=STEP_MIN) <= datetime.now():
            start += timedelta(days=1)
            continue
        break

    lines: list[tuple[int, str]] = []
    if not slots:
        return [(NONE, "Stanotte il cielo non diventa abbastanza buio.")]
    end = slots[-1][0] + timedelta(minutes=STEP_MIN)
    lines.append((NONE, f"Notte {slots[0][0]:%H:%M}–{end:%H:%M}"))

    def level(s):
        return min(s[1], s[2])

    minutes = [0, 0, 0, 0]
    for s in slots:
        minutes[level(s)] += STEP_MIN
    best = NOT_VISIBLE
    for lv in (GREAT, GOOD, POOR):
        if minutes[lv] >= HEADLINE_MIN:
            best = lv
            break
    who = "Cielo"
    if has_object:
        who = re.sub(r"\s+", " ", object_name.strip()) or "Oggetto"
    head = f"{who} stanotte: " + ("non visibile" if best == NOT_VISIBLE else "al meglio " + GRADES[best])
    if has_object and max_alt > -90:
        head += f" · max {max_alt:.0f}°"
    lines.append((best, head))
    for lv in (GREAT, GOOD, POOR, NOT_VISIBLE):
        r = _ranges(slots, lambda s, lv=lv: level(s) == lv)
        if r:
            lines.append((lv, f"{LABELS[lv]}  {r}"))
    if weather is None:
        lines.append((NONE, "Meteo non raggiungibile: conta solo l'altezza dell'oggetto."))
    elif has_object:
        clouds = _ranges(slots, lambda s: s[1] < GOOD and s[1] <= s[2])
        low = _ranges(slots, lambda s: s[2] < GOOD and s[2] <= s[1])
        if clouds or low:
            lines.append((NONE, "Perché:"))
        if clouds:
            lines.append((NONE, f"  nuvole/pioggia  {clouds}"))
        if low:
            lines.append((NONE, f"  oggetto basso  {low}"))
    return lines


GRADES = {GREAT: "ottima", GOOD: "buona", POOR: "scarsa", NOT_VISIBLE: "non visibile"}
LABELS = {GREAT: "Ottima", GOOD: "Buona", POOR: "Scarsa", NOT_VISIBLE: "Non visibile"}
COLORS = {GREAT: "#1E88E5", GOOD: "#43A047", POOR: "#FDD835", NOT_VISIBLE: "#E53935"}


def _ranges(slots, test) -> str:
    parts: list[str] = []
    open_at = prev = None
    for s in slots:
        if not test(s):
            continue
        if open_at is None:
            open_at = s[0]
        elif (s[0] - prev) > timedelta(minutes=STEP_MIN):
            parts.append(f"{open_at:%H:%M}–{prev + timedelta(minutes=STEP_MIN):%H:%M}")
            open_at = s[0]
        prev = s[0]
    if open_at is not None:
        parts.append(f"{open_at:%H:%M}–{prev + timedelta(minutes=STEP_MIN):%H:%M}")
    return ", ".join(parts)


def face_image(level: int, size: int = 18):
    """Faccina colorata (PIL Image RGBA) come le icone ic_vis_* dell'APK."""
    from PIL import Image, ImageDraw

    scale = 4
    s = size * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = s / 24.0
    d.ellipse([1.6 * k, 1.6 * k, 22.4 * k, 22.4 * k], fill=COLORS.get(level, "#90A4AE"),
              outline=(0, 0, 0, 60), width=max(1, int(0.8 * k)))
    ink = "#263238"
    for cx in (8.6, 15.4):
        d.ellipse([(cx - 1.5) * k, 6.7 * k, (cx + 1.5) * k, 9.7 * k], fill=ink)
    w = max(2, int(1.9 * k))
    if level == GREAT:
        d.arc([6.8 * k, 8.6 * k, 17.2 * k, 18.0 * k], 20, 160, fill=ink, width=w)
    elif level == GOOD:
        d.arc([7.8 * k, 11.0 * k, 16.2 * k, 17.0 * k], 25, 155, fill=ink, width=w)
    elif level == POOR:
        d.line([8 * k, 15.6 * k, 16 * k, 15.6 * k], fill=ink, width=w)
    else:
        d.arc([8 * k, 14.6 * k, 16 * k, 20.0 * k], 205, 335, fill=ink, width=w)
    return img.resize((size, size), Image.Resampling.LANCZOS)


# --------------------------------------------------------------------------- preferiti

@dataclass
class Rank:
    name: str
    type_code: str
    ra_deg: float
    dec_deg: float
    best: int          # livello migliore tenuto almeno 30 minuti
    score: int         # minuti pesati: ottima×3 + buona×2 + scarsa×1
    best_minutes: int
    max_alt: float
    best_ranges: str

    def headline(self) -> str:
        """'NGC 6888 · ottima 2h15 · max 87°'"""
        grade = "non visibile" if self.best == NOT_VISIBLE else f"{GRADES[self.best]} {_duration(self.best_minutes)}"
        return f"{self.name} · {grade} · max {self.max_alt:.0f}°"


def _duration(minutes: int) -> str:
    h, m = divmod(minutes, 60)
    if h == 0:
        return f"{m} min"
    return f"{h}h" if m == 0 else f"{h}h{m:02d}"


def default_night(lat: float, lon: float) -> date:
    """Sera della notte in corso, o di stanotte se è già giorno."""
    now = datetime.now()
    if now.hour >= 12:
        return now.date()
    utc = datetime.now(_UTC)
    sra, sdec = sun_ra_dec(utc)
    if alt_az(lat, lon, sra, sdec, utc)[0] < -6:
        return now.date() - timedelta(days=1)
    return now.date()


def rank(lat: float, lon: float, items: list[dict], night: date) -> tuple[str, bool, list[Rank]]:
    """Classifica i preferiti per la notte che inizia la sera di `night`:
    prima il livello migliore, poi i minuti pesati, poi l'altezza massima.
    Oltre i 16 giorni del meteo conta solo l'altezza. Come NightSky.rank dell'APK.
    Ritorna (riga notte, meteo presente, righe)."""
    weather = _weather_cached(lat, lon)
    start = datetime(night.year, night.month, night.day, 12, 0)
    dark: list[tuple[datetime, datetime, int]] = []
    seen = False
    cursor = start
    while cursor < start + timedelta(days=1):
        utc = datetime.fromtimestamp(cursor.timestamp(), _UTC)
        sra, sdec = sun_ra_dec(utc)
        if alt_az(lat, lon, sra, sdec, utc)[0] < -6:
            v = weather.get(cursor.strftime("%Y-%m-%dT%H")) if weather is not None else None
            if v is not None:
                seen = True
            dark.append((cursor, utc, GREAT if v is None else v))
        cursor += timedelta(minutes=STEP_MIN)
    if dark:
        night_line = f"Notte {dark[0][0]:%H:%M}–{dark[-1][0] + timedelta(minutes=STEP_MIN):%H:%M}"
    else:
        night_line = "Il cielo non diventa abbastanza buio."
    rows: list[Rank] = []
    for item in items:
        try:
            ra, dec = float(item["ra"]), float(item["dec"])
        except (KeyError, TypeError, ValueError):
            continue
        slots = []
        max_alt = -90.0
        for local, utc, sky_lv in dark:
            alt = alt_az(lat, lon, ra, dec, utc)[0]
            max_alt = max(max_alt, alt)
            slots.append((local, sky_lv, _altitude_level(alt)))
        minutes = [0, 0, 0, 0]
        for s in slots:
            minutes[min(s[1], s[2])] += STEP_MIN
        best = NOT_VISIBLE
        for lv in (GREAT, GOOD, POOR):
            if minutes[lv] >= HEADLINE_MIN:
                best = lv
                break
        ranges = "" if best == NOT_VISIBLE else _ranges(slots, lambda s, b=best: min(s[1], s[2]) == b)
        rows.append(Rank(
            str(item.get("name") or ""), str(item.get("type") or ""), ra, dec, best,
            minutes[GREAT] * 3 + minutes[GOOD] * 2 + minutes[POOR],
            0 if best == NOT_VISIBLE else minutes[best], max_alt, ranges))
    rows.sort(key=lambda r: (-r.best, -r.score, -r.max_alt))
    return night_line, seen, rows


# --------------------------------------------------------------------------- piano della notte

PLAN_PERIOD_MIN = 60     # periodo minimo
PLAN_PERIOD_IDEAL = 75   # durata a cui puntano i periodi quando la notte è lunga
PLAN_SAMPLE_MIN = 5      # passo di campionamento di sole/altezze/meteo

# meteo di un periodo: (soglia, icona, etichetta). Pioggia vince sulle nubi.
SKY_SUNNY, SKY_FEW, SKY_CLOUDY, SKY_RAIN = "sereno", "poco_nuvoloso", "nuvoloso", "pioggia"
SKY_TEXT = {SKY_SUNNY: ("☀", "Sereno"), SKY_FEW: ("⛅", "Poco nuvoloso"),
            SKY_CLOUDY: ("☁", "Nuvoloso"), SKY_RAIN: ("☂", "Pioggia")}


def sky_kind(cloud: int, pop: int) -> str:
    """Cielo del periodo: pioggia se probabilità ≥ 40%, nuvoloso con nubi ≥ 50%,
    poco nuvoloso con nubi ≥ 20%, altrimenti sereno. Come NightSky.skyKind dell'APK."""
    if pop >= 40:
        return SKY_RAIN
    if cloud >= 50:
        return SKY_CLOUDY
    if cloud >= 20:
        return SKY_FEW
    return SKY_SUNNY


def sky_text(sky: dict | None) -> str:
    """'☁ Nuvoloso · nubi 75% · pioggia 10%' oppure 'meteo n.d.'."""
    if not sky:
        return "meteo n.d."
    icon, label = SKY_TEXT.get(sky.get("kind"), ("", "?"))
    return f"{icon} {label} · nubi {sky.get('cloud', 0)}% · pioggia {sky.get('pop', 0)}%"


def _period_score(a: list) -> float:
    """Punteggio di un oggetto in un periodo da [media, minima, massima] altezza:
    la media, penalizzata se scende sotto 30°; -1000 se non sale mai a 15°."""
    avg, low, high = a
    if high < 15:
        return -1000.0
    return avg - 2.0 * max(0.0, 30.0 - low)


def _hungarian(cost: list[list[float]]) -> list[int]:
    """Assegnazione a costo minimo (righe ≤ colonne). Ritorna la colonna di ogni riga."""
    n = len(cost)
    m = len(cost[0]) if n else 0
    INF = float("inf")
    u = [0.0] * (n + 1)
    v = [0.0] * (m + 1)
    p = [0] * (m + 1)
    way = [0] * (m + 1)
    for i in range(1, n + 1):
        p[0] = i
        j0 = 0
        minv = [INF] * (m + 1)
        used = [False] * (m + 1)
        while True:
            used[j0] = True
            i0 = p[j0]
            delta = INF
            j1 = 0
            for j in range(1, m + 1):
                if not used[j]:
                    cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                    if cur < minv[j]:
                        minv[j] = cur
                        way[j] = j0
                    if minv[j] < delta:
                        delta = minv[j]
                        j1 = j
            for j in range(m + 1):
                if used[j]:
                    u[p[j]] += delta
                    v[j] -= delta
                else:
                    minv[j] -= delta
            j0 = j1
            if p[j0] == 0:
                break
        while True:
            j1 = way[j0]
            p[j0] = p[j1]
            j0 = j1
            if j0 == 0:
                break
    out = [-1] * n
    for j in range(1, m + 1):
        if p[j]:
            out[p[j] - 1] = j - 1
    return out


def plan_propose(plan: dict) -> None:
    """Proposta automatica: ogni oggetto visibile riceve lo stesso numero di periodi attivi
    (±1), scegliendo per ciascuno i periodi dove è più alto; poi scambi che riducono i cambi
    di oggetto senza perdere quota. I periodi spenti propongono l'oggetto migliore.
    Scrive 'proposed' e 'choice' di ogni periodo. Come NightSky.propose dell'APK."""
    periods = plan.get("periods") or []
    m = len(plan.get("items") or [])
    if not periods or not m:
        for p in periods:
            p["proposed"] = p["choice"] = -1
        return
    score = [[_period_score(p["alts"][o]) for o in range(m)] for p in periods]

    def best_of(i: int) -> int:
        o = max(range(m), key=lambda k: (score[i][k], -k))
        return o if score[i][o] > -1000 else -1

    active = [i for i, p in enumerate(periods) if p.get("enabled")]
    pick = {i: best_of(i) for i in range(len(periods))}
    visible = [o for o in range(m) if any(score[i][o] > -1000 for i in active)]
    if active and visible:
        e, v = len(active), len(visible)
        # ogni oggetto e // v periodi; i periodi in più (e % v) vanno agli oggetti che rendono
        # di più: si provano tutte le scelte (al massimo 300) e si tiene la migliore
        from itertools import combinations, islice
        best = None
        for extra in islice(combinations(range(v), e % v), 300):
            cols = [visible[k] for k in range(v) for _ in range(e // v + (1 if k in extra else 0))]
            cost = [[-score[i][o] for o in cols] for i in active]
            cols_of = _hungarian(cost)
            total = sum(score[i][cols[cols_of[r]]] for r, i in enumerate(active))
            if best is None or total > best[0] + 1e-9:
                best = (total, [cols[c] for c in cols_of])
        for r, i in enumerate(active):
            o = best[1][r]
            pick[i] = o if score[i][o] > -1000 else best_of(i)

        def switches() -> int:
            seq = [pick[i] for i in active]
            return sum(1 for a, b in zip(seq, seq[1:]) if a != b)

        changed = True
        while changed:
            changed = False
            for x in range(len(active)):
                for y in range(x + 1, len(active)):
                    i, j = active[x], active[y]
                    a, b = pick[i], pick[j]
                    if a == b or a < 0 or b < 0:
                        continue
                    before = score[i][a] + score[j][b]
                    after = score[i][b] + score[j][a]
                    if after < before - 2.0 * 2 or min(score[i][b], score[j][a]) <= -1000:
                        continue
                    old = switches()
                    pick[i], pick[j] = b, a
                    if switches() < old:
                        changed = True
                    else:
                        pick[i], pick[j] = a, b
    for i, p in enumerate(periods):
        p["proposed"] = p["choice"] = pick[i]


def plan_finalize(plan: dict) -> dict:
    """Ricava 'steps' (per l'Helper, periodi consecutivi uguali uniti) e 'lines' dai periodi."""
    items = plan.get("items") or []
    periods = plan.get("periods") or []
    steps: list[dict] = []
    lines: list[list] = []
    for p in periods:
        t0 = datetime.fromtimestamp(p["start"] / 1000)
        t1 = datetime.fromtimestamp(p["end"] / 1000)
        span = f"{t0:%H:%M}–{t1:%H:%M}"
        sky = p.get("sky")
        o = int(p.get("choice", -1))
        if not p.get("enabled") or o < 0 or o >= len(items):
            why = "saltato" if not p.get("enabled") else "nessun oggetto"
            lines.append([NONE, f"{span}  {sky_text(sky)}  →  {why}"])
            continue
        avg, low, high = p["alts"][o]
        if high < 15:
            lines.append([NONE, f"{span}  {sky_text(sky)}  →  {items[o].get('name')} non visibile: "
                                f"periodo non ripreso"])
            continue
        level = _altitude_level(avg)  # solo altezza: il meteo è nel testo della riga
        item = items[o]
        lines.append([level, f"{span}  {sky_text(sky)}  →  {item.get('name')} · {avg:.0f}° (min {low:.0f}°)"])
        if steps and steps[-1]["name"] == str(item.get("name") or "") and steps[-1]["end"] == p["start"]:
            steps[-1]["end"] = p["end"]
            steps[-1]["level"] = min(steps[-1]["level"], level)
            continue
        steps.append({
            "start": int(p["start"]), "end": int(p["end"]),
            "name": str(item.get("name") or ""), "type": str(item.get("type") or ""),
            "ra": float(item["ra"]), "dec": float(item["dec"]), "level": level,
        })
    if not periods:
        lines.append([NONE, "Nessuna ora buia rimasta per questa notte."])
    elif not steps:
        lines.append([NONE, "Nessun periodo attivo con un oggetto: il piano è vuoto."])
    plan["steps"] = steps
    plan["lines"] = lines
    plan["totals"] = plan_totals(plan)
    return plan


ALT_GRADES = {GREAT: "ottima", GOOD: "buona", POOR: "scarsa", NOT_VISIBLE: "bassa <15°"}
SKY_ORDER = (SKY_SUNNY, SKY_FEW, SKY_CLOUDY, SKY_RAIN)


def plan_totals(plan: dict) -> list[dict]:
    """Tempo di osservazione per oggetto dai periodi attivi in cui sale sopra 15°.
    `levels` = minuti per visibilità, SOLO dall'altezza media (ottima ≥ 50°, buona ≥ 30°,
    scarsa ≥ 15°): il meteo è a parte in `sky` (minuti per sereno / poco nuvoloso / nuvoloso /
    pioggia) con `cloud` = nubi medie % e `pop` = pioggia max % sui minuti col meteo.
    `visible` = minuti della notte in cui l'oggetto sta a ≥ 30° di media (solo altezza).
    [{name, minutes, levels {"3": min…}, sky {kind: min}, cloud, pop, visible}]. Come NightSky.totals."""
    items = plan.get("items") or []
    out = [{"name": str(it.get("name") or ""), "minutes": 0, "levels": {}, "sky": {},
            "cloud": None, "pop": None, "visible": 0} for it in items]
    cloud_sum = [0.0] * len(items)
    cloud_min = [0] * len(items)
    for p in plan.get("periods") or []:
        minutes = round((int(p["end"]) - int(p["start"])) / 60000)
        for k in range(len(items)):
            if p["alts"][k][0] >= 30:
                out[k]["visible"] += minutes
        o = int(p.get("choice", -1))
        if not p.get("enabled") or o < 0 or o >= len(items):
            continue
        avg, _low, high = p["alts"][o]
        if high < 15:
            continue
        row = out[o]
        row["minutes"] += minutes
        lv = str(_altitude_level(avg))
        row["levels"][lv] = row["levels"].get(lv, 0) + minutes
        sky = p.get("sky")
        if sky:
            row["sky"][sky["kind"]] = row["sky"].get(sky["kind"], 0) + minutes
            cloud_sum[o] += float(sky.get("cloud", 0)) * minutes
            cloud_min[o] += minutes
            row["pop"] = max(int(row["pop"] or 0), int(sky.get("pop", 0)))
    for k, row in enumerate(out):
        if cloud_min[k]:
            row["cloud"] = round(cloud_sum[k] / cloud_min[k])
    return out


def _levels_text(levels: dict) -> str:
    parts = [f"{ALT_GRADES.get(int(lv), lv)} {_duration(int(m))}"
             for lv, m in sorted(levels.items(), key=lambda kv: -int(kv[0])) if int(m)]
    return ", ".join(parts)


def _sky_minutes_text(sky: dict, cloud, pop) -> str:
    parts = [f"{SKY_TEXT[k][0]} {SKY_TEXT[k][1].lower()} {_duration(int(sky[k]))}"
             for k in SKY_ORDER if int(sky.get(k, 0))]
    if not parts:
        return "meteo n.d."
    extra = []
    if cloud is not None:
        extra.append(f"nubi medie {cloud}%")
    if pop:
        extra.append(f"pioggia max {pop}%")
    return "meteo: " + ", ".join(parts) + (f" ({', '.join(extra)})" if extra else "")


def totals_text(totals: list[dict]) -> list[str]:
    """Per oggetto: 'NGC 7380 · 6h08 · visibilità: ottima 4h00, buona 2h08 · meteo: ☁ nuvoloso 6h08
    (nubi medie 78%) · alto ≥30° in tutta la notte 7h00', poi 'Totale piano …' con le stesse divisioni.
    Visibilità = solo altezza; il meteo è a parte."""
    lines = []
    all_levels: dict[str, int] = {}
    all_sky: dict[str, int] = {}
    cloud_sum = 0.0
    cloud_min = 0
    pop_max = 0
    for t in totals or []:
        minutes = int(t.get("minutes") or 0)
        line = f"{t.get('name')} · {_duration(minutes) if minutes else 'non in piano'}"
        levels = t.get("levels") or {}
        if minutes and levels:
            line += f" · visibilità: {_levels_text(levels)}"
        if minutes:
            line += f" · {_sky_minutes_text(t.get('sky') or {}, t.get('cloud'), t.get('pop'))}"
        if "visible" in t:
            line += f" · alto ≥30° in tutta la notte {_duration(int(t['visible']))}"
        lines.append(line)
        for lv, m in levels.items():
            all_levels[lv] = all_levels.get(lv, 0) + int(m)
        sky = t.get("sky") or {}
        for k, m in sky.items():
            all_sky[k] = all_sky.get(k, 0) + int(m)
        if t.get("cloud") is not None:
            sm = sum(int(m) for m in sky.values())
            cloud_sum += float(t["cloud"]) * sm
            cloud_min += sm
        pop_max = max(pop_max, int(t.get("pop") or 0))
    if len(totals) > 1:
        total = sum(int(t.get("minutes") or 0) for t in totals)
        line = f"Totale piano {_duration(total)}"
        if all_levels:
            line += f" · visibilità: {_levels_text(all_levels)}"
        if total:
            line += " · " + _sky_minutes_text(all_sky, round(cloud_sum / cloud_min) if cloud_min else None,
                                              pop_max)
        lines.append(line)
    return lines


def plan_option_text(plan: dict, period: dict, o: int) -> str:
    """Voce del menu oggetti di un periodo: '★ NGC 7000 · 62° (min 48°)'."""
    item = (plan.get("items") or [])[o]
    avg, low, high = period["alts"][o]
    star = "★ " if o == period.get("proposed") else ""
    if high < 15:
        return f"{star}{item.get('name')} · non visibile (max {high:.0f}°)"
    return f"{star}{item.get('name')} · {avg:.0f}° (min {low:.0f}°)"


def plan_night(lat: float, lon: float, items: list[dict], night: date) -> dict:
    """Piano per la notte che inizia la sera di `night` con gli oggetti scelti.
    La notte (sole sotto -6°, da adesso se è già iniziata) è divisa in periodi tutti uguali:
    un multiplo del numero di oggetti, lunghi circa 75 min e mai meno di 60. Ogni periodo
    ha il suo meteo (sereno / poco nuvoloso / nuvoloso / pioggia, con nubi e pioggia %),
    l'altezza di ogni oggetto e la proposta automatica; nuvoloso e pioggia partono spenti.
    L'utente può accendere/spegnere i periodi e cambiare l'oggetto (poi plan_finalize).
    Come NightSky.plan dell'APK."""
    weather_ok = _weather_cached(lat, lon) is not None
    raw = _WEATHER_RAW.get((round(lat, 2), round(lon, 2))) if weather_ok else None
    noon = datetime(night.year, night.month, night.day, 12, 0)
    now = datetime.now()
    dark: list[datetime] = []
    cursor = noon
    while cursor < noon + timedelta(days=1):
        utc = datetime.fromtimestamp(cursor.timestamp(), _UTC)
        sra, sdec = sun_ra_dec(utc)
        if alt_az(lat, lon, sra, sdec, utc)[0] < -6:
            dark.append(cursor)
        cursor += timedelta(minutes=PLAN_SAMPLE_MIN)
    objs = []
    for item in items:
        try:
            objs.append({"name": str(item.get("name") or ""), "type": str(item.get("type") or ""),
                         "ra": float(item["ra"]), "dec": float(item["dec"])})
        except (KeyError, TypeError, ValueError):
            continue
    periods: list[dict] = []
    seen = False
    if dark:
        start = dark[0]
        end = dark[-1] + timedelta(minutes=PLAN_SAMPLE_MIN)
        if now > start:
            start = now.replace(second=0, microsecond=0) + timedelta(minutes=1)
        total = (end - start).total_seconds() / 60.0
        m = max(1, len(objs))
        if total >= PLAN_PERIOD_MIN / 2:
            per_obj = max(1, round(total / (m * PLAN_PERIOD_IDEAL)))
            k = m * per_obj
            while k > 1 and total / k < PLAN_PERIOD_MIN:
                per_obj = per_obj - 1 if per_obj > 1 else 0
                k = m * per_obj if per_obj else k - 1
            k = max(1, k)
            length = total / k
            for i in range(k):
                p0 = start + timedelta(minutes=round(i * length))
                p1 = end if i == k - 1 else start + timedelta(minutes=round((i + 1) * length))
                samples = []
                t = p0
                while t < p1:
                    samples.append(t)
                    t += timedelta(minutes=PLAN_SAMPLE_MIN)
                clouds, pops = [], []
                for t in samples:
                    v = raw.get(t.strftime("%Y-%m-%dT%H")) if raw else None
                    if v:
                        clouds.append(v[0])
                        pops.append(v[1])
                sky = None
                if clouds:
                    seen = True
                    cloud = round(sum(clouds) / len(clouds))
                    pop = max(pops)
                    sky = {"kind": sky_kind(cloud, pop), "cloud": cloud, "pop": pop,
                           "level": _weather_level(cloud, pop)}
                alts = []
                for ob in objs:
                    vals = [alt_az(lat, lon, ob["ra"], ob["dec"],
                                   datetime.fromtimestamp(t.timestamp(), _UTC))[0] for t in samples]
                    alts.append([round(sum(vals) / len(vals), 1), round(min(vals), 1), round(max(vals), 1)])
                periods.append({
                    "start": int(p0.timestamp() * 1000), "end": int(p1.timestamp() * 1000),
                    "sky": sky, "enabled": not sky or sky["kind"] in (SKY_SUNNY, SKY_FEW),
                    "alts": alts, "proposed": -1, "choice": -1,
                })
    plan = {
        "title": f"Piano {_it_short(night)}", "night": night.isoformat(), "created": int(time.time() * 1000),
        "items": objs, "periods": periods, "weather": seen, "parkAtEnd": True,
    }
    plan_propose(plan)
    for p in periods:
        if p["proposed"] < 0:
            p["enabled"] = False
    return plan_finalize(plan)


def _it_short(d: date) -> str:
    days = ("lun", "mar", "mer", "gio", "ven", "sab", "dom")
    months = ("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic")
    return f"{days[d.weekday()]} {d.day} {months[d.month - 1]}"
