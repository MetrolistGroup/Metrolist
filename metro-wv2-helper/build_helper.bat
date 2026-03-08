@echo off
setlocal enabledelayedexpansion

echo [1/3] Cleaning previous builds...
if exist bin rmdir /s /q bin
if exist obj rmdir /s /q obj

echo [2/3] Publishing Metro WebView2 Helper (Standalone)...
dotnet publish -c Release -r win-x64 --self-contained true /p:PublishSingleFile=true /p:IncludeNativeLibrariesForSelfExtract=true /p:DebugType=None /p:DebugSymbols=false /p:PublishReadyToRun=true

if %errorlevel% neq 0 (
    echo.
    echo ERROR: Build failed!
    exit /b %errorlevel%
)

echo [3/3] Deploying to Desktop App resources...
set DEST=..\desktopApp\src\desktopMain\resources\windows
if not exist "%DEST%" mkdir "%DEST%"

copy /Y "bin\Release\net8.0-windows\win-x64\publish\metro-wv2-helper.exe" "%DEST%\"

echo.
echo SUCCESS: Helper deployed to %DEST%\metro-wv2-helper.exe
pause
