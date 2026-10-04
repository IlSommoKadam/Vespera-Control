Placeholder per il binario ADB.

Opzioni:
1) Copia qui (e poi estrai a runtime in filesDir/adb/adb) il binary adb
   per l'ABI del device client (es. arm64-v8a).
2) Imposta un path assoluto nel tab Connessioni (campo "Path adb").

Senza un binario valido, AdbBridge userà il comando "adb" dal PATH
di Runtime.exec (utile solo in ambienti di sviluppo particolari).
