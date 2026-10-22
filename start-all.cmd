@echo off
rem One-click launcher. Keep this file pure ASCII: cmd.exe decodes it with the OEM
rem code page, so a literal CJK path written as UTF-8 will not resolve. Chinese
rem output comes from start-all.ps1 instead.
setlocal
set "SCRIPT=%~dp0start-all.ps1"
if not exist "%SCRIPT%" set "SCRIPT=%~dp0audiorag-java\start-all.ps1"
if not exist "%SCRIPT%" if defined AUDIORAG_HOME set "SCRIPT=%AUDIORAG_HOME%\start-all.ps1"
if not exist "%SCRIPT%" (
    echo [start-all] cannot find start-all.ps1 next to this file.
    echo [start-all] Run it from the audiorag-java folder, set AUDIORAG_HOME to that folder,
    echo [start-all] or use a desktop shortcut pointing at audiorag-java\start-all.cmd.
    pause
    exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
if errorlevel 1 pause
