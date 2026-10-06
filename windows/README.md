# Vespera Control (Windows)

Client all-in-one per pilotare l’osservatorio dal PC. Sul Pi resta **Vespera Helper**.

1. **Helper remoto** — comandi/settings via ADB → RemoteBridge su Helper
2. **Anteprima** — FTP oggetti + ultimo `*-output.jpg` (senza stacking); clic sull’immagine per livelli / autostretch / salva / condividi
3. **Schermo Pi** — scrcpy desktop (opzionale)

## Requisiti

- Python 3 + Pillow (`pip install pillow`)
- `adb` / `scrcpy`
- Sul Pi: **Vespera Helper ≥ 0.8.19** con RemoteBridge

## Avvio

Usa il collegamento **Avvia Vespera Control** (icona `vespera.ico`).  
In alternativa:

```bat
Avvia.bat
```

## Protocollo

Vedi [PROTOCOL.md](PROTOCOL.md).

## Struttura

```
vespera_control/
  adb_bridge.py
  preview_ftp.py
  preview_editor.py
  scrcpy_util.py
  updates.py
  app.py
vespera_control.pyw
```
