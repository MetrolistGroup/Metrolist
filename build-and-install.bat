@echo off
setlocal
cd /d "%~dp0"

echo ========================================================
echo Building Metrolist FOSS Debug and Installing on Tablet...
echo ========================================================

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ADB=C:\Users\ASANKA\AppData\Local\Android\Sdk\platform-tools\adb.exe"

call gradlew.bat :app:assembleFossDebug
if errorlevel 1 (
    echo [ERROR] Build failed!
    pause
    exit /b %errorlevel%
)

echo.
echo Installing APK onto USB connected Android tablet...
%ADB% -d install -r -d app\build\outputs\apk\foss\debug\app-foss-debug.apk
if errorlevel 1 (
    echo [ERROR] Installation failed!
    pause
    exit /b %errorlevel%
)

echo.
echo Launching Metrolist on tablet...
%ADB% -d shell am start -n com.metrolist.music/.MainActivity
echo Done!
pause
