# Protocollo RemoteBridge (Vespera Helper ≥ 0.8.19)

I client **Vespera Control** (Windows e Android) controllano **Vespera Helper** sul Pi via ADB, senza sostituire le automazioni.

## Path sul Pi

Directory file Helper (esposta anche ad ADB senza root):

`/sdcard/Android/data/com.vaonis.vesperahelper/files/`

| File | Ruolo |
|------|--------|
| `remote.req` | una riga di comando dal client |
| `remote.ack` | esito `OK\|…` oppure `ERR\|…` |
| `remote.state.json` | snapshot UI (wifi, hd, telescopio, system, telegram) |

## Comandi

```
ping
get|state
get|system
get|telegram
set|system|{json}
set|device|<ssid>|<bssid>|<freqMhz>
set|telegram|{json}
cmd|wifi|connect|disconnect|scan|refresh
cmd|singularity|restart|start|check
cmd|hd|list|mount|<spec>|unmount|eject|<spec>|wake|status
cmd|sync|now|resume|pause
cmd|telescope|park|stop|resume|init|shutdown
```

Esempio ADB manuale:

```bat
adb connect 192.168.1.4:5555
adb -s 192.168.1.4:5555 shell rm -f /sdcard/Android/data/com.vaonis.vesperahelper/files/remote.ack
echo cmd|telescope|park> %TEMP%\remote.req
adb -s 192.168.1.4:5555 push %TEMP%\remote.req /sdcard/Android/data/com.vaonis.vesperahelper/files/remote.req
adb -s 192.168.1.4:5555 pull /sdcard/Android/data/com.vaonis.vesperahelper/files/remote.ack
adb -s 192.168.1.4:5555 pull /sdcard/Android/data/com.vaonis.vesperahelper/files/remote.state.json
```

## Anteprima (non passa da remote.req)

FTP diretto verso Helper sul Pi (come StarStacKadam):

| Sorgente | Porta |
|----------|------:|
| HD montato | 2121 |
| Proxy telescopio | 2122 |

L’anteprima mostra l’elenco oggetti e l’ultimo `*-output.jpg`. **Nessuno stacking** sul client.

## scrcpy

- Windows: client desktop Genymobile (tab Schermo Pi).
- Android: fork `Scrcpy-for-Android` da integrare nel tab Schermo di `VesperaControl`.

Stesso IP:5555 ADB del bridge comandi.
