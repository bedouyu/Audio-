$ErrorActionPreference = 'Stop'
$audioRoot = $PSScriptRoot
$env:JAVA_HOME = 'D:\AI-Toolchain\jdk-17.0.20.1+1'
$env:Path = "$env:JAVA_HOME\bin;D:\DevTools\nodejs;$env:Path"
$audioRepo = Join-Path $audioRoot '.cache\m2'
Set-Location -LiteralPath (Join-Path $audioRoot 'backend')
& 'D:\DevTools\apache-maven-3.9.16\bin\mvn.cmd' "-Dmaven.repo.local=$audioRepo" verify
if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed.' }
Set-Location -LiteralPath (Join-Path $audioRoot 'desktop')
& 'D:\DevTools\nodejs\npm.cmd' test
if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed.' }
& 'D:\DevTools\nodejs\npm.cmd' run build
if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
Write-Host 'AudioRAG backend and desktop verified.' -ForegroundColor Green
