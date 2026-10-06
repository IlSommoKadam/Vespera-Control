# Vespera Control

App Android all-in-one (scaffold) per controllare da remoto **Vespera Helper** tramite ADB e, in seguito, mirror schermo via scrcpy.

- **applicationId**: `com.vaonis.vesperacontrol`
- **version**: 0.2.15 (versionCode 18)
- **minSdk**: 29 · **compileSdk / targetSdk**: 35 · **Java**: 17

## Dipendenze di sistema

### 1. Vespera Helper ≥ 0.6.95 (RemoteBridge)

L’app non parla direttamente con l’hardware: invia comandi al Helper sul Pi (**Vespera Helper ≥ 0.8.19**).

| File remoto | Path |
|-------------|------|
| Richiesta | `/sdcard/Android/data/com.vaonis.vesperahelper/files/remote.req` |
| Stato | `/sdcard/Android/data/com.vaonis.vesperahelper/files/remote.state.json` |
| Ack | `/sdcard/Android/data/com.vaonis.vesperahelper/files/remote.ack` |

`AdbBridge` usa il protocollo a pipe di RemoteBridge (Helper ≥ 0.6.95):

```
ping
cmd|wifi|connect
cmd|telescope|park
cmd|hd|mount|
get|state
```

Vedi anche [`../windows/PROTOCOL.md`](../windows/PROTOCOL.md).

I comandi JSON stub iniziali sono stati sostituiti dal formato pipe.

### 2. Fork Scrcpy-for-Android (solo integrazione futura)

Il tab **Schermo** è uno stub: campi IP / porta / bitrate e Activity “Apri mirror”.

Integrazione prevista con il package `org.client.scrcpy` dal fork locale:

`c:\Danger\MieiProgetti\Vespera II - Osservatorio\Scrcpy-for-Android`

**Non** copiare l’intero progetto Scrcpy dentro VesperaControl: linkare il modulo/AAR quando si implementa il mirror reale.

## Tab UI

| Tab | Contenuto |
|-----|-----------|
| Connessioni | IP, porta ADB, path adb, Connetti / Disconnetti, pull stato |
| Foto | Scatta / Avvia / Ferma osservazione → `AdbBridge.sendCommand` |
| Telescopio | Park, Unpark, Monta/Smonta HD, GoTo, Abort |
| Anteprima | Stub FTP host:2121/2122 + lista + `ImageView` (logica FTP = TODO) |
| Sistema | Monta HD, Smonta HD, Riavvia Helper, refresh stato |
| Notifiche | Stub: mostra `remote.state.json` / `remote.ack` |
| Schermo | Stub mirror scrcpy |

**Stacking**: non incluso di proposito.

## Build

Requisiti: Android Studio Ladybug+ / JDK 17, Android SDK 35.

```text
settings.gradle
build.gradle          (AGP 8.7.2)
app/build.gradle
```

Apri la cartella `android/` di questo monorepo in Android Studio, poi **Run** su device/emulatore.

## Struttura essenziale

```text
app/src/main/java/com/vaonis/vesperacontrol/
  MainActivity.java
  AdbBridgeHolder.java
  adb/AdbBridge.java
  ui/connections|foto|telescopio|anteprima|sistema|notifiche|schermo/
```

## Note

- Permesso `INTERNET` dichiarato (FTP / rete / mirror).
- Traffico HTTP dell'app solo in HTTPS. FTP e ADB usano socket TCP verso il telescopio.
- I comandi JSON (`{"cmd":"park"}` ecc.) sono stub allineati al protocollo RemoteBridge; adattare i nomi campo quando il contratto Helper è definitivo.
