# -*- coding: utf-8 -*-
"""Editor anteprima stile Siril / StarStacKadam / GIMP: livelli, autostretch, salva, condividi."""

from __future__ import annotations

import io
import os
import subprocess
import tempfile
import tkinter as tk
from dataclasses import dataclass
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

BG = "#E8EEF4"
PANEL = "#F3F7FA"
FG = "#212121"
MUTED = "#546E7A"
TITLE = "#1A237E"
PRIMARY = "#3B7F55"
INK = "#2A2D31"
PANEL_STROKE = "#90A4AE"
ERR = "#B05757"
CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


@dataclass
class AdjustParams:
    black: float = 0.0  # 0..1
    white: float = 1.0  # 0..1
    mid: float = 1.0  # gamma 0.2..3
    saturation: float = 1.0  # 0..2


def _percentile_from_hist(hist: list[int], pct: float) -> int:
    total = sum(hist)
    if total <= 0:
        return 0
    target = max(0.0, min(100.0, pct)) / 100.0 * total
    acc = 0
    for value, count in enumerate(hist):
        acc += count
        if acc >= target:
            return value
    return 255


def estimate_autostretch(image, low_pct: float = 0.5, high_pct: float = 99.8) -> AdjustParams:
    """Stima black/white da percentili della luminanza (stretch tipico astro)."""
    from PIL import ImageOps

    gray = ImageOps.grayscale(image.convert("RGB"))
    hist = gray.histogram()
    lo = _percentile_from_hist(hist, low_pct)
    hi = _percentile_from_hist(hist, high_pct)
    if hi <= lo:
        hi = min(255, lo + 1)
    return AdjustParams(black=lo / 255.0, white=hi / 255.0, mid=1.0, saturation=1.0)


def apply_adjustments(image, params: AdjustParams):
    """Applica livelli (black/white/gamma) + saturazione. Restituisce RGB."""
    from PIL import Image, ImageEnhance

    src = image.convert("RGB")
    black = max(0.0, min(0.98, float(params.black)))
    white = max(black + 0.01, min(1.0, float(params.white)))
    mid = max(0.2, min(3.0, float(params.mid)))
    sat = max(0.0, min(2.5, float(params.saturation)))

    b = int(round(black * 255))
    w = int(round(white * 255))
    span = max(1, w - b)
    inv_gamma = 1.0 / mid
    lut = []
    for i in range(256):
        if i <= b:
            v = 0.0
        elif i >= w:
            v = 1.0
        else:
            v = (i - b) / span
            v = v**inv_gamma
        lut.append(max(0, min(255, int(round(v * 255)))))

    out = src.point(lut * 3)
    if abs(sat - 1.0) > 0.01:
        out = ImageEnhance.Color(out).enhance(sat)
    return out


def copy_image_to_clipboard(image) -> None:
    """Copia bitmap RGB negli appunti Windows (CF_DIB)."""
    from PIL import Image

    bmp = image.convert("RGB")
    with io.BytesIO() as buf:
        bmp.save(buf, format="BMP")
        data = buf.getvalue()[14:]  # salta header file BMP → DIB

    import ctypes

    user32 = ctypes.windll.user32
    kernel32 = ctypes.windll.kernel32
    CF_DIB = 8
    GMEM_MOVEABLE = 0x0002

    if not user32.OpenClipboard(None):
        raise OSError("OpenClipboard fallito")
    try:
        user32.EmptyClipboard()
        hglobal = kernel32.GlobalAlloc(GMEM_MOVEABLE, len(data))
        if not hglobal:
            raise OSError("GlobalAlloc fallito")
        locked = kernel32.GlobalLock(hglobal)
        ctypes.memmove(locked, data, len(data))
        kernel32.GlobalUnlock(hglobal)
        if not user32.SetClipboardData(CF_DIB, hglobal):
            kernel32.GlobalFree(hglobal)
            raise OSError("SetClipboardData fallito")
    finally:
        user32.CloseClipboard()


def copy_file_to_clipboard(path: Path) -> None:
    """Copia il percorso file negli appunti (elenco file Explorer)."""
    abs_path = str(path.resolve())
    ps = (
        "Add-Type -AssemblyName System.Windows.Forms; "
        "$f = New-Object System.Collections.Specialized.StringCollection; "
        f"$f.Add('{abs_path.replace(chr(39), chr(39)+chr(39))}'); "
        "[System.Windows.Forms.Clipboard]::SetFileDropList($f)"
    )
    subprocess.run(
        ["powershell", "-NoProfile", "-Command", ps],
        check=True,
        creationflags=CREATE_NO_WINDOW,
        capture_output=True,
    )


class PreviewEditor(tk.Toplevel):
    """Finestra: click sull'anteprima → zoom, livelli, autostretch, salva/condividi."""

    def __init__(
        self,
        master: tk.Misc,
        image_bytes: bytes,
        title: str = "Editor anteprima",
        suggested_name: str = "vespera-preview.jpg",
    ) -> None:
        super().__init__(master)
        self.title(title)
        self.configure(bg=BG)
        self.transient(master)
        self.geometry("1180x820")
        self.minsize(900, 640)
        self._title_text = title
        self._suggested = suggested_name or "vespera-preview.jpg"
        self._photo = None
        self._pil_src = None
        self._pil_view = None
        self._scale = 1.0
        self._offset_x = 0.0
        self._offset_y = 0.0
        self._drag: tuple[int, int] | None = None
        self._moved = False
        self._resize_after: str | None = None
        self._apply_after: str | None = None
        self._last_export: Path | None = None
        self._params = AdjustParams()
        self._crop_mode = False
        self._crop_start: tuple[float, float] | None = None
        self._crop_rect: tuple[float, float, float, float] | None = None  # x0,y0,x1,y1 in px immagine

        self.var_black = tk.DoubleVar(value=0.0)
        self.var_white = tk.DoubleVar(value=1.0)
        self.var_mid = tk.DoubleVar(value=1.0)
        self.var_sat = tk.DoubleVar(value=1.0)
        self.hint = tk.StringVar(value="Autostretch / livelli · rotella zoom · trascina sposta · R/L ruota · C crop")

        self._build()
        self.bind("<Escape>", self._on_escape)
        self.bind("<Return>", lambda _e: self._apply_crop())
        self.bind("<KP_Enter>", lambda _e: self._apply_crop())
        self.protocol("WM_DELETE_WINDOW", self.destroy)
        self.after(40, lambda: self._load(image_bytes))

    def _build(self) -> None:
        bar = ttk.Frame(self, padding=(12, 8))
        bar.pack(fill="x")
        ttk.Label(bar, text=self._title_text, foreground=TITLE, font=("Segoe UI", 12, "bold")).pack(
            side="left"
        )
        ttk.Label(bar, textvariable=self.hint, foreground=MUTED).pack(side="left", padx=(14, 0))
        ttk.Button(bar, text="Chiudi", command=self.destroy).pack(side="right")
        ttk.Button(bar, text="Condividi", command=self._share_menu).pack(side="right", padx=(0, 6))
        ttk.Button(bar, text="Salva...", command=self._save).pack(side="right", padx=(0, 6))

        body = ttk.Frame(self, padding=(12, 0, 12, 12))
        body.pack(fill="both", expand=True)
        body.columnconfigure(0, weight=1)
        body.rowconfigure(0, weight=1)

        self.canvas = tk.Canvas(body, bg=INK, highlightthickness=1, highlightbackground=PANEL_STROKE, cursor="fleur")
        self.canvas.grid(row=0, column=0, sticky="nsew")
        self.canvas.bind("<Configure>", self._on_configure)
        self.canvas.bind("<MouseWheel>", self._on_wheel)
        self.canvas.bind("<ButtonPress-1>", self._on_press)
        self.canvas.bind("<B1-Motion>", self._on_drag)
        self.canvas.bind("<ButtonRelease-1>", self._on_release)
        self.bind("<plus>", lambda _e: self._zoom_at(1.15))
        self.bind("<minus>", lambda _e: self._zoom_at(1 / 1.15))
        self.bind("<KP_Add>", lambda _e: self._zoom_at(1.15))
        self.bind("<KP_Subtract>", lambda _e: self._zoom_at(1 / 1.15))
        self.bind("<r>", lambda _e: self._rotate(-90))
        self.bind("<l>", lambda _e: self._rotate(90))
        self.bind("<c>", lambda _e: self._toggle_crop_mode())
        self.bind("<a>", lambda _e: self._autostretch())
        self.bind("<Control-r>", lambda _e: self._reset())

        side = ttk.Frame(body, padding=(12, 0, 0, 0))
        side.grid(row=0, column=1, sticky="ns")

        card = tk.Frame(side, bg=PANEL, highlightbackground=PANEL_STROKE, highlightthickness=1, padx=12, pady=12)
        card.pack(fill="y")
        tk.Label(card, text="Regolazione", bg=PANEL, fg=TITLE, font=("Segoe UI", 11, "bold")).pack(anchor="w")
        tk.Label(
            card,
            text="Come Siril / StarStacKadam:\npercentili + livelli + midtone",
            bg=PANEL,
            fg=MUTED,
            font=("Segoe UI", 9),
            justify="left",
        ).pack(anchor="w", pady=(4, 10))

        ttk.Button(card, text="Autostretch", command=self._autostretch).pack(fill="x", pady=(0, 6))
        ttk.Button(card, text="Reset livelli", command=self._reset).pack(fill="x", pady=(0, 12))

        self._slider(card, "Neri (black)", self.var_black, 0.0, 0.95, self._schedule_apply)
        self._slider(card, "Medi (gamma)", self.var_mid, 0.2, 3.0, self._schedule_apply)
        self._slider(card, "Bianchi (white)", self.var_white, 0.05, 1.0, self._schedule_apply)
        self._slider(card, "Saturazione", self.var_sat, 0.0, 2.0, self._schedule_apply)

        ttk.Separator(card, orient="horizontal").pack(fill="x", pady=12)
        tk.Label(card, text="Geometria", bg=PANEL, fg=TITLE, font=("Segoe UI", 11, "bold")).pack(anchor="w")
        rot = tk.Frame(card, bg=PANEL)
        rot.pack(fill="x", pady=(8, 6))
        ttk.Button(rot, text="↺ 90°", command=lambda: self._rotate(90)).pack(side="left", expand=True, fill="x")
        ttk.Button(rot, text="↻ 90°", command=lambda: self._rotate(-90)).pack(
            side="left", expand=True, fill="x", padx=(6, 0)
        )
        self.btn_crop = ttk.Button(card, text="Crop (C)", command=self._toggle_crop_mode)
        self.btn_crop.pack(fill="x", pady=(0, 6))
        self.btn_apply_crop = ttk.Button(card, text="Applica crop (Invio)", command=self._apply_crop)
        self.btn_apply_crop.pack(fill="x", pady=(0, 6))
        self.btn_apply_crop.state(["disabled"])
        ttk.Button(card, text="Annulla selezione", command=self._clear_crop_selection).pack(fill="x", pady=(0, 12))

        ttk.Separator(card, orient="horizontal").pack(fill="x", pady=(0, 12))
        ttk.Button(card, text="Copia immagine", command=self._copy_image).pack(fill="x", pady=(0, 6))
        ttk.Button(card, text="Apri con app…", command=self._open_temp).pack(fill="x", pady=(0, 6))
        ttk.Button(card, text="Adatta alla finestra", command=self._fit_and_draw).pack(fill="x")

    def _slider(self, parent, label: str, var: tk.DoubleVar, frm: float, to: float, cmd) -> None:
        box = tk.Frame(parent, bg=PANEL)
        box.pack(fill="x", pady=(0, 8))
        head = tk.Frame(box, bg=PANEL)
        head.pack(fill="x")
        tk.Label(head, text=label, bg=PANEL, fg=FG, font=("Segoe UI", 9)).pack(side="left")
        val = tk.Label(head, text="", bg=PANEL, fg=MUTED, font=("Segoe UI", 9))
        val.pack(side="right")

        def refresh(_event=None) -> None:
            val.configure(text=f"{var.get():.2f}")
            cmd()

        scale = ttk.Scale(box, from_=frm, to=to, variable=var, command=lambda _v: refresh())
        scale.pack(fill="x")
        refresh()

    def _load(self, data: bytes) -> None:
        try:
            from PIL import Image

            self._pil_src = Image.open(io.BytesIO(data)).convert("RGB")
        except Exception as exc:
            self.canvas.create_text(
                self.canvas.winfo_width() // 2 or 400,
                self.canvas.winfo_height() // 2 or 300,
                text=f"Immagine non apribile:\n{exc}",
                fill=ERR,
                font=("Segoe UI", 12),
                justify="center",
            )
            return
        self._params = AdjustParams()
        self._sync_vars()
        self._recompute(fit=True)

    def _sync_vars(self) -> None:
        self.var_black.set(self._params.black)
        self.var_white.set(self._params.white)
        self.var_mid.set(self._params.mid)
        self.var_sat.set(self._params.saturation)

    def _read_params(self) -> AdjustParams:
        black = float(self.var_black.get())
        white = float(self.var_white.get())
        if white <= black + 0.01:
            white = min(1.0, black + 0.01)
            self.var_white.set(white)
        return AdjustParams(
            black=black,
            white=white,
            mid=float(self.var_mid.get()),
            saturation=float(self.var_sat.get()),
        )

    def _schedule_apply(self) -> None:
        if self._apply_after is not None:
            self.after_cancel(self._apply_after)
        self._apply_after = self.after(60, lambda: self._recompute(fit=False))

    def _recompute(self, fit: bool) -> None:
        self._apply_after = None
        if self._pil_src is None:
            return
        self._params = self._read_params()
        try:
            self._pil_view = apply_adjustments(self._pil_src, self._params)
        except Exception as exc:
            self.hint.set(f"Errore regolazione: {exc}")
            return
        if fit:
            self._fit()
        self._redraw()

    def _autostretch(self) -> None:
        if self._pil_src is None:
            return
        self._params = estimate_autostretch(self._pil_src)
        self._sync_vars()
        self.hint.set(
            f"Autostretch · neri {self._params.black:.2f} · bianchi {self._params.white:.2f}"
        )
        self._recompute(fit=False)

    def _reset(self) -> None:
        self._params = AdjustParams()
        self._sync_vars()
        self.hint.set("Livelli azzerati")
        self._recompute(fit=False)

    def _fit(self) -> None:
        if self._pil_view is None:
            return
        cw = max(self.canvas.winfo_width(), 2)
        ch = max(self.canvas.winfo_height(), 2)
        iw, ih = self._pil_view.size
        self._scale = min(cw / iw, ch / ih, 1.0)
        self._offset_x = (cw - iw * self._scale) / 2
        self._offset_y = (ch - ih * self._scale) / 2

    def _fit_and_draw(self) -> None:
        self._fit()
        self._redraw()

    def _on_configure(self, event: tk.Event) -> None:
        if event.width < 40 or event.height < 40:
            return
        if self._resize_after is not None:
            self.after_cancel(self._resize_after)
        self._resize_after = self.after(80, self._redraw)

    def _on_wheel(self, event: tk.Event) -> None:
        factor = 1.15 if event.delta > 0 else 1 / 1.15
        self._zoom_at(factor, event.x, event.y)

    def _zoom_at(self, factor: float, x: int | None = None, y: int | None = None) -> None:
        if self._pil_view is None:
            return
        if x is None:
            x = max(self.canvas.winfo_width(), 2) // 2
        if y is None:
            y = max(self.canvas.winfo_height(), 2) // 2
        old = self._scale
        self._scale = max(0.08, min(10.0, self._scale * factor))
        ratio = self._scale / old
        self._offset_x = x - (x - self._offset_x) * ratio
        self._offset_y = y - (y - self._offset_y) * ratio
        self._redraw()

    def _on_escape(self, _event: tk.Event | None = None) -> None:
        if self._crop_mode or self._crop_rect is not None:
            if self._crop_rect is not None:
                self._clear_crop_selection()
            else:
                self._set_crop_mode(False)
            return
        self.destroy()

    def _canvas_to_image(self, x: float, y: float) -> tuple[float, float]:
        return ((x - self._offset_x) / self._scale, (y - self._offset_y) / self._scale)

    def _image_to_canvas(self, x: float, y: float) -> tuple[float, float]:
        return (x * self._scale + self._offset_x, y * self._scale + self._offset_y)

    def _clamp_image_xy(self, x: float, y: float) -> tuple[float, float]:
        if self._pil_view is None:
            return x, y
        iw, ih = self._pil_view.size
        return max(0.0, min(float(iw), x)), max(0.0, min(float(ih), y))

    def _normalize_rect(
        self, x0: float, y0: float, x1: float, y1: float
    ) -> tuple[float, float, float, float]:
        return (min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))

    def _rotate(self, degrees: int) -> None:
        if self._pil_src is None:
            return
        from PIL import Image

        deg = int(degrees) % 360
        if deg == 90:
            self._pil_src = self._pil_src.transpose(Image.Transpose.ROTATE_90)
            label = "↺ 90°"
        elif deg == 180:
            self._pil_src = self._pil_src.transpose(Image.Transpose.ROTATE_180)
            label = "180°"
        elif deg == 270:
            self._pil_src = self._pil_src.transpose(Image.Transpose.ROTATE_270)
            label = "↻ 90°"
        else:
            return
        self._clear_crop_selection(redraw=False)
        self.hint.set(f"Ruotata {label}")
        self._recompute(fit=True)

    def _toggle_crop_mode(self) -> None:
        self._set_crop_mode(not self._crop_mode)

    def _set_crop_mode(self, enabled: bool) -> None:
        self._crop_mode = bool(enabled)
        if self._crop_mode:
            self.canvas.configure(cursor="crosshair")
            self.btn_crop.state(["pressed"])
            self.hint.set("Crop: trascina per selezionare · Invio applica · Esc annulla")
        else:
            self.canvas.configure(cursor="fleur")
            self.btn_crop.state(["!pressed"])
            self._crop_start = None
            if self._crop_rect is None:
                self.hint.set("Autostretch / livelli · rotella zoom · trascina sposta · R/L ruota · C crop")
            else:
                self.hint.set("Selezione crop pronta · Invio applica · Esc cancella")
        self._update_crop_buttons()
        self._redraw()

    def _clear_crop_selection(self, redraw: bool = True) -> None:
        self._crop_start = None
        self._crop_rect = None
        self._update_crop_buttons()
        if self._crop_mode:
            self.hint.set("Crop: trascina per selezionare · Invio applica · Esc annulla")
        else:
            self.hint.set("Autostretch / livelli · rotella zoom · trascina sposta · R/L ruota · C crop")
        if redraw:
            self._redraw()

    def _update_crop_buttons(self) -> None:
        ready = self._crop_rect is not None and self._crop_selection_valid()
        self.btn_apply_crop.state(["!disabled"] if ready else ["disabled"])

    def _crop_selection_valid(self) -> bool:
        if self._crop_rect is None:
            return False
        x0, y0, x1, y1 = self._crop_rect
        return (x1 - x0) >= 2 and (y1 - y0) >= 2

    def _apply_crop(self) -> None:
        if self._pil_src is None or not self._crop_selection_valid() or self._crop_rect is None:
            return
        x0, y0, x1, y1 = self._crop_rect
        box = (int(round(x0)), int(round(y0)), int(round(x1)), int(round(y1)))
        iw, ih = self._pil_src.size
        box = (
            max(0, min(iw - 1, box[0])),
            max(0, min(ih - 1, box[1])),
            max(1, min(iw, box[2])),
            max(1, min(ih, box[3])),
        )
        if box[2] - box[0] < 2 or box[3] - box[1] < 2:
            self.hint.set("Selezione troppo piccola")
            return
        self._pil_src = self._pil_src.crop(box)
        self._set_crop_mode(False)
        self._clear_crop_selection(redraw=False)
        self.hint.set(f"Crop applicato · {self._pil_src.size[0]}×{self._pil_src.size[1]}")
        self._recompute(fit=True)

    def _on_press(self, event: tk.Event) -> None:
        if self._crop_mode:
            ix, iy = self._clamp_image_xy(*self._canvas_to_image(event.x, event.y))
            self._crop_start = (ix, iy)
            self._crop_rect = (ix, iy, ix, iy)
            self._update_crop_buttons()
            self._redraw()
            return
        self._drag = (event.x, event.y)
        self._moved = False

    def _on_drag(self, event: tk.Event) -> None:
        if self._crop_mode and self._crop_start is not None:
            ix, iy = self._clamp_image_xy(*self._canvas_to_image(event.x, event.y))
            x0, y0 = self._crop_start
            self._crop_rect = self._normalize_rect(x0, y0, ix, iy)
            self._update_crop_buttons()
            self._redraw()
            return
        if self._drag is None:
            return
        dx = event.x - self._drag[0]
        dy = event.y - self._drag[1]
        if abs(dx) + abs(dy) > 2:
            self._moved = True
        self._drag = (event.x, event.y)
        self._offset_x += dx
        self._offset_y += dy
        self._redraw()

    def _on_release(self, _event: tk.Event) -> None:
        if self._crop_mode:
            self._crop_start = None
            if self._crop_selection_valid():
                x0, y0, x1, y1 = self._crop_rect  # type: ignore[misc]
                self.hint.set(
                    f"Crop {int(x1 - x0)}×{int(y1 - y0)} px · Invio applica · Esc cancella"
                )
            else:
                self._crop_rect = None
                self.hint.set("Crop: trascina per selezionare · Invio applica · Esc annulla")
            self._update_crop_buttons()
            self._redraw()
            return
        self._drag = None

    def _draw_crop_overlay(self) -> None:
        if self._crop_rect is None or self._pil_view is None:
            return
        x0, y0, x1, y1 = self._crop_rect
        cx0, cy0 = self._image_to_canvas(x0, y0)
        cx1, cy1 = self._image_to_canvas(x1, y1)
        # Oscura l'esterno con 4 rettangoli semi-trasparenti via stipple
        cw = max(self.canvas.winfo_width(), 2)
        ch = max(self.canvas.winfo_height(), 2)
        shade = "#000000"
        self.canvas.create_rectangle(0, 0, cw, cy0, fill=shade, stipple="gray50", outline="")
        self.canvas.create_rectangle(0, cy1, cw, ch, fill=shade, stipple="gray50", outline="")
        self.canvas.create_rectangle(0, cy0, cx0, cy1, fill=shade, stipple="gray50", outline="")
        self.canvas.create_rectangle(cx1, cy0, cw, cy1, fill=shade, stipple="gray50", outline="")
        self.canvas.create_rectangle(cx0, cy0, cx1, cy1, outline="#FFEB3B", width=2)
        # Maniglie angoli
        for hx, hy in ((cx0, cy0), (cx1, cy0), (cx0, cy1), (cx1, cy1)):
            self.canvas.create_rectangle(hx - 3, hy - 3, hx + 3, hy + 3, fill="#FFEB3B", outline="")

    def _redraw(self) -> None:
        self._resize_after = None
        if self._pil_view is None:
            return
        try:
            from PIL import Image, ImageTk
        except Exception:
            return
        iw, ih = self._pil_view.size
        w = max(1, int(iw * self._scale))
        h = max(1, int(ih * self._scale))
        if w * h > 45_000_000:
            return
        resample = Image.Resampling.BILINEAR if self._scale >= 1 else Image.Resampling.LANCZOS
        resized = self._pil_view.resize((w, h), resample)
        self._photo = ImageTk.PhotoImage(resized)
        self.canvas.delete("all")
        self.canvas.create_image(int(self._offset_x), int(self._offset_y), image=self._photo, anchor="nw")
        if self._crop_mode or self._crop_rect is not None:
            self._draw_crop_overlay()
        pct = int(round(self._scale * 100))
        help_txt = (
            f"{pct}%  ·  C crop  ·  R/L ruota  ·  A autostretch  ·  Ctrl+R reset livelli"
            if not self._crop_mode
            else f"{pct}%  ·  Crop attivo  ·  Invio applica  ·  Esc annulla"
        )
        self.canvas.create_text(
            12,
            max(self.canvas.winfo_height(), 2) - 12,
            text=help_txt,
            fill="#B0BEC5",
            font=("Segoe UI", 10),
            anchor="sw",
        )

    def _current_image(self):
        if self._pil_view is None and self._pil_src is not None:
            self._pil_view = apply_adjustments(self._pil_src, self._read_params())
        return self._pil_view

    def _save(self) -> None:
        image = self._current_image()
        if image is None:
            return
        initial = self._suggested
        path = filedialog.asksaveasfilename(
            parent=self,
            title="Salva anteprima regolata",
            initialfile=initial,
            defaultextension=".jpg",
            filetypes=[
                ("JPEG", "*.jpg;*.jpeg"),
                ("PNG", "*.png"),
                ("TIFF", "*.tif;*.tiff"),
                ("Tutti", "*.*"),
            ],
        )
        if not path:
            return
        out = Path(path)
        try:
            self._export_to(out, image)
        except OSError as exc:
            messagebox.showerror("Salva", str(exc), parent=self)
            return
        self._last_export = out
        self.hint.set(f"Salvato: {out}")

    def _export_to(self, path: Path, image) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        ext = path.suffix.lower()
        if ext in {".png"}:
            image.save(path, format="PNG")
        elif ext in {".tif", ".tiff"}:
            image.save(path, format="TIFF")
        else:
            image.save(path, format="JPEG", quality=95, optimize=True)

    def _temp_export(self) -> Path:
        image = self._current_image()
        if image is None:
            raise RuntimeError("Nessuna immagine")
        suffix = Path(self._suggested).suffix or ".jpg"
        fd, name = tempfile.mkstemp(prefix="vespera_preview_", suffix=suffix)
        os.close(fd)
        path = Path(name)
        self._export_to(path, image)
        self._last_export = path
        return path

    def _copy_image(self) -> None:
        image = self._current_image()
        if image is None:
            return
        try:
            copy_image_to_clipboard(image)
            self.hint.set("Immagine copiata negli appunti")
        except Exception as exc:
            messagebox.showerror("Copia", f"Copia negli appunti non riuscita:\n{exc}", parent=self)

    def _open_temp(self) -> None:
        try:
            path = self._temp_export()
            os.startfile(path)  # type: ignore[attr-defined]
            self.hint.set(f"Aperto: {path.name}")
        except Exception as exc:
            messagebox.showerror("Apri", str(exc), parent=self)

    def _share_menu(self) -> None:
        menu = tk.Menu(self, tearoff=0)
        menu.add_command(label="Copia immagine negli appunti", command=self._copy_image)
        menu.add_command(label="Copia file negli appunti", command=self._copy_file)
        menu.add_command(label="Salva…", command=self._save)
        menu.add_command(label="Apri con app predefinita", command=self._open_temp)
        menu.add_command(label="Mostra in Esplora file", command=self._reveal)
        try:
            menu.tk_popup(self.winfo_pointerx(), self.winfo_pointery())
        finally:
            menu.grab_release()

    def _copy_file(self) -> None:
        try:
            path = self._last_export if self._last_export and self._last_export.is_file() else self._temp_export()
            copy_file_to_clipboard(path)
            self.hint.set(f"File copiato: {path.name}")
        except Exception as exc:
            messagebox.showerror("Condividi", str(exc), parent=self)

    def _reveal(self) -> None:
        try:
            path = self._last_export if self._last_export and self._last_export.is_file() else self._temp_export()
            subprocess.run(["explorer", f"/select,{path}"], check=False, creationflags=CREATE_NO_WINDOW)
            self.hint.set("Cartella aperta in Esplora file")
        except Exception as exc:
            messagebox.showerror("Esplora file", str(exc), parent=self)
