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
cmd|telescope|motor|<AZ|ALT>|<rel|abs>|<gradi>
cmd|cable|unwind|set|zero|auto|on|off
cmd|plan|load|{json}
cmd|plan|cancel
```

`init` e `resume` possono portare la posizione: `cmd|telescope|init|{"lat":41.0,"lon":16.86}` (Helper â‰¥ 0.8.26; senza, l'Helper usa la posizione di Sistema, poi API Vespera e status). I client chiedono conferma con popup prima di ogni comando telescopio e mostrano l'esito in un popup.

### Rotazione solo azimut (Helper ≥ 0.8.44)

`cmd|telescope|azRelative|<gradi>` (±0,1…90) ruota solo il motore AZ con la route firmware `POST /v1/motors/goRelative`, body `{"AZ":<gradi>}` (firmato come park/init). Se il firmware risponde `NOT_CALIBRATED` (di giorno l'init fallisce) l'Helper ≥ 0.8.46 calibra solo l'AZ con `POST /v1/motors/seekStop {"AZ":true}` (finecorsa come nel park, braccio chiuso) e riprova. Dalla 0.8.47 la rotazione è asincrona: l'ack `OK|telescope|azRelative|0|avviata +5,0°` arriva subito e l'esito finisce in `cable.lastResult`.

`cmd|telescope|azStop` → `POST /v1/motors/stop {"AZ":true}` (hard stop delle operazioni che usano l'AZ) e interrompe anche lo srotolamento a passi.

### Movimento manuale AZ/ALT (Helper ≥ 0.8.48)

`cmd|telescope|motor|<AZ|ALT>|<rel|abs>|<gradi>`: `rel` muove l'asse di ±0,1…180° (`POST /v1/motors/goRelative`), `abs` lo porta a una posizione (`POST /v1/motors/goAbsolute`; AZ 0…360°, ALT −90…90°, −90° = braccio chiuso). Body `{"<asse>":<gradi>}`; se NOT_CALIBRATED l'Helper fa prima `seekStop` di quell'asse (per ALT chiude il braccio). Asincrono: ack `OK|telescope|motor|0|avviato …`, esito in `cable.lastResult`. `cmd|telescope|azStop` (o `motorStop`) ferma AZ e ALT (`/v1/motors/stop {"AZ":true,"ALT":true}`). Il firmware non controlla il Sole: alzare il braccio di giorno è a rischio dell'utente. Posizione corrente in `cable.az` / `cable.alt`.

### Giri del cavo (Helper ≥ 0.8.47)

L'Helper somma le variazioni dell'AZ (`motors.AZ.position`, ognuna riportata in −180…+180°; campioni ogni 3 s in movimento, 15 s da fermo). Il park (`seekStop` di ALT/AZ/MAP) viene contato come gli altri movimenti. Di giorno, braccio chiuso e telescopio fermo da 5 min, con almeno mezzo giro srotola da solo i giri interi (`−360·round(giri)`) a passi di 90°, una volta al giorno. `cmd|cable|unwind` srotola tutto il conteggio, `cmd|cable|zero` lo azzera (cavo raddrizzato a mano), `cmd|cable|auto|on|off` attiva lo srotolamento automatico.
Stato in `remote.state.json` → `cable`: `{turnsDeg, turns, auto, busy, activity, lastResult, lastSampleAt, az, alt, refAz, obsTarget, obsStartAz, obsStartAlt, log}`. `log` (Helper ≥ 0.8.50) = ultime 15 righe del registro movimenti `motion.log` (nella cartella dell'Helper, 300 righe), la più recente per prima.

### Set e riposizionamento a fine osservazione (Helper ≥ 0.8.49)

`cmd|cable|set` (alias `zero`): con il cavo dritto, l'AZ attuale diventa il riferimento (`refAz`) e il conteggio riparte da 0. All'avvio di un'osservazione (OBS_STARTED/RESUMED) l'Helper salva AZ/ALT/cavo e li manda su Telegram. Alla fine (OBS_STOPPED/FINISHED), dopo 3 min e se nessun passo del piano parte entro 30 min, manda «inizio riposizionamento», ruota l'azimut di `−turnsDeg` (torna al Set) e manda lo stato finale: AZ, ALT, braccio, scarto dal Set, cavo srotolato o no. Di giorno a braccio chiuso fa lo stesso (una volta al giorno). `cmd|cable|auto|off` disattiva entrambi.

Flag Telegram (Helper ≥ 0.8.51, in `set|telegram|{json}`): `reposition` accende i messaggi di riposizionamento al Set; `park` quelli di park avviato e concluso (braccio chiuso). `batteryOffMains` viene notificato anche alla connessione del Vespera, se è già a batteria.

### Piano della notte (Helper â‰¥ 0.8.23)

`cmd|plan|load|{"title","night":"YYYY-MM-DD","parkAtEnd":true,"steps":[{"start":ms,"end":ms,"name","type","ra","dec","level"}]}`
sostituisce il piano attivo (salvato in `plan.json`, sopravvive al riavvio). Ogni 30 s l'Helper:
all'inizio di un passo continua la sessione multi-notte dell'oggetto se esiste, altrimenti ne crea una
(init se serve); nelle pause non manda nulla; dentro un passo i tentativi seguono la sezione «Tentativi di tracking» sotto;
a fine piano park se `parkAtEnd`. Stato in `remote.state.json` â†’ `plan`:
`{active, title, night, done, message, current, steps:[{name,start,end,status}]}`.
`telescope.captureStore` riporta le sessioni multi-notte per i client.

### Tentativi di tracking ed errori del firmware (Helper ≥ 0.8.52, Control ≥ 0.2.69)

Gli errori vengono dalle tabelle `ERRORS` del firmware 3.3.6 (`PREFISSO.NOME` o `PREFISSO_NOME`), classificati da `FirmwareErrors`:
- **non si ritenta**: `SUN_TOO_HIGH`, `SUN_TOO_HIGH_AT_START_TIME`, `SUN_FILTER_DETECTED`, `BATTERY_TOO_LOW`, memoria (`NO_USER_STORAGE`, `NOT_ENOUGH_SPACE*`, `TOO_MANY_RESUMABLE_STORED`), spegnimento/aggiornamento, guasti (`CAMERA_*`, `NOT_CALIBRATED`, `BOARD_MOTOR_ERROR`, `MCBOARD_*`, `OPEN_ARM_FAILED`), `TIME_NOT_SET`, `POSITION_NOT_SET`, fine orario (`END_TIME_ALREADY_PASSED`, `REACHED_END_TIME`), parametri (`INVALID_TARGET`, `INCORRECT_PARAMS`);
- **fermato a mano, non si ritenta**: `USER_INTERRUPTION`, `OPERATION_ABORTED`, `INTERRUPTED` (nel piano anche un'osservazione chiusa senza errori);
- **si ritenta** (nuvole e simili): astrometria (`*ASTROMETRY_FAILED`), `IMAGE_TOO_DARK/BRIGHT`, `MEAN_VARIATION_IS_TOO_BIG`, fuoco (`AUTO_FOCUS_FAILED`, `PARABOLA_FIT_FAILED`…), tracking (`TRACKING_STOPPED`, `ONE_MOTOR_STOPPED_TRACKING`, `TRACKING_TIMEOUT`, `DID_NOT_CORRECT`), acquisizione e timeout, init (`AUTO_INIT_FAILED`, `ALL_ATTEMPTS_FAILED`), `BUSY`, `REQUIRE_RESOURCES_FAILED`, ed errori sconosciuti.

`set|system|{"trackRetry":true,"trackRetryMin":10}` (default acceso, 10 min, 2…120): fuori dai piani l'Helper riprende ogni `trackRetryMin` minuti la sessione che il firmware ha fermato con un errore da ritentare, finché l'oggetto è sopra l'altezza minima e prima dell'alba. Nel piano (passo in corso) i tentativi li fa il piano: stesso intervallo, senza limite di numero finché il passo ha almeno 15 min; comandi rifiutati 5 volte ogni 2 min, poi ogni `trackRetryMin`; con un errore che non va ritentato il passo diventa «fermo: …» e riparte solo se l'oggetto torna in tracking (a mano) o al passo successivo. Stato in `system.trackRetry`, `system.trackRetryMin`.

Piano della notte (Control ≥ 0.2.69): i periodi nuvolosi o con pioggia prevista restano accesi (spenti solo quelli senza oggetti sopra 15°); «Proposta automatica» rimette le spunte così e rifà la proposta.

### Piani salvati condivisi fra APK e Windows (Control ≥ 0.2.71, Telegram ≥ 0.2.72)

File `shared.plans.json` nella cartella dell'Helper (`/sdcard/Android/data/com.vaonis.vesperahelper/files/`), letto e scritto dai client via ADB (l'Helper non lo usa): `{"version":1,"plans":[…piani come in locale…],"deleted":[{"created","at"}]}`.
Ogni client legge il file (assente = vuoto), lo unisce ai suoi piani e lo riscrive: per lo stesso `created` vince `modified` (messo a ogni «Salva»), un piano in `deleted` sparisce ovunque se non è stato risalvato dopo `at`; massimo 30 piani, cancellazioni tenute 90 giorni. Se la lettura fallisce per la rete non scrive nulla.
Quando: APK all'apertura del tab Target e a ogni Salva/Elimina; Windows 8 s dopo l'avvio, ogni 5 min e a ogni Salva/Elimina.

Copia nel bot Telegram (Control ≥ 0.2.72), per lavorare a Pi spento: stesso JSON come documento `vespera-plans.json` fissato nella chat del bot dell'Helper. Lettura: `getChat` → `pinned_message.document` → `getFile`; scrittura solo se cambia: `editMessageMedia` sullo stesso messaggio, se non riesce `sendDocument` + `pinChatMessage` (silenziosi). Token e chat id si inseriscono nei client (APK e Windows: tab Impostazioni) o si prendono dall'Helper con `get|telegram`; la prima sync col Pi li prende da sola. Se nella chat si fissa un altro messaggio la copia si perde: al giro dopo ne viene creata una nuova con i piani del client.

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

In fondo ai client: ‹ giorno › fisso e un riquadro con la prima previsione disponibile di quella notte (Open-Meteo);
solo il riquadro è cliccabile e apre sopra il footer tutte le fasce di 3 h della notte scelta, a griglia (va a capo
e rimpicciolisce le icone per stare nello schermo, senza scroll). Tap/clic fuori dal pannello lo chiude; swipe (o trascinamento)
a sinistra/destra su footer o pannello = giorno dopo/prima. A destra solo il
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
