# Windows counterpart of build_linux_webview.sh: the published Compose WebView JAR carries only an x64 DLL.
param([Parameter(Mandatory = $true)][string]$Output)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
if (-not [IO.Path]::IsPathRooted($Output)) { $Output = Join-Path (Get-Location).Path $Output }

switch ($env:PROCESSOR_ARCHITECTURE) {
    "ARM64" { $target = "aarch64-pc-windows-msvc"; $resource = "win32-aarch64" }
    "AMD64" { $target = "x86_64-pc-windows-msvc"; $resource = "win32-x86-64" }
    default { throw "Unsupported Windows WebView architecture: $env:PROCESSOR_ARCHITECTURE" }
}

# Keep the revision and checksum in sync with build_linux_webview.sh.
$revision = "58314a9dc161a9892a5ecc2b2c0b3a593217c258"
$sha256 = "326c138a70ff66e62183198d625337a90970494f34df6b97281b6af5a1db39cd"
$root = Split-Path -Parent $PSScriptRoot
$work = Join-Path $root "desktopApp/build/windows-webview"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$archive = Join-Path $work "source.tar.gz"
Invoke-WebRequest -Uri "https://codeload.github.com/NucleusFramework/ComposeNativeWebview/tar.gz/$revision" -OutFile $archive
$actual = (Get-FileHash -Algorithm SHA256 $archive).Hash
if ($actual -ne $sha256.ToUpper()) { throw "WebView source checksum mismatch: $actual" }
$source = Join-Path $work "source"
if (Test-Path $source) { Remove-Item -Recurse -Force $source }
New-Item -ItemType Directory -Force -Path $source | Out-Null
# Git for Windows puts GNU tar first on PATH, which reads "C:" as a remote host.
& "$env:SystemRoot\System32\tar.exe" -xzf $archive -C $source --strip-components=1
if ($LASTEXITCODE -ne 0) { throw "Extracting WebView sources failed" }

$env:CARGO_TARGET_DIR = Join-Path $work "target"
$jobs = if ($env:CARGO_BUILD_JOBS) { $env:CARGO_BUILD_JOBS } else { "2" }
cargo +1.99.1 build --manifest-path "$source/wrywebview/Cargo.toml" --locked --release --lib --target $target --jobs $jobs
if ($LASTEXITCODE -ne 0) { throw "Cargo failed with exit code $LASTEXITCODE" }
New-Item -ItemType Directory -Force -Path "$Output/$resource" | Out-Null
Copy-Item "$env:CARGO_TARGET_DIR/$target/release/composewebview_wry.dll" "$Output/$resource/"
Copy-Item "$source/LICENSE" "$Output/composewebview-LICENSE"
