#Requires -Version 7.4
# Executes the real benchmark script in an isolated directory with service/API commands stubbed.
[CmdletBinding()]
param([string]$OutDir)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $OutDir) {
    $OutDir = Join-Path $repositoryRoot ('.local/state/benchmark-guard-tests/' + [guid]::NewGuid().ToString('N'))
}
$testRoot = [IO.Path]::GetFullPath($OutDir)
if (Test-Path -LiteralPath $testRoot) { throw 'Use a fresh test output directory.' }
$null = New-Item -ItemType Directory -Path $testRoot
$source = Join-Path $repositoryRoot 'scripts/benchmark-bonsai-drafting.ps1'
$parseTokens = $null; $parseErrors = $null
$null = [Management.Automation.Language.Parser]::ParseFile($source, [ref]$parseTokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors.Message -join '; ') }

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Get-ScheduledTask {
    param($TaskName, $ErrorAction)
    if ($TaskName -ne 'ConSense-Local') { throw 'Unexpected task query.' }
    $global:benchmarkTest.Task
}
function Stop-ScheduledTask {
    param($TaskName, $ErrorAction)
    $global:benchmarkTest.Events.Add('watcher-stop')
}
function Get-NetTCPConnection { param($State, $ErrorAction) }
function Invoke-RestMethod {
    param($Uri, $TimeoutSec, $ErrorAction)
    if ($Uri -eq 'http://127.0.0.1:8091/slots') {
        $global:benchmarkTest.Events.Add('slots-read')
        if ($global:benchmarkTest.SlotsError) { throw 'Stubbed slots endpoint unavailable.' }
        return $global:benchmarkTest.Slots
    }
    if ($Uri -eq 'http://127.0.0.1:8091/v1/models') {
        return [pscustomobject]@{ data = @([pscustomobject]@{ id = 'test-model' }) }
    }
    throw "Unexpected API request: $Uri"
}
function Get-Command {
    param($Name, $ErrorAction)
    if ($Name -ne 'python.exe') { throw "Unexpected executable lookup: $Name" }
    [pscustomobject]@{ Source = $global:benchmarkTest.PythonStub }
}
function nvidia-smi {
    if ($args -contains '--query-gpu=memory.free') { return '8192' }
    return 'Stub GPU, 16384, 8192, 8192'
}
function Set-Content {
    [CmdletBinding()]
    param([Parameter(ValueFromPipeline)]$Value, [string]$LiteralPath, [string]$Encoding)
    begin { $values = [Collections.Generic.List[object]]::new() }
    process { $values.Add($Value) }
    end {
        if ($LiteralPath -eq $global:benchmarkTest.RuntimePath) { $global:benchmarkTest.Events.Add('runtime-write') }
        $parameters = @{ LiteralPath = $LiteralPath }
        if ($Encoding) { $parameters.Encoding = $Encoding }
        $values | Microsoft.PowerShell.Management\Set-Content @parameters
    }
}

$lifecycleStub = @'
param([string]$Action)
function Stop-Component([string]$Name) {
    $global:benchmarkTest.Events.Add("stop:$Name")
    $row = $global:benchmarkTest.Components | Where-Object Component -eq $Name
    $row.Running = $false; $row.Ready = $false
}
if ($Action -eq 'status') {
    $global:benchmarkTest.Events.Add('status-read')
    if ($global:benchmarkTest.OwnershipError) { throw 'Stubbed receipt ownership mismatch.' }
    $global:benchmarkTest.Components
} elseif ($Action -eq 'start') {
    $global:benchmarkTest.Events.Add('start')
    foreach ($row in $global:benchmarkTest.Components) { $row.Running = $true; $row.Ready = $true }
} else { throw "Unexpected lifecycle action: $Action" }
'@
$pythonStub = @'
$global:benchmarkTest.Events.Add('runner')
if ($global:benchmarkTest.AfterRunnerSlots) { $global:benchmarkTest.Slots = $global:benchmarkTest.AfterRunnerSlots }
if ($global:benchmarkTest.AfterRunnerSlotsError) { $global:benchmarkTest.SlotsError = $true }
exit $global:benchmarkTest.RunnerExit
'@
$cases = @(
    @{ Name = 'busy preflight'; Slots = @([pscustomobject]@{ is_processing = $true }); Rejected = $true }
    @{ Name = 'missing slot state'; Slots = @([pscustomobject]@{ id = 0 }); Rejected = $true }
    @{ Name = 'nonboolean slot state'; Slots = @([pscustomobject]@{ is_processing = 'false' }); Rejected = $true }
    @{ Name = 'empty slot response'; Slots = @(); Rejected = $true }
    @{ Name = 'unavailable slots'; SlotsError = $true; Rejected = $true }
    @{ Name = 'backend unready'; Unready = 'backend'; Rejected = $true }
    @{ Name = 'ownership mismatch'; OwnershipError = $true; Rejected = $true }
    @{ Name = 'watcher executable mismatch'; WrongTaskExecutable = $true; Rejected = $true }
    @{ Name = 'idle campaign restores'; Rejected = $false }
    @{ Name = 'failed runner still busy defers'; RunnerExit = 2; AfterRunnerSlots = @([pscustomobject]@{ is_processing = $true }); Deferred = $true }
    @{ Name = 'failed runner slot endpoint unknown defers'; RunnerExit = 2; AfterRunnerSlotsError = $true; Deferred = $true }
    @{ Name = 'busy next profile defers'; Profiles = @('off16k', 'medium4k'); AfterRunnerSlots = @([pscustomobject]@{ is_processing = $true }); Deferred = $true }
)
$results = @(); $caseNumber = 0
foreach ($case in $cases) {
    $caseNumber++
    $caseRoot = Join-Path $testRoot ('case-' + $caseNumber)
    foreach ($directory in @('scripts', '.local/state', 'ai_consense_service/target')) {
        $null = New-Item -ItemType Directory -Path (Join-Path $caseRoot $directory) -Force
    }
    $copiedScript = Join-Path $caseRoot 'scripts/benchmark-bonsai-drafting.ps1'
    Copy-Item -LiteralPath $source -Destination $copiedScript
    [IO.File]::WriteAllText((Join-Path $caseRoot 'scripts/local.ps1'), $lifecycleStub)
    $pythonPath = Join-Path $caseRoot 'scripts/python-stub.ps1'
    [IO.File]::WriteAllText($pythonPath, $pythonStub)
    $runtimePath = Join-Path $caseRoot '.local/runtime.json'
    $runtime = [ordered]@{
        PwshPath = 'stub-pwsh.exe'; BackendPort = 8080; FrontendPort = 5173
        Model = @{ Enabled = $true; Port = 8091; ContextSize = 32768; MaxOutputTokens = 2048; ModelPath = 'stub-model.gguf'; Id = 'test-model'; Arguments = @() }
    }
    $originalRuntimeBytes = [Text.Encoding]::UTF8.GetBytes(($runtime | ConvertTo-Json -Depth 10))
    [IO.File]::WriteAllBytes($runtimePath, $originalRuntimeBytes)
    $jarPath = Join-Path $caseRoot 'ai_consense_service/target/consense-service-1.0.0.jar'
    [IO.File]::WriteAllText($jarPath, 'fixed test artifact')
    $originalJarHash = (Get-FileHash -LiteralPath $jarPath).Hash
    $task = [pscustomobject]@{ State = 'Running'; Actions = @([pscustomobject]@{
        Execute = if ($case.WrongTaskExecutable) { 'unrelated.exe' } else { 'stub-pwsh.exe' }
        WorkingDirectory = $caseRoot
        Arguments = "-NoProfile -WindowStyle Hidden -File `"$(Join-Path $caseRoot 'scripts/watch-local.ps1')`""
    }) }
    $global:benchmarkTest = @{
        Task = $task; RuntimePath = $runtimePath; PythonStub = $pythonPath
        Components = @('bonsai', 'backend', 'frontend') | ForEach-Object { [pscustomobject]@{ Component = $_; Running = $true; Ready = $_ -ne $case.Unready } }
        Slots = if ($case.ContainsKey('Slots')) { $case.Slots } else { @([pscustomobject]@{ is_processing = $false }) }
        SlotsError = [bool]$case.SlotsError; OwnershipError = [bool]$case.OwnershipError
        AfterRunnerSlots = $case.AfterRunnerSlots; AfterRunnerSlotsError = [bool]$case.AfterRunnerSlotsError
        RunnerExit = if ($case.ContainsKey('RunnerExit')) { $case.RunnerExit } else { 0 }
        Events = [Collections.Generic.List[string]]::new()
    }
    $campaignDirectory = Join-Path $caseRoot 'campaign'
    $caught = $null
    try {
        $profiles = if ($case.Profiles) { $case.Profiles } else { @('off16k') }
        & $copiedScript -OutputRoot $campaignDirectory -Profiles $profiles *> (Join-Path $caseRoot 'script.log')
    } catch { $caught = $_ }
    $receipt = Get-Content -LiteralPath (Join-Path $campaignDirectory 'campaign.json') -Raw | ConvertFrom-Json
    $mutations = @($global:benchmarkTest.Events | Where-Object { $_ -eq 'watcher-stop' -or $_ -eq 'runtime-write' -or $_ -eq 'start' -or $_.StartsWith('stop:') })
    Assert-True ((Get-FileHash -LiteralPath $jarPath).Hash -eq $originalJarHash) "$($case.Name): JAR changed."
    if ($case.Rejected) {
        Assert-True ($null -ne $caught -and $receipt.status -eq 'preflight_rejected') "$($case.Name): missing explicit preflight refusal."
        Assert-True ($mutations.Count -eq 0) "$($case.Name): preflight performed a mutation: $($mutations -join ', ')."
        Assert-True ([Convert]::ToHexString([IO.File]::ReadAllBytes($runtimePath)) -eq [Convert]::ToHexString($originalRuntimeBytes)) "$($case.Name): preflight changed runtime."
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $caseRoot '.local/state/stopped'))) "$($case.Name): preflight paused watcher."
    } elseif ($case.Deferred) {
        Assert-True ($receipt.status -eq 'restore_deferred' -and $receipt.restorationStatus -eq 'deferred' -and -not $receipt.restored) "$($case.Name): missing deferred restoration receipt."
        Assert-True (@($global:benchmarkTest.Events | Where-Object { $_.StartsWith('stop:') }).Count -eq 2) "$($case.Name): busy/unknown cleanup stopped inference."
        Assert-True (@($global:benchmarkTest.Events | Where-Object { $_ -eq 'start' }).Count -eq 1) "$($case.Name): busy/unknown cleanup started another profile."
        Assert-True ((Get-Content -LiteralPath $runtimePath -Raw | ConvertFrom-Json).Model.ContextSize -eq 65536) "$($case.Name): active trial runtime overwritten."
        Assert-True (Test-Path -LiteralPath (Join-Path $campaignDirectory 'runtime-original.json')) "$($case.Name): original runtime backup missing."
    } else {
        Assert-True ($null -eq $caught -and $receipt.status -eq 'completed' -and $receipt.restored -and $receipt.restorationStatus -eq 'restored') "$($case.Name): idle campaign failed restoration."
        Assert-True ([Convert]::ToHexString([IO.File]::ReadAllBytes($runtimePath)) -eq [Convert]::ToHexString($originalRuntimeBytes)) "$($case.Name): original runtime bytes not restored."
        Assert-True (@($global:benchmarkTest.Events | Where-Object { $_.StartsWith('stop:') }).Count -eq 4) "$($case.Name): idle stop/restore flow not exercised."
    }
    $results += [ordered]@{ name = $case.Name; status = 'PASS'; events = @($global:benchmarkTest.Events); campaignStatus = $receipt.status }
    Write-Host "PASS: $($case.Name)"
}
$results | ConvertTo-Json -Depth 8 | Microsoft.PowerShell.Management\Set-Content -LiteralPath (Join-Path $testRoot 'results.json') -Encoding utf8
Write-Host "All $($results.Count) stubbed control-flow tests passed. Evidence: $testRoot"
