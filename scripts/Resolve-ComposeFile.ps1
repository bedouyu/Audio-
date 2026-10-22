# Resolves which compose file the one-click launcher should use.
# Priority: explicit -ComposeFile, AUDIORAG_COMPOSE_FILE, the legacy local path, the file in this repo.
# ASCII only: Windows PowerShell reads BOM-less script files as ANSI.

function Resolve-ComposeFile {
    param(
        [string]$Explicit,
        [string]$Root,
        [string]$LegacyPath = 'D:\AI-Services\docker-compose.yml'
    )

    # An explicitly requested file must exist; silently falling back would start the
    # wrong stack and look like it worked.
    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit)) {
            throw "指定的 compose 文件不存在：$Explicit"
        }
        return (Resolve-Path -LiteralPath $Explicit).Path
    }

    $candidates = @(
        $env:AUDIORAG_COMPOSE_FILE,
        $LegacyPath,
        (Join-Path $Root 'docker\ai-services.yml')
    )

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw "找不到 docker compose 文件，可用 -ComposeFile 指定，或设置 AUDIORAG_COMPOSE_FILE"
}
