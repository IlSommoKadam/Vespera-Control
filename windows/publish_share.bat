@echo off
cd /d "%~dp0"
set SHARE=C:\WORK\ESA\Share
if not exist "%SHARE%" mkdir "%SHARE%"

powershell -NoProfile -Command ^
  "$src='%~dp0'; $zip=Join-Path 'C:\WORK\ESA\Share' 'VesperaControl-Win.zip';" ^
  "if (Test-Path $zip) { Remove-Item $zip -Force };" ^
  "Compress-Archive -Path (Join-Path $src 'vespera_control'),(Join-Path $src 'vespera_control.pyw'),(Join-Path $src 'Avvia.bat'),(Join-Path $src 'PROTOCOL.md'),(Join-Path $src 'README.md') -DestinationPath $zip -Force;" ^
  "@{ name='VesperaControl-Win'; version='0.2.0'; versionCode=3; package='VesperaControl-Win.zip'; source='mega' } |" ^
  "ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path 'C:\WORK\ESA\Share' 'vespera-control-win-version.json');" ^
  "Write-Host ('Pubblicato ' + $zip)"

echo Fatto.
