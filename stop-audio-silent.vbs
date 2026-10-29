Set WshShell = CreateObject("WScript.Shell")
WshShell.Run "powershell -NoProfile -Command ""Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'wscript.exe' -and $_.CommandLine -like '*stream-audio-silent.vbs*' } | Stop-Process -Force""", 0, True
WshShell.Run "taskkill /F /IM scrcpy.exe", 0, True
