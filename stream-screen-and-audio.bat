@echo off
title Metrolist Screen & Audio Mirroring
echo ========================================================
echo Mirroring Screen + Audio from Android Tablet to Laptop...
echo Close the window to stop mirroring.
echo ========================================================

set SCRCPY="C:\Users\ASANKA\AppData\Local\Microsoft\WinGet\Packages\Genymobile.scrcpy_Microsoft.Winget.Source_8wekyb3d8bbwe\scrcpy-win64-v4.1\scrcpy.exe"

%SCRCPY% -d --audio-source=output --audio-buffer=50 %*
