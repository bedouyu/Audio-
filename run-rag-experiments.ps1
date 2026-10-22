param(
    [string]$ModelDirectory = 'D:\AI-Toolchain\models\bge-small-zh-v1.5',
    [string]$Dataset = '',
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot
$env:JAVA_HOME = 'D:\AI-Toolchain\jdk-17.0.20.1+1'
$env:Path = "$env:JAVA_HOME\bin;D:\DevTools\apache-maven-3.9.16\bin;$env:Path"
if (-not (Test-Path -LiteralPath (Join-Path $ModelDirectory 'model_optimized.onnx'))) {
    throw 'Local ONNX model not found. Set -ModelDirectory; no model is downloaded automatically.'
}
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $projectDirectory ('.cache\rag-experiments\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
Push-Location $projectDirectory
try {
    & mvn -o -q "-Dmaven.repo.local=$projectDirectory\.cache\m2" -pl backend -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Build failed. Restore Maven dependencies before running experiments.' }
    $runnerArguments = @('--model-dir', $ModelDirectory, '--output-dir', $OutputDirectory)
    if ($Dataset) { $runnerArguments += @('--dataset', $Dataset) }
    # PropertiesLauncher selects only the offline main class; no Spring application or private config is loaded.
    & java '-Dloader.main=com.bedouyu.audiorag.rag.experiment.RagExperimentRunner' -cp 'backend\target\audiorag-backend.jar' org.springframework.boot.loader.launch.PropertiesLauncher @runnerArguments
    if ($LASTEXITCODE -ne 0) { throw 'RAG experiment failed; partial reports must not be treated as completed results.' }
} finally { Pop-Location }
