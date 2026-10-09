"""Footer meteo della finestra (stessi dati di NightForecastFooter in Vespera Helper).

Chiuso: un riquadro con la prima previsione della sera e ▲, a destra il nome del paese.
Un clic sul riquadro apre sopra il footer una barra sovrapposta con l'evoluzione della notte
(‹ giorno ›, fasce di 3 h fino all'alba); un altro clic (o Esc) la chiude.
"""
from __future__ import annotations

import threading
import tkinter as tk
from datetime import datetime

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


class ForecastFooter(tk.Frame):
    def __init__(self, master: tk.Misc) -> None:
        super().__init__(master, bg=FOOT_BG, padx=16, pady=5)
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

        # Riquadro meteo (cliccabile)
        self.box = tk.Frame(self, bg=BOX_BG, highlightthickness=1, highlightbackground=BOX_BORDER,
                            padx=8, pady=3, cursor="hand2")
        self.box.pack(side="left")
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

        # Barra sovrapposta con l'evoluzione della notte (posizionata sopra il footer)
        top = self.winfo_toplevel()
        self.panel = tk.Frame(top, bg=BOX_BG, highlightthickness=1, highlightbackground=BOX_BORDER,
                              padx=8, pady=6)
        self.prev = self._nav("‹", -1)
        self.day = tk.Label(self.panel, bg=BOX_BG, fg=TEXT, font=("Segoe UI", 11, "bold"), padx=4)
        self.day.pack(side="left")
        self.next = self._nav("›", 1)
        self.slots = tk.Frame(self.panel, bg=BOX_BG)
        self.slots.pack(side="left", fill="x", expand=True, padx=(8, 0))
        top.bind("<Escape>", lambda _e: self.close(), add="+")
        self._show_box()

    def _nav(self, text: str, delta: int) -> tk.Label:
        lab = tk.Label(self.panel, text=text, bg=BOX_BG, fg=NAV_ON, font=("Segoe UI", 20, "bold"),
                       padx=6, cursor="hand2")
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
        if not self._result or not self._result.nights:
            return
        self._open = True
        self.update_idletasks()
        self.panel.place(x=16, rely=1.0, y=-(self.winfo_height() + 2), anchor="sw", relwidth=1.0, width=-32)
        self.panel.lift()
        self.box_arrow.configure(text="▼")

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
            self._show_box("Imposta la posizione in Impostazioni per il meteo")
            return
        if self._result is None:
            self._show_box("Aggiornamento meteo…")
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
            self._show_box(nf.friendly_error(result.error))
            return
        self._result = result
        if self._index >= len(result.nights):
            self._index = 0
        self._show_box()
        self._bind_night()

    # ------------------------------------------------------------------ vista

    def _show_place(self) -> None:
        self.place_label.configure(text=self._label or self._town)

    def _show_box(self, message: str = "") -> None:
        first = nf.first_slot(self._result) if self._result and not message else None
        if first is None:
            self.box_icon.configure(image="")
            self.box_icon.pack_forget()
            self.box_text.configure(text=message, fg=TEXT)
            self.box_arrow.pack_forget()
            return
        label, slot = first
        self.box_icon.configure(image=self._icon(nf.kind(slot.code), 22) or "")
        self.box_icon.pack(side="left", before=self.box_text)
        meta = nf.slot_meta(slot)
        self.box_text.configure(text=f"{label} {slot.when:%H:%M}" + (f" · {meta}" if meta else ""),
                                fg=RAIN if slot.mm >= 0.05 else TEXT)
        self.box_arrow.pack(side="left")

    def _shift(self, delta: int) -> None:
        if not self._result or not self._result.nights:
            return
        nxt = self._index + delta
        if 0 <= nxt < len(self._result.nights):
            self._index = nxt
            self._bind_night()

    def _bind_night(self) -> None:
        for child in self.slots.winfo_children():
            child.destroy()
        night = self._result.nights[self._index]
        self.day.configure(text=nf.day_caption(night.start, self._result.now))
        for i, slot in enumerate(night.slots):
            cell = tk.Frame(self.slots, bg=BOX_BG)
            cell.pack(side="left", padx=(0 if i == 0 else 10, 0))
            tk.Label(cell, image=self._icon(nf.kind(slot.code)) or "", bg=BOX_BG).pack(side="left")
            texts = tk.Frame(cell, bg=BOX_BG)
            texts.pack(side="left", padx=(4, 0))
            tk.Label(texts, text=f"{slot.when:%H:%M}", bg=BOX_BG, fg=TIME,
                     font=("Segoe UI", 11, "bold")).pack(anchor="w")
            meta = nf.slot_meta(slot)
            if meta:
                tk.Label(texts, text=meta, bg=BOX_BG, fg=RAIN if slot.mm >= 0.05 else TEXT,
                         font=("Segoe UI", 9, "bold")).pack(anchor="w")
        count = len(self._result.nights)
        self.prev.configure(fg=NAV_ON if self._index > 0 else NAV_OFF)
        self.next.configure(fg=NAV_ON if self._index + 1 < count else NAV_OFF)
