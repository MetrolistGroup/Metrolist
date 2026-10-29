@echo off
setlocal
echo Setting up Metrolist Audio Streamer to run automatically on Windows Startup...

powershell -NoProfile -Command "$wsh = New-Object -ComObject WScript.Shell; $p = Join-Path ([System.Environment]::GetFolderPath('Startup')) 'MetrolistAudioStreamer.lnk'; $s = $wsh.CreateShortcut($p); $s.TargetPath = 'wscript.exe'; $s.Arguments = '\"%~dp0stream-audio-silent.vbs\"'; $s.WorkingDirectory = '%~dp0'; $s.Save()"

if errorlevel 0 (
    echo [SUCCESS] Metrolist Audio Streamer will now start automatically when Windows boots!
) else (
    echo [ERROR] Failed to create startup shortcut.
)
pause
