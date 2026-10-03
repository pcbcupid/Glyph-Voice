# Process-local setup only: no administrator, registry or system PATH changes.
$ErrorActionPreference = 'Stop'
try {
    if ($env:PROCESSOR_ARCHITECTURE -ne 'AMD64' -and $env:PROCESSOR_ARCHITEW6432 -ne 'AMD64') {
        throw 'The Windows installer requires an x64 PC. ARM64 Windows is not supported by this installer.'
    }
    $glyphRoot = Split-Path -Parent $PSScriptRoot
    Set-Location -LiteralPath $glyphRoot
    $glyphBoot = Join-Path $glyphRoot '.tools/bootstrap/uv-0.12.22'
    New-Item -ItemType Directory -Force -Path $glyphBoot | Out-Null
    $glyphArchive = Join-Path $glyphBoot 'uv-x86_64-pc-windows-msvc.zip'
    $glyphSha = 'ea1397797a0ca15f63516dd0f49c2dde9776db9be5861cab152ebe8ad199894d'
    Write-Host 'Installing web tools inside this project only. Internet required; no Arduino or administrator access.'
    if (!(Test-Path -LiteralPath $glyphArchive) -or (Get-FileHash -LiteralPath $glyphArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $glyphSha) {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -UseBasicParsing -TimeoutSec 180 -Uri 'https://github.com/astral-sh/uv/releases/download/0.12.22/uv-x86_64-pc-windows-msvc.zip' -OutFile "$glyphArchive.part"
        if ((Get-FileHash -LiteralPath "$glyphArchive.part" -Algorithm SHA256).Hash.ToLowerInvariant() -ne $glyphSha) {
            throw 'uv checksum mismatch; nothing executed. Retry or contact your instructor.'
        }
        Move-Item -LiteralPath "$glyphArchive.part" -Destination $glyphArchive -Force
    }
    Expand-Archive -LiteralPath $glyphArchive -DestinationPath $glyphBoot -Force
    $glyphUv = Join-Path $glyphBoot 'uv.exe'
    if (!(Test-Path -LiteralPath $glyphUv)) { throw 'uv archive did not contain uv.exe.' }
    $env:UV_PYTHON_INSTALL_DIR = Join-Path $glyphRoot '.tools/runtime/python'
    $env:UV_CACHE_DIR = Join-Path $glyphRoot '.tools/uv-cache'
    $env:UV_NO_CONFIG = '1'
    & $glyphUv python install 3.13 --no-bin --no-registry --no-config
    if ($LASTEXITCODE -ne 0) { throw 'Python installation failed. Check internet access and available disk space.' }
    $glyphPython = & $glyphUv python find --managed-python 3.13
    if ($LASTEXITCODE -ne 0) { throw 'Cannot locate the installed Python.' }
    & $glyphPython (Join-Path $PSScriptRoot 'setup.py') @args
    exit $LASTEXITCODE
} catch {
    Write-Host "Installation failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
