# Resolves the local JDK, Maven and Node used by the start/build scripts.
# Priority: explicit environment variable, then the known local toolchain, then PATH.
# ASCII only: Windows PowerShell reads BOM-less script files as ANSI.

function Get-JavaHome {
    param([string[]]$Candidates = @())

    $roots = @()
    if ($env:JAVA_HOME) { $roots += $env:JAVA_HOME }
    $roots += $Candidates
    $roots += @('D:\AI-Toolchain\jdk-17.0.20.1+1', 'D:\DevTools\jdk-17')

    foreach ($root in $roots) {
        if (-not $root) { continue }
        $exe = Join-Path $root 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $exe)) { continue }
        if ((Get-JavaMajorVersion $exe) -eq 17) { return (Resolve-Path -LiteralPath $root).Path }
    }

    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) {
        if ((Get-JavaMajorVersion $onPath.Source) -eq 17) {
            return Split-Path -Parent (Split-Path -Parent $onPath.Source)
        }
    }

    throw 'JDK 17 was not found. Set JAVA_HOME to a JDK 17 directory, or install one under D:\AI-Toolchain.'
}

function Get-MavenExecutable {
    param([string[]]$Candidates = @())

    $paths = @()
    if ($env:MAVEN_HOME) { $paths += (Join-Path $env:MAVEN_HOME 'bin\mvn.cmd') }
    $paths += $Candidates
    $paths += @('D:\DevTools\apache-maven-3.9.16\bin\mvn.cmd')

    foreach ($path in $paths) {
        if ($path -and (Test-Path -LiteralPath $path)) { return $path }
    }

    $onPath = Get-Command mvn -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    throw 'Maven was not found. Set MAVEN_HOME, or install Maven under D:\DevTools.'
}

function Get-NodeExecutable {
    param([string[]]$Candidates = @())

    $paths = @()
    if ($env:NODE_HOME) { $paths += (Join-Path $env:NODE_HOME 'node.exe') }
    $paths += $Candidates
    $paths += @('D:\DevTools\nodejs\node.exe')

    foreach ($path in $paths) {
        if ($path -and (Test-Path -LiteralPath $path)) { return $path }
    }

    $onPath = Get-Command node -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    throw 'Node.js was not found. Set NODE_HOME, or install Node.js under D:\DevTools.'
}

function Get-JavaMajorVersion {
    param([string]$JavaExe)

    # java -version writes to stderr; keep the caller's Stop preference from turning
    # that into a terminating error.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = (& $JavaExe -version 2>&1 | Out-String)
    } catch {
        return -1
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($output -match 'version "(\d+)') { return [int]$Matches[1] }
    return -1
}
