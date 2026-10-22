param([int]$WaitSeconds = 180)
$ErrorActionPreference = 'Stop'
$cosyRoot = if ($env:AUDIORAG_COSYVOICE_ROOT) { $env:AUDIORAG_COSYVOICE_ROOT } else { [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\CosyVoice')) }
$cosyPython = Join-Path $cosyRoot '.venv\Scripts\python.exe'
$cosyEnsure = Join-Path $PSScriptRoot 'tools\cosyvoice\ensure_service.py'
& $cosyPython -u $cosyEnsure --repo-root $cosyRoot --wait-seconds $WaitSeconds
if ($LASTEXITCODE -ne 0) { throw 'Local CosyVoice did not become ready. Check runtime/cosyvoice logs.' }