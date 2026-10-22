param(
    [string]$Dataset = '',
    [string]$OutputDirectory = '',
    [switch]$RunModelEvaluation,
    [int]$ModelBudget = 3000,
    [int]$MaxModelCalls = 8
)
$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot
$env:JAVA_HOME = 'D:\AI-Toolchain\jdk-17.0.20.1+1'
$env:Path = "$env:JAVA_HOME\bin;D:\DevTools\apache-maven-3.9.16\bin;$env:Path"
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $projectDirectory ('.cache\memory-experiments\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
Push-Location $projectDirectory
try {
    & mvn -o -q "-Dmaven.repo.local=$projectDirectory\.cache\m2" -pl backend -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Build failed. Restore Maven dependencies before running experiments.' }
    $runnerArguments = @('--output-dir', $OutputDirectory)
    if ($Dataset) { $runnerArguments += @('--dataset', $Dataset) }
    & java '-Dloader.main=com.bedouyu.audiorag.memory.experiment.MemoryExperimentRunner' -cp 'backend\target\audiorag-backend.jar' org.springframework.boot.loader.launch.PropertiesLauncher @runnerArguments
    if ($LASTEXITCODE -ne 0) { throw 'Memory diagnostic failed; do not treat partial reports as complete.' }
    & java '-Dloader.main=com.bedouyu.audiorag.memory.experiment.ToolCompactionExperimentRunner' -cp 'backend\target\audiorag-backend.jar' org.springframework.boot.loader.launch.PropertiesLauncher '--output-dir' $OutputDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Tool compaction replay failed; do not treat partial reports as complete.' }
    if ($RunModelEvaluation) {
        Write-Host 'Explicit real-model evaluation enabled: API requests may incur charges. No private config is imported.'
        $modelArguments = @('--output-dir', (Join-Path $OutputDirectory 'model-evaluation'), '--budget', $ModelBudget, '--max-calls', $MaxModelCalls)
        if ($Dataset) { $modelArguments += @('--dataset', $Dataset) }
        & java '-Dloader.main=com.bedouyu.audiorag.memory.experiment.MemoryModelEvaluationRunner' -cp 'backend\target\audiorag-backend.jar' org.springframework.boot.loader.launch.PropertiesLauncher @modelArguments
        if ($LASTEXITCODE -ne 0) { throw 'Real-model evaluation failed; see its local status report without exposing API keys.' }
    }
} finally { Pop-Location }
