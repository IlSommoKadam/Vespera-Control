# Protocollo RemoteBridge (Vespera Helper â‰¥ 0.8.19; observe e piani â‰¥ 0.8.23; segnale Wiâ€‘Fi â‰¥ 0.8.24)

I client **Vespera Control** (Windows e Android) controllano **Vespera Helper** sul Pi via ADB, senza sostituire le automazioni.

## Path sul Pi

Directory file Helper (esposta anche ad ADB senza root):

`/sdcard/Android/data/com.vaonis.vesperahelper/files/`

| File | Ruolo |
|------|--------|
| `remote.req` | una riga di comando dal client |
| `remote.ack` | esito `OK\|â€¦` oppure `ERR\|â€¦` |
| `remote.state.json` | snapshot UI (wifi, hd, telescopio, system, telegram) |
| `sniff.log` | registro sniffer comandi Singularity (append, ruota a 1 MB in `sniff.log.1`) |

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
cmd|telescope|park|stop|resume|init|shutdown|observe|{json}|observeResume|{storeId}
cmd|telescope|azRelative|<gradi>
cmd|telescope|azStop
cmd|cable|unwind|zero|auto|on|off
cmd|plan|load|{json}
cmd|plan|cancel
```

`init` e `resume` possono portare la posizione: `cmd|telescope|init|{"lat":41.0,"lon":16.86}` (Helper â‰¥ 0.8.26; senza, l'Helper usa la posizione di Sistema, poi API Vespera e status). I client chiedono conferma con popup prima di ogni comando telescopio e mostrano l'esito in un popup.

### Rotazione solo azimut (Helper ≥ 0.8.44)

`cmd|telescope|azRelative|<gradi>` (±0,1…90) ruota solo il motore AZ con la route firmware `POST /v1/motors/goRelative`, body `{"AZ":<gradi>}` (firmato come park/init). Se il firmware risponde `NOT_CALIBRATED` (di giorno l'init fallisce) l'Helper ≥ 0.8.46 calibra solo l'AZ con `POST /v1/motors/seekStop {"AZ":true}` (finecorsa come nel park, braccio chiuso) e riprova. Dalla 0.8.47 la rotazione è asincrona: l'ack `OK|telescope|azRelative|0|avviata +5,0°` arriva subito e l'esito finisce in `cable.lastResult`.

`cmd|telescope|azStop` → `POST /v1/motors/stop {"AZ":true}` (hard stop delle operazioni che usano l'AZ) e interrompe anche lo srotolamento a passi.

### Giri del cavo (Helper ≥ 0.8.47)

L'Helper somma le variazioni dell'AZ (`motors.AZ.position`, ognuna riportata in −180…+180°; campioni ogni 3 s in movimento, 15 s da fermo). Il park (`seekStop` di ALT/AZ/MAP) viene contato come gli altri movimenti. Di giorno, braccio chiuso e telescopio fermo da 5 min, con almeno mezzo giro srotola da solo i giri interi (`−360·round(giri)`) a passi di 90°, una volta al giorno. `cmd|cable|unwind` srotola tutto il conteggio, `cmd|cable|zero` lo azzera (cavo raddrizzato a mano), `cmd|cable|auto|on|off` attiva lo srotolamento automatico.
Stato in `remote.state.json` → `cable`: `{turnsDeg, turns, auto, busy, activity, lastResult, lastSampleAt, az}`.

### Piano della notte (Helper â‰¥ 0.8.23)

`cmd|plan|load|{"title","night":"YYYY-MM-DD","parkAtEnd":true,"steps":[{"start":ms,"end":ms,"name","type","ra","dec","level"}]}`
sostituisce il piano attivo (salvato in `plan.json`, sopravvive al riavvio). Ogni 30 s l'Helper:
all'inizio di un passo continua la sessione multi-notte dell'oggetto se esiste, altrimenti ne crea una
(init se serve); nelle pause non manda nulla (con le nuvole il firmware si ferma da solo); dentro un passo,
se il Vespera si Ã¨ fermato riprova ogni 10 min (max 3); errori di avvio ritentati ogni 2 min (max 5);
a fine piano park se `parkAtEnd`. Stato in `remote.state.json` â†’ `plan`:
`{active, title, night, done, message, current, steps:[{name,start,end,status}]}`.
`telescope.captureStore` riporta le sessioni multi-notte per i client.

### Tempo di tracking reale (Helper ≥ 0.8.47)

L'Helper campiona lo stato del Vespera (~ogni 20 s) e conta il tempo in tracking sullo stesso oggetto
(buchi oltre 10 min non contati). `telescope.trackingClock` = `{target, since, runMs, tonightMs}` mentre traccia
(`runMs` = tracking continuo in corso, `tonightMs` = somma della notte su quell'oggetto, notte dalle 12 alle 12).
Nel piano ogni passo ha `trackedMs` (tempo tracciato su quell'oggetto nel suo orario, o dopo se sfora) e
`plan.trackedMs` è il totale. Righe `details` «Tempo tracking» e «Tempo piano»; i client sommano `trackedMs`
per oggetto (più passi dello stesso oggetto) e mostrano il totale del piano.

### Stato dettagliato telescopio (Helper â‰¥ 0.8.36)

`telescope.details` = `[{"label","value"}, â€¦]`: le stesse righe del pannello Telescopio â€º Stato dell'Helper
(stato, braccio, motori, fuoco, coordinate, filtro, temperatura, firmware, errore, **Foto interne %** con soglia sync, batteria, ID).
`telescope.storage` / `storageUsedPercent` usano la lettura FTP `/USER` dell'Helper; nuovi campi `instrumentError`, `arm`, `lastTarget`.
I client mostrano queste righe nel tab Telescopio (con Helper vecchi le ricavano dai singoli campi) al posto della riga riassuntiva.
Dalla 0.8.37 l'Helper rilegge la memoria interna via FTP anche senza pannello aperto (ogni 10 min, 2 min finchÃ© non riesce); nel frattempo la riga vale Â«controllo FTPâ€¦Â».

### Footer meteo (Control ≥ 0.2.53, Helper ≥ 0.8.48)

In fondo ai client: un riquadro con la prima previsione della sera (Open-Meteo) e ▲; un tap apre sopra il footer
una barra con l'evoluzione della notte a fasce di 3 h (‹ giorno ›), come il footer dell'Helper. A destra solo il
nome del paese. Posizione: quella salvata nel client; se coincide col sito dell'Helper (entro ~0,1°) usano da
`telescope` il primo pezzo di `siteLabel` e `nightStartHour` / `nightEndHour` (= fine / inizio giornata del sito;
default 19 → 10), altrimenti il nome viene dal reverse geocoding (BigDataCloud).

### Coda sync foto (Helper ≥ 0.8.37)

`remote.state.json` → `sync`: `running`, `paused`, `autoSync`, `nextAutoAt`, `lastSync`, avanzamento corrente
(`phase` list|download|disk|verify|delete|done|error|paused, `fileName`, `fileIndex/fileTotal`, `fileBytes/fileSize`,
`doneBytes/totalBytes`, `speedBps`, `etaMs`, `permille`) e coda dell'ultima sync:
`queueTotal`, `queuePending`, `queuePendingBytes`, `queueCopied`, `queueSkipped`, `queueFailed`, `queueFrom`,
`queue:[{folder,name,size,status}]` con `status` pending|active|copied|skipped|failed (max 400 righe attorno al file in corso).
I client la mostrano nel tab Foto, aggiornata ogni 3 s; Pausa/Riprendi usano `cmd|sync|pause|resume`.

Esempio ADB manuale:

```bat
adb connect 192.168.1.4:5555
adb -s 192.168.1.4:5555 shell rm -f /sdcard/Android/data/com.vaonis.vesperahelper/files/remote.ack
echo cmd|telescope|park> %TEMP%\remote.req
adb -s 192.168.1.4:5555 push %TEMP%\remote.req /sdcard/Android/data/com.vaonis.vesperahelper/files/remote.req
adb -s 192.168.1.4:5555 pull /sdcard/Android/data/com.vaonis.vesperahelper/files/remote.ack
adb -s 192.168.1.4:5555 pull /sdcard/Android/data/com.vaonis.vesperahelper/files/remote.state.json
adb -s 192.168.1.4:5555 pull /sdcard/Android/data/com.vaonis.vesperahelper/files/sniff.log
```

## Anteprima (non passa da remote.req)

FTP diretto verso Helper sul Pi (come StarStacKadam):

| Sorgente | Porta |
|----------|------:|
| HD montato | 2121 |
| Proxy telescopio | 2122 |

Lâ€™anteprima mostra lâ€™elenco oggetti e lâ€™ultimo `*-output.jpg`. **Nessuno stacking** sul client.
Clic sullâ€™immagine apre lâ€™editor locale (autostretch, livelli, saturazione, salva/condividi).

## scrcpy

- Windows: client desktop Genymobile (tab Schermo Pi).
- Android: fork `Scrcpy-for-Android` da integrare nel tab Schermo di `VesperaControl`.

Stesso IP:5555 ADB del bridge comandi.
