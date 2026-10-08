#Requires -Version 7.4
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RuntimePath,
    [string]$ExpectedSha256,
    [switch]$AllowRendererChange
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$activePath = Join-Path $projectRoot '.local/runtime.json'
$sourcePath = (Resolve-Path -LiteralPath $RuntimePath).Path
$originalBytes = [IO.File]::ReadAllBytes($activePath)
$targetBytes = [IO.File]::ReadAllBytes($sourcePath)
$original = [Text.Encoding]::UTF8.GetString($originalBytes).TrimStart([char]0xFEFF) | ConvertFrom-Json
$target = [Text.Encoding]::UTF8.GetString($targetBytes).TrimStart([char]0xFEFF) | ConvertFrom-Json
$targetHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($targetBytes))
if ($ExpectedSha256 -and $targetHash -ine $ExpectedSha256) { throw 'Target runtime SHA256 mismatch.' }
if (-not $target.Model.Enabled) { throw 'This promotion script requires an enabled local model.' }
function Assert-RuntimeChangeScope([object]$Before, [object]$After, [bool]$RendererChangeAllowed) {
    # Compare both key sets so additions/removals, including null-valued fields, are checked.
    $propertyNames = @(@($Before.PSObject.Properties.Name) + @($After.PSObject.Properties.Name) | Sort-Object -Unique)
    foreach ($propertyName in $propertyNames) {
        if ($propertyName -eq 'Model' -or ($RendererChangeAllowed -and $propertyName -eq 'DraftingRenderer')) { continue }
        $beforeProperty = $Before.PSObject.Properties[$propertyName]
        $afterProperty = $After.PSObject.Properties[$propertyName]
        if (($null -eq $beforeProperty) -ne ($null -eq $afterProperty) -or
            ($beforeProperty.Value | ConvertTo-Json -Depth 100 -Compress) -cne
            ($afterProperty.Value | ConvertTo-Json -Depth 100 -Compress)) {
            throw "Unrelated runtime field changed: $propertyName"
        }
    }
}
Assert-RuntimeChangeScope -Before $original -After $target -RendererChangeAllowed ([bool]$AllowRendererChange)
foreach ($name in @('Executable', 'WorkingDirectory', 'DllDirectory', 'ModelPath', 'Id', 'Port')) {
    if ($original.Model.$name -cne $target.Model.$name) { throw "Model location/identity changed: $name" }
}
if (Test-Path -LiteralPath $target.JavaUnixSocketFallbackPath) { throw 'Java TCP fallback path must remain absent.' }

$lifecycle = Join-Path $PSScriptRoot 'local.ps1'
$task = Get-ScheduledTask -TaskName 'ConSense-Local' -ErrorAction Stop
$actions = @($task.Actions)
if ($actions.Count -ne 1 -or $actions[0].Execute -ine $original.PwshPath -or
    $actions[0].WorkingDirectory -ine $projectRoot -or
    $actions[0].Arguments -cne "-NoProfile -WindowStyle Hidden -File `"$(Join-Path $PSScriptRoot 'watch-local.ps1')`"") {
    throw 'ConSense-Local task does not match this checkout; refusing to stop it.'
}
# Lifecycle status verifies PID, executable, creation time and ownership markers.
$before = @(& $lifecycle -Action status)
if (@($before | Where-Object { -not $_.Running -or -not $_.Ready }).Count) {
    throw 'All three owned services must be ready before promotion.'
}
$slots = @(Invoke-RestMethod "http://127.0.0.1:$($original.Model.Port)/slots" -TimeoutSec 10)
if (@($slots | Where-Object is_processing).Count) { throw 'Model is processing a request; retry after it finishes.' }

$deploymentDir = Join-Path $projectRoot ('.local/state/deployments/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $deploymentDir | Out-Null
$backupPath = Join-Path $deploymentDir 'runtime-before.json'
[IO.File]::WriteAllBytes($backupPath, $originalBytes)
[IO.File]::WriteAllBytes((Join-Path $deploymentDir 'runtime-target.json'), $targetBytes)
$jarPath = Join-Path $projectRoot 'ai_consense_service/target/consense-service-1.0.0.jar'
$jarHash = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash
$receipt = [ordered]@{
    StartedUtc = [datetime]::UtcNow.ToString('o'); Status = 'prepared'; Source = $sourcePath
    BeforeSha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($originalBytes))
    TargetSha256 = $targetHash; BackupPath = $backupPath; JarSha256 = $jarHash
    AllowRendererChange = [bool]$AllowRendererChange
    Before = $before; ContextSize = $target.Model.ContextSize; MaxOutputTokens = $target.Model.MaxOutputTokens
    DistIndexSha256 = (Get-FileHash -LiteralPath (Join-Path $projectRoot 'ai_consense_web/dist/index.html')).Hash
}
function Save-Receipt {
    $receipt | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $deploymentDir 'receipt.json') -Encoding utf8
}
function Wait-PortsReleased {
    $deadline = [datetime]::UtcNow.AddSeconds(30)
    do {
        $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object LocalPort -in @($original.Model.Port, $original.BackendPort, $original.FrontendPort))
        if (-not $listeners.Count) { return }
        Start-Sleep -Milliseconds 250
    } while ([datetime]::UtcNow -lt $deadline)
    throw "Ports did not release: $(($listeners | ForEach-Object { "$($_.LocalPort)/PID=$($_.OwningProcess)" }) -join ', '); no discovered listener was stopped."
}
function Get-VerifiedProcess([string]$Name) {
    $ownedReceipt = Get-Content -LiteralPath (Join-Path $projectRoot ".local/state/$Name.json") -Raw | ConvertFrom-Json
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($ownedReceipt.ProcessId)"
    if (-not $process -or $process.ExecutablePath -ine $ownedReceipt.Executable -or
        $process.CreationDate.ToUniversalTime().Ticks -ne ([datetime]$ownedReceipt.CreatedUtc).ToUniversalTime().Ticks -or
        -not $process.CommandLine.Contains($ownedReceipt.Marker)) { throw "Ownership mismatch: $Name" }
    $process
}
Save-Receipt
$stopped = $false
try {
    # local stop pauses the verified watcher and stops only receipt-owned services.
    $stopped = $true
    & $lifecycle -Action stop
    Wait-PortsReleased
    [IO.File]::WriteAllBytes($activePath, $targetBytes)
    & $lifecycle -Action start
    $after = @(& $lifecycle -Action status)
    if (@($after | Where-Object { -not $_.Running -or -not $_.Ready }).Count) { throw 'A restarted service is not ready.' }
    $models = Invoke-RestMethod "http://127.0.0.1:$($target.Model.Port)/v1/models" -TimeoutSec 15
    $model = @($models.data | Where-Object id -eq $target.Model.Id)
    if ($model.Count -ne 1 -or $model[0].meta.n_ctx -ne $target.Model.ContextSize) { throw 'Live context metadata mismatch.' }
    $bonsai = Get-VerifiedProcess 'bonsai'
    # Verify every installed model argument, including order and quoting used by local.ps1.
    $expectedArguments = ($target.Model.Arguments | ForEach-Object {
        '"' + [regex]::Replace([regex]::Replace($_, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
    }) -join ' '
    if (-not $bonsai.CommandLine.Contains($expectedArguments)) { throw 'Live model command line differs from target runtime.' }
    $backend = Get-VerifiedProcess 'backend'
    foreach ($argument in @("--consense.llm.num-ctx=$($target.Model.ContextSize)",
        "--consense.llm.structured-max-tokens=$($target.Model.MaxOutputTokens)",
        "--consense.llm.chat-model=$($target.Model.Id)")) {
        if (-not $backend.CommandLine.Contains('"' + $argument + '"')) { throw "Live backend missing $argument" }
    }
    if ((Get-ScheduledTask -TaskName 'ConSense-Local').State -ne 'Running') { throw 'Watcher was not restored.' }
    if ((Get-FileHash -LiteralPath $jarPath).Hash -ne $jarHash) { throw 'Backend artifact changed during promotion.' }
    if ((Get-FileHash -LiteralPath $activePath).Hash -ne $targetHash) { throw 'Active runtime bytes differ from target.' }
    $receipt.Status = 'deployed'; $receipt.After = $after; $receipt.Models = $models
    $receipt.ModelCommandLine = $bonsai.CommandLine; $receipt.BackendCommandLine = $backend.CommandLine
    $receipt.WatcherState = 'Running'; $receipt.CompletedUtc = [datetime]::UtcNow.ToString('o')
    Save-Receipt
    Write-Host "Deployed runtime. Receipt: $(Join-Path $deploymentDir 'receipt.json')"
    $rollbackRendererFlag = if ($AllowRendererChange) { ' -AllowRendererChange' } else { '' }
    Write-Host "Rollback: deploy-local-runtime.ps1 -RuntimePath `"$backupPath`" -ExpectedSha256 $($receipt.BeforeSha256)$rollbackRendererFlag"
}
catch {
    $receipt.Status = 'failed'; $receipt.Error = $_.Exception.Message
    if ($stopped) {
        try {
            & $lifecycle -Action stop
            Wait-PortsReleased
            [IO.File]::WriteAllBytes($activePath, $originalBytes)
            & $lifecycle -Action start
            $receipt.Rollback = @(& $lifecycle -Action status)
            $receipt.RollbackReady = @($receipt.Rollback | Where-Object { -not $_.Running -or -not $_.Ready }).Count -eq 0
        }
        catch { $receipt.RollbackError = $_.Exception.Message }
    }
    Save-Receipt
    throw
}
