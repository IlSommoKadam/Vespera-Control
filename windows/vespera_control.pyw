# -*- coding: utf-8 -*-
"""Launcher Vespera Control (Windows)."""

import sys
from pathlib import Path

# Evita che la taskbar Windows raggruppi sotto pythonw.exe (icona Python).
APP_USER_MODEL_ID = "IlSommoKadam.VesperaControl"

try:
    import ctypes

    ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(APP_USER_MODEL_ID)
except Exception:
    pass

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from vespera_control.app import main

if __name__ == "__main__":
    main()
