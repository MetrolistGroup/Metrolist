Set WshShell = CreateObject("WScript.Shell")

scrcpyDir = "C:\Users\ASANKA\AppData\Local\Microsoft\WinGet\Packages\Genymobile.scrcpy_Microsoft.Winget.Source_8wekyb3d8bbwe\scrcpy-win64-v4.1"
adbPath = scrcpyDir & "\adb.exe"
scrcpyPath = scrcpyDir & "\scrcpy.exe"

Do
    ' Wait silently until USB device is connected
    WshShell.Run """" & adbPath & """ -d wait-for-device", 0, True
    
    ' Stream audio silently in background (blocks until tablet is disconnected or scrcpy is stopped)
    WshShell.Run """" & scrcpyPath & """ -d --no-video --no-window --audio-source=output --audio-buffer=50", 0, True
    
    WScript.Sleep 2000
Loop
