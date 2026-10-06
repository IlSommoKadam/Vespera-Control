@echo off
cd /d "%~dp0"
set SHARE=C:\WORK\ESA\Share\pub\Pubblici
if not exist "%SHARE%" mkdir "%SHARE%"

powershell -NoProfile -Command ^
  "$src='%~dp0'; $zip=Join-Path '%SHARE%' 'VesperaControl-Win.zip';" ^
  "if (Test-Path $zip) { Remove-Item $zip -Force };" ^
  "$lnk=Join-Path $src 'Avvia Vespera Control.lnk'; $ico=Join-Path $src 'vespera.ico'; $script=Join-Path $src 'vespera_control.pyw';" ^
  "$pyw = (& py -3 -c \"import sys; from pathlib import Path; print(Path(sys.executable).with_name('pythonw.exe'))\").Trim();" ^
  "if (-not (Test-Path $pyw)) { throw 'pythonw.exe non trovato' };" ^
  "$sh=New-Object -ComObject WScript.Shell; $s=$sh.CreateShortcut($lnk);" ^
  "$s.TargetPath=$pyw; $s.Arguments=('\"'+$script+'\"'); $s.WorkingDirectory=$src; $s.IconLocation=($ico+',0');" ^
  "$s.Description='Avvia Vespera Control'; $s.WindowStyle=7; $s.Save();" ^
  "Compress-Archive -Path (Join-Path $src 'vespera_control'),(Join-Path $src 'vespera_control.pyw'),(Join-Path $src 'Avvia.bat'),(Join-Path $src 'Avvia Vespera Control.lnk'),(Join-Path $src 'vespera.ico'),(Join-Path $src 'vespera_launcher_icon.png'),(Join-Path $src 'PROTOCOL.md'),(Join-Path $src 'README.md') -DestinationPath $zip -Force;" ^
  "@{ name='VesperaControl-Win'; version='0.2.44'; versionCode=47; package='VesperaControl-Win.zip'; source='mega' } |" ^
  "ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path '%SHARE%' 'vespera-control-win-version.json');" ^
  "Write-Host ('Pubblicato ' + $zip)"

echo Fatto.
