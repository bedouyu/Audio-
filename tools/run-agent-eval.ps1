param(
    [Parameter(Mandatory = $true)][long]$ProjectId,
    [string]$Backend = 'http://127.0.0.1:18081'
)
$ErrorActionPreference = 'Stop'
$audioEvalRoot = Split-Path -Parent $PSScriptRoot
$audioEvalToken = [Environment]::GetEnvironmentVariable('AUDIORAG_EVAL_TOKEN', 'Process')
if (-not $audioEvalToken) { throw 'Set AUDIORAG_EVAL_TOKEN to a local login token. Never commit or print it.' }
$audioEvalCases = Get-Content -LiteralPath (Join-Path $audioEvalRoot 'backend\src\test\resources\evals\agent-scenarios.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$audioEvalSession = $null
$audioEvalResults = @()
foreach ($audioEvalCase in $audioEvalCases) {
    $audioEvalBody = @{ project_id = $ProjectId; session_id = $audioEvalSession; message = $audioEvalCase.message; request_id = [Guid]::NewGuid().ToString() } | ConvertTo-Json -Compress
    $audioEvalTimer = [Diagnostics.Stopwatch]::StartNew()
    try {
        $audioEvalRequest = @{
            Uri = $Backend.TrimEnd('/') + '/api/v1/chat'
            Method = 'Post'
            TimeoutSec = 900
            Headers = @{ Authorization = "Bearer $audioEvalToken" }
            ContentType = 'application/json; charset=utf-8'
            Body = [Text.Encoding]::UTF8.GetBytes($audioEvalBody)
        }
        $audioEvalResponse = Invoke-RestMethod @audioEvalRequest
        $audioEvalSession = $audioEvalResponse.session_id
        $audioEvalPayloads = @($audioEvalResponse.trace | ForEach-Object { $_.payload })
        $audioEvalAgents = @($audioEvalPayloads | ForEach-Object { $_.agent_id } | Sort-Object -Unique)
        # 观察为NO_RESULTS/TRUNCATED不算工具成功，不能虚增契约通过率。
        $audioEvalTools = @($audioEvalPayloads | Where-Object { $_.kind -eq 'tool_end' -and $_.status -eq 'COMPLETED' } | ForEach-Object {
            try { if (($_.observation | ConvertFrom-Json).status -eq 'OK') { $_.tool } } catch { }
        } | Sort-Object -Unique)
        $audioEvalKinds = @($audioEvalResponse.artifacts | ForEach-Object { $_.kind })
        $audioEvalMissing = @(
            $audioEvalCase.agents | Where-Object { $_ -notin $audioEvalAgents }
            $audioEvalCase.tools | Where-Object { $_ -notin $audioEvalTools }
            $audioEvalCase.artifacts | Where-Object { $_ -notin $audioEvalKinds }
        )
        $audioEvalResults += [pscustomobject]@{
            case_id = $audioEvalCase.id; contract_passed = ($audioEvalMissing.Count -eq 0)
            status = $audioEvalResponse.status; run_id = $audioEvalResponse.run_id
            model_calls = $audioEvalResponse.stats.model_calls; tool_calls = $audioEvalResponse.stats.tool_calls
            delegations = $audioEvalResponse.stats.delegations; seconds = [Math]::Round($audioEvalTimer.Elapsed.TotalSeconds, 2)
            missing = ($audioEvalMissing -join ',')
        }
    } catch {
        $audioEvalResults += [pscustomobject]@{ case_id = $audioEvalCase.id; contract_passed = $false; status = 'ERROR'; seconds = [Math]::Round($audioEvalTimer.Elapsed.TotalSeconds, 2) }
    }
}
$audioEvalDirectory = Join-Path $audioEvalRoot '.cache\evals'
New-Item -ItemType Directory -Path $audioEvalDirectory -Force | Out-Null
$audioEvalReport = Join-Path $audioEvalDirectory ('agent-eval-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.csv')
$audioEvalResults | Export-Csv -LiteralPath $audioEvalReport -NoTypeInformation -Encoding UTF8
$audioEvalResults | Format-Table case_id, contract_passed, status, model_calls, tool_calls, delegations, seconds
Write-Host "Saved contract evaluation: $audioEvalReport"
Write-Host 'These checks measure workflow contracts, not answer accuracy. Review the actual artifacts in the desktop app.'
