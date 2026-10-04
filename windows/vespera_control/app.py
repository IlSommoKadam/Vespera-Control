# -*- coding: utf-8 -*-
"""Vespera Control — app Windows all-in-one (Helper ADB + Anteprima FTP + scrcpy)."""

from __future__ import annotations

import base64
import io
import json
import os
import queue
import subprocess
import sys
import threading
import time
import tkinter as tk
import traceback
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

# Pacchetto locale
ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from vespera_control.adb_bridge import AdbBridge, AdbError, locate_adb  # noqa: E402
from vespera_control import preview_ftp as preview  # noqa: E402
from vespera_control.scrcpy_util import (  # noqa: E402
    SOFTWARE_ENCODER,
    build_scrcpy_command,
    locate_scrcpy,
    scrcpy_help,
)

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
TAB_WELL = "#9AA9B5"
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


class App(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("Vespera Control")
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
        self._objects: list[preview.SkyObject] = []
        self._sys_vars: dict[str, tk.BooleanVar] = {}

        self.pi_host = tk.StringVar(value="192.168.1.4")
        self.adb_port = tk.StringVar(value="5555")
        self.scrcpy_path = tk.StringVar()
        self.max_size = tk.StringVar(value="1920")
        self.bitrate = tk.StringVar(value="8M")
        self.software_encoder = tk.BooleanVar(value=True)
        self.no_audio = tk.BooleanVar(value=True)
        self.ftp_hd = tk.StringVar(value="192.168.1.4:2121")
        self.ftp_vespera = tk.StringVar(value="192.168.1.4:2122")
        self.ftp_source = tk.StringVar(value="hd")
        self.status_text = tk.StringVar(value="Indica l'IP del Pi e connetti ADB.")
        self.meta = tk.StringVar(value="")
        self.wifi_status = tk.StringVar(value="—")
        self.tel_status = tk.StringVar(value="—")
        self.hd_status = tk.StringVar(value="—")

        self._style()
        self._build()
        self._load_settings()
        if not self.scrcpy_path.get().strip():
            found = locate_scrcpy()
            if found:
                self.scrcpy_path.set(str(found))
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self.after(100, self._poll)
        self.after(1500, self.refresh_state)
        self.after(2500, self._check_updates)

    def _set_app_icon(self) -> None:
        """Stessa icona di VesperaHelper (vespera_launcher_icon)."""
        ico = ROOT / "vespera.ico"
        png = ROOT / "vespera_launcher_icon.png"
        if ico.is_file():
            try:
                self.iconbitmap(default=str(ico))
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

        def done(remote, message) -> None:
            if remote:
                self.queue.put(
                    (
                        "update",
                        remote,
                    )
                )
            elif message:
                self.queue.put(("log", f"Aggiornamenti: {message}"))

        upd.check_async(done)

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
        style.configure("TNotebook", background=BG, borderwidth=0, tabmargins=(4, 4, 4, 0))
        style.configure(
            "TNotebook.Tab",
            background=TAB_IDLE,
            foreground="#263238",
            padding=(14, 8),
            font=("Segoe UI", 10, "bold"),
        )
        style.map(
            "TNotebook.Tab",
            background=[("selected", PRIMARY), ("active", TAB_WELL)],
            foreground=[("selected", "#ffffff"), ("active", FG)],
        )
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
            text="stesso menu e tema di VesperaHelper  ·  + Anteprima / Schermo",
            style="Sub.TLabel",
        ).pack(side="left", padx=(16, 0))

        bar = ttk.Frame(self, padding=(16, 0, 16, 8))
        bar.pack(fill="x")
        ttk.Label(bar, text="Pi IP").pack(side="left")
        ttk.Entry(bar, textvariable=self.pi_host, width=16).pack(side="left", padx=(6, 8))
        ttk.Label(bar, text="ADB").pack(side="left")
        ttk.Entry(bar, textvariable=self.adb_port, width=6).pack(side="left", padx=(6, 8))
        ttk.Button(bar, text="Connetti ADB", style="Primary.TButton", command=self.connect_adb).pack(
            side="left", padx=(0, 6)
        )
        ttk.Button(bar, text="Aggiorna stato", style="TButton", command=self.refresh_state).pack(side="left")

        self.notebook = ttk.Notebook(self)
        self.notebook.pack(fill="both", expand=True, padx=16, pady=(0, 8))

        self.tab_conn = ttk.Frame(self.notebook, padding=12)
        self.tab_foto = ttk.Frame(self.notebook, padding=12)
        self.tab_tel = ttk.Frame(self.notebook, padding=12)
        self.tab_sys = ttk.Frame(self.notebook, padding=12)
        self.tab_tg = ttk.Frame(self.notebook, padding=12)
        self.tab_prev = ttk.Frame(self.notebook, padding=12)
        self.tab_scr = ttk.Frame(self.notebook, padding=12)
        # Ordine speculare a Helper, poi le due tab solo-remote
        for tab, title in (
            (self.tab_conn, "Connessioni"),
            (self.tab_foto, "Foto / Hard Disk"),
            (self.tab_tel, "Telescopio"),
            (self.tab_sys, "Sistema"),
            (self.tab_tg, "Notifiche"),
            (self.tab_prev, "Anteprima"),
            (self.tab_scr, "Schermo Pi"),
        ):
            self.notebook.add(tab, text=title)

        self._build_conn()
        self._build_foto()
        self._build_tel()
        self._build_sys()
        self._build_tg()
        self._build_preview()
        self._build_scrcpy()

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

    def _card(self, parent: ttk.Frame, title: str) -> ttk.Frame:
        card = ttk.Frame(parent, style="Card.TFrame", padding=12)
        card.pack(fill="x", pady=(0, 10))
        ttk.Label(card, text=title, style="Section.TLabel").pack(anchor="w", pady=(0, 8))
        return card

    def _build_conn(self) -> None:
        card = self._card(self.tab_conn, "Wi‑Fi Vespera / Singularity")
        ttk.Label(card, textvariable=self.wifi_status, style="Card.TLabel").pack(anchor="w")
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

    def _build_tel(self) -> None:
        card = self._card(self.tab_tel, "Comandi telescopio")
        ttk.Label(card, textvariable=self.tel_status, style="Card.TLabel", wraplength=900).pack(anchor="w")
        row = ttk.Frame(card, style="Card.TFrame")
        row.pack(fill="x", pady=(10, 0))
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
            wait = 120 if action == "init" else 45
            ttk.Button(
                row,
                text=label,
                style=style,
                command=lambda a=action, w=wait: self.cmd(f"cmd|telescope|{a}", wait=w),
            ).pack(side="left", padx=(0, 6))

    def _build_preview(self) -> None:
        top = ttk.Frame(self.tab_prev)
        top.pack(fill="x")
        ttk.Label(top, text="HD").pack(side="left")
        ttk.Entry(top, textvariable=self.ftp_hd, width=22).pack(side="left", padx=(6, 12))
        ttk.Label(top, text="Vespera").pack(side="left")
        ttk.Entry(top, textvariable=self.ftp_vespera, width=22).pack(side="left", padx=(6, 12))
        ttk.Radiobutton(top, text="HD", variable=self.ftp_source, value="hd").pack(side="left")
        ttk.Radiobutton(top, text="Vespera", variable=self.ftp_source, value="vespera").pack(side="left", padx=(0, 8))
        ttk.Button(top, text="Elenco oggetti", style="Primary.TButton", command=self.load_objects).pack(side="left")

        body = ttk.Frame(self.tab_prev)
        body.pack(fill="both", expand=True, pady=(10, 0))
        left = ttk.Frame(body)
        left.pack(side="left", fill="y")
        self.obj_list = tk.Listbox(
            left,
            width=42,
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
        )
        self.obj_list.pack(fill="y", expand=True)
        self.obj_list.bind("<<ListboxSelect>>", self.on_object_select)
        right = ttk.Frame(body)
        right.pack(side="left", fill="both", expand=True, padx=(12, 0))
        ttk.Label(right, textvariable=self.meta, style="Sub.TLabel").pack(anchor="w")
        self.canvas = tk.Canvas(right, bg=INK, highlightthickness=1, highlightbackground=PANEL_STROKE)
        self.canvas.pack(fill="both", expand=True, pady=(6, 0))
        self.canvas.bind("<Configure>", lambda _e: self._redraw())

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
        card = self._card(self.tab_tg, "Telegram (flags eventi sul Pi)")
        self.tg_configured = tk.StringVar(value="Non configurato")
        ttk.Label(card, textvariable=self.tg_configured, style="Card.TLabel").pack(anchor="w")
        ttk.Label(
            card,
            text="Token/chat si gestiscono meglio sull'app Pi. Qui vedi solo lo stato mascherato.",
            style="Muted.TLabel",
            wraplength=800,
        ).pack(anchor="w", pady=(8, 0))

    def _build_scrcpy(self) -> None:
        card = self._card(self.tab_scr, "Mirror scrcpy (opzionale)")
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

    def cmd(self, line: str, wait: float = 40) -> None:
        def job() -> None:
            bridge = self.ensure_bridge()
            try:
                bridge.connect()
            except AdbError:
                pass
            self.queue.put(("status", f"Invio {line}…", "info"))
            self.queue.put(("log", f"> {line}"))
            ack = bridge.send_command(line, wait_ack=wait)
            self.queue.put(("log", ack))
            kind = "ok" if ack.startswith("OK|") else "err"
            self.queue.put(("status", ack[:120], kind))
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

    def _apply_state(self, state: dict) -> None:
        self.state = state or {}
        wifi = self.state.get("wifi") or {}
        tel = self.state.get("telescope") or {}
        hd = self.state.get("hd") or {}
        syss = self.state.get("system") or {}
        tg = self.state.get("telegram") or {}
        self.wifi_status.set(
            f"{wifi.get('status', '—')}  ·  {wifi.get('ssid') or 'n/d'}  ·  {wifi.get('bssid') or ''}"
        )
        if tel.get("reachable"):
            self.tel_status.set(
                f"{tel.get('model') or 'Vespera'}  ·  {tel.get('state') or '—'}  ·  "
                f"{tel.get('observationStatus') or '—'}  ·  target {tel.get('targetName') or '—'}  ·  "
                f"stack {tel.get('stackingCount', 0)}  ·  batt {tel.get('batteryPercent', -1)}%"
            )
        else:
            self.tel_status.set(f"Non raggiungibile ({tel.get('error') or '—'})")
        space = hd.get("spaceLabel") or (f"{hd.get('spacePercent')}%" if hd.get("spaceKnown") else "")
        self.hd_status.set(
            f"{'Montato' if hd.get('mounted') else 'Spento/smontato'}  ·  {hd.get('label') or hd.get('uuid') or '—'}  ·  {space}"
        )
        for key, var in self._sys_vars.items():
            if key in syss:
                var.set(bool(syss[key]))
        conf = "Configurato" if tg.get("configured") else "Non configurato"
        self.tg_configured.set(f"{conf}  ·  chat {tg.get('chatId') or '—'}  ·  err {tg.get('lastError') or '—'}")
        ver = self.state.get("appVersion", "?")
        self.set_status(f"Stato Pi aggiornato (Helper {ver}).", "ok")

    def ftp_endpoint(self) -> tuple[str, int]:
        raw = self.ftp_hd.get() if self.ftp_source.get() == "hd" else self.ftp_vespera.get()
        default = 2121 if self.ftp_source.get() == "hd" else 2122
        return preview.parse_endpoint(raw, default)

    def load_objects(self) -> None:
        def job() -> None:
            host, port = self.ftp_endpoint()
            self.queue.put(("status", f"Elenco oggetti su {host}:{port}…", "info"))
            online = preview.reachable(host, port)
            self.queue.put(("log", f"FTP {host}:{port} {'online' if online else 'offline'}"))
            if not online:
                raise preview.PreviewError(f"FTP offline: {host}:{port}")
            objects = preview.list_objects(host, port)
            self.queue.put(("objects", objects))

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

    def _on_objects(self, objects: list[preview.SkyObject]) -> None:
        self._objects = objects
        self.obj_list.delete(0, "end")
        for obj in objects:
            suffix = f"  ·  {obj.when}" if obj.when else ""
            self.obj_list.insert("end", f"{obj.label}{suffix}")
        self.set_status(f"{len(objects)} oggetti.", "ok")

    def _on_preview_image(self, data: bytes, detail: preview.SkyObject) -> None:
        self._png = data
        self.meta.set(f"{detail.label}  ·  {detail.output_path}")
        self._redraw()
        self.set_status("Anteprima caricata.", "ok")

    def _redraw(self) -> None:
        w = max(self.canvas.winfo_width(), 2)
        h = max(self.canvas.winfo_height(), 2)
        self.canvas.delete("all")
        if not self._png:
            self.canvas.create_text(
                w // 2,
                h // 2,
                text="Seleziona un oggetto per l'ultimo *-output.jpg\n(nessuno stacking)",
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
                    self._on_objects(item[1])
                elif kind == "object_detail":
                    detail = item[1]
                    self.meta.set(f"{detail.label}  ·  {len(detail.images)} immagini")
                elif kind == "preview_image":
                    self._on_preview_image(item[1], item[2])
                elif kind == "update":
                    remote = item[1]
                    ver = remote.get("version")
                    if messagebox.askyesno(
                        "Aggiornamento",
                        f"È disponibile Vespera Control {ver} (ora hai 0.2.0).\n\n"
                        f"Aprire la cartella Mega per scaricare?",
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
        self.ftp_hd.set(str(raw.get("ftp_hd") or self.ftp_hd.get()))
        self.ftp_vespera.set(str(raw.get("ftp_vespera") or self.ftp_vespera.get()))
        self.ftp_source.set(str(raw.get("ftp_source") or "hd"))

    def _save_settings(self) -> None:
        payload = {
            "pi_host": self.pi_host.get().strip(),
            "adb_port": self.adb_port.get().strip() or "5555",
            "scrcpy_path": self.scrcpy_path.get().strip(),
            "max_size": self.max_size.get().strip(),
            "bitrate": self.bitrate.get().strip(),
            "software_encoder": self.software_encoder.get(),
            "no_audio": self.no_audio.get(),
            "ftp_hd": self.ftp_hd.get().strip(),
            "ftp_vespera": self.ftp_vespera.get().strip(),
            "ftp_source": self.ftp_source.get(),
        }
        try:
            atomic_write(settings_path(), json.dumps(payload, ensure_ascii=False, indent=2))
        except OSError as exc:
            self.log(f"Salvataggio impostazioni: {exc}")

    def on_close(self) -> None:
        self._closing = True
        self._save_settings()
        self.destroy()


def main() -> None:
    App().mainloop()


if __name__ == "__main__":
    main()
