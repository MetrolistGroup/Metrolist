@echo off
setlocal
echo Removing Metrolist Audio Streamer from Windows Startup...

powershell -NoProfile -Command "$p = Join-Path ([System.Environment]::GetFolderPath('Startup')) 'MetrolistAudioStreamer.lnk'; if (Test-Path $p) { Remove-Item $p -Force; Write-Host '[SUCCESS] Removed from Startup.' } else { Write-Host 'Not found in Startup.' }"
pause
