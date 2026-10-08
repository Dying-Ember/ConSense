#Requires -Version 7.4
[CmdletBinding()]
param(
    [ValidateSet('off16k', 'medium4k', 'medium8k')][string[]]$Profiles = @('medium4k'),
    [string]$OutputRoot,
    [int]$Seed = 42
)

$ErrorActionPreference = 'Stop'
$benchRoot = Split-Path -Parent $PSScriptRoot
$runtimePath = Join-Path $benchRoot '.local\runtime.json'
$lifecyclePath = Join-Path $PSScriptRoot 'local.ps1'
$pythonPath = (Get-Command python.exe -ErrorAction Stop).Source
if (-not $OutputRoot) { $OutputRoot = Join-Path $benchRoot ('.local\state\drafting-exam\campaign-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
if (Test-Path -LiteralPath $OutputRoot) { throw "Campaign directory already exists: $OutputRoot" }
New-Item -ItemType Directory -Path $OutputRoot | Out-Null

$originalBytes = [IO.File]::ReadAllBytes($runtimePath)
$originalRuntime = [Text.Encoding]::UTF8.GetString($originalBytes) | ConvertFrom-Json
[IO.File]::WriteAllBytes((Join-Path $OutputRoot 'runtime-original.json'), $originalBytes)
$jarPath = Join-Path $benchRoot 'ai_consense_service\target\consense-service-1.0.0.jar'
$originalJarHash = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash
$watchTask = Get-ScheduledTask -TaskName 'ConSense-Local' -ErrorAction SilentlyContinue
$watchWasRunning = $watchTask -and $watchTask.State -eq 'Running'
$campaign = [ordered]@{
    startedAt = (Get-Date).ToUniversalTime().ToString('o'); status = 'preparing'; profiles = $Profiles;
    originalRuntimeSha256 = (Get-FileHash -LiteralPath $runtimePath -Algorithm SHA256).Hash;
    backendJarSha256 = $originalJarHash; seed = $Seed; harnessChanged = $false;
    watchWasRunning = [bool]$watchWasRunning; runs = @(); restored = $false
}
function Save-Campaign {
    $campaign | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $OutputRoot 'campaign.json') -Encoding utf8
}
function Assert-OwnedWatcher {
    if (-not $watchTask) { return }
    $actions = @($watchTask.Actions)
    if ($actions.Count -ne 1 -or $actions[0].Execute -ine $originalRuntime.PwshPath -or
        $actions[0].WorkingDirectory -ine $benchRoot -or
        $actions[0].Arguments -cne "-NoProfile -WindowStyle Hidden -File `"$(Join-Path $PSScriptRoot 'watch-local.ps1')`"") {
        throw 'ConSense-Local task does not match this checkout; refusing to change it.'
    }
}
function Assert-OwnedInferenceIdle([switch]$RequireReady) {
    # local status checks PID, executable, creation time and ownership before returning.
    $components = @(& $lifecyclePath -Action status)
    foreach ($name in @('bonsai', 'backend', 'frontend')) {
        $component = @($components | Where-Object Component -eq $name)
        if ($component.Count -ne 1 -or
            ($RequireReady -and (-not $component[0].Running -or -not $component[0].Ready))) {
            throw "Owned $name must be uniquely identified and ready before benchmarking."
        }
    }
    $model = @($components | Where-Object Component -eq 'bonsai')[0]
    if (-not $model.Running) { return } # A stopped owned model cannot be serving a request.
    if (-not $model.Ready) { throw 'Owned model is not ready; inference idleness cannot be established.' }
    $slots = @(Invoke-RestMethod -Uri "http://127.0.0.1:$($originalRuntime.Model.Port)/slots" -TimeoutSec 10 -ErrorAction Stop)
    if (-not $slots.Count) { throw 'Model returned no slots; inference idleness cannot be established.' }
    foreach ($slot in $slots) {
        if ($null -eq $slot -or $null -eq $slot.PSObject.Properties['is_processing'] -or
            $slot.is_processing -isnot [bool]) {
            throw 'Model slot state is unknown; inference idleness cannot be established.'
        }
        if ($slot.is_processing) { throw 'Model is processing a request; no inference component was stopped.' }
    }
}
function Pause-OwnedWatcher {
    if ($watchTask) { Stop-ScheduledTask -TaskName 'ConSense-Local' -ErrorAction Stop }
    Set-Content -LiteralPath (Join-Path $benchRoot '.local\state\stopped') -Value 'Paused only for the owned Drafting model benchmark.'
}
function Stop-OwnedInferenceComponents([switch]$RequireReady) {
    Assert-OwnedInferenceIdle -RequireReady:$RequireReady
    . $lifecyclePath -Action status | Out-Null
    $script:inferenceStopStarted = $true
    Stop-Component -Name 'backend'
    Stop-Component -Name 'bonsai'
    # Stop-Process can return before Windows releases the old listening socket.
    # Await release only; a listener that persists is never killed by this gate.
    $releaseDeadline = (Get-Date).AddSeconds(20)
    $ownedPorts = @([int]$originalRuntime.BackendPort, [int]$originalRuntime.Model.Port)
    do {
        $remainingListeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $_.LocalPort -in $ownedPorts })
        if ($remainingListeners.Count -eq 0) { return }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $releaseDeadline)
    $listenerSummary = ($remainingListeners | ForEach-Object { "$($_.LocalPort):PID=$($_.OwningProcess)" }) -join ', '
    throw "Inference ports did not release within 20 seconds ($listenerSummary); no other process was stopped."
}

Save-Campaign
# Keep rejection outside the mutation try/finally: a failed preflight must never trigger restoration.
try {
    Assert-OwnedWatcher
    Assert-OwnedInferenceIdle -RequireReady
} catch {
    $campaign.status = 'preflight_rejected'; $campaign.error = $_.Exception.Message
    $campaign.finishedAt = (Get-Date).ToUniversalTime().ToString('o')
    Save-Campaign
    throw
}
$campaign.status = 'running'
Save-Campaign
try {
    # Only verified project-owned processes and the exact project-owned watcher are operated on.
    Pause-OwnedWatcher
    foreach ($profile in $Profiles) {
        if ((Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash -ne $originalJarHash) {
            throw 'The harness/backend artifact changed during a model-only comparison.'
        }
        Stop-OwnedInferenceComponents -RequireReady
        $trialRuntime = [Text.Encoding]::UTF8.GetString($originalBytes) | ConvertFrom-Json
        $trialRuntime.Model.ContextSize = 65536
        $trialRuntime.Model.MaxOutputTokens = 16384
        $arguments = [Collections.Generic.List[string]]::new()
        foreach ($arg in @('--model', $trialRuntime.Model.ModelPath, '--alias', $trialRuntime.Model.Id,
                 '--host', '127.0.0.1', '--port', [string]$trialRuntime.Model.Port,
                 '--ctx-size', '65536', '--parallel', '1', '--gpu-layers', '99', '--flash-attn', 'on',
                 '--batch-size', '512', '--ubatch-size', '128', '--n-predict', '16384',
                 '--cache-type-k', 'f16', '--cache-type-v', 'f16', '--seed', [string]$Seed,
                 '--jinja', '--reasoning-format', 'deepseek', '--no-webui')) { $arguments.Add($arg) }
        if ($profile -eq 'off16k') {
            $arguments.Add('--reasoning'); $arguments.Add('off')
        } else {
            $arguments.Add('--reasoning'); $arguments.Add('on')
            $arguments.Add('--reasoning-effort'); $arguments.Add('medium')
            $arguments.Add('--reasoning-budget'); $arguments.Add($(if ($profile -eq 'medium4k') { '4096' } else { '8192' }))
        }
        $trialRuntime.Model.Arguments = $arguments.ToArray()
        $trialRuntime | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $runtimePath -Encoding utf8
        $trialRuntime | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $OutputRoot "$profile-runtime.json") -Encoding utf8
        Write-Host "Starting experimental profile $profile (64K F16, 16K total generation, seed $Seed)."
        & $lifecyclePath -Action start
        # local.ps1 normally resumes autostart after startup. Keep it paused during the comparison.
        Pause-OwnedWatcher
        $modelInfo = Invoke-RestMethod -Uri "http://127.0.0.1:$($trialRuntime.Model.Port)/v1/models" -TimeoutSec 15
        $modelInfo | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $OutputRoot "$profile-model-info.json") -Encoding utf8
        & nvidia-smi --query-gpu=name,memory.total,memory.used,memory.free --format=csv,noheader,nounits |
            Set-Content -LiteralPath (Join-Path $OutputRoot "$profile-loaded-gpu.csv") -Encoding utf8
        $freeGpuMiB = @(& nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits)[0]
        if ([int]$freeGpuMiB -lt 2048) { throw 'Experimental allocation leaves less than 2 GiB GPU headroom; restoring the verified deployment.' }
        $runDirectory = Join-Path $OutputRoot $profile
        $run = [ordered]@{ profile = $profile; runDirectory = $runDirectory; startedAt = (Get-Date).ToUniversalTime().ToString('o') }
        $campaign.runs += $run
        Save-Campaign
        & $pythonPath (Join-Path $PSScriptRoot 'run-drafting-exam.py') --out-dir $runDirectory
        $run.runnerExitCode = $LASTEXITCODE
        if (Test-Path -LiteralPath (Join-Path $runDirectory 'variables.json')) {
            & $pythonPath (Join-Path $PSScriptRoot 'validate-drafting-fixture.py') `
                --fixture (Join-Path $benchRoot 'Ref\simulated_2b_correspondence\03_missing_evidence_test') `
                --actual (Join-Path $runDirectory 'variables.json') `
                --trace (Join-Path $runDirectory 'trace.json') --plan (Join-Path $runDirectory 'plan.json') `
                --out-dir (Join-Path $runDirectory 'scoring')
            $run.scorerExitCode = $LASTEXITCODE
            $score = Get-Content -LiteralPath (Join-Path $runDirectory 'scoring\drafting-score.json') -Raw | ConvertFrom-Json
            $run.verdict = $score.verdict; $run.summary = $score.summary
        }
        $run.finishedAt = (Get-Date).ToUniversalTime().ToString('o')
        Save-Campaign
        if ($run.runnerExitCode -ne 0) { throw "Profile $profile did not complete the real extraction; receipts are preserved." }
    }
    $campaign.status = 'completed'
}
catch {
    $campaign.status = 'failed'; $campaign.error = $_.Exception.Message
    Write-Host "Benchmark failure: $($campaign.error)"
}
finally {
    $script:inferenceStopStarted = $false
    try {
        # A failed/timed-out runner can leave inference running. Preserve that profile until it is idle.
        Assert-OwnedInferenceIdle
        Pause-OwnedWatcher
        Stop-OwnedInferenceComponents
        [IO.File]::WriteAllBytes($runtimePath, $originalBytes)
        & $lifecyclePath -Action start
        if (-not $watchWasRunning -and $watchTask) { Stop-ScheduledTask -TaskName 'ConSense-Local' -ErrorAction Stop }
        $campaign.restored = (Get-FileHash -LiteralPath $runtimePath -Algorithm SHA256).Hash -eq $campaign.originalRuntimeSha256
        $campaign.restoredBackendJarUnchanged = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash -eq $originalJarHash
        $campaign.restorationStatus = if ($campaign.restored -and $campaign.restoredBackendJarUnchanged) { 'restored' } else { 'verification_failed' }
        & $lifecyclePath -Action status | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutputRoot 'restored-status.json') -Encoding utf8
    }
    catch {
        $campaign.restoreError = $_.Exception.Message
        if (-not $script:inferenceStopStarted) {
            $campaign.restorationStatus = 'deferred'; $campaign.status = 'restore_deferred'
            Write-Error "Restore deferred; no inference component was stopped for restoration and the current runtime was retained. Retry only after owned inference is idle. Original runtime: $(Join-Path $OutputRoot 'runtime-original.json'). Reason: $($campaign.restoreError)" -ErrorAction Continue
        } else {
            $campaign.restorationStatus = 'failed'; $campaign.status = 'restore_failed'
            Write-Error "Restore failed after component shutdown began. Original runtime: $(Join-Path $OutputRoot 'runtime-original.json'). Reason: $($campaign.restoreError)" -ErrorAction Continue
        }
    }
    $campaign.finishedAt = (Get-Date).ToUniversalTime().ToString('o')
    Save-Campaign
}
if (-not $campaign.restored -or $campaign.status -ne 'completed') { exit 2 }
