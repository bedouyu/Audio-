param([int]$Port = 50000)
$ErrorActionPreference = 'Stop'
$audioRoot = $PSScriptRoot
$cosyRoot = if ($env:AUDIORAG_COSYVOICE_ROOT) { $env:AUDIORAG_COSYVOICE_ROOT } else { [IO.Path]::GetFullPath((Join-Path $audioRoot '..\..\CosyVoice')) }
$cosyPython = Join-Path $cosyRoot '.venv\Scripts\python.exe'
$cosyService = Join-Path $audioRoot 'tools\cosyvoice\service.py'
if (-not (Test-Path -LiteralPath $cosyPython)) { throw 'CosyVoice Python environment is missing.' }
if (-not (Test-Path -LiteralPath (Join-Path $cosyRoot 'pretrained_models\Fun-CosyVoice3-0.5B\cosyvoice3.yaml'))) { throw 'CosyVoice model is missing.' }
$env:PYTHONUTF8 = '1'
$env:OMP_NUM_THREADS = '4'
$env:MKL_NUM_THREADS = '4'
$env:HF_HOME = Join-Path $cosyRoot '.cache\huggingface'
$env:MODELSCOPE_CACHE = Join-Path $cosyRoot '.cache\modelscope'
Set-Location -LiteralPath $cosyRoot
& $cosyPython -u $cosyService --repo-root $cosyRoot --port $Port
if ($LASTEXITCODE -ne 0) { throw 'CosyVoice service exited with an error.' }
