# -*- coding: utf-8 -*-
"""Vespera Control — app Windows all-in-one (Helper ADB + Anteprima FTP + scrcpy)."""

from __future__ import annotations

import base64
import io
import json
import os
import queue
import re
import subprocess
import sys
import threading
import time
import tkinter as tk
import traceback
from datetime import date, datetime, timedelta
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

# Pacchetto locale
ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from vespera_control.adb_bridge import AdbBridge, AdbError, locate_adb  # noqa: E402
from vespera_control import preview_ftp as preview  # noqa: E402
from vespera_control.preview_editor import PreviewEditor  # noqa: E402
from vespera_control.scrcpy_util import (  # noqa: E402
    SOFTWARE_ENCODER,
    build_scrcpy_command,
    locate_scrcpy,
    scrcpy_help,
)
from vespera_control import sky  # noqa: E402
from vespera_control.updates import LOCAL_CODE, LOCAL_VERSION  # noqa: E402
from vespera_control import instrument_status as istatus  # noqa: E402

# Palette speculare a VesperaHelper / UiStyle (tema chiaro)
BG = "#E8EEF4"
PANEL = "#F3F7FA"
ENTRY = "#FFFFFF"
FG = "#212121"
MUTED = "#546E7A"
ACCENT = "#1C6E8C"
TITLE = "#1A237E"
OK = "#3B7F55"       # GREEN — connect / tab selezionata
WARN = "#C0724A"     # TERRACOTTA
ERR = "#B05757"      # ROSE
PRIMARY = "#3B7F55"
SLATE = "#4A6F86"
TAB_IDLE = "#F4F7FA"
STEEL = "#8A97A3"
INK = "#2A2D31"
PANEL_STROKE = "#90A4AE"
CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

SYSTEM_FLAGS = [
    ("photoSync", "Sync foto"),
    ("storageSync", "Sync per spazio"),
    ("resumeSync", "Riprendi sync"),
    ("hdMount", "Auto-monta HD"),
    ("clockNtp", "Orologio NTP"),
    ("bootStart", "Avvio al boot"),
    ("wifiConnect", "Auto Wi‑Fi"),
    ("singularityStart", "Avvia Singularity"),
    ("watchdog", "Watchdog Singularity"),
    ("ftpLocal", "FTP locale"),
    ("keepAlive", "Keep-alive"),
    ("sunCheck", "Controllo sole"),
    ("sunSync", "Sync a sole alto"),
    ("sunTelescopeShutdown", "Spegni telescopio (sole)"),
    ("sunHdShutdown", "Spegni HD (sole)"),
    ("sunPiShutdown", "Spegni Pi (sole)"),
]

TELEGRAM_FLAGS = [
    ("initialized", "Helper avviato"),
    ("shutdown", "Spegnimento Pi"),
    ("connected", "Telescopio connesso"),
    ("lost", "Telescopio perso"),
    ("obsStarted", "Osservazione avviata"),
    ("obsStopped", "Osservazione fermata"),
    ("obsFinished", "Osservazione terminata"),
    ("error", "Errore"),
    ("batteryLow", "Batteria scarica"),
    ("batteryOffMains", "Batteria senza rete"),
    ("hdHigh", "HD quasi pieno"),
    ("storageInternalHigh", "Memoria interna piena"),
    ("sunTooHigh", "Sole troppo alto"),
    ("rainForecast", "Previsione pioggia"),
]


def appdata_dir() -> Path:
    root = os.environ.get("LOCALAPPDATA")
    path = Path(root) / "VesperaControl" if root else Path(__file__).resolve().parent
    path.mkdir(parents=True, exist_ok=True)
    return path


def settings_path() -> Path:
    return appdata_dir() / "settings.json"


def atomic_write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(path)



# Comandi telescopio: etichetta, domanda di conferma, pericoloso.
TEL_ACTIONS = {
    "init": ("Init", "Inizializza il telescopio (apertura braccio, calibrazione e puntamento iniziale).", False),
    "park": ("Park", "Riporta il telescopio in posizione di parcheggio (braccio chiuso).", False),
    "stop": ("Stop", "Ferma l'osservazione in corso. Lo stacking si interrompe.", True),
    "resume": ("Riprendi", "Riprende l'osservazione dell'ultimo oggetto (fa l'Init se serve).", False),
    "shutdown": ("Shutdown", "Spegne il telescopio. Per riaccenderlo serve il pulsante fisico.", True),
}


def tel_error_text(ack: str) -> str:
    """Spiega in italiano gli errori più comuni dei comandi telescopio."""
    low = (ack or "").lower()
    if "no_site" in low:
        hint = ("Posizione dell'osservatorio sconosciuta.\n"
                "Impostala in Impostazioni (o in Sistema dell'Helper) e aggiorna "
                "Vespera Helper sul Pi (≥ 0.8.26).")
    elif "status_unavailable" in low:
        hint = "L'Helper non raggiunge il Vespera: controlla il Wi‑Fi del telescopio (tab Connessioni)."
    elif "auth" in low:
        hint = "Il Vespera non accetta i comandi firmati dall'Helper (autenticazione)."
    elif "init_not_ready" in low:
        hint = "L'inizializzazione non è ancora finita: riprova tra poco."
    elif "nessun ack" in low or "helper avviato" in low:
        hint = "Nessuna risposta dall'Helper sul Pi: è avviato? (tab Connessioni → Aggiorna stato)."
    else:
        hint = "Il telescopio ha rifiutato il comando."
    return f"{hint}\n\nRisposta: {ack}"

def _fmt_bytes(n) -> str:
    n = float(n or 0)
    if n < 1024:
        return f"{int(n)} B"
    for unit in ("KB", "MB", "GB", "TB"):
        n /= 1024
        if n < 1024 or unit == "TB":
            return (f"{n:.0f} {unit}" if n >= 100 else f"{n:.1f} {unit}").replace(".", ",")
    return ""


def _fmt_eta(ms: int) -> str:
    sec = max(0, (int(ms) + 500) // 1000)
    if sec < 60:
        return f"{sec} s"
    mins, sec = divmod(sec, 60)
    if mins < 60:
        return f"{mins} min {sec} s"
    return f"{mins // 60} h {mins % 60} min"


class App(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title(f"Vespera Control {LOCAL_VERSION}")
        self.geometry("1280x860")
        self.minsize(1060, 720)
        self.configure(bg=BG)
        self._set_app_icon()

        self.queue: queue.Queue = queue.Queue()
        self.bridge: AdbBridge | None = None
        self.state: dict = {}
        self._closing = False
        self._busy = False
        self._state_after: str | None = None
        self._scrcpy: subprocess.Popen | None = None
        self._photo = None
        self._png: bytes | None = None
        self._preview_detail: preview.SkyObject | None = None
        self._preview_editor: PreviewEditor | None = None
        self._objects: list[preview.SkyObject] = []
        # Elenco già caricato (cartella -> firma ultimo output) per sorgente host:porta.
        self._loaded_signatures: dict[str, str] = {}
        self._loaded_key = ""
        self._sys_vars: dict[str, tk.BooleanVar] = {}
        self._pages: dict[str, ttk.Frame] = {}
        self._tab_labels: dict[str, tuple[tk.Frame, tk.Label]] = {}
        self._active_tab: str | None = None

        self.pi_host = tk.StringVar(value="192.168.1.4")
        self.adb_port = tk.StringVar(value="5555")
        self.scrcpy_path = tk.StringVar()
        self.max_size = tk.StringVar(value="1920")
        self.bitrate = tk.StringVar(value="8M")
        self.software_encoder = tk.BooleanVar(value=True)
        self.no_audio = tk.BooleanVar(value=True)
        self.ftp_hd_port = tk.StringVar(value="2121")
        self.ftp_vespera_port = tk.StringVar(value="2122")
        self.ftp_source = tk.StringVar(value="hd")
        self.shared_ip_text = tk.StringVar(value="")
        self.status_text = tk.StringVar(value="Indica l'IP del Pi e connetti ADB.")
        self.meta = tk.StringVar(value="")
        self.wifi_status = tk.StringVar(value="—")
        self.sing_info = tk.StringVar(value="Stato Singularity: —")
        self.tel_status = tk.StringVar(value="—")
        self.hd_status = tk.StringVar(value="—")
        self.conn_address = tk.StringVar(value="")
        # Impostazioni: posizione osservatorio + aggiornamenti (come APK)
        self.site_lat = tk.StringVar(value="")
        self.site_lon = tk.StringVar(value="")
        self.site_source = ""  # manual | internet | vespera
        self.site_text = tk.StringVar(value="Nessuna posizione salvata")
        self.update_text = tk.StringVar(value="")
        # Osserva (Telescopio)
        self.obs_query = tk.StringVar(value="")
        self.obs_details = tk.StringVar(value="")
        self.obs_session_text = tk.StringVar(value="")
        self.obs_result = tk.StringVar(value="")
        self._obs_hits: list[sky.Hit] = []
        self._obs_target: sky.Target | None = None
        self._obs_session: sky.Session | None = None
        self._obs_photo = None
        self._obs_faces: list = []
        self._obs_gen = 0
        self._vis_gen = 0
        self._obs_busy = False
        # Preferiti (come APK): lista {name, type, ra, dec} in settings.json
        self.favorites: list[dict] = []
        self._fav_night = None  # sera scelta; None = stanotte
        self._fav_gen = 0
        self._fav_faces: list = []
        self.fav_day = tk.StringVar(value="")
        self.fav_status = tk.StringVar(value="")
        # Piano della notte (come APK)
        self.plans: list[dict] = []
        self.fav_checks: dict[str, tk.BooleanVar] = {}
        self._fav_widgets: list = []
        self._plan: dict | None = None
        self._plan_saved = False
        self._plan_faces: list = []
        self._plan_period_faces: list = []
        self.plan_helper = tk.StringVar(value="")

        self._style()
        self._build()
        self._load_settings()
        if not self.scrcpy_path.get().strip():
            found = locate_scrcpy()
            if found:
                self.scrcpy_path.set(str(found))
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self.after(100, self._poll)
        if self.pi_host.get().strip():
            self.after(1500, self.refresh_state)
        else:
            # Primo avvio senza IP: apri Impostazioni, dove si inseriscono IP e porte.
            self._select_tab("imp")
        self.after(2500, self._check_updates)
        self.after(800, self.refresh_visibility)
        self.after(900, self.refresh_favorites)

    def _set_app_icon(self) -> None:
        """Icona telescopio in titlebar/taskbar (non quella di pythonw)."""
        ico = ROOT / "vespera.ico"
        png = ROOT / "vespera_launcher_icon.png"
        if ico.is_file():
            ico_path = str(ico.resolve())
            try:
                # Senza questo la finestra corrente resta con l'icona di default.
                self.iconbitmap(ico_path)
                self.iconbitmap(default=ico_path)
            except tk.TclError:
                pass
        if not png.is_file():
            return
        try:
            from PIL import Image, ImageTk

            image = Image.open(png).convert("RGBA")
            image.thumbnail((64, 64), Image.Resampling.LANCZOS)
            self._app_icon = ImageTk.PhotoImage(image)
            self.iconphoto(True, self._app_icon)
        except Exception:
            pass

    def _check_updates(self) -> None:
        from vespera_control import updates as upd

        def done(remote, _message) -> None:
            # Solo popup se c'è un aggiornamento; altrimenti silenzio (anche se non sa).
            stamp = time.strftime("%d/%m/%Y %H:%M")
            if remote:
                self.queue.put(("update_text", f"{stamp} · Disponibile {remote.get('version')}"))
                self.queue.put(("update", remote))
            else:
                self.queue.put(("update_text", f"{stamp} · Sei aggiornato ({LOCAL_VERSION} · rev {LOCAL_CODE})"))

        upd.check_async(done)

    def check_updates_now(self) -> None:
        self.update_text.set("Controllo aggiornamenti…")
        self._check_updates()

    def _style(self) -> None:
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
        style.configure("Title.TLabel", background=BG, foreground=TITLE, font=("Segoe UI", 18, "bold"))
        style.configure("Sub.TLabel", background=BG, foreground=MUTED)
        style.configure("Section.TLabel", background=PANEL, foreground=TITLE, font=("Segoe UI", 11, "bold"))
        style.configure("Muted.TLabel", background=PANEL, foreground=MUTED, font=("Segoe UI", 9))
        style.configure("Status.TLabel", background=BG, foreground=MUTED)
        style.configure(
            "TEntry",
            fieldbackground=ENTRY,
            foreground=FG,
            insertcolor=FG,
            padding=4,
            bordercolor=PANEL_STROKE,
            lightcolor=PANEL_STROKE,
            darkcolor=PANEL_STROKE,
        )
        style.configure("TButton", background=SLATE, foreground="#ffffff", padding=(10, 7))
        style.map("TButton", background=[("active", "#5A8499"), ("disabled", STEEL)])
        style.configure(
            "Primary.TButton",
            background=PRIMARY,
            foreground="#ffffff",
            padding=(10, 9),
            font=("Segoe UI", 10, "bold"),
        )
        style.map("Primary.TButton", background=[("active", "#4A9968"), ("disabled", STEEL)])
        style.configure("Danger.TButton", background=ERR, foreground="#ffffff", padding=(10, 7))
        style.map("Danger.TButton", background=[("active", "#C46A6A"), ("disabled", STEEL)])
        style.configure("Warn.TButton", background=WARN, foreground="#ffffff", padding=(10, 7))
        style.map("Warn.TButton", background=[("active", "#D08A62"), ("disabled", STEEL)])
        style.configure("TCheckbutton", background=PANEL, foreground=FG)
        style.map("TCheckbutton", background=[("active", PANEL)])
        style.configure("TRadiobutton", background=BG, foreground=FG)
        style.map("TRadiobutton", background=[("active", BG)])

    def _build(self) -> None:
        head = ttk.Frame(self, padding=(16, 12, 16, 8))
        head.pack(fill="x")
        ttk.Label(head, text="Vespera Control", style="Title.TLabel").pack(side="left")
        ttk.Label(
            head,
            text="stesse tab e funzioni dell'APK Android",
            style="Sub.TLabel",
        ).pack(side="left", padx=(16, 0))
        ttk.Label(
            head,
            text=f"Win {LOCAL_VERSION} · rev {LOCAL_CODE}",
            style="Sub.TLabel",
        ).pack(side="right")

        tab_bar = ttk.Frame(self)
        tab_bar.pack(fill="x", padx=16, pady=(0, 0))
        page_host = ttk.Frame(self)
        page_host.pack(fill="both", expand=True, padx=16, pady=(4, 8))

        # Stesso ordine dell'APK: Connessioni all'inizio, Impostazioni in fondo
        specs = (
            ("conn", "Connessioni", "tab_conn"),
            ("foto", "Foto / Hard Disk", "tab_foto"),
            ("tel", "Telescopio", "tab_tel"),
            ("sys", "Sistema", "tab_sys"),
            ("tg", "Notifiche", "tab_tg"),
            ("prev", "Anteprima", "tab_prev"),
            ("scr", "Schermo Pi", "tab_scr"),
            ("imp", "Impostazioni", "tab_imp"),
        )
        for index, (key, title, attr) in enumerate(specs):
            tab_bar.columnconfigure(index, weight=1, uniform="tabs")
            page = ttk.Frame(page_host, padding=12)
            self._pages[key] = page
            if key == "tel":
                # Telescopio scorre in verticale: Osserva + Preferiti + Piano non stanno in una schermata.
                setattr(self, attr, self._scrollable(page))
            else:
                setattr(self, attr, page)

            cell = tk.Frame(tab_bar, bg=PANEL_STROKE, highlightthickness=0, bd=0)
            cell.grid(row=0, column=index, sticky="nsew", padx=(0 if index == 0 else 3, 0))
            label = tk.Label(
                cell,
                text=title,
                bg=TAB_IDLE,
                fg="#263238",
                font=("Segoe UI", 10, "bold"),
                padx=6,
                pady=10,
                cursor="hand2",
                anchor="center",
            )
            label.pack(fill="both", expand=True, padx=1, pady=1)
            label.bind("<Button-1>", lambda _e, k=key: self._select_tab(k))
            cell.bind("<Button-1>", lambda _e, k=key: self._select_tab(k))
            self._tab_labels[key] = (cell, label)

        self._build_conn()
        for var in (self.pi_host, self.adb_port, self.ftp_hd_port, self.ftp_vespera_port, self.ftp_source):
            var.trace_add("write", lambda *_a: self._refresh_shared_ip())
        self._refresh_shared_ip()
        self._build_foto()
        self._build_tel()
        self._build_sys()
        self._build_tg()
        self._build_preview()
        self._build_scrcpy()
        self._build_settings()
        self._select_tab("conn", initial=True)

        foot = ttk.Frame(self, padding=(16, 0, 16, 12))
        foot.pack(fill="x")
        self.status_label = ttk.Label(foot, textvariable=self.status_text, style="Status.TLabel")
        self.status_label.pack(side="left")

        log_frame = ttk.Frame(self, padding=(16, 0, 16, 12))
        log_frame.pack(fill="x")
        self.logbox = tk.Text(
            log_frame,
            height=5,
            bg=ENTRY,
            fg=FG,
            insertbackground=FG,
            relief="solid",
            borderwidth=1,
            highlightthickness=0,
            font=("Consolas", 9),
        )
        self.logbox.pack(fill="x")

    def _select_tab(self, key: str, *, initial: bool = False) -> None:
        page = self._pages.get(key)
        if page is None:
            return
        changed = self._active_tab != key
        self._active_tab = key
        if changed or initial:
            for other in self._pages.values():
                other.pack_forget()
            page.pack(fill="both", expand=True)
        for tab_key, (cell, label) in self._tab_labels.items():
            selected = tab_key == key
            label.configure(
                bg=PRIMARY if selected else TAB_IDLE,
                fg="#ffffff" if selected else "#263238",
            )
            cell.configure(bg=PRIMARY if selected else PANEL_STROKE)
        if not initial:
            self._refresh_tab(key)

    def _refresh_tab(self, key: str) -> None:
        if key == "tel":
            self.refresh_favorites()
        if key == "prev":
            self.load_objects(from_tab=True)
            return
        if key == "imp":
            self._fill_site_fields()
            return
        if key == "scr":
            running = self._scrcpy is not None and self._scrcpy.poll() is None
            self.set_status("scrcpy in esecuzione." if running else "Scheda Schermo Pi.", "ok" if running else "info")
            return
        self.refresh_state()

    def _scrollable(self, page: ttk.Frame) -> ttk.Frame:
        canvas = tk.Canvas(page, bg=BG, highlightthickness=0, borderwidth=0)
        bar = ttk.Scrollbar(page, orient="vertical", command=canvas.yview)
        inner = ttk.Frame(canvas)
        win = canvas.create_window((0, 0), window=inner, anchor="nw")
        inner.bind("<Configure>", lambda _e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda e: canvas.itemconfigure(win, width=e.width))
        canvas.configure(yscrollcommand=bar.set)
        bar.pack(side="right", fill="y")
        canvas.pack(side="left", fill="both", expand=True)

        def wheel(event) -> None:
            if self._active_tab == "tel":
                canvas.yview_scroll(int(-event.delta / 120) or (-1 if event.delta > 0 else 1), "units")

        canvas.bind_all("<MouseWheel>", wheel, add="+")
        canvas.bind_all("<Button-4>", lambda _e: self._active_tab == "tel" and canvas.yview_scroll(-1, "units"), add="+")
        canvas.bind_all("<Button-5>", lambda _e: self._active_tab == "tel" and canvas.yview_scroll(1, "units"), add="+")
        return inner

    def _card(self, parent: ttk.Frame, title: str) -> ttk.Frame:
        card = ttk.Frame(parent, style="Card.TFrame", padding=12)
        card.pack(fill="x", pady=(0, 10))
        ttk.Label(card, text=title, style="Section.TLabel").pack(anchor="w", pady=(0, 8))
        return card

    def _build_conn(self) -> None:
        link = self._card(self.tab_conn, "Raspberry Pi")
        ttk.Label(link, textvariable=self.conn_address, style="Card.TLabel").pack(anchor="w", pady=(0, 8))
        row = ttk.Frame(link, style="Card.TFrame")
        row.pack(fill="x")
        ttk.Button(row, text="Connetti ADB", style="Primary.TButton", command=self.connect_adb).pack(
            side="left", padx=(0, 6)
        )
        ttk.Button(row, text="Aggiorna stato", style="TButton", command=self.refresh_state).pack(side="left")

        card = self._card(self.tab_conn, "Wi‑Fi Vespera / Singularity")
        ttk.Label(card, textvariable=self.wifi_status, style="Card.TLabel").pack(anchor="w")
        # Barre di stato come nella UI di Vespera Helper: strumento Wi‑Fi + Singularity.
        self.vespera_bar = self._status_bar(card)
        self.vespera_bar.pack(fill="x", pady=(8, 0))
        ttk.Label(card, text="Singularity → strumento", style="Section.TLabel").pack(anchor="w", pady=(12, 0))
        ttk.Label(card, textvariable=self.sing_info, style="Card.TLabel").pack(anchor="w")
        self.sing_bar = self._status_bar(card)
        self.sing_bar.pack(fill="x", pady=(4, 0))
        self._paint_bars({})
        row = ttk.Frame(card, style="Card.TFrame")
        row.pack(fill="x", pady=(10, 0))
        ttk.Button(row, text="Connetti Wi‑Fi", style="Primary.TButton", command=lambda: self.cmd("cmd|wifi|connect")).pack(
            side="left", padx=(0, 6)
        )
        ttk.Button(row, text="Disconnetti", style="Danger.TButton", command=lambda: self.cmd("cmd|wifi|disconnect")).pack(
            side="left", padx=(0, 6)
        )
        ttk.Button(row, text="Scan AP", style="TButton", command=lambda: self.cmd("cmd|wifi|scan", wait=30)).pack(
            side="left", padx=(0, 6)
        )
        ttk.Button(
            row,
            text="Riavvia Singularity",
            style="Warn.TButton",
            command=lambda: self.cmd("cmd|singularity|restart"),
        ).pack(side="left")

    def _status_bar(self, parent: ttk.Frame) -> tk.Label:
        return tk.Label(
            parent,
            text="—",
            justify="center",
            anchor="center",
            font=("Segoe UI", 10),
            relief="sunken",
            bd=1,
            padx=10,
            pady=6,
            bg=istatus.STEEL,
            fg=istatus.text_on(istatus.STEEL),
        )

    def _paint_bars(self, state: dict) -> None:
        if not hasattr(self, "vespera_bar"):
            return
        text, color = istatus.vespera_bar(state.get("wifi") or {}) if state else ("—", istatus.STEEL)
        self.vespera_bar.configure(text=text, bg=color, fg=istatus.text_on(color))
        sing = state.get("singularity") if state else {"status": "IDLE"}
        info, bar, color = istatus.singularity(sing)
        self.sing_info.set(info)
        self.sing_bar.configure(text=bar, bg=color, fg=istatus.text_on(color))

    def _build_foto(self) -> None:
        card = self._card(self.tab_foto, "Hard disk USB")
        ttk.Label(card, textvariable=self.hd_status, style="Card.TLabel").pack(anchor="w")
        row = ttk.Frame(card, style="Card.TFrame")
        row.pack(fill="x", pady=(10, 0))
        ttk.Button(row, text="Elenco dischi", style="TButton", command=lambda: self.cmd("cmd|hd|list", wait=25)).pack(
            side="left", padx=(0, 6)
        )
        ttk.Button(row, text="Monta", style="Primary.TButton", command=lambda: self.cmd("cmd|hd|mount|")).pack(
            side="left", padx=(0, 6)
        )
        ttk.Button(row, text="Spegni HD", style="Danger.TButton", command=lambda: self.cmd("cmd|hd|eject|")).pack(
            side="left", padx=(0, 6)
        )
        ttk.Button(row, text="Attiva HD", style="TButton", command=lambda: self.cmd("cmd|hd|wake")).pack(
            side="left", padx=(0, 6)
        )
        ttk.Button(row, text="Sincronizza ora", style="TButton", command=lambda: self.cmd("cmd|sync|now", wait=10)).pack(
            side="left"
        )

        # Coda sync foto USER → HD (Helper ≥ 0.8.37): si aggiorna da sola mentre il tab è aperto.
        queue_card = ttk.Frame(self.tab_foto, style="Card.TFrame", padding=12)
        queue_card.pack(fill="x", pady=(0, 10))
        ttk.Label(queue_card, text="Coda sincronizzazione foto", style="Section.TLabel").pack(anchor="w", pady=(0, 8))
        self.sync_summary = tk.StringVar(value="—")
        self.sync_current = tk.StringVar(value="")
        ttk.Label(queue_card, textvariable=self.sync_summary, style="Card.TLabel", justify="left").pack(anchor="w")
        self.sync_bar = ttk.Progressbar(queue_card, orient="horizontal", mode="determinate", maximum=1000)
        self.sync_bar.pack(fill="x", pady=(8, 4))
        ttk.Label(queue_card, textvariable=self.sync_current, style="Muted.TLabel", justify="left").pack(anchor="w")
        row = ttk.Frame(queue_card, style="Card.TFrame")
        row.pack(fill="x", pady=(8, 0))
        ttk.Button(row, text="Pausa", style="TButton", command=lambda: self.cmd("cmd|sync|pause")).pack(
            side="left", padx=(0, 6)
        )
        ttk.Button(row, text="Riprendi", style="Primary.TButton", command=lambda: self.cmd("cmd|sync|resume")).pack(
            side="left"
        )
        self._foto_tick_running = False
        self.after(3000, self._foto_tick)

    SYNC_PHASE = {
        "list": "lettura elenco",
        "download": "download",
        "disk": "scrittura su HD",
        "verify": "verifica",
        "delete": "cancellazione dal Vespera",
    }

    def _foto_tick(self) -> None:
        """Rilegge remote.state.json ogni 3 s mentre il tab Foto è aperto (silenzioso)."""
        self.after(3000, self._foto_tick)
        if self._active_tab != "foto" or self._busy or self._foto_tick_running or not self.pi_host.get().strip():
            return
        self._foto_tick_running = True

        def job() -> None:
            try:
                state = self.ensure_bridge().get_state()
                self.queue.put(("state", state))
            except Exception:
                pass
            finally:
                self._foto_tick_running = False

        threading.Thread(target=job, daemon=True).start()

    def _apply_sync(self, sync) -> None:
        if not hasattr(self, "sync_bar"):
            return
        if not isinstance(sync, dict):
            self.sync_summary.set("Coda non disponibile: aggiorna Vespera Helper (≥ 0.8.37).")
            self.sync_current.set("")
            self.sync_bar.configure(value=0)
            return
        running = bool(sync.get("running"))
        total = int(sync.get("queueTotal") or 0)
        pending = int(sync.get("queuePending") or 0)
        if running:
            head = "In corso · " + self.SYNC_PHASE.get(sync.get("phase") or "", sync.get("phase") or "")
        elif sync.get("paused"):
            head = "In pausa"
        else:
            head = "Inattiva"
        lines = [head]
        if total:
            line = (
                f"Coda: {total} file · in attesa {pending} ({_fmt_bytes(sync.get('queuePendingBytes') or 0)})"
                f" · copiati {sync.get('queueCopied', 0)} · già presenti {sync.get('queueSkipped', 0)}"
            )
            if sync.get("queueFailed"):
                line += f" · errori {sync.get('queueFailed')}"
            lines.append(line)
        else:
            lines.append("Coda vuota: nessuna sincronizzazione da quando l’Helper è partito.")
        nxt = int(sync.get("nextAutoAt") or 0)
        if not running and nxt > 0:
            lines.append("Prossima automatica: " + time.strftime("%d/%m %H:%M", time.localtime(nxt / 1000)))
        last = sync.get("lastSync") or ""
        if last:
            lines.append("Ultima: " + last)
        self.sync_summary.set("\n".join(lines))

        if running:
            self.sync_bar.configure(value=int(sync.get("permille") or 0))
            cur = []
            name = sync.get("fileName") or ""
            tot = int(sync.get("fileTotal") or 0)
            if name and tot:
                part = f"{sync.get('fileIndex', 0)}/{tot}  {name}"
                if sync.get("fileSize"):
                    part += f"  ·  {_fmt_bytes(sync.get('fileBytes') or 0)} / {_fmt_bytes(sync['fileSize'])}"
                cur.append(part)
            elif sync.get("detail"):
                cur.append(str(sync.get("detail")))
            extra = []
            if sync.get("totalBytes"):
                extra.append(f"Totale {_fmt_bytes(sync.get('doneBytes') or 0)} / {_fmt_bytes(sync['totalBytes'])}")
            if sync.get("speedBps"):
                extra.append(f"{_fmt_bytes(sync['speedBps'])}/s")
            eta = int(sync.get("etaMs") or -1)
            if eta > 0:
                extra.append("fine tra " + _fmt_eta(eta))
            if extra:
                cur.append("  ·  ".join(extra))
            self.sync_current.set("\n".join(cur))
        else:
            self.sync_bar.configure(value=1000 if sync.get("phase") == "done" else 0)
            detail = sync.get("detail") or ""
            self.sync_current.set("" if detail == last else detail)

    def _build_tel(self) -> None:
        card = self._card(self.tab_tel, "Comandi telescopio")
        self.tel_status_label = ttk.Label(card, textvariable=self.tel_status, style="Card.TLabel", wraplength=900)
        self.tel_status_label.pack(anchor="w")
        row = ttk.Frame(card, style="Card.TFrame")
        row.pack(fill="x", pady=(10, 0))
        self._tel_buttons_row = row
        for label, action in (
            ("Init", "init"),
            ("Park", "park"),
            ("Stop", "stop"),
            ("Riprendi", "resume"),
            ("Shutdown", "shutdown"),
        ):
            if action in {"init", "park", "resume"}:
                style = "Primary.TButton"
            elif action == "stop":
                style = "Danger.TButton"
            elif action == "shutdown":
                style = "Warn.TButton"
            else:
                style = "TButton"
            ttk.Button(
                row,
                text=label,
                style=style,
                command=lambda a=action: self.tel_command(a),
            ).pack(side="left", padx=(0, 6))
        ttk.Label(card, text="Stato dettagliato (Helper)", style="Section.TLabel").pack(anchor="w", pady=(12, 4))
        self.tel_details_frame = ttk.Frame(card, style="Card.TFrame")
        self.tel_details_frame.pack(fill="x")
        self._tel_details_rows: list | None = None
        self._build_observe()
        self._build_favorites()

    def _build_preview(self) -> None:
        top = ttk.Frame(self.tab_prev)
        top.pack(fill="x")
        ttk.Label(top, textvariable=self.shared_ip_text, style="Sub.TLabel").pack(side="left", padx=(0, 12))
        ttk.Radiobutton(top, text="HD", variable=self.ftp_source, value="hd").pack(side="left")
        ttk.Radiobutton(top, text="Vespera", variable=self.ftp_source, value="vespera").pack(side="left", padx=(0, 8))
        ttk.Button(top, text="Elenco oggetti", style="Primary.TButton", command=self.load_objects).pack(side="left")

        body = ttk.Frame(self.tab_prev)
        body.pack(fill="both", expand=True, pady=(10, 0))
        left = ttk.Frame(body)
        left.pack(side="left", fill="y")
        list_wrap = ttk.Frame(left)
        list_wrap.pack(fill="both", expand=True)
        list_wrap.rowconfigure(0, weight=1)
        list_wrap.columnconfigure(0, weight=1)
        self.obj_list = tk.Listbox(
            list_wrap,
            width=56,
            height=24,
            bg=ENTRY,
            fg=FG,
            selectbackground=PRIMARY,
            selectforeground="#ffffff",
            activestyle="none",
            relief="solid",
            borderwidth=1,
            highlightthickness=0,
            font=("Segoe UI", 10),
            exportselection=False,
        )
        yscroll = ttk.Scrollbar(list_wrap, orient="vertical", command=self.obj_list.yview)
        xscroll = ttk.Scrollbar(list_wrap, orient="horizontal", command=self.obj_list.xview)
        self.obj_list.configure(yscrollcommand=yscroll.set, xscrollcommand=xscroll.set)
        self.obj_list.grid(row=0, column=0, sticky="nsew")
        yscroll.grid(row=0, column=1, sticky="ns")
        xscroll.grid(row=1, column=0, sticky="ew")
        self.obj_list.bind("<<ListboxSelect>>", self.on_object_select)
        right = ttk.Frame(body)
        right.pack(side="left", fill="both", expand=True, padx=(12, 0))
        ttk.Label(right, textvariable=self.meta, style="Sub.TLabel").pack(anchor="w")
        ttk.Label(
            right,
            text="Clic sull'immagine → livelli, autostretch, salva / condividi",
            style="Sub.TLabel",
        ).pack(anchor="w", pady=(2, 0))
        self.canvas = tk.Canvas(
            right,
            bg=INK,
            highlightthickness=1,
            highlightbackground=PANEL_STROKE,
            cursor="hand2",
        )
        self.canvas.pack(fill="both", expand=True, pady=(6, 0))
        self.canvas.bind("<Configure>", lambda _e: self._redraw())
        self.canvas.bind("<Button-1>", self._open_preview_editor)
        self.canvas.bind("<Double-Button-1>", self._open_preview_editor)
        self.canvas.bind("<Return>", self._open_preview_editor)

    def _build_sys(self) -> None:
        card = self._card(self.tab_sys, "Automazioni sul Pi (si applicano subito a Helper)")
        grid = ttk.Frame(card, style="Card.TFrame")
        grid.pack(fill="x")
        for i, (key, label) in enumerate(SYSTEM_FLAGS):
            var = tk.BooleanVar(value=True)
            self._sys_vars[key] = var
            ttk.Checkbutton(grid, text=label, variable=var).grid(
                row=i // 2, column=i % 2, sticky="w", padx=(0, 24), pady=2
            )
        ttk.Button(card, text="Applica su Pi", style="Primary.TButton", command=self.apply_system).pack(
            anchor="w", pady=(12, 0)
        )

    def _build_tg(self) -> None:
        card = self._card(self.tab_tg, "Notifiche Telegram")
        self.tg_configured = tk.StringVar(value="Non configurato")
        self.tg_enabled = tk.BooleanVar(value=False)
        self._tg_vars: dict[str, tk.BooleanVar] = {}
        self._tg_binding = False
        ttk.Label(card, textvariable=self.tg_configured, style="Card.TLabel").pack(anchor="w")
        ttk.Checkbutton(
            card,
            text="Notifiche Telegram (tutte)",
            variable=self.tg_enabled,
            command=self.apply_telegram,
        ).pack(anchor="w", pady=(10, 0))
        ttk.Label(
            card,
            text="Accende o spegne tutti gli avvisi sul Pi. Token e chat restano sull'Helper.",
            style="Muted.TLabel",
            wraplength=800,
        ).pack(anchor="w", pady=(4, 0))

        events = self._card(self.tab_tg, "Avvisi singoli")
        grid = ttk.Frame(events, style="Card.TFrame")
        grid.pack(fill="x")
        for i, (key, label) in enumerate(TELEGRAM_FLAGS):
            var = tk.BooleanVar(value=False)
            self._tg_vars[key] = var
            ttk.Checkbutton(
                grid,
                text=label,
                variable=var,
                command=self.apply_telegram_event,
            ).grid(row=i // 2, column=i % 2, sticky="w", padx=(0, 24), pady=2)
        ttk.Label(
            events,
            text="Ogni modifica viene inviata subito all'Helper.",
            style="Muted.TLabel",
            wraplength=800,
        ).pack(anchor="w", pady=(8, 0))

    def _build_scrcpy(self) -> None:
        card = self._card(self.tab_scr, "Mirror scrcpy (opzionale)")
        ttk.Label(card, textvariable=self.shared_ip_text, style="Muted.TLabel").pack(anchor="w", pady=(0, 8))
        row = ttk.Frame(card, style="Card.TFrame")
        row.pack(fill="x")
        ttk.Label(row, text="scrcpy", style="Card.TLabel").pack(side="left")
        ttk.Entry(row, textvariable=self.scrcpy_path, width=50).pack(side="left", padx=8)
        ttk.Button(row, text="Sfoglia", command=self.browse_scrcpy).pack(side="left")
        opts = ttk.Frame(card, style="Card.TFrame")
        opts.pack(fill="x", pady=(10, 0))
        ttk.Label(opts, text="Lato max", style="Card.TLabel").pack(side="left")
        ttk.Entry(opts, textvariable=self.max_size, width=8).pack(side="left", padx=(6, 12))
        ttk.Label(opts, text="Bitrate", style="Card.TLabel").pack(side="left")
        ttk.Entry(opts, textvariable=self.bitrate, width=8).pack(side="left", padx=(6, 12))
        ttk.Checkbutton(opts, text="Encoder software (Pi)", variable=self.software_encoder).pack(side="left", padx=(0, 12))
        ttk.Checkbutton(opts, text="Senza audio", variable=self.no_audio).pack(side="left")
        btns = ttk.Frame(card, style="Card.TFrame")
        btns.pack(fill="x", pady=(12, 0))
        ttk.Button(btns, text="Avvia scrcpy", style="Primary.TButton", command=self.start_scrcpy).pack(
            side="left", padx=(0, 6)
        )
        ttk.Button(btns, text="Chiudi scrcpy", command=self.stop_scrcpy).pack(side="left")

    # ------------------------------------------------------------------ Osserva (come APK)

    def _build_observe(self) -> None:
        card = self._card(self.tab_tel, "Osserva")
        card.pack_configure(fill="both", expand=True)
        self._obs_card = card
        ttk.Label(
            card,
            text="Cerca in italiano o inglese, anche un nome parziale (Orione, Androm, M4). "
            "Se esce una lista, clicca la riga per foto e coordinate.",
            style="Muted.TLabel",
            wraplength=1100,
        ).pack(anchor="w")
        body = ttk.Frame(card, style="Card.TFrame")
        body.pack(fill="both", expand=True, pady=(8, 0))
        left = ttk.Frame(body, style="Card.TFrame")
        left.pack(side="left", fill="y")
        row = ttk.Frame(left, style="Card.TFrame")
        row.pack(fill="x")
        entry = ttk.Entry(row, textvariable=self.obs_query, width=34)
        entry.pack(side="left", fill="x", expand=True)
        entry.bind("<Return>", lambda _e: self.search_target())
        self.obs_search_btn = ttk.Button(row, text="Cerca", style="Primary.TButton", command=self.search_target)
        self.obs_search_btn.pack(side="left", padx=(6, 0))
        self.vis_text = tk.Text(
            left, width=58, height=12, bg=PANEL, fg=FG, relief="flat", borderwidth=0,
            highlightthickness=0, font=("Segoe UI", 10), wrap="word", cursor="arrow",
        )
        self.vis_text.pack(fill="x", pady=(8, 0))
        self.vis_text.configure(state="disabled")
        self.obs_hits_list = tk.Listbox(
            left, width=58, height=7, bg=ENTRY, fg=FG, selectbackground=PRIMARY,
            selectforeground="#ffffff", activestyle="none", relief="solid", borderwidth=1,
            highlightthickness=0, font=("Segoe UI", 10), exportselection=False,
        )
        self.obs_hits_list.bind("<<ListboxSelect>>", self._on_hit_select)
        ttk.Label(left, textvariable=self.obs_result, style="Card.TLabel", wraplength=470).pack(
            anchor="w", side="bottom", pady=(6, 0))
        right = ttk.Frame(body, style="Card.TFrame")
        right.pack(side="left", fill="both", expand=True, padx=(12, 0))
        self.obs_canvas = tk.Canvas(right, bg=INK, highlightthickness=1,
                                    highlightbackground=PANEL_STROKE, height=260)
        self.obs_canvas.pack(fill="both", expand=True)
        self.obs_canvas.bind("<Configure>", lambda _e: self._draw_obs_preview())
        details = ttk.Label(right, textvariable=self.obs_details, style="Card.TLabel", justify="left")
        session = ttk.Label(right, textvariable=self.obs_session_text, style="Muted.TLabel", wraplength=600)
        btns = ttk.Frame(right, style="Card.TFrame")
        # Dal basso, prima dell'anteprima: se manca spazio si rimpicciolisce la foto, non i pulsanti.
        btns.pack(side="bottom", fill="x", pady=(6, 0), before=self.obs_canvas)
        session.pack(side="bottom", anchor="w", pady=(2, 0), before=self.obs_canvas)
        details.pack(side="bottom", anchor="w", pady=(6, 0), before=self.obs_canvas)
        self.obs_continue_btn = ttk.Button(btns, text="Continua multi-notte", style="Primary.TButton",
                                           command=lambda: self.start_observe(True))
        self.obs_start_btn = ttk.Button(btns, text="Avvia multi-notte", style="TButton",
                                        command=lambda: self.start_observe(False))
        self.fav_toggle_btn = ttk.Button(btns, text="☆ Aggiungi ai preferiti", style="TButton",
                                         command=self.toggle_favorite)
        self._draw_obs_preview()

    def search_target(self) -> None:
        name = self.obs_query.get().strip()
        if not name or self._obs_busy:
            return
        self._clear_observe()
        self._obs_busy = True
        self.obs_search_btn.state(["disabled"])
        self.obs_result.set("Ricerca nel catalogo…")
        self._obs_gen += 1
        gen = self._obs_gen

        def work() -> None:
            try:
                hits = sky.find(name)
                if len(hits) == 1:
                    self.queue.put(("obs_target", gen, self._load_target(hits[0])))
                else:
                    self.queue.put(("obs_hits", gen, hits))
            except sky.SkyError as exc:
                self.queue.put(("obs_error", gen, str(exc)))
            except Exception:
                self.queue.put(("obs_error", gen, "net"))

        threading.Thread(target=work, daemon=True).start()

    def _load_target(self, hit: sky.Hit) -> tuple:
        target = sky.with_preview(hit)
        return target, sky.find_session(self.state, target.name, target.query)

    def _clear_observe(self) -> None:
        self._obs_target = None
        self._obs_session = None
        self._obs_photo = None
        self._obs_png = None
        self.obs_details.set("")
        self.obs_session_text.set("")
        self.obs_result.set("")
        self.obs_hits_list.delete(0, "end")
        self.obs_hits_list.pack_forget()
        self.obs_start_btn.pack_forget()
        self.obs_continue_btn.pack_forget()
        self.fav_toggle_btn.pack_forget()
        self._draw_obs_preview()
        self.refresh_visibility()

    def _on_obs_hits(self, gen: int, hits: list) -> None:
        if gen != self._obs_gen:
            return
        self._obs_busy = False
        self.obs_search_btn.state(["!disabled"])
        self._obs_hits = hits
        self.obs_hits_list.delete(0, "end")
        for hit in hits:
            self.obs_hits_list.insert("end", hit.choice_label())
        self.obs_hits_list.pack(fill="both", expand=True, pady=(8, 0))
        self.obs_result.set("Clicca un oggetto per foto e coordinate")

    def _on_hit_select(self, _event=None) -> None:
        sel = self.obs_hits_list.curselection()
        if not sel or self._obs_busy or sel[0] >= len(self._obs_hits):
            return
        hit = self._obs_hits[sel[0]]
        self._obs_busy = True
        self.obs_search_btn.state(["disabled"])
        self.obs_result.set(f"Carico {hit.name}…")
        self._obs_gen += 1
        gen = self._obs_gen

        def work() -> None:
            try:
                self.queue.put(("obs_target", gen, self._load_target(hit)))
            except Exception:
                self.queue.put(("obs_error", gen, "net"))

        threading.Thread(target=work, daemon=True).start()

    def _on_obs_target(self, gen: int, loaded: tuple) -> None:
        if gen != self._obs_gen:
            return
        target, session = loaded
        self._obs_busy = False
        self.obs_search_btn.state(["!disabled"])
        self.obs_hits_list.pack_forget()
        self._obs_target = target
        self._obs_session = session
        self._obs_png = target.preview_jpeg
        kind = sky.type_label(target.type_code)
        type_part = f"{kind} ({target.type_code})" if kind and kind != target.type_code else target.type_code
        self.obs_details.set(
            f"{target.name}\n{type_part}\nRA {sky.ra_text(target.ra_deg)}   ·   Dec {sky.dec_text(target.dec_deg)}\n"
            f"RA {target.ra_deg:.3f}°   ·   Dec {target.dec_deg:.3f}°"
        )
        self.obs_result.set("" if target.preview_jpeg else "Coordinate pronte. L'anteprima del campo non è arrivata.")
        self._draw_obs_preview()
        self._show_obs_session()
        self.refresh_visibility()

    def _on_obs_error(self, gen: int, code: str) -> None:
        if gen != self._obs_gen:
            return
        self._obs_busy = False
        self.obs_search_btn.state(["!disabled"])
        if code == "short":
            msg = "Scrivi almeno 3 lettere, oppure una sigla (M42, NGC 7000)."
        elif code in ("not_found", "empty"):
            msg = "Oggetto non trovato. Prova la sigla (M42) o il nome in italiano o inglese."
        else:
            msg = "Catalogo non raggiungibile. Serve Internet sul PC."
        self.obs_result.set(msg)

    def _show_obs_session(self) -> None:
        if self._obs_target is None:
            return
        self.obs_start_btn.pack_forget()
        self.obs_continue_btn.pack_forget()
        if self._obs_session is not None:
            self.obs_session_text.set(
                f"Multi-notte già sul telescopio: {self._obs_session.stacks} pose "
                f"({self._obs_session.object_name}).")
            self.obs_continue_btn.pack(side="left", padx=(0, 6))
            self.obs_start_btn.configure(text="Nuova sessione")
        else:
            self.obs_session_text.set(
                "Nessuna sessione di questo oggetto. Avvia ne crea una che si può continuare.")
            self.obs_start_btn.configure(text="Avvia multi-notte")
        self.obs_start_btn.pack(side="left")
        self._update_fav_button()
        self.fav_toggle_btn.pack(side="left", padx=(6, 0))

    def start_observe(self, resume: bool) -> None:
        target = self._obs_target
        if target is None:
            return
        if resume:
            if self._obs_session is None or not self._obs_session.store_id:
                return
            line = f"cmd|telescope|observeResume|{self._obs_session.store_id}"
            done = f"Multi-notte ripresa: {target.name}"
        else:
            line = "cmd|telescope|observe|" + sky.start_body(target, self.state.get("telescope"))
            done = f"Multi-notte avviata: {target.name}"
        self.obs_result.set("Invio al telescopio…")
        self._obs_pending = done
        self.cmd(line, wait=60)

    def _draw_obs_preview(self) -> None:
        canvas = getattr(self, "obs_canvas", None)
        if canvas is None:
            return
        w = max(canvas.winfo_width(), 2)
        h = max(canvas.winfo_height(), 2)
        canvas.delete("all")
        data = getattr(self, "_obs_png", None)
        if not data:
            canvas.create_text(w // 2, h // 2, text="Anteprima del campo dell'oggetto",
                               fill=MUTED, font=("Segoe UI", 11))
            return
        try:
            from PIL import Image, ImageTk

            image = Image.open(io.BytesIO(data))
            image.thumbnail((max(w - 8, 1), max(h - 8, 1)), Image.Resampling.LANCZOS)
            self._obs_photo = ImageTk.PhotoImage(image)
            canvas.create_image(w // 2, h // 2, image=self._obs_photo, anchor="center")
        except Exception as exc:
            canvas.create_text(w // 2, h // 2, text=str(exc), fill=ERR)

    def refresh_visibility(self) -> None:
        if not hasattr(self, "vis_text"):
            return
        site = self._site()
        if site is None:
            self._render_visibility([(-1, "Posizione non impostata. In Impostazioni scrivi le coordinate, "
                                          "usa \"Da Internet\" oppure collegati al Vespera.")])
            return
        lat, lon = site
        target = self._obs_target
        self._vis_gen += 1
        gen = self._vis_gen
        where = self._site_line(lat, lon)
        self._render_visibility([(-1, "Visibilità della notte…"), (-1, where)])

        def work() -> None:
            try:
                if target is None:
                    lines = sky.report(lat, lon)
                else:
                    lines = sky.report(lat, lon, target.name, target.ra_deg, target.dec_deg)
            except Exception as exc:
                lines = [(-1, f"Visibilità non calcolabile: {exc}")]
            self.queue.put(("visibility", gen, [(-1, where)] + lines))

        threading.Thread(target=work, daemon=True).start()

    def _on_visibility(self, gen: int, lines: list) -> None:
        if gen == self._vis_gen:
            self._render_visibility(lines)

    def _render_visibility(self, lines: list) -> None:
        text = self.vis_text
        text.configure(state="normal")
        text.delete("1.0", "end")
        self._obs_faces = []
        try:
            from PIL import ImageTk
        except Exception:
            ImageTk = None
        for i, (level, line) in enumerate(lines):
            if i:
                text.insert("end", "\n")
            if level >= 0:
                if ImageTk is not None:
                    face = ImageTk.PhotoImage(sky.face_image(level, 18))
                    self._obs_faces.append(face)
                    text.image_create("end", image=face, padx=2)
                    text.insert("end", "  ")
                else:
                    text.insert("end", "● ", (f"lv{level}",))
            text.insert("end", line)
        for level, color in sky.COLORS.items():
            text.tag_configure(f"lv{level}", foreground=color)
        text.configure(height=max(4, min(14, len(lines) + 1)), state="disabled")

    # ------------------------------------------------------------------ Preferiti (come APK)

    def _build_favorites(self) -> None:
        card = self._card(self.tab_tel, "Preferiti")
        ttk.Label(
            card,
            text="Ordinati per visibilità nella notte scelta (meteo + altezza). Clicca un oggetto per aprirlo.",
            style="Muted.TLabel", wraplength=1100,
        ).pack(anchor="w")
        row = ttk.Frame(card, style="Card.TFrame")
        row.pack(fill="x", pady=(6, 0))
        ttk.Button(row, text="‹", width=3, command=lambda: self.shift_fav_night(-1)).pack(side="left")
        ttk.Label(row, textvariable=self.fav_day, style="Card.TLabel", width=34, anchor="center").pack(
            side="left", padx=8)
        ttk.Button(row, text="›", width=3, command=lambda: self.shift_fav_night(1)).pack(side="left")
        ttk.Label(row, textvariable=self.fav_status, style="Muted.TLabel").pack(side="left", padx=(14, 0))
        cols = ttk.Frame(card, style="Card.TFrame")
        cols.pack(fill="x", pady=(6, 0))
        left = ttk.Frame(cols, style="Card.TFrame")
        left.pack(side="left", fill="both", expand=True)
        right = ttk.Frame(cols, style="Card.TFrame")
        right.pack(side="left", fill="both", expand=True, padx=(12, 0))
        self.fav_text = tk.Text(
            left, height=6, width=60, bg=PANEL, fg=FG, relief="flat", borderwidth=0, highlightthickness=0,
            font=("Segoe UI", 10), wrap="word", cursor="arrow",
        )
        self.fav_text.pack(fill="x")
        self.fav_text.configure(state="disabled")
        ttk.Button(left, text="Piano della notte (oggetti spuntati)", style="Primary.TButton",
                   command=self.make_plan).pack(anchor="w", pady=(6, 0))
        # Piano: righe, azioni, stato sull'Helper, piani salvati
        self.plan_text = tk.Text(
            right, height=5, width=60, bg=PANEL, fg=FG, relief="flat", borderwidth=0, highlightthickness=0,
            font=("Segoe UI", 10), wrap="word", cursor="arrow",
        )
        self.plan_text.pack(fill="x")
        self.plan_text.configure(state="disabled")
        # Periodi del piano: spunta (attivo), orario, meteo, oggetto scelto (★ = proposto)
        self.plan_periods = ttk.Frame(right, style="Card.TFrame")
        self.plan_periods.pack(fill="x")
        acts = ttk.Frame(right, style="Card.TFrame")
        acts.pack(fill="x", pady=(4, 0))
        self.plan_propose_btn = ttk.Button(acts, text="Proposta automatica", command=self.propose_plan)
        self.plan_save_btn = ttk.Button(acts, text="Salva piano", command=self.save_plan)
        self.plan_send_btn = ttk.Button(acts, text="Carica sull'Helper", style="Primary.TButton",
                                        command=self.send_plan)
        self.plan_delete_btn = ttk.Button(acts, text="Elimina piano salvato", style="Danger.TButton",
                                          command=self.delete_plan)
        ttk.Label(right, text="Piano caricato sull'Helper", style="Card.TLabel",
                  font=("Segoe UI", 10, "bold")).pack(anchor="w", pady=(10, 0))
        ttk.Label(right, textvariable=self.plan_helper, style="Card.TLabel", wraplength=820,
                  justify="left").pack(anchor="w", pady=(2, 0))
        helper_row = ttk.Frame(right, style="Card.TFrame")
        helper_row.pack(fill="x")
        self.plan_cancel_btn = ttk.Button(helper_row, text="Annulla piano sull'Helper", style="Warn.TButton",
                                          command=lambda: self.cmd("cmd|plan|cancel", wait=15))
        saved = ttk.Frame(right, style="Card.TFrame")
        saved.pack(fill="x", pady=(4, 0))
        ttk.Label(saved, text="Piani salvati", style="Card.TLabel").pack(anchor="w")
        self.plans_list = tk.Listbox(
            saved, height=3, bg=ENTRY, fg=FG, selectbackground=PRIMARY, selectforeground="#ffffff",
            activestyle="none", relief="solid", borderwidth=1, highlightthickness=0,
            font=("Segoe UI", 10), exportselection=False,
        )
        self.plans_list.pack(fill="x")
        self.plans_list.bind("<<ListboxSelect>>", self._on_plan_select)
        self._show_saved_plans()

    def _fav_key(self, name: str) -> str:
        return re.sub(r"[\s_-]", "", name or "").upper()

    def _is_favorite(self, name: str) -> bool:
        key = self._fav_key(name)
        return any(self._fav_key(f.get("name", "")) == key for f in self.favorites)

    def _update_fav_button(self) -> None:
        target = self._obs_target
        if target is None:
            return
        self.fav_toggle_btn.configure(
            text="★ Togli dai preferiti" if self._is_favorite(target.name) else "☆ Aggiungi ai preferiti")

    def toggle_favorite(self) -> None:
        target = self._obs_target
        if target is None:
            return
        key = self._fav_key(target.name)
        before = len(self.favorites)
        self.favorites = [f for f in self.favorites if self._fav_key(f.get("name", "")) != key]
        if len(self.favorites) == before:
            self.favorites.append({"name": target.name, "type": target.type_code,
                                   "ra": target.ra_deg, "dec": target.dec_deg})
            self.set_status(f"{target.name} aggiunto ai preferiti.", "ok")
        else:
            self.set_status(f"{target.name} tolto dai preferiti.", "info")
        self._save_settings()
        self._update_fav_button()
        self.refresh_favorites()

    def shift_fav_night(self, days: int) -> None:
        site = self._site()
        if site is None:
            return
        first = sky.default_night(*site)
        base = self._fav_night or first
        nxt = base + timedelta(days=days)
        nxt = max(first, min(first + timedelta(days=365), nxt))
        self._fav_night = None if nxt == first else nxt
        self.refresh_favorites()

    def refresh_favorites(self) -> None:
        if not hasattr(self, "fav_text"):
            return
        self._fav_gen += 1
        gen = self._fav_gen
        site = self._site()
        if site is None:
            self.fav_day.set("")
            self.fav_status.set("")
            self._render_favorites([], "Posizione non impostata: vai in Impostazioni.")
            return
        lat, lon = site
        first = sky.default_night(lat, lon)
        night = self._fav_night or first
        day = f"{_it_day(night)} → {_it_day(night + timedelta(days=1))}"
        self.fav_day.set(f"Stanotte · {day}" if night == first else f"Notte {day}")
        items = list(self.favorites)
        if not items:
            self.fav_status.set("")
            self._render_favorites([], "Nessun preferito. Cerca un oggetto e clicca \"Aggiungi ai preferiti\".")
            return
        self.fav_status.set("Calcolo la classifica…")

        def work() -> None:
            try:
                result = sky.rank(lat, lon, items, night)
            except Exception as exc:
                result = (f"Classifica non calcolabile: {exc}", True, [])
            self.queue.put(("favorites", gen, result))

        threading.Thread(target=work, daemon=True).start()

    def _on_favorites(self, gen: int, result: tuple) -> None:
        if gen != self._fav_gen:
            return
        night_line, weather, rows = result
        status = night_line
        if not weather:
            status += " · Meteo non disponibile per questa notte: conta solo l'altezza."
        self.fav_status.set(status)
        self._render_favorites(rows, "")

    def _render_favorites(self, rows: list, empty: str) -> None:
        text = self.fav_text
        text.configure(state="normal")
        text.delete("1.0", "end")
        self._fav_faces = []
        if not rows:
            text.insert("end", empty)
        try:
            from PIL import ImageTk
        except Exception:
            ImageTk = None
        for w in self._fav_widgets:
            w.destroy()
        self._fav_widgets = []
        for i, r in enumerate(rows):
            tag = f"fav{i}"
            if i:
                text.insert("end", "\n")
            key = self._fav_key(r.name)
            var = self.fav_checks.setdefault(key, tk.BooleanVar(value=False))
            check = tk.Checkbutton(text, variable=var, bg=PANEL, activebackground=PANEL,
                                   highlightthickness=0, borderwidth=0, cursor="arrow")
            self._fav_widgets.append(check)
            text.window_create("end", window=check)
            if ImageTk is not None:
                face = ImageTk.PhotoImage(sky.face_image(r.best, 18))
                self._fav_faces.append(face)
                text.image_create("end", image=face, padx=2)
                text.insert("end", "  ", (tag,))
            else:
                text.insert("end", "● ", (f"lv{r.best}", tag))
            kind = sky.type_label(r.type_code)
            extra = " · ".join(x for x in (kind, r.best_ranges) if x)
            text.insert("end", f"{i + 1}. {r.headline()}", (tag, "favhead"))
            if extra:
                text.insert("end", f"   {extra}", (tag, "favsub"))
            text.tag_bind(tag, "<Button-1>", lambda _e, rr=r: self.open_favorite(rr))
            text.tag_bind(tag, "<Enter>", lambda _e: text.configure(cursor="hand2"))
            text.tag_bind(tag, "<Leave>", lambda _e: text.configure(cursor="arrow"))
        for level, color in sky.COLORS.items():
            text.tag_configure(f"lv{level}", foreground=color)
        text.tag_configure("favhead", font=("Segoe UI", 10, "bold"))
        text.tag_configure("favsub", foreground=MUTED)
        text.configure(height=max(2, min(14, len(rows) or 1)), state="disabled")
        self.after(50, lambda: self._fit_text(text, 14))

    # ------------------------------------------------------------------ Piano della notte (come APK)

    def make_plan(self) -> None:
        site = self._site()
        if site is None:
            self._plan_message("Posizione non impostata: vai in Impostazioni.")
            return
        chosen = [f for f in self.favorites
                  if self.fav_checks.get(self._fav_key(f.get("name", ""))) is not None
                  and self.fav_checks[self._fav_key(f.get("name", ""))].get()]
        if not chosen:
            self._plan_message("Spunta almeno un preferito per fare il piano.")
            return
        lat, lon = site
        night = self._fav_night or sky.default_night(lat, lon)
        self._plan_message("Calcolo il piano…")

        def work() -> None:
            try:
                plan = sky.plan_night(lat, lon, chosen, night)
            except Exception as exc:
                plan = {"error": str(exc)}
            self.queue.put(("plan", plan))

        threading.Thread(target=work, daemon=True).start()

    def _on_plan(self, plan: dict) -> None:
        if plan.get("error"):
            self._plan_message(f"Piano non calcolabile: {plan['error']}")
            return
        self._show_plan(plan, saved=False)

    def _plan_message(self, text_value: str) -> None:
        self._plan = None
        self._render_plan_lines([(-1, text_value)])
        self._render_plan_periods(None)
        for b in (self.plan_propose_btn, self.plan_save_btn, self.plan_send_btn, self.plan_delete_btn):
            b.pack_forget()

    @staticmethod
    def _plan_over(plan: dict) -> bool:
        ends = [int(p.get("end") or 0) for p in plan.get("periods") or []]
        ends += [int(s.get("end") or 0) for s in plan.get("steps") or []]
        return not ends or max(ends) <= int(time.time() * 1000)

    def _show_plan(self, plan: dict, saved: bool) -> None:
        self._plan = plan
        self._plan_saved = saved
        names = ", ".join(str(i.get("name")) for i in plan.get("items") or [])
        lines = [(-1, f"{plan.get('title')} · {names}")]
        if not plan.get("weather", True):
            lines.append((-1, "Meteo non disponibile per questa notte: conta solo l'altezza."))
        periods = plan.get("periods")
        if periods:
            mins = round((int(periods[0]["end"]) - int(periods[0]["start"])) / 60000)
            off = sum(1 for p in periods if not p.get("enabled"))
            lines.append((-1, f"{len(periods)} periodi da {mins} min. Spunta i periodi da usare e scegli "
                              f"l'oggetto (★ = proposto, il più alto)."
                              + (f" {off} spenti: nuvoloso/pioggia o nessun oggetto alto." if off else "")))
            if not plan.get("steps"):
                lines.append((-1, "Nessun periodo attivo con un oggetto: il piano è vuoto."))
        else:  # piani salvati prima della 0.2.31
            for lv, txt in plan.get("lines") or []:
                lines.append((int(lv), txt if int(lv) >= 0 else "      " + txt))
        if self._plan_over(plan):
            lines.append((-1, "Questo piano è per una notte già passata: premi \"Piano della notte\" "
                              "per rifarlo sulla notte scelta."))
        self._render_plan_lines(lines)
        self._render_plan_periods(plan)
        for b in (self.plan_propose_btn, self.plan_save_btn, self.plan_send_btn, self.plan_delete_btn):
            b.pack_forget()
        if periods:
            self.plan_propose_btn.pack(side="left", padx=(0, 6))
        self.plan_save_btn.pack(side="left", padx=(0, 6))
        self.plan_send_btn.pack(side="left", padx=(0, 6))
        if saved:
            self.plan_delete_btn.pack(side="left", padx=(0, 6))

    def _render_plan_lines(self, lines: list) -> None:
        text = self.plan_text
        text.configure(state="normal")
        text.delete("1.0", "end")
        self._plan_faces = []
        try:
            from PIL import ImageTk
        except Exception:
            ImageTk = None
        for i, (level, line) in enumerate(lines):
            if i:
                text.insert("end", "\n")
            if level >= 0:
                if ImageTk is not None:
                    face = ImageTk.PhotoImage(sky.face_image(level, 16))
                    self._plan_faces.append(face)
                    text.image_create("end", image=face, padx=2)
                    text.insert("end", "  ")
                else:
                    text.insert("end", "● ", (f"lv{level}",))
            text.insert("end", line)
        for level, color in sky.COLORS.items():
            text.tag_configure(f"lv{level}", foreground=color)
        text.configure(height=max(2, min(16, len(lines))), state="disabled")
        self.after(50, lambda: self._fit_text(text, 16))

    SKY_COLORS = {sky.SKY_SUNNY: "#B7791F", sky.SKY_FEW: "#4A6F86",
                  sky.SKY_CLOUDY: "#37474F", sky.SKY_RAIN: "#1E5AA8"}

    def _render_plan_periods(self, plan: dict | None) -> None:
        for w in self.plan_periods.winfo_children():
            w.destroy()
        self._plan_period_faces = []
        periods = (plan or {}).get("periods") or []
        if not periods:
            return
        try:
            from PIL import ImageTk
        except Exception:
            ImageTk = None
        items = plan.get("items") or []
        levels = [lv for lv, _t in plan.get("lines") or []]
        for i, p in enumerate(periods):
            row = ttk.Frame(self.plan_periods, style="Card.TFrame")
            row.pack(fill="x", pady=1)
            var = tk.BooleanVar(value=bool(p.get("enabled")))
            ttk.Checkbutton(row, variable=var,
                            command=lambda i=i, v=var: self._plan_toggle(i, v.get())).pack(side="left")
            level = int(levels[i]) if i < len(levels) else -1
            if level >= 0 and ImageTk is not None:
                face = ImageTk.PhotoImage(sky.face_image(level, 16))
                self._plan_period_faces.append(face)
                tk.Label(row, image=face, bg=PANEL).pack(side="left", padx=(0, 4))
            else:
                blank = tk.PhotoImage(width=16, height=16)
                self._plan_period_faces.append(blank)
                tk.Label(row, image=blank, bg=PANEL).pack(side="left", padx=(0, 4))
            t0 = datetime.fromtimestamp(int(p["start"]) / 1000)
            t1 = datetime.fromtimestamp(int(p["end"]) / 1000)
            fg = FG if p.get("enabled") else MUTED
            tk.Label(row, text=f"{t0:%H:%M}–{t1:%H:%M}", bg=PANEL, fg=fg, width=11, anchor="w",
                     font=("Segoe UI", 10)).pack(side="left")
            kind = (p.get("sky") or {}).get("kind")
            bad = kind in (sky.SKY_CLOUDY, sky.SKY_RAIN)
            tk.Label(row, text=sky.sky_text(p.get("sky")), bg=PANEL, fg=self.SKY_COLORS.get(kind, MUTED),
                     width=34, anchor="w", font=("Segoe UI", 10, "bold" if bad else "normal")).pack(side="left")
            values = [sky.plan_option_text(plan, p, o) for o in range(len(items))] + ["— nessun oggetto —"]
            box = ttk.Combobox(row, values=values, state="readonly", width=36)
            choice = int(p.get("choice", -1))
            box.current(choice if 0 <= choice < len(items) else len(items))
            box.bind("<<ComboboxSelected>>", lambda _e, i=i, b=box: self._plan_choose(i, b.current()))
            box.pack(side="left", padx=(6, 0))
        totals = sky.totals_text(plan.get("totals") or sky.plan_totals(plan))
        if totals:
            ttk.Label(self.plan_periods, text="Tempo di osservazione per oggetto", style="Card.TLabel",
                      font=("Segoe UI", 10, "bold")).pack(anchor="w", pady=(6, 0))
            ttk.Label(self.plan_periods, text="\n".join(totals), style="Card.TLabel", justify="left",
                      wraplength=820).pack(anchor="w")

    def _plan_changed(self) -> None:
        sky.plan_finalize(self._plan)
        self._show_plan(self._plan, self._plan_saved)

    def _plan_toggle(self, i: int, on: bool) -> None:
        if self._plan is None:
            return
        p = self._plan["periods"][i]
        p["enabled"] = bool(on)
        if on and int(p.get("choice", -1)) < 0:
            p["choice"] = int(p.get("proposed", -1))
        self._plan_changed()

    def _plan_choose(self, i: int, index: int) -> None:
        if self._plan is None:
            return
        n = len(self._plan.get("items") or [])
        p = self._plan["periods"][i]
        p["choice"] = index if 0 <= index < n else -1
        if p["choice"] >= 0:
            p["enabled"] = True
        self._plan_changed()

    def propose_plan(self) -> None:
        """Rifà la proposta sui periodi attivi (spunte come sono ora)."""
        if self._plan is None or not self._plan.get("periods"):
            return
        sky.plan_propose(self._plan)
        self._plan_changed()

    def save_plan(self) -> None:
        if self._plan is None:
            return
        created = self._plan.get("created")
        self.plans = [self._plan] + [p for p in self.plans if p.get("created") != created][:29]
        self._plan_saved = True
        self._save_settings()
        self._show_saved_plans()
        self._show_plan(self._plan, saved=True)
        self.set_status("Piano salvato.", "ok")

    def delete_plan(self) -> None:
        if self._plan is None or not self._plan_saved:
            return
        created = self._plan.get("created")
        self.plans = [p for p in self.plans if p.get("created") != created]
        self._save_settings()
        self._show_saved_plans()
        self._plan_message("Piano eliminato.")

    def send_plan(self) -> None:
        if self._plan is None:
            return
        if self._plan_over(self._plan):
            self.set_status("Piano di una notte già passata: rifallo sulla notte scelta.", "err")
            return
        if not self._plan.get("steps"):
            self.set_status("Nessun periodo attivo con un oggetto: spunta almeno un periodo.", "err")
            return
        body = {"title": self._plan.get("title"), "night": self._plan.get("night"),
                "parkAtEnd": bool(self._plan.get("parkAtEnd", True)), "steps": self._plan.get("steps"),
                # per vedere il piano sull'Helper: righe dei periodi e tempo per oggetto
                "lines": [t for _lv, t in self._plan.get("lines") or []],
                "totals": sky.totals_text(self._plan.get("totals") or [])}
        self.plan_helper.set("Invio del piano all'Helper…")
        self.cmd("cmd|plan|load|" + json.dumps(body, ensure_ascii=False, separators=(",", ":")), wait=20)

    def _show_saved_plans(self) -> None:
        self.plans_list.delete(0, "end")
        for p in self.plans:
            names = ", ".join(str(i.get("name")) for i in p.get("items") or [])
            self.plans_list.insert("end", f"{p.get('title')}  ·  {names}")

    def _on_plan_select(self, _event=None) -> None:
        sel = self.plans_list.curselection()
        if not sel or sel[0] >= len(self.plans):
            return
        plan = self.plans[sel[0]]
        keys = {self._fav_key(str(i.get("name"))) for i in plan.get("items") or []}
        for key, var in self.fav_checks.items():
            var.set(key in keys)
        for key in keys:
            self.fav_checks.setdefault(key, tk.BooleanVar(value=True)).set(True)
        site = self._site()
        try:
            night = date.fromisoformat(str(plan.get("night")))
            if site is not None:
                first = sky.default_night(*site)
                if night >= first:
                    self._fav_night = None if night == first else night
        except ValueError:
            pass
        self._show_plan(plan, saved=True)
        self.refresh_favorites()

    def _show_helper_plan(self, state: dict) -> None:
        plan = state.get("plan")
        self.plan_cancel_btn.pack_forget()
        if not isinstance(plan, dict):
            self.plan_helper.set("L'Helper non gestisce i piani: aggiorna Vespera Helper (≥ 0.8.23).")
            return
        steps = plan.get("steps") or []
        msg = plan.get("message") or ""
        if not steps:
            self.plan_helper.set("Helper: nessun piano" + (f" · {msg}" if msg else ""))
            return
        out = [f"Helper: {plan.get('title')} · " + ("terminato" if plan.get("done") else "attivo")]
        # "Errore su X: ..." è già nella riga del passo, non ripeterlo
        if msg and not msg.startswith("Errore su "):
            out.append(self._readable_plan_error(msg))
        cur = plan.get("current", -1)
        for i, s in enumerate(steps):
            t0 = datetime.fromtimestamp(int(s.get("start") or 0) / 1000)
            t1 = datetime.fromtimestamp(int(s.get("end") or 0) / 1000)
            st = self._readable_plan_error(s.get("status") or "")
            out.append(f"{'▶ ' if i == cur else '   '}{t0:%H:%M}–{t1:%H:%M}  {s.get('name')}"
                       + (f" · {st}" if st and not st.startswith("errore") else ""))
            if st.startswith("errore"):
                out.append(f"      ⚠ {st}")
        totals = plan.get("totals") or []
        if totals:
            out.append("Tempo per oggetto:")
            out.extend("   " + str(t) for t in totals)
        self.plan_helper.set("\n".join(out))
        if plan.get("active"):
            self.plan_cancel_btn.pack(side="left", padx=(0, 6))

    @staticmethod
    def _readable_plan_error(text: str) -> str:
        """Sostituisce il JSON d'errore del firmware (Helper < 0.8.31) con una frase leggibile."""
        start = text.find('{"')
        if start < 0:
            return text
        prefix, raw = text[:start], text[start:]
        if "RESOURCE_IS_NOT_AVAILABLE" in raw or "REQUIRE_RESOURCES_FAILED" in raw:
            res = list(dict.fromkeys(re.findall(r'"resourceName"\s*:\s*"([^"]+)"', raw)))
            detail = "Vespera occupato da un'altra osservazione"
            if res:
                detail += " (in uso: " + ", ".join(res) + ")"
        else:
            m = re.search(r'"name"\s*:\s*"([^"]+)"', raw)
            detail = "firmware " + (m.group(1) if m else "rifiuta il comando")
        return prefix + detail.strip()

    @staticmethod
    def _fit_text(text: tk.Text, limit: int) -> None:
        """Altezza del Text = righe visualizzate (con a capo), al massimo `limit`."""
        try:
            shown = text.count("1.0", "end", "displaylines")
            lines = shown[0] if isinstance(shown, tuple) else int(shown or 1)
            # +1: le spunte e le faccine rendono le righe più alte del testo
            text.configure(height=max(2, min(limit, lines + 2)))
        except Exception:
            pass

    def open_favorite(self, r) -> None:
        if self._obs_busy:
            return
        self.obs_query.set(r.name)
        self._clear_observe()
        hit = sky.Hit(r.name, r.type_code, r.ra_deg, r.dec_deg)
        self._obs_busy = True
        self.obs_search_btn.state(["disabled"])
        self.obs_result.set(f"Carico {hit.name}…")
        self._obs_gen += 1
        gen = self._obs_gen

        def work() -> None:
            try:
                self.queue.put(("obs_target", gen, self._load_target(hit)))
            except Exception:
                self.queue.put(("obs_error", gen, "net"))

        threading.Thread(target=work, daemon=True).start()

    # ------------------------------------------------------------------ Impostazioni (come APK)

    def _build_settings(self) -> None:
        net = self._card(self.tab_imp, "Raspberry Pi: IP e porte")
        ttk.Label(net, text="Usati da Connessioni, Anteprima (FTP HD / Vespera) e Schermo Pi.",
                  style="Muted.TLabel").pack(anchor="w", pady=(0, 6))
        row = ttk.Frame(net, style="Card.TFrame")
        row.pack(fill="x")
        for label, var, width in (
            ("IP", self.pi_host, 16),
            ("ADB", self.adb_port, 6),
            ("Porta HD", self.ftp_hd_port, 6),
            ("Porta Vespera", self.ftp_vespera_port, 6),
        ):
            ttk.Label(row, text=label, style="Card.TLabel").pack(side="left")
            ttk.Entry(row, textvariable=var, width=width).pack(side="left", padx=(6, 14))
        ttk.Button(row, text="Salva", style="Primary.TButton", command=self._save_network).pack(side="left")

        site = self._card(self.tab_imp, "Posizione osservatorio")
        ttk.Label(site, text="Serve per la visibilità della notte. Si aggiorna dal Vespera se non l'hai scritta tu, "
                             "oppure usa la posizione approssimata da Internet.",
                  style="Muted.TLabel", wraplength=1000).pack(anchor="w", pady=(0, 6))
        row = ttk.Frame(site, style="Card.TFrame")
        row.pack(fill="x")
        ttk.Label(row, text="Latitudine", style="Card.TLabel").pack(side="left")
        ttk.Entry(row, textvariable=self.site_lat, width=12).pack(side="left", padx=(6, 14))
        ttk.Label(row, text="Longitudine", style="Card.TLabel").pack(side="left")
        ttk.Entry(row, textvariable=self.site_lon, width=12).pack(side="left", padx=(6, 14))
        ttk.Button(row, text="Salva posizione", style="Primary.TButton", command=self.save_site_manual).pack(
            side="left", padx=(0, 6))
        ttk.Button(row, text="Da Internet", style="TButton", command=self.site_from_internet).pack(
            side="left", padx=(0, 6))
        ttk.Button(row, text="Dal Vespera", style="TButton", command=self.site_from_vespera).pack(side="left")
        ttk.Label(site, textvariable=self.site_text, style="Muted.TLabel").pack(anchor="w", pady=(6, 0))

        upd = self._card(self.tab_imp, "Aggiornamenti app")
        ttk.Label(upd, text="Controllo automatico all'avvio. Qui puoi forzarlo.",
                  style="Muted.TLabel").pack(anchor="w")
        ttk.Button(upd, text="Controlla aggiornamenti", style="Primary.TButton",
                   command=self.check_updates_now).pack(anchor="w", pady=(8, 0))
        ttk.Label(upd, textvariable=self.update_text, style="Muted.TLabel").pack(anchor="w", pady=(6, 0))

    def _save_network(self) -> None:
        self._save_settings()
        self._refresh_shared_ip()
        self.set_status("IP e porte salvati.", "ok")

    def _site(self) -> tuple[float, float] | None:
        try:
            lat = float(self.site_lat.get().strip().replace(",", "."))
            lon = float(self.site_lon.get().strip().replace(",", "."))
        except ValueError:
            return None
        return sky._valid(lat, lon)

    def _site_line(self, lat: float, lon: float) -> str:
        source = {
            "manual": "Posizione inserita a mano",
            "internet": "Posizione approssimata da Internet",
            "vespera": "Posizione letta dal Vespera",
        }.get(self.site_source, "")
        text = f"{abs(lat):.2f}° {'N' if lat >= 0 else 'S'}  {abs(lon):.2f}° {'E' if lon >= 0 else 'O'}"
        return (text + " · " + source if source else text).replace(".", ",")

    def _show_site_source(self) -> None:
        if self._site() is None:
            self.site_text.set("Nessuna posizione salvata")
            return
        self.site_text.set(self._site_line(*self._site()))

    def _fill_site_fields(self) -> None:
        self._show_site_source()

    def _set_site(self, lat: float, lon: float, source: str) -> None:
        self.site_lat.set(f"{lat:.5f}")
        self.site_lon.set(f"{lon:.5f}")
        self.site_source = source
        self._show_site_source()
        self._save_settings()
        self.refresh_visibility()
        self.refresh_favorites()

    def save_site_manual(self) -> None:
        site = self._site()
        if site is None:
            self.set_status("Coordinate non valide (es. 41.00 e 16.86).", "warn")
            return
        self._set_site(site[0], site[1], "manual")
        self.set_status("Posizione salvata.", "ok")

    def site_from_internet(self) -> None:
        self.site_text.set("Cerco la posizione…")

        def work() -> None:
            try:
                lat, lon = sky.site_from_internet()
                self.queue.put(("site_found", lat, lon, "internet"))
            except Exception:
                self.queue.put(("error", "Posizione da Internet non disponibile."))

        threading.Thread(target=work, daemon=True).start()

    def site_from_vespera(self) -> None:
        found = sky.parse_site(self.state.get("telescope"))
        if found is None:
            self.set_status("Il Vespera non riporta la posizione (connetti e aggiorna lo stato).", "warn")
            return
        self._set_site(found[0], found[1], "vespera")

    def _capture_vespera_site(self, telescope: dict) -> None:
        """Come APK: prende la posizione dal Vespera se non l'hai messa tu."""
        if self.site_source in ("manual", "internet"):
            return
        found = sky.parse_site(telescope)
        if found is None:
            return
        old = self._site()
        if old and abs(old[0] - found[0]) < 1e-4 and abs(old[1] - found[1]) < 1e-4:
            return
        self._set_site(found[0], found[1], "vespera")

    def log(self, text: str) -> None:
        line = (text or "").strip()
        if not line:
            return
        self.logbox.insert("end", f"{time.strftime('%H:%M:%S')}  {line}\n")
        self.logbox.see("end")

    def set_status(self, text: str, kind: str = "info") -> None:
        colors = {"info": MUTED, "ok": OK, "warn": WARN, "err": ERR}
        self.status_text.set(text)
        try:
            self.status_label.configure(foreground=colors.get(kind, FG))
        except tk.TclError:
            pass

    def _refresh_shared_ip(self) -> None:
        host = self.pi_host.get().strip()
        port = self.adb_port.get().strip() or "5555"
        if host:
            ftp = self.ftp_hd_port.get().strip() if self.ftp_source.get() == "hd" else self.ftp_vespera_port.get().strip()
            self.shared_ip_text.set(f"IP da Impostazioni: {host} · FTP {ftp}")
            self.conn_address.set(f"Pi {host} · ADB {port}  (IP e porte in Impostazioni)")
        else:
            self.shared_ip_text.set("IP non impostato. Inseriscilo in Impostazioni.")
            self.conn_address.set("IP del Pi non impostato: inseriscilo in Impostazioni.")

    def serial(self) -> str:
        host = self.pi_host.get().strip()
        port = self.adb_port.get().strip() or "5555"
        return f"{host}:{port}"

    def ensure_bridge(self) -> AdbBridge:
        adb = locate_adb(Path(self.scrcpy_path.get()) if self.scrcpy_path.get().strip() else None)
        if adb is None:
            raise AdbError("adb.exe non trovato (mettilo con scrcpy o nel PATH).")
        bridge = AdbBridge(adb, self.serial())
        self.bridge = bridge
        return bridge

    def run_job(self, job) -> None:
        if self._busy:
            self.set_status("Operazione già in corso.", "warn")
            return
        self._busy = True

        def wrap() -> None:
            try:
                job()
            except AdbError as exc:
                self.queue.put(("error", str(exc)))
            except preview.PreviewError as exc:
                self.queue.put(("error", str(exc)))
            except Exception:
                self.queue.put(("error", "Errore imprevisto — vedi registro"))
                self.queue.put(("log", traceback.format_exc()))
            finally:
                self._busy = False

        threading.Thread(target=wrap, daemon=True).start()

    def connect_adb(self) -> None:
        def job() -> None:
            bridge = self.ensure_bridge()
            self.queue.put(("status", f"Connessione ADB a {bridge.serial}…", "info"))
            msg = bridge.connect()
            self.queue.put(("log", msg))
            ack = bridge.send_command("ping", wait_ack=15)
            self.queue.put(("log", ack))
            self.queue.put(("status", f"ADB ok · RemoteBridge {ack}", "ok"))
            self.queue.put(("refresh",))

        self.run_job(job)
        self._save_settings()

    def tel_command(self, action: str) -> None:
        """Comando telescopio con conferma; init/resume portano la posizione."""
        info = TEL_ACTIONS.get(action)
        if not info:
            return
        label, question, danger = info
        if self._busy:
            messagebox.showwarning(
                "Comando telescopio",
                "C'è già un'operazione in corso: aspetta che finisca e riprova.",
                parent=self,
            )
            return
        ask = messagebox.askyesno if not danger else messagebox.askokcancel
        if not ask(
            f"Conferma: {label}",
            question + "\n\nInviare il comando al telescopio?",
            icon="warning" if danger else "question",
            default="no" if ask is messagebox.askyesno else "cancel",
            parent=self,
        ):
            self.set_status(f"{label}: annullato.", "info")
            return
        line = f"cmd|telescope|{action}"
        if action in {"init", "resume"}:
            site = self._site()
            if site:
                line += "|" + json.dumps({"lat": round(site[0], 6), "lon": round(site[1], 6)},
                                         separators=(",", ":"))
        self.cmd(line, wait=150 if action in {"init", "resume"} else 45)

    def _tel_result(self, action: str, ok: bool, ack: str) -> None:
        label = TEL_ACTIONS.get(action, (action,))[0]
        if ok:
            messagebox.showinfo(f"{label}", f"{label}: comando accettato dal telescopio.\n\n{ack}", parent=self)
            return
        messagebox.showerror(f"{label}: non riuscito", tel_error_text(ack), parent=self)

    def cmd(self, line: str, wait: float = 40) -> None:
        def job() -> None:
            bridge = self.ensure_bridge()
            try:
                bridge.connect()
            except AdbError:
                pass
            self.queue.put(("status", f"Invio {line}…", "info"))
            self.queue.put(("log", f"> {line}"))
            tel_action = ""
            if line.startswith("cmd|telescope|") and not line.startswith("cmd|telescope|observe"):
                tel_action = line.split("|")[2]
            try:
                ack = bridge.send_command(line, wait_ack=wait)
            except AdbError as exc:
                if tel_action:
                    self.queue.put(("tel_done", tel_action, False, str(exc)))
                raise
            self.queue.put(("log", ack))
            kind = "ok" if ack.startswith("OK|") else "err"
            self.queue.put(("status", ack[:120], kind))
            if tel_action:
                self.queue.put(("tel_done", tel_action, kind == "ok", ack))
            if line.startswith("cmd|plan|load"):
                if kind == "ok":
                    msg = "Piano caricato sull'Helper: parte da solo agli orari."
                elif "unknown" in ack:
                    msg = f"Piano non caricato: {ack} (aggiorna Vespera Helper ≥ 0.8.23)"
                else:
                    msg = f"Piano non caricato: {ack}"
                self.queue.put(("plan_sent", msg))
            if line.startswith("cmd|telescope|observe"):
                done = getattr(self, "_obs_pending", "")
                self.queue.put(("obs_done", done if kind == "ok" else f"Osservazione non partita: {ack}"))
            self.queue.put(("refresh",))

        self.run_job(job)

    def refresh_state(self) -> None:
        def job() -> None:
            bridge = self.ensure_bridge()
            try:
                bridge.connect()
            except AdbError:
                pass
            state = bridge.get_state()
            self.queue.put(("state", state))

        self.run_job(job)

    def apply_system(self) -> None:
        payload = {key: var.get() for key, var in self._sys_vars.items()}
        line = "set|system|" + json.dumps(payload, separators=(",", ":"))
        self.cmd(line, wait=20)

    def apply_telegram(self) -> None:
        """Interruttore generale: accende o spegne tutti gli avvisi."""
        if getattr(self, "_tg_binding", False):
            return
        enabled = bool(self.tg_enabled.get())
        self._tg_binding = True
        try:
            for var in self._tg_vars.values():
                var.set(enabled)
        finally:
            self._tg_binding = False
        self._send_telegram()

    def apply_telegram_event(self) -> None:
        """Singolo avviso cambiato: il generale resta acceso se almeno uno è attivo."""
        if getattr(self, "_tg_binding", False):
            return
        self._tg_binding = True
        try:
            self.tg_enabled.set(any(v.get() for v in self._tg_vars.values()))
        finally:
            self._tg_binding = False
        self._send_telegram()

    def _send_telegram(self) -> None:
        payload = {"enabled": bool(self.tg_enabled.get())}
        for key, var in self._tg_vars.items():
            payload[key] = bool(var.get())
        line = "set|telegram|" + json.dumps(payload, separators=(",", ":"))
        self._update_tg_label(self.state.get("telegram") or {})
        self.cmd(line, wait=20)

    def _update_tg_label(self, tg: dict) -> None:
        conf = "Configurato" if tg.get("configured") else "Non configurato"
        active = sum(1 for v in self._tg_vars.values() if v.get())
        total = len(self._tg_vars)
        if not self.tg_enabled.get() or active == 0:
            line = f"{conf}  ·  notifiche spente"
        else:
            line = f"{conf}  ·  notifiche attive {active}/{total}"
        err = tg.get("lastError") or ""
        if err:
            line += f"  ·  err {err}"
        self.tg_configured.set(line)

    def _apply_state(self, state: dict) -> None:
        self.state = state or {}
        if hasattr(self, "plan_helper"):
            self._show_helper_plan(self.state)
        wifi = self.state.get("wifi") or {}
        tel = self.state.get("telescope") or {}
        hd = self.state.get("hd") or {}
        syss = self.state.get("system") or {}
        tg = self.state.get("telegram") or {}
        self.wifi_status.set(
            f"{wifi.get('status', '—')}  ·  {wifi.get('ssid') or 'n/d'}  ·  {wifi.get('bssid') or ''}"
        )
        self._paint_bars(self.state)
        tel_rows = self._tel_details(tel) if tel.get("reachable") else []
        if tel.get("reachable") and tel_rows:
            # Le righe dettagliate contengono già modello, stato, target, stack e batteria.
            self.tel_status.set("")
        elif tel.get("reachable"):
            self.tel_status.set(
                f"{tel.get('model') or 'Vespera'}  ·  {tel.get('state') or '—'}  ·  "
                f"{tel.get('observationStatus') or '—'}  ·  target {tel.get('targetName') or '—'}  ·  "
                f"stack {tel.get('stackingCount', 0)}  ·  batt {tel.get('batteryPercent', -1)}%"
            )
        else:
            self.tel_status.set(f"Non raggiungibile ({tel.get('error') or '—'})")
        if hasattr(self, "tel_status_label"):
            if self.tel_status.get():
                if not self.tel_status_label.winfo_manager():
                    self.tel_status_label.pack(anchor="w", before=self._tel_buttons_row)
            else:
                self.tel_status_label.pack_forget()
        self._render_tel_details(tel_rows)
        space = hd.get("spaceLabel") or (f"{hd.get('spacePercent')}%" if hd.get("spaceKnown") else "")
        self.hd_status.set(
            f"{'Montato' if hd.get('mounted') else 'Spento/smontato'}  ·  {hd.get('label') or hd.get('uuid') or '—'}  ·  {space}"
        )
        self._apply_sync(self.state.get("sync") if self.state else None)
        for key, var in self._sys_vars.items():
            if key in syss:
                var.set(bool(syss[key]))
        keys = [k for k, _ in TELEGRAM_FLAGS]
        enabled = bool(tg.get("enabled")) if "enabled" in tg else any(bool(tg.get(k)) for k in keys)
        self._tg_binding = True
        try:
            self.tg_enabled.set(enabled)
            for key, var in self._tg_vars.items():
                # Se l'Helper non riporta il singolo flag, segue il generale.
                var.set(bool(tg.get(key, enabled)))
        finally:
            self._tg_binding = False
        self._update_tg_label(tg)
        self._capture_vespera_site(tel)
        if self._obs_target is not None:
            self._obs_session = sky.find_session(self.state, self._obs_target.name, self._obs_target.query)
            self._show_obs_session()
        ver = self.state.get("appVersion", "?")
        self.set_status(f"Stato Pi aggiornato (Helper {ver}).", "ok")

    @staticmethod
    def _tel_details(tel: dict) -> list:
        """Righe (etichetta, valore) del pannello Stato dell'Helper; Helper < 0.8.36 → dai singoli campi."""
        rows = tel.get("details")
        if isinstance(rows, list):
            out = [(str(r.get("label", "")), str(r.get("value", ""))) for r in rows if isinstance(r, dict)]
            return [(k, v) for k, v in out if v.strip()]
        out = []

        def add(label: str, value) -> None:
            if value is not None and str(value).strip():
                out.append((label, str(value).strip()))

        for label, key in (
            ("Modello", "model"), ("Stato", "state"), ("Osservazione", "observationStatus"),
            ("Operazione", "operationType"), ("Passo", "step"), ("Tracking", "tracking"),
            ("Motori", "motors"), ("Fuoco", "focus"), ("Bersaglio", "targetName"),
            ("Coordinate", "coordinates"), ("Posizione", "location"),
        ):
            add(label, tel.get(key))
        if (tel.get("stackingCount") or 0) > 0:
            add("Stack", tel.get("stackingCount"))
        if (tel.get("exposureMicroSec") or 0) > 0:
            add("Esposizione", f"{tel['exposureMicroSec'] / 1_000_000:.1f} s")
        if (tel.get("gain") or 0) > 0:
            add("Gain", tel.get("gain"))
        for label, key in (("Filtro", "filter"), ("Temperatura", "temperature"), ("Firmware", "firmware")):
            add(label, tel.get(key))
        pct = tel.get("storageUsedPercent", -1)
        add("Foto interne", tel.get("storage") or (f"{pct}%" if isinstance(pct, int) and pct >= 0 else ""))
        batt = tel.get("batteryPercent", -1)
        if isinstance(batt, int) and batt >= 0:
            status = tel.get("batteryStatus") or ""
            add("Batteria", f"{batt}%" + (f" ({status})" if status else ""))
        return out

    def _render_tel_details(self, rows: list) -> None:
        if not hasattr(self, "tel_details_frame") or rows == self._tel_details_rows:
            return
        self._tel_details_rows = rows
        for child in self.tel_details_frame.winfo_children():
            child.destroy()
        if not rows:
            ttk.Label(self.tel_details_frame, text="—", style="Muted.TLabel").grid(row=0, column=0, sticky="w")
            return
        # Due colonne di coppie etichetta/valore, come il pannello Stato dell'Helper.
        half = (len(rows) + 1) // 2
        for i, (label, value) in enumerate(rows):
            r, c = (i, 0) if i < half else (i - half, 2)
            ttk.Label(self.tel_details_frame, text=f"{label}:", style="Muted.TLabel").grid(
                row=r, column=c, sticky="nw", padx=(0 if c == 0 else 24, 8), pady=1
            )
            ttk.Label(self.tel_details_frame, text=value, style="Card.TLabel", wraplength=320).grid(
                row=r, column=c + 1, sticky="nw", pady=1
            )

    def ftp_endpoint(self) -> tuple[str, int]:
        host = self.pi_host.get().strip()
        if not host:
            raise preview.PreviewError("IP non impostato. Inseriscilo in Impostazioni.")
        if self.ftp_source.get() == "hd":
            raw = self.ftp_hd_port.get().strip() or "2121"
            default = 2121
        else:
            raw = self.ftp_vespera_port.get().strip() or "2122"
            default = 2122
        try:
            port = int(raw)
        except ValueError:
            _host, port = preview.parse_endpoint(raw, default)
        return host, port

    def load_objects(self, from_tab: bool = False) -> None:
        def job() -> None:
            host, port = self.ftp_endpoint()
            self.queue.put(("status", f"Elenco oggetti in aggiornamento su {host}:{port}…", "info"))
            online = preview.reachable(host, port)
            self.queue.put(("log", f"FTP {host}:{port} {'online' if online else 'offline'}"))
            if not online:
                raise preview.PreviewError(f"FTP offline: {host}:{port}")
            objects = preview.list_objects(host, port, all_roots=self.ftp_source.get() == "hd")
            self.queue.put(("objects", objects, f"{host}:{port}", from_tab))

        self.run_job(job)
        self._save_settings()

    def on_object_select(self, _event=None) -> None:
        sel = self.obj_list.curselection()
        if not sel:
            return
        index = sel[0]
        if index >= len(self._objects):
            return
        obj = self._objects[index]

        def job() -> None:
            host, port = self.ftp_endpoint()
            detail = preview.load_object_preview(host, port, obj.folder)
            self.queue.put(("object_detail", detail))
            if not detail.output_path:
                raise preview.PreviewError("Nessuna anteprima (*-output.jpg) in questa cartella.")
            data = preview.download_bytes(host, port, detail.output_path)
            self.queue.put(("preview_image", data, detail))

        self.run_job(job)

    def _on_objects(self, objects: list[preview.SkyObject], key: str = "", from_tab: bool = False) -> None:
        # Confronto solo con quanto già caricato dalla stessa sorgente.
        before = self._loaded_signatures if key == self._loaded_key else {}
        has_baseline = bool(before)
        added = updated = 0
        self._objects = objects
        self.obj_list.delete(0, "end")
        for obj in objects:
            suffix = f"  ·  {obj.when}" if obj.when else ""
            mark = ""
            color = FG
            if has_baseline:
                old = before.get(obj.folder)
                if old is None:
                    mark, color = "  ·  nuovo", OK
                    added += 1
                elif old != obj.signature:
                    mark, color = "  ·  in aggiornamento", WARN
                    updated += 1
            self.obj_list.insert("end", f"{obj.label}{suffix}{mark}")
            if mark:
                self.obj_list.itemconfig("end", foreground=color)
        self._loaded_signatures = {obj.folder: obj.signature for obj in objects}
        self._loaded_key = key
        text = f"{len(objects)} oggetti"
        if has_baseline:
            parts = []
            if added:
                parts.append(f"{added} nuovi")
            if updated:
                parts.append(f"{updated} in aggiornamento")
            text += "  ·  " + ("  ·  ".join(parts) if parts else "nessuna novità")
        changed = bool(added or updated)
        if changed and from_tab:
            self.log(f"Osservazioni: {text}")
        self.set_status(text + ".", "warn" if changed else "ok")

    def _on_preview_image(self, data: bytes, detail: preview.SkyObject) -> None:
        self._png = data
        self._preview_detail = detail
        self.meta.set(f"{detail.label}  ·  {detail.output_path}")
        self._redraw()
        self.set_status("Anteprima caricata — clic per regolare / salvare.", "ok")

    def _open_preview_editor(self, _event=None) -> None:
        if not self._png:
            self.set_status("Carica prima un'anteprima dall'elenco oggetti.", "warn")
            return
        if self._preview_editor is not None and self._preview_editor.winfo_exists():
            self._preview_editor.lift()
            self._preview_editor.focus_force()
            return
        detail = self._preview_detail
        label = detail.label if detail else "Anteprima"
        remote = detail.output_path if detail else "vespera-preview.jpg"
        name = Path(remote).name or "vespera-preview.jpg"
        if not Path(name).suffix:
            name += ".jpg"
        stem = Path(name).stem
        if not stem.lower().endswith("-edited"):
            name = f"{stem}-edited{Path(name).suffix or '.jpg'}"
        self._preview_editor = PreviewEditor(
            self,
            self._png,
            title=f"Editor · {label}",
            suggested_name=name,
        )

    def _redraw(self) -> None:
        w = max(self.canvas.winfo_width(), 2)
        h = max(self.canvas.winfo_height(), 2)
        self.canvas.delete("all")
        if not self._png:
            self.canvas.create_text(
                w // 2,
                h // 2,
                text="Seleziona un oggetto per l'ultimo *-output.jpg\n"
                "(nessuno stacking)\n\nPoi clicca l'immagine per livelli / salva",
                fill=MUTED,
                font=("Segoe UI", 12),
                justify="center",
            )
            return
        try:
            from PIL import Image, ImageTk

            image = Image.open(io.BytesIO(self._png))
            image.thumbnail((max(w - 12, 1), max(h - 12, 1)), Image.Resampling.LANCZOS)
            self._photo = ImageTk.PhotoImage(image)
        except Exception:
            try:
                raw = tk.PhotoImage(data=base64.b64encode(self._png).decode("ascii"))
                self._photo = raw
            except tk.TclError as exc:
                self.canvas.create_text(w // 2, h // 2, text=str(exc), fill=ERR)
                return
        self.canvas.create_image(w // 2, h // 2, image=self._photo, anchor="center")
        self.canvas.create_text(
            w // 2,
            h - 14,
            text="Clic = editor (autostretch · livelli · salva · condividi)",
            fill="#B0BEC5",
            font=("Segoe UI", 9),
        )
    def browse_scrcpy(self) -> None:
        picked = filedialog.askopenfilename(
            title="scrcpy.exe",
            filetypes=[("scrcpy", "scrcpy.exe"), ("Programmi", "*.exe")],
        )
        if picked:
            self.scrcpy_path.set(picked)
            self._save_settings()

    def start_scrcpy(self) -> None:
        if self._scrcpy is not None and self._scrcpy.poll() is None:
            self.set_status("scrcpy già aperto.", "warn")
            return
        raw = self.scrcpy_path.get().strip()
        exe = Path(raw) if raw else locate_scrcpy()
        if exe is None or not exe.is_file():
            messagebox.showerror("scrcpy", "Indica scrcpy.exe")
            return
        self.scrcpy_path.set(str(exe))
        serial = self.serial()
        adb = locate_adb(exe)
        if adb:
            try:
                AdbBridge(adb, serial).connect()
            except AdbError as exc:
                self.log(str(exc))
        cmd = build_scrcpy_command(
            str(exe),
            serial,
            max_size=self.max_size.get(),
            bitrate=self.bitrate.get(),
            no_audio=self.no_audio.get(),
            video_encoder=SOFTWARE_ENCODER if self.software_encoder.get() else "",
            help_text=scrcpy_help(exe),
            title="Vespera Pi",
        )
        env = os.environ.copy()
        env["PATH"] = str(exe.parent) + os.pathsep + env.get("PATH", "")
        if adb:
            env["ADB"] = str(adb)
        self.log(" ".join(cmd))
        try:
            self._scrcpy = subprocess.Popen(
                cmd,
                cwd=str(exe.parent),
                env=env,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=CREATE_NO_WINDOW,
            )
        except OSError as exc:
            self.set_status(str(exc), "err")
            return
        self.set_status("scrcpy avviato.", "ok")
        self._save_settings()

    def stop_scrcpy(self) -> None:
        proc = self._scrcpy
        if proc is None or proc.poll() is not None:
            self.set_status("scrcpy non in esecuzione.", "info")
            return
        proc.terminate()
        self.set_status("Chiusura scrcpy…", "info")

    def _poll(self) -> None:
        if self._closing:
            return
        try:
            while True:
                item = self.queue.get_nowait()
                kind = item[0]
                if kind == "log":
                    self.log(item[1])
                elif kind == "status":
                    self.set_status(item[1], item[2] if len(item) > 2 else "info")
                elif kind == "error":
                    self.set_status(item[1], "err")
                    self.log(item[1])
                elif kind == "state":
                    self._apply_state(item[1])
                elif kind == "refresh":
                    self.after(200, self.refresh_state)
                elif kind == "objects":
                    self._on_objects(*item[1:])
                elif kind == "object_detail":
                    detail = item[1]
                    self.meta.set(f"{detail.label}  ·  {len(detail.images)} immagini")
                elif kind == "preview_image":
                    self._on_preview_image(item[1], item[2])
                elif kind == "obs_done":
                    self.obs_result.set(item[1])
                elif kind == "tel_done":
                    self._tel_result(item[1], item[2], item[3])
                elif kind == "update_text":
                    self.update_text.set(item[1])
                elif kind == "obs_hits":
                    self._on_obs_hits(item[1], item[2])
                elif kind == "obs_target":
                    self._on_obs_target(item[1], item[2])
                elif kind == "obs_error":
                    self._on_obs_error(item[1], item[2])
                elif kind == "visibility":
                    self._on_visibility(item[1], item[2])
                elif kind == "favorites":
                    self._on_favorites(item[1], item[2])
                elif kind == "plan":
                    self._on_plan(item[1])
                elif kind == "plan_sent":
                    self.plan_helper.set(item[1])
                elif kind == "site_found":
                    self._set_site(item[1], item[2], item[3])
                elif kind == "update":
                    remote = item[1]
                    ver = remote.get("version")
                    if messagebox.askyesno(
                        "Aggiornamento disponibile",
                        f"È disponibile Vespera Control {ver} "
                        f"(ora hai {LOCAL_VERSION}).\n\n"
                        f"Aprire la cartella Mega per scaricare?",
                        parent=self,
                    ):
                        from vespera_control import updates as upd

                        upd.open_download_page()
        except queue.Empty:
            pass
        self.after(80, self._poll)

    def _load_settings(self) -> None:
        path = settings_path()
        if not path.is_file():
            return
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return
        if not isinstance(raw, dict):
            return
        self.pi_host.set(str(raw.get("pi_host") or self.pi_host.get()))
        self.adb_port.set(str(raw.get("adb_port") or "5555"))
        self.scrcpy_path.set(str(raw.get("scrcpy_path") or ""))
        self.max_size.set(str(raw.get("max_size") or "1920"))
        self.bitrate.set(str(raw.get("bitrate") or "8M"))
        self.software_encoder.set(bool(raw.get("software_encoder", True)))
        self.no_audio.set(bool(raw.get("no_audio", True)))
        self.ftp_hd_port.set(_saved_port(raw.get("ftp_hd_port") or raw.get("ftp_hd"), "2121"))
        self.ftp_vespera_port.set(_saved_port(raw.get("ftp_vespera_port") or raw.get("ftp_vespera"), "2122"))
        self.ftp_source.set(str(raw.get("ftp_source") or "hd"))
        try:
            lat = float(raw.get("site_lat"))
            lon = float(raw.get("site_lon"))
            self.site_lat.set(f"{lat:.5f}")
            self.site_lon.set(f"{lon:.5f}")
            self.site_source = str(raw.get("site_source") or "manual")
        except (TypeError, ValueError):
            self.site_source = ""
        plans = raw.get("plans")
        if isinstance(plans, list):
            self.plans = [p for p in plans if isinstance(p, dict) and isinstance(p.get("steps"), list)]
            if hasattr(self, "plans_list"):
                self._show_saved_plans()
        favs = raw.get("favorites")
        if isinstance(favs, list):
            self.favorites = [f for f in favs if isinstance(f, dict) and f.get("name")]
        self._show_site_source()

    def _save_settings(self) -> None:
        payload = {
            "pi_host": self.pi_host.get().strip(),
            "adb_port": self.adb_port.get().strip() or "5555",
            "scrcpy_path": self.scrcpy_path.get().strip(),
            "max_size": self.max_size.get().strip(),
            "bitrate": self.bitrate.get().strip(),
            "software_encoder": self.software_encoder.get(),
            "no_audio": self.no_audio.get(),
            "ftp_hd_port": self.ftp_hd_port.get().strip() or "2121",
            "ftp_vespera_port": self.ftp_vespera_port.get().strip() or "2122",
            "ftp_source": self.ftp_source.get(),
            "favorites": self.favorites,
            "plans": self.plans,
        }
        site = self._site()
        if site:
            payload["site_lat"], payload["site_lon"] = site
            payload["site_source"] = self.site_source or "manual"
        try:
            atomic_write(settings_path(), json.dumps(payload, ensure_ascii=False, indent=2))
        except OSError as exc:
            self.log(f"Salvataggio impostazioni: {exc}")

    def on_close(self) -> None:
        self._closing = True
        self._save_settings()
        self.destroy()


_IT_DAYS = ("lun", "mar", "mer", "gio", "ven", "sab", "dom")
_IT_MONTHS = ("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic")


def _it_day(d) -> str:
    return f"{_IT_DAYS[d.weekday()]} {d.day} {_IT_MONTHS[d.month - 1]}"


def _saved_port(value, default: str) -> str:
    raw = str(value or "").strip()
    if not raw:
        return default
    if ":" in raw:
        _host, port_s = raw.rsplit(":", 1)
        if port_s.isdigit():
            return port_s
    if raw.isdigit():
        return raw
    return default


def main() -> None:
    App().mainloop()


if __name__ == "__main__":
    main()
