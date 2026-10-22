$ErrorActionPreference = 'Stop'
$audioRoot = $PSScriptRoot
. (Join-Path $audioRoot 'scripts\Resolve-Toolchain.ps1')
$javaRoot = Get-JavaHome
$mvnExe = Get-MavenExecutable
$nodeExe = Get-NodeExecutable
$nodeRoot = Split-Path -Parent $nodeExe
$npmExe = Join-Path $nodeRoot 'npm.cmd'
if (-not (Test-Path -LiteralPath $npmExe)) { $npmExe = (Get-Command npm -ErrorAction Stop).Source }
$env:JAVA_HOME = $javaRoot
$env:Path = "$javaRoot\bin;$nodeRoot;$env:Path"
$audioRepo = Join-Path $audioRoot '.cache\m2'
Set-Location -LiteralPath (Join-Path $audioRoot 'backend')
& $mvnExe "-Dmaven.repo.local=$audioRepo" verify
if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed.' }
Set-Location -LiteralPath (Join-Path $audioRoot 'desktop')
& $npmExe test
if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed.' }
& $npmExe run build
if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
Write-Host 'AudioRAG backend and desktop verified.' -ForegroundColor Green
