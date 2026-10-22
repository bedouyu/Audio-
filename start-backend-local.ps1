$ErrorActionPreference = 'Stop'
$audioRoot = $PSScriptRoot
$audioSpeechMarker = Join-Path $audioRoot 'runtime\speech-provider.txt'
if (-not $env:AUDIORAG_SPEECH_PROVIDER -and (Test-Path -LiteralPath $audioSpeechMarker)) {
    $env:AUDIORAG_SPEECH_PROVIDER = [IO.File]::ReadAllText($audioSpeechMarker).Trim()
}
if ($env:AUDIORAG_SPEECH_PROVIDER -eq 'cosyvoice') {
    if (-not $env:AUDIORAG_SYNTHESIS_LEASE_SECONDS) { $env:AUDIORAG_SYNTHESIS_LEASE_SECONDS = '1500' }
    & (Join-Path $audioRoot 'ensure-cosyvoice-local.ps1')
}
$javaCandidates = @('D:\AI-Toolchain\jdk-17.0.20.1+1', 'D:\DevTools\jdk-17')
$javaRoot = $javaCandidates | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
if (-not $javaRoot) { throw 'JDK 17 was not found. Set JAVA_HOME to your JDK 17 directory.' }
$env:JAVA_HOME = $javaRoot
$env:Path = "$javaRoot\bin;$env:Path"
foreach ($audioVariable in @('DEEPSEEK_API_KEY','AUDIORAG_LLM_API_KEY','AUDIORAG_MYSQL_USER','AUDIORAG_MYSQL_PASSWORD','DOCPILOT_MINIO_ACCESS_KEY','DOCPILOT_MINIO_SECRET_KEY')) {
    if (-not [Environment]::GetEnvironmentVariable($audioVariable, 'Process')) {
        $audioUserValue = [Environment]::GetEnvironmentVariable($audioVariable, 'User')
        if ($audioUserValue) { [Environment]::SetEnvironmentVariable($audioVariable, $audioUserValue, 'Process') }
    }
}
Set-Location -LiteralPath (Join-Path $audioRoot 'backend')
$mavenExe = 'D:\DevTools\apache-maven-3.9.16\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $mavenExe)) { $mavenExe = (Get-Command mvn -ErrorAction Stop).Source }
$audioRepo = Join-Path $audioRoot '.cache\m2'
Write-Host 'Building Audio智能助手 Java 17 backend...' -ForegroundColor Green
& $mavenExe "-Dmaven.repo.local=$audioRepo" -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
Write-Host 'Starting backend. Health: http://127.0.0.1:18081/api/health' -ForegroundColor Green
& (Join-Path $javaRoot 'bin\java.exe') '-Dfile.encoding=UTF-8' '-Djava.net.useSystemProxies=true' -jar (Join-Path $audioRoot 'backend\target\audiorag-backend.jar')
