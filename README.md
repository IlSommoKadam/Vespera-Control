# Vespera Control

Client remoti all-in-one per pilotare **Vespera Helper** sul Pi (osservatorio).

| Cartella | Piattaforma | Package / avvio |
|----------|-------------|-----------------|
| [`android/`](android/) | APK | `com.vaonis.vesperacontrol` |
| [`windows/`](windows/) | Desktop Python | `Avvia.bat` / `vespera_control.pyw` |

Sul telescopio resta **[Vespera Helper](https://github.com/IlSommoKadam/VesperaHelper)** (≥ 0.8.19, RemoteBridge).

## Architettura

```
[ Vespera Control Win / APK ]
        │ ADB (TCP)  +  FTP preview (opz.)  +  scrcpy (opz.)
        ▼
[ Vespera Helper sul Pi ]  →  telescopio / HD / automazioni
```

Protocollo comandi: file `remote.req` / `remote.ack` / `remote.state.json` sotto la dir files del Helper. Dettagli in [`windows/PROTOCOL.md`](windows/PROTOCOL.md).

## Aggiornamenti

Build Share/Mega: cartella pubblica Mega e `C:\WORK\ESA\Share` (vedi script in `windows/publish_share.bat` e tab aggiornamenti nei client).

## Repo correlati

- Helper (Pi): https://github.com/IlSommoKadam/VesperaHelper
- Questo monorepo: https://github.com/IlSommoKadam/Vespera-Control
