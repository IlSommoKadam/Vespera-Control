@echo off
cd /d "%~dp0"
where pyw >nul 2>&1
if errorlevel 1 (
  echo Python 3 non trovato. Installa Python da python.org e riprova.
  pause
  exit /b 1
)
start "" pyw -3 "%~dp0vespera_control.pyw"

