# 兼容旧启动入口，转入新的 backend/desktop 工作区布局。
& (Join-Path (Split-Path -Parent $PSScriptRoot) 'start-backend-local.ps1')
