"""Footer meteo della finestra (stessi dati di NightForecastFooter in Vespera Helper).

Footer: ‹ giorno › fisso, un riquadro con la prima previsione disponibile di quella notte (▲) e il nome
del paese. Solo il riquadro è cliccabile: apre sopra il footer tutte le fasce di 3 h della notte
scelta, disposte a griglia così da stare nella finestra senza scorrere (icone più piccole se serve).
Un altro clic (o Esc) chiude.
"""
from __future__ import annotations

import threading
import tkinter as tk
from datetime import datetime, timedelta

from vespera_control import night_forecast as nf

REFRESH_MS = 30 * 60 * 1000
FOOT_BG = "#E8EEF4"
BOX_BG = "#FFFFFF"
BOX_BORDER = "#90A4AE"
TEXT = "#37474F"
TIME = "#1A237E"
RAIN = "#1565C0"
NAV_ON = "#1565C0"
NAV_OFF = "#B0BEC5"
GAP = 10


class ForecastFooter(tk.Frame):
    def __init__(self, master: tk.Misc) -> None:
        super().__init__(master, bg=FOOT_BG, padx=12, pady=4)
        self._site: tuple[float, float] | None = None
        self._label = ""
        self._town = ""
        self._hours = (nf.DEFAULT_NIGHT_START, nf.DEFAULT_NIGHT_END)
        self._result: nf.Result | None = None
        self._index = 0
        self._gen = 0
        self._after: str | None = None
        self._icons: dict[str, object] = {}
        self._open = False
        self._layout_key: tuple | None = None

        # ‹ giorno ›  (fisso)
        self.prev = self._nav("‹", -1)
        self.day = tk.Label(self, bg=FOOT_BG, fg=TEXT, font=("Segoe UI", 11, "bold"), padx=2)
        self.day.pack(side="left")
        self.next = self._nav("›", 1)

        # Riquadro con la prima previsione: l'unica parte cliccabile
        self.box = tk.Frame(self, bg=BOX_BG, highlightthickness=1, highlightbackground=BOX_BORDER,
                            padx=8, pady=3, cursor="hand2")
        self.box.pack(side="left", padx=(8, 0))
        self.box_icon = tk.Label(self.box, bg=BOX_BG)
        self.box_icon.pack(side="left")
        self.box_text = tk.Label(self.box, bg=BOX_BG, fg=TEXT, font=("Segoe UI", 10, "bold"), padx=6)
        self.box_text.pack(side="left")
        self.box_arrow = tk.Label(self.box, text="▲", bg=BOX_BG, fg=NAV_ON, font=("Segoe UI", 9, "bold"))
        self.box_arrow.pack(side="left")
        for w in (self.box, self.box_icon, self.box_text, self.box_arrow):
            w.bind("<Button-1>", lambda _e: self.toggle())

        self.place_label = tk.Label(self, bg=FOOT_BG, fg=TEXT, font=("Segoe UI", 10, "bold"))
        self.place_label.pack(side="right")

        # Pannello sovrapposto con tutte le fasce della notte scelta
        top = self.winfo_toplevel()
        self.panel = tk.Frame(top, bg=BOX_BG, highlightthickness=1, highlightbackground=BOX_BORDER,
                              padx=10, pady=8)
        self.grid_host = tk.Frame(self.panel, bg=BOX_BG)
        self.grid_host.pack(fill="both", expand=True)
        self.panel.bind("<Configure>", self._on_panel_resize)
        top.bind("<Escape>", lambda _e: self.close(), add="+")
        # Clic fuori dal pannello lo chiude; trascinare a sinistra/destra su footer o pannello cambia giorno.
        self._press: tuple[int, int, bool, bool] | None = None
        top.bind("<ButtonPress-1>", self._on_press, add="+")
        top.bind("<ButtonRelease-1>", self._on_release, add="+")
        self._show_day()

    @staticmethod
    def _inside(widget, area: tk.Misc) -> bool:
        path, root = str(widget), str(area)
        return path == root or path.startswith(root + ".")

    def _on_press(self, event: tk.Event) -> None:
        on_panel = self._open and self._inside(event.widget, self.panel)
        keeps_open = on_panel or any(self._inside(event.widget, w) for w in (self.box, self.prev, self.next))
        swipeable = on_panel or self._inside(event.widget, self)
        self._press = (event.x_root, event.y_root, swipeable, keeps_open)

    def _on_release(self, event: tk.Event) -> None:
        press, self._press = self._press, None
        if not press:
            return
        x0, y0, swipeable, keeps_open = press
        dx, dy = event.x_root - x0, event.y_root - y0
        if swipeable and abs(dx) >= 60 and abs(dx) > 2 * abs(dy):
            self._shift(1 if dx < 0 else -1)  # verso sinistra = giorno dopo
        elif self._open and not keeps_open:
            self.close()  # clic fuori dal pannello

    def _nav(self, text: str, delta: int) -> tk.Label:
        lab = tk.Label(self, text=text, bg=FOOT_BG, fg=NAV_ON, font=("Segoe UI", 18, "bold"),
                       padx=4, cursor="hand2")
        lab.pack(side="left")
        lab.bind("<Button-1>", lambda _e: self._shift(delta))
        return lab

    def _icon(self, name: str, size: int = 28):
        key = f"{name}{size}"
        if key not in self._icons:
            try:
                from PIL import ImageTk
                self._icons[key] = ImageTk.PhotoImage(nf.icon_image(name, size))
            except Exception:
                self._icons[key] = None
        return self._icons[key]

    # ------------------------------------------------------------------ apertura

    def toggle(self) -> None:
        if self._open:
            self.close()
        else:
            self.open()

    def open(self) -> None:
        if not self._night():
            return
        self._open = True
        self._place_panel()
        self.box_arrow.configure(text="▼")

    def _place_panel(self) -> None:
        self.update_idletasks()
        self._layout_key = None
        self.panel.place(x=12, rely=1.0, y=-(self.winfo_height() + 2), anchor="sw", relwidth=1.0, width=-24)
        self.panel.lift()
        self._fill_panel()

    def close(self) -> None:
        self._open = False
        self.panel.place_forget()
        self.box_arrow.configure(text="▲")

    # ------------------------------------------------------------------ dati

    def set_site(self, site: tuple[float, float] | None, label: str = "",
                 night_hours: tuple[int, int] | None = None) -> None:
        """Nuovo sito / nome dall'Helper / ore notte: riscarica solo se qualcosa è cambiato."""
        hours = night_hours or (nf.DEFAULT_NIGHT_START, nf.DEFAULT_NIGHT_END)
        self._label = nf.town_from_label(label)
        changed = (site != self._site) or (hours != self._hours)
        if site != self._site:
            self._town = ""
        self._show_place()
        if not changed and self._after is not None:
            return
        self._site = site
        self._hours = hours
        self.refresh()

    def refresh(self) -> None:
        if self._after is not None:
            try:
                self.after_cancel(self._after)
            except tk.TclError:
                pass
        self._after = self.after(REFRESH_MS, self.refresh)
        self._gen += 1
        gen = self._gen
        if self._site is None:
            self._result = None
            self._index = 0
            self.close()
            self._show_day("Imposta la posizione in Impostazioni per il meteo")
            return
        if self._result is None:
            self._show_day("Aggiornamento meteo…")
        lat, lon = self._site
        start_h, end_h = self._hours
        need_town = not self._label and not self._town

        def work() -> None:
            try:
                result = nf.fetch(lat, lon, start_h, end_h)
            except Exception as exc:  # rete, JSON…
                result = nf.Result([], datetime.now(), str(exc) or exc.__class__.__name__)
            town = ""
            if need_town:
                try:
                    town = nf.town(lat, lon)
                except Exception:
                    town = ""
            try:
                self.after(0, lambda: self._apply(gen, result, town))
            except (tk.TclError, RuntimeError):
                pass

        threading.Thread(target=work, daemon=True).start()

    def _apply(self, gen: int, result: nf.Result, town: str) -> None:
        if gen != self._gen:
            return
        if town:
            self._town = town
            self._show_place()
        if result.error or not result.nights:
            if self._result is not None and self._result.nights:
                return  # tieni l'ultimo aggiornamento buono
            self.close()
            self._show_day(nf.friendly_error(result.error))
            return
        self._result = result
        if self._index >= len(result.nights):
            self._index = 0
        self._show_day()

    # ------------------------------------------------------------------ vista

    def _night(self) -> nf.Night | None:
        if not self._result or not self._result.nights:
            return None
        return self._result.nights[self._index]

    def _first_slot(self, night: nf.Night) -> nf.Slot:
        """Prima previsione ancora disponibile (per stanotte salta le fasce già passate)."""
        since = self._result.now - timedelta(hours=1, minutes=30)
        upcoming = [s for s in night.slots if s.when >= since]
        return upcoming[0] if upcoming else night.slots[0]

    def _show_place(self) -> None:
        self.place_label.configure(text=self._label or self._town)

    def _show_day(self, message: str = "") -> None:
        """Giorno scelto nel footer + riquadro con la sua prima previsione."""
        night = None if message else self._night()
        count = len(self._result.nights) if self._result else 0
        self.prev.configure(fg=NAV_ON if night and self._index > 0 else NAV_OFF)
        self.next.configure(fg=NAV_ON if night and self._index + 1 < count else NAV_OFF)
        if night is None:
            self.day.configure(text="")
            self.box_icon.configure(image="")
            self.box_icon.pack_forget()
            self.box_arrow.pack_forget()
            self.box_text.configure(text=message, fg=TEXT)
            return
        self.day.configure(text=nf.day_caption(night.start, self._result.now))
        slot = self._first_slot(night)
        self.box_icon.configure(image=self._icon(nf.kind(slot.code), 22) or "")
        self.box_icon.pack(side="left", before=self.box_text)
        meta = nf.slot_meta(slot)
        self.box_text.configure(text=f"{slot.when:%H:%M}" + (f" · {meta}" if meta else ""),
                                fg=RAIN if slot.mm >= 0.05 else TEXT)
        self.box_arrow.pack(side="left")
        if self._open:
            self._place_panel()

    def _shift(self, delta: int) -> None:
        if not self._result or not self._result.nights:
            return
        nxt = self._index + delta
        if 0 <= nxt < len(self._result.nights):
            self._index = nxt
            self._show_day()

    def _on_panel_resize(self, event: tk.Event) -> None:
        """Larghezza cambiata: ricalcola dimensione delle icone e colonne."""
        if self._open and event.width != getattr(self, "_panel_w", None):
            self._panel_w = event.width
            self._fill_panel()

    def _fill_panel(self) -> None:
        """Crea le celle della notte scelta (grandi, o piccole se non stanno in una riga)."""
        night = self._night()
        if night is None:
            return
        self.update_idletasks()
        width = max(200, self.panel.winfo_width() - 24)
        big = self._make_cells(night, 28, 11, 9)
        cell_w = max(c.winfo_reqwidth() for c in big) + GAP
        cells = big
        if cell_w * len(big) > width:
            for c in big:
                c.destroy()
            cells = self._make_cells(night, 20, 10, 8)
            cell_w = max(c.winfo_reqwidth() for c in cells) + GAP
        self._cells = cells
        self._cell_w = cell_w
        self._layout_key = None
        self._layout_cells()

    def _make_cells(self, night: nf.Night, icon: int, time_pt: int, meta_pt: int) -> list[tk.Frame]:
        for child in self.grid_host.winfo_children():
            child.destroy()
        cells = []
        for slot in night.slots:
            cell = tk.Frame(self.grid_host, bg=BOX_BG)
            tk.Label(cell, image=self._icon(nf.kind(slot.code), icon) or "", bg=BOX_BG).pack(side="left")
            texts = tk.Frame(cell, bg=BOX_BG)
            texts.pack(side="left", padx=(4, 0))
            tk.Label(texts, text=f"{slot.when:%H:%M}", bg=BOX_BG, fg=TIME,
                     font=("Segoe UI", time_pt, "bold")).pack(anchor="w")
            meta = nf.slot_meta(slot)
            if meta:
                tk.Label(texts, text=meta, bg=BOX_BG, fg=RAIN if slot.mm >= 0.05 else TEXT,
                         font=("Segoe UI", meta_pt, "bold")).pack(anchor="w")
            cells.append(cell)
        self.update_idletasks()
        return cells

    def _layout_cells(self) -> None:
        """Griglia: tante colonne quante ne stanno, il resto va a capo (niente scroll)."""
        cells = getattr(self, "_cells", None)
        if not self._open or not cells:
            return
        width = max(200, self.panel.winfo_width() - 24)
        cols = max(1, min(len(cells), width // self._cell_w))
        key = (cols, len(cells))
        if key == self._layout_key:
            return
        self._layout_key = key
        for c in range(len(cells)):
            self.grid_host.columnconfigure(c, weight=0, minsize=0)
        for c in range(cols):
            self.grid_host.columnconfigure(c, minsize=self._cell_w)
        for i, cell in enumerate(cells):
            cell.grid(row=i // cols, column=i % cols, sticky="w", pady=2)
