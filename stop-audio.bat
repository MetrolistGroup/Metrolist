@echo off
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'wscript.exe' -and $_.CommandLine -like '*stream-audio-silent.vbs*' } | Stop-Process -Force" >nul 2>&1
taskkill /F /IM scrcpy.exe >nul 2>&1
