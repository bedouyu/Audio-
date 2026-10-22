# 一键启动：Docker 服务 → 后端 → 前端。
# 只做启动与等待，不修改配置；某一步已在运行就跳过，避免重复进程。
param(
    [switch]$SkipDocker,
    [switch]$SkipFrontend,
    [string]$ComposeFile = '',
    [int]$DockerWaitSeconds = 240
)

$ErrorActionPreference = 'Stop'
$audioRoot = $PSScriptRoot
. (Join-Path $audioRoot 'scripts\Resolve-Toolchain.ps1')
. (Join-Path $audioRoot 'scripts\Resolve-ComposeFile.ps1')
# 未显式指定时按「环境变量 → 旧的本机路径 → 仓库内 docker\ai-services.yml」解析。
$ComposeFile = Resolve-ComposeFile -Explicit $ComposeFile -Root $audioRoot

$services = [ordered]@{ 3306 = 'MySQL'; 6379 = 'Redis'; 6333 = 'Qdrant'; 9000 = 'MinIO' }

function Test-Port([int]$Port) {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-Port([int]$Port, [int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $Port) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

# docker 会把进度写到 stderr；Stop 偏好下会被当成终止错误，所以单独包一层。
function Invoke-Docker([string[]]$Arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & docker @Arguments 2>&1 | Out-Host
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Test-DockerEngine {
    # 只要退出码：docker info 的正常输出会把启动日志淹没。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & docker info *> $null
        return ($LASTEXITCODE -eq 0)
    } finally {
        $ErrorActionPreference = $previous
    }
}

if (-not $SkipDocker) {
    if (-not (Test-Path -LiteralPath $ComposeFile)) {
        throw "找不到 compose 文件：$ComposeFile（可用 -ComposeFile 指定）"
    }
    if (-not (Test-DockerEngine)) {
        Write-Host 'Docker 引擎未运行，正在启动 Docker Desktop…' -ForegroundColor Yellow
        $desktop = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
        if (-not (Test-Path -LiteralPath $desktop)) { throw "未找到 Docker Desktop：$desktop" }
        Start-Process -FilePath $desktop
        $deadline = (Get-Date).AddSeconds($DockerWaitSeconds)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 5
            if (Test-DockerEngine) { break }
        }
        if (-not (Test-DockerEngine)) {
            throw "Docker 引擎在 $DockerWaitSeconds 秒内没有就绪，请手动打开 Docker Desktop 后重试"
        }
    }
    Write-Host '启动 ai-services 容器（MySQL/Redis/Qdrant/MinIO）…' -ForegroundColor Green
    $exit = Invoke-Docker @('compose', '-f', $ComposeFile, 'start')
    if ($exit -ne 0) { throw "docker compose start 失败（退出码 $exit）" }
    foreach ($port in $services.Keys) {
        if (-not (Wait-Port $port 120)) { throw "$($services[$port]) 的端口 $port 在 120 秒内没有就绪" }
        Write-Host ("  {0,-7} :{1} 就绪" -f $services[$port], $port)
    }
} else {
    Write-Host '已跳过 Docker（-SkipDocker）' -ForegroundColor Yellow
}

if (Test-Port 18081) {
    Write-Host '后端已在 18081 运行，跳过启动' -ForegroundColor Yellow
} else {
    # CosyVoice 的 venv 不在时退回 Windows TTS，否则后端启动脚本会直接中止。
    $speech = 'windows'
    $cosyRoot = if ($env:AUDIORAG_COSYVOICE_ROOT) { $env:AUDIORAG_COSYVOICE_ROOT }
        else { [IO.Path]::GetFullPath((Join-Path $audioRoot '..\..\CosyVoice')) }
    if (Test-Path -LiteralPath (Join-Path $cosyRoot '.venv\Scripts\python.exe')) { $speech = 'cosyvoice' }
    Write-Host "启动后端（语音提供方：$speech）…" -ForegroundColor Green
    Start-Process -FilePath 'powershell.exe' -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
        "`$env:AUDIORAG_SPEECH_PROVIDER='$speech'; & '$audioRoot\start-backend-local.ps1'"
    )
    if (-not (Wait-Port 18081 300)) {
        throw '后端 5 分钟内没有监听 18081，请查看后端窗口里的 Maven/启动报错'
    }
    $healthy = $false
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        try {
            $null = Invoke-RestMethod -Uri 'http://127.0.0.1:18081/api/health' -TimeoutSec 3
            $healthy = $true; break
        } catch { Start-Sleep -Seconds 2 }
    }
    if (-not $healthy) { throw '后端端口已开但健康检查未通过，请查看后端窗口' }
    Write-Host '  后端 :18081 就绪（健康检查通过）'
}

if ($SkipFrontend) {
    Write-Host '已跳过前端（-SkipFrontend）' -ForegroundColor Yellow
} elseif (Test-Port 5174) {
    Write-Host '前端已在 5174 运行，跳过启动' -ForegroundColor Yellow
} else {
    Write-Host '启动桌面端（Electron + Vue）…' -ForegroundColor Green
    # 外部工具常把 ELECTRON_RUN_AS_NODE=1 留在环境里，那会让 Electron 以纯 Node 运行、
    # 主进程拿不到 electron 模块，窗口永远不出现。启动前显式清掉。
    Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue
    Start-Process -FilePath 'powershell.exe' -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $audioRoot 'start-frontend-local.ps1')
    )
    if (Wait-Port 5174 180) { Write-Host '  前端 :5174 就绪，Electron 窗口稍后出现' }
    else { Write-Host '  前端 3 分钟内未监听 5174，请查看前端窗口输出' -ForegroundColor Yellow }
}

Write-Host ''
Write-Host 'Audio智能助手已启动' -ForegroundColor Green
Write-Host '  后端健康检查: http://127.0.0.1:18081/api/health'
Write-Host '  桌面开发界面: http://127.0.0.1:5174'
Write-Host '  停止容器:     docker compose -f "D:\AI-Services\docker-compose.yml" stop'
Write-Host '  仅缺省后端:   .\start-all.cmd -SkipDocker -SkipFrontend'
