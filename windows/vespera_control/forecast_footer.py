"""Footer meteo della finestra: una riga con ‹ giorno ›, fasce di 3 h fino all'alba e 📍 luogo.

Stessa visualizzazione di NightForecastFooter in Vespera Helper.
"""
from __future__ import annotations

import threading
import tkinter as tk
from datetime import datetime

from vespera_control import night_forecast as nf

REFRESH_MS = 30 * 60 * 1000
FOOT_BG = "#E8EEF4"
TEXT = "#37474F"
TIME = "#1A237E"
RAIN = "#1565C0"
NAV_ON = "#1565C0"
NAV_OFF = "#B0BEC5"


class ForecastFooter(tk.Frame):
    def __init__(self, master: tk.Misc) -> None:
        super().__init__(master, bg=FOOT_BG, padx=8, pady=4)
        self._site: tuple[float, float] | None = None
        self._place = ""
        self._hours = (nf.DEFAULT_NIGHT_START, nf.DEFAULT_NIGHT_END)
        self._result: nf.Result | None = None
        self._index = 0
        self._gen = 0
        self._after: str | None = None
        self._icons: dict[str, object] = {}

        self.prev = self._nav("‹", -1)
        self.day = tk.Label(self, bg=FOOT_BG, fg=TEXT, font=("Segoe UI", 11, "bold"), padx=4)
        self.day.pack(side="left")
        self.next = self._nav("›", 1)
        self.place_box = tk.Frame(self, bg=FOOT_BG)
        self.place_box.pack(side="right")
        self.pin = tk.Label(self.place_box, bg=FOOT_BG)
        self.pin.pack(side="left", padx=(0, 4))
        self.place = tk.Label(self.place_box, bg=FOOT_BG, fg=TEXT, font=("Segoe UI", 11, "bold"))
        self.place.pack(side="left")
        self.slots = tk.Frame(self, bg=FOOT_BG)
        self.slots.pack(side="left", fill="x", expand=True, padx=(6, 0))
        self._update_nav()

    def _nav(self, text: str, delta: int) -> tk.Label:
        lab = tk.Label(self, text=text, bg=FOOT_BG, fg=NAV_ON, font=("Segoe UI", 20, "bold"),
                       padx=6, cursor="hand2")
        lab.pack(side="left")
        lab.bind("<Button-1>", lambda _e: self._shift(delta))
        return lab

    def _icon(self, name: str):
        if name not in self._icons:
            try:
                from PIL import ImageTk
                self._icons[name] = ImageTk.PhotoImage(nf.icon_image(name, 28 if name != "pin" else 16))
            except Exception:
                self._icons[name] = None
        return self._icons[name]

    # ------------------------------------------------------------------ dati

    def set_site(self, site: tuple[float, float] | None, place: str = "",
                 night_hours: tuple[int, int] | None = None) -> None:
        """Nuovo sito / etichetta / ore notte: riscarica solo se qualcosa è cambiato."""
        hours = night_hours or (nf.DEFAULT_NIGHT_START, nf.DEFAULT_NIGHT_END)
        if site is not None and not place:
            place = f"{site[0]:.2f}, {site[1]:.2f}"
        changed = (site != self._site) or (hours != self._hours)
        self._place = place
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
            self._clear_slots()
            self.day.configure(text="Imposta la posizione in Impostazioni per le previsioni.")
            self._update_nav()
            return
        if self._result is None:
            self.day.configure(text="Aggiornamento meteo…")
        lat, lon = self._site
        start_h, end_h = self._hours

        def work() -> None:
            try:
                result = nf.fetch(lat, lon, start_h, end_h)
            except Exception as exc:  # rete, JSON…
                result = nf.Result([], datetime.now(), str(exc) or exc.__class__.__name__)
            try:
                self.after(0, lambda: self._apply(gen, result))
            except (tk.TclError, RuntimeError):
                pass

        threading.Thread(target=work, daemon=True).start()

    def _apply(self, gen: int, result: nf.Result) -> None:
        if gen != self._gen:
            return
        if result.error or not result.nights:
            if self._result is not None and self._result.nights:
                self._bind_night()  # tieni l'ultimo aggiornamento buono
                return
            self._clear_slots()
            self.day.configure(text=nf.friendly_error(self._place, result.error))
            self._update_nav()
            return
        self._result = result
        if self._index >= len(result.nights):
            self._index = 0
        self._bind_night()

    # ------------------------------------------------------------------ vista

    def _shift(self, delta: int) -> None:
        if not self._result or not self._result.nights:
            return
        nxt = self._index + delta
        if 0 <= nxt < len(self._result.nights):
            self._index = nxt
            self._bind_night()

    def _show_place(self) -> None:
        self.place.configure(text=self._place)
        if self._place:
            self.pin.configure(image=self._icon("pin") or "")
            self.pin.pack(side="left", padx=(0, 4), before=self.place)
        else:
            self.pin.pack_forget()

    def _clear_slots(self) -> None:
        for child in self.slots.winfo_children():
            child.destroy()

    def _bind_night(self) -> None:
        self._clear_slots()
        night = self._result.nights[self._index]
        self.day.configure(text=nf.day_caption(night.start, self._result.now))
        for i, slot in enumerate(night.slots):
            cell = tk.Frame(self.slots, bg=FOOT_BG)
            cell.pack(side="left", padx=(0 if i == 0 else 8, 0))
            tk.Label(cell, image=self._icon(nf.kind(slot.code)) or "", bg=FOOT_BG).pack(side="left")
            texts = tk.Frame(cell, bg=FOOT_BG)
            texts.pack(side="left", padx=(4, 0))
            tk.Label(texts, text=f"{slot.when:%H:%M}", bg=FOOT_BG, fg=TIME,
                     font=("Segoe UI", 11, "bold")).pack(anchor="w")
            meta = nf.slot_meta(slot)
            if meta:
                tk.Label(texts, text=meta, bg=FOOT_BG, fg=RAIN if slot.mm >= 0.05 else TEXT,
                         font=("Segoe UI", 9, "bold")).pack(anchor="w")
        self._update_nav()

    def _update_nav(self) -> None:
        count = len(self._result.nights) if self._result else 0
        self.prev.configure(fg=NAV_ON if self._index > 0 else NAV_OFF)
        self.next.configure(fg=NAV_ON if self._index + 1 < count else NAV_OFF)
