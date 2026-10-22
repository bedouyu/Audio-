$ErrorActionPreference = 'Stop'
$audioRoot = $PSScriptRoot
$nodeExe = 'D:\DevTools\nodejs\node.exe'
if (-not (Test-Path -LiteralPath $nodeExe)) { $nodeExe = (Get-Command node -ErrorAction Stop).Source }
$nodeRoot = Split-Path -Parent $nodeExe
$env:Path = "$nodeRoot;$env:Path"
$npmExe = Join-Path $nodeRoot 'npm.cmd'
$env:npm_config_cache = Join-Path $audioRoot '.cache\npm'
$env:electron_config_cache = Join-Path $audioRoot '.cache\electron'
Set-Location -LiteralPath (Join-Path $audioRoot 'desktop')
if (-not (Test-Path -LiteralPath 'node_modules\electron-vite')) {
    & $npmExe ci
    if ($LASTEXITCODE -ne 0) { throw 'npm dependency installation failed.' }
}
if (-not (Test-Path -LiteralPath 'node_modules\electron\dist\electron.exe')) {
    & $nodeExe 'node_modules\electron\install.js'
    if ($LASTEXITCODE -ne 0) { throw 'Electron runtime installation failed.' }
}
Write-Host 'Starting Audio智能助手 Electron + Vue desktop...' -ForegroundColor Green
& $npmExe run dev
