# Bytedeco publishes no windows-arm64 FFmpeg natives. This pairs a pinned FFmpeg 8.1 ARM64 build with JNI
# libraries compiled by JavaCPP from the same preset classes the app loads. Run inside an MSVC ARM64 environment.
param(
    [Parameter(Mandatory = $true)][string]$Output,
    [string]$Java = $env:METROLIST_FFMPEG_JAVA,
    [string]$ClassPath = $env:METROLIST_FFMPEG_CLASSPATH
)
$ErrorActionPreference = "Stop"
if (-not $Java -or -not $ClassPath) { throw "Pass -Java and -ClassPath or set METROLIST_FFMPEG_JAVA and METROLIST_FFMPEG_CLASSPATH" }
$ProgressPreference = "SilentlyContinue"
# JavaCPP runs the compiler from its output directory, so every path handed to it must be absolute.
if (-not [IO.Path]::IsPathRooted($Output)) { $Output = Join-Path (Get-Location).Path $Output }

$release = "autobuild-2026-10-31-12-59"
$name = "ffmpeg-n8.1.3-27-g8dca1586f4-winarm64-lgpl-shared-8.1"
$sha256 = "7cc09ffd85f4f74609564f157d037b202cf91f9d3a349ef42e3f72ee19277320"

$work = Join-Path (Split-Path -Parent $Output) "windows-arm64-ffmpeg-work"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$archive = Join-Path $work "$name.zip"
if (-not (Test-Path $archive) -or (Get-FileHash -Algorithm SHA256 $archive).Hash -ne $sha256.ToUpper()) {
    Invoke-WebRequest -Uri "https://github.com/BtbN/FFmpeg-Builds/releases/download/$release/$name.zip" -OutFile $archive
}
$actual = (Get-FileHash -Algorithm SHA256 $archive).Hash
if ($actual -ne $sha256.ToUpper()) { throw "FFmpeg archive checksum mismatch: $actual" }
$ffmpeg = Join-Path $work $name
if (Test-Path $ffmpeg) { Remove-Item -Recurse -Force $ffmpeg }
Expand-Archive -Path $archive -DestinationPath $work

if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) {
    $vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
    $vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.ARM64 -property installationPath
    if (-not $vs) { throw "Visual Studio with the MSVC ARM64 build tools is required" }
    cmd /c "`"$vs\VC\Auxiliary\Build\vcvarsall.bat`" arm64 >nul && set" | ForEach-Object {
        if ($_ -match "^([^=]+)=(.*)$") { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2]) }
    }
    if ($LASTEXITCODE -ne 0) { throw "vcvarsall.bat arm64 failed" }
}

# The preset's own C header ships only in the Bytedeco sources, not in the published JARs.
$presetInclude = Join-Path $work "preset-include"
New-Item -ItemType Directory -Force -Path $presetInclude | Out-Null
$logCallback = Join-Path $presetInclude "log_callback.h"
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/bytedeco/javacpp-presets/1.5.14/ffmpeg/src/main/resources/org/bytedeco/ffmpeg/include/log_callback.h" -OutFile $logCallback
$actual = (Get-FileHash -Algorithm SHA256 $logCallback).Hash
if ($actual -ne "2506ed28e711176edf3b0f69e1c8093a6082b4d3368bac3c35490c3ae0e3bc0f".ToUpper()) { throw "log_callback.h checksum mismatch: $actual" }

# Bytedeco compiles its Windows JNI with MinGW GCC; intreadwrite.h declares these unions only for GCC,
# but the preset maps them for every compiler.
$unalignedUnions = Join-Path $presetInclude "msvc_unaligned_unions.h"
Set-Content -Path $unalignedUnions -Encoding ASCII -Value @"
#include <stdint.h>
union unaligned_64 { uint64_t l; };
union unaligned_32 { uint32_t l; };
union unaligned_16 { uint16_t l; };
"@

$natives = Join-Path $Output "org/bytedeco/ffmpeg/windows-arm64"
if (Test-Path $Output) { Remove-Item -Recurse -Force $Output }
New-Item -ItemType Directory -Force -Path $natives | Out-Null
& $Java -cp $ClassPath org.bytedeco.javacpp.tools.Builder `
    -classpath $ClassPath `
    -properties windows-arm64 `
    "-Dplatform.includepath=$ffmpeg\include;$presetInclude" `
    "-Dplatform.linkpath=$ffmpeg\lib" `
    "-Dplatform.compiler.default=/Oi /O2 /EHsc /Gy /GL /MD /LD /FImsvc_unaligned_unions.h" `
    -d $natives `
    "org.bytedeco.ffmpeg.**"
if ($LASTEXITCODE -ne 0) { throw "JavaCPP failed with exit code $LASTEXITCODE" }

Copy-Item "$ffmpeg\bin\*.dll" $natives
Copy-Item "$ffmpeg\LICENSE.txt" (Join-Path $natives "FFmpeg-LICENSE.txt")
foreach ($library in "avutil", "swresample", "avcodec", "avformat", "swscale", "avfilter", "avdevice") {
    if (-not (Test-Path (Join-Path $natives "jni$library.dll"))) { throw "JavaCPP did not produce jni$library.dll" }
}
