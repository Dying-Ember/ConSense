#Requires -Version 7.4
[CmdletBinding()]
param(
    [ValidateSet('start', 'start-model', 'restart-backend', 'restart-web', 'restart-minimax-relay', 'deploy-backend', 'stop', 'status')][string]$Action = 'status',
    [string]$BackendArtifact,
    [string]$ExpectedBackendSha256
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localRoot = Join-Path $projectRoot '.local'
$configPath = Join-Path $localRoot 'runtime.json'
if (-not (Test-Path -LiteralPath $configPath)) { throw "Missing deployment configuration: $configPath" }
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$backendMaxHeapMiB = 2048
if ($null -ne $config.BackendMaxHeapMiB) {
    if (-not [int]::TryParse([string]$config.BackendMaxHeapMiB, [ref]$backendMaxHeapMiB) -or
        $backendMaxHeapMiB -lt 256) {
        throw 'BackendMaxHeapMiB must be an integer of at least 256 MiB.'
    }
}
$stateRoot = Join-Path $localRoot 'state'
$logRoot = Join-Path $localRoot 'logs'
New-Item -ItemType Directory -Force -Path $stateRoot, $logRoot | Out-Null

function ConvertTo-ProcessArgument([string]$Value) {
    # Quote for CreateProcess, including JSON arguments and trailing backslashes.
    '"' + [regex]::Replace([regex]::Replace($Value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}

function Get-DraftingRendererArguments([object]$Renderer, [string]$LocalDirectory) {
    if ($null -eq $Renderer) { return }
    if ([string]::IsNullOrWhiteSpace([string]$Renderer.Kind) -or
        [string]::IsNullOrWhiteSpace([string]$Renderer.Executable) -or
        [string]::IsNullOrWhiteSpace([string]$Renderer.WorkRoot)) {
        throw 'DraftingRenderer requires explicit Kind, Executable and WorkRoot.'
    }
    $rendererTimeout = 0L
    if (-not [long]::TryParse([string]$Renderer.TimeoutMs, [ref]$rendererTimeout) -or $rendererTimeout -le 0) {
        throw 'DraftingRenderer.TimeoutMs must be a positive integer.'
    }
    $rendererLocal = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($LocalDirectory))
    $rendererProject = Split-Path -Parent $rendererLocal
    $rendererExecutable = [IO.Path]::GetFullPath([string]$Renderer.Executable, $rendererProject)
    if (-not (Test-Path -LiteralPath $rendererExecutable -PathType Leaf)) {
        throw "Missing DraftingRenderer executable: $rendererExecutable"
    }
    $rendererWork = [IO.Path]::TrimEndingDirectorySeparator(
        [IO.Path]::GetFullPath([string]$Renderer.WorkRoot, $rendererProject))
    if (-not $rendererWork.StartsWith($rendererLocal + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'DraftingRenderer.WorkRoot must be a child directory of this project .local.'
    }
    foreach ($reservedDirectory in @('tools', 'data')) {
        $reservedRoot = Join-Path $rendererLocal $reservedDirectory
        if ($rendererWork.Equals($reservedRoot, [StringComparison]::OrdinalIgnoreCase) -or
            $rendererWork.StartsWith($reservedRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'DraftingRenderer.WorkRoot must be separate from .local/tools and .local/data.'
        }
    }
    # Existing links must not redirect the mutable renderer workspace outside its boundary.
    for ($rendererAncestor = $rendererWork; $rendererAncestor; $rendererAncestor = Split-Path -Parent $rendererAncestor) {
        if (Test-Path -LiteralPath $rendererAncestor) {
            $rendererItem = Get-Item -LiteralPath $rendererAncestor -Force
            if (-not $rendererItem.PSIsContainer -or ($rendererItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                throw "DraftingRenderer.WorkRoot has a file or linked ancestor: $rendererAncestor"
            }
        }
        if ($rendererAncestor.Equals($rendererLocal, [StringComparison]::OrdinalIgnoreCase)) { break }
    }
    @("--consense.drafting.renderer-kind=$($Renderer.Kind)",
        "--consense.drafting.renderer-executable=$rendererExecutable",
        "--consense.drafting.renderer-work-root=$rendererWork",
        "--consense.drafting.renderer-timeout-ms=$rendererTimeout")
}

function Get-OwnedProcess([string]$Name) {
    $statePath = Join-Path $stateRoot "$Name.json"
    if (-not (Test-Path -LiteralPath $statePath)) { return $null }
    $receipt = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $candidate = Get-CimInstance Win32_Process -Filter "ProcessId=$($receipt.ProcessId)" -ErrorAction SilentlyContinue
    if (-not $candidate) { return $null }
    if ([IO.Path]::GetFullPath($candidate.ExecutablePath) -ine [IO.Path]::GetFullPath([string]$receipt.Executable) -or
        $candidate.CreationDate.ToUniversalTime().Ticks -ne ([datetime]$receipt.CreatedUtc).ToUniversalTime().Ticks -or
        -not $candidate.CommandLine.Contains($receipt.Marker)) {
        throw "PID $($receipt.ProcessId) no longer belongs to ConSense $Name; refusing to operate on it."
    }
    $candidate
}

function Test-Endpoint([string]$Url) {
    try { (Invoke-WebRequest -Uri $Url -TimeoutSec 5 -SkipHttpErrorCheck).StatusCode -eq 200 }
    catch { $false }
}

function Start-Component([string]$Name, [string]$Executable, [string[]]$Arguments,
                         [string]$Directory, [hashtable]$Environment, [string]$Marker,
                         [int]$Port, [string]$ReadyUrl, [int]$TimeoutSeconds = 120) {
    $Executable = [IO.Path]::GetFullPath($Executable)
    $existing = Get-OwnedProcess $Name
    if ($existing) {
        if (-not (Test-Endpoint $ReadyUrl)) { throw "ConSense $Name is running but is not ready; inspect its logs." }
        Write-Host "$Name is already running (PID $($existing.ProcessId))."
        return
    }
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $Port is occupied by another process; no process was stopped."
    }
    if (-not (Test-Path -LiteralPath $Executable)) { throw "Missing $Name executable: $Executable" }
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $stdout = Join-Path $logRoot "$Name-$stamp.stdout.log"
    $stderr = Join-Path $logRoot "$Name-$stamp.stderr.log"
    $argumentLine = ($Arguments | ForEach-Object { ConvertTo-ProcessArgument $_ }) -join ' '
    $child = Start-Process -FilePath $Executable -ArgumentList $argumentLine -WorkingDirectory $Directory `
        -Environment $Environment -WindowStyle Hidden -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr -PassThru
    $owned = Get-CimInstance Win32_Process -Filter "ProcessId=$($child.Id)"
    if (-not $owned) { throw "$Name exited immediately; inspect $stderr" }
    [ordered]@{ ProcessId = $child.Id; Executable = $Executable; Marker = $Marker;
        CreatedUtc = $owned.CreationDate.ToUniversalTime().ToString('o');
        Stdout = $stdout; Stderr = $stderr; Port = $Port; ReadyUrl = $ReadyUrl } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stateRoot "$Name.json") -Encoding utf8
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-OwnedProcess $Name)) {
            Get-Content -LiteralPath $stderr -Tail 25
            Get-Content -LiteralPath $stdout -Tail 25
            throw "$Name exited during startup."
        }
        if (Test-Endpoint $ReadyUrl) {
            Write-Host "$Name ready at $ReadyUrl (PID $($child.Id))."
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$Name startup timed out; inspect $stdout and $stderr"
}

function Stop-Component([string]$Name) {
    $owned = Get-OwnedProcess $Name
    if ($owned) {
        Stop-Process -Id $owned.ProcessId -ErrorAction Stop
        Write-Host "Stopped ConSense $Name (PID $($owned.ProcessId))."
    }
    $statePath = Join-Path $stateRoot "$Name.json"
    if (Test-Path -LiteralPath $statePath) { Remove-Item -LiteralPath $statePath }
}

function Wait-PortReleased([int]$Port, [string]$Name) {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ($true) {
        $listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
        if (-not $listeners.Count) { return }
        if ([DateTime]::UtcNow -ge $deadline) {
            $details = ($listeners | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort) (PID $($_.OwningProcess))" }) -join ', '
            throw "$Name port did not release within 30 seconds: $details. No listener process was killed."
        }
        Start-Sleep -Milliseconds 250
    }
}

function Start-MiniMaxRelay([switch]$Restart) {
    if (-not $config.MiniMaxRelay -or -not $config.MiniMaxRelay.Enabled) {
        if ($Restart) { throw 'MiniMaxRelay is not enabled in runtime.json.' }
        return
    }
    $relayScript = Join-Path $PSScriptRoot 'minimax-relay.py'
    if (-not (Test-Path -LiteralPath $relayScript -PathType Leaf)) { throw 'Missing MiniMax relay script.' }
    if ($Restart) {
        Stop-Component 'minimax-relay'
        Wait-PortReleased -Port $config.MiniMaxRelay.Port -Name 'MiniMax relay'
    }
    Start-Component -Name 'minimax-relay' -Executable $config.MiniMaxRelay.PythonExecutable `
        -Arguments @($relayScript, '--host', [string]$config.MiniMaxRelay.BindAddress,
            '--port', [string]$config.MiniMaxRelay.Port, '--model', [string]$config.MiniMaxRelay.Model,
            '--api-key-env', [string]$config.MiniMaxRelay.ApiKeyEnvironmentVariable,
            '--access-token-env', [string]$config.MiniMaxRelay.AccessTokenEnvironmentVariable) `
        -Directory $projectRoot -Environment @{} -Marker $relayScript -Port $config.MiniMaxRelay.Port `
        -ReadyUrl "http://127.0.0.1:$($config.MiniMaxRelay.Port)/health"
}

$backendUrl = "http://127.0.0.1:$($config.BackendPort)"
$frontendUrl = "http://127.0.0.1:$($config.FrontendPort)"
$webBindAddress = if ([string]::IsNullOrWhiteSpace([string]$config.WebBindAddress)) { '127.0.0.1' } else { [string]$config.WebBindAddress }
$parsedWebAddress = $null
if (-not [Net.IPAddress]::TryParse($webBindAddress, [ref]$parsedWebAddress)) {
    throw 'WebBindAddress must be an IP address.'
}
$frontendOrigins = @($frontendUrl, "http://localhost:$($config.FrontendPort)") + @(
    $config.FrontendAllowedOrigins | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }
)
$frontendOrigins = @($frontendOrigins | Select-Object -Unique)
$canonicalRoot = [IO.Path]::GetFullPath($projectRoot).ToUpperInvariant()
$mutexName = 'Global\ConSense-' + [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($canonicalRoot))).Substring(0, 16)
$deploymentMutex = [Threading.Mutex]::new($false, $mutexName)
try { $lockAcquired = $deploymentMutex.WaitOne([TimeSpan]::FromSeconds(30)) }
catch [Threading.AbandonedMutexException] { $lockAcquired = $true }
if (-not $lockAcquired) { throw 'Another ConSense lifecycle operation did not finish within 30 seconds.' }
try {
    if ($Action -eq 'stop') {
        Set-Content -LiteralPath (Join-Path $stateRoot 'stopped') -Value 'Stopped explicitly by the owner.'
        $localTask = Get-ScheduledTask -TaskName 'ConSense-Local' -ErrorAction SilentlyContinue
        if ($localTask -and $localTask.Actions.Arguments.Contains((Join-Path $PSScriptRoot 'watch-local.ps1'))) {
            Stop-ScheduledTask -TaskName 'ConSense-Local' -ErrorAction SilentlyContinue
        }
        'frontend', 'backend', 'minimax-relay', 'bonsai' | ForEach-Object { Stop-Component $_ }
        return
    }
    if ($Action -eq 'status') {
        $componentNames = @('bonsai', 'backend', 'frontend')
        if ($config.MiniMaxRelay -and $config.MiniMaxRelay.Enabled) { $componentNames += 'minimax-relay' }
        foreach ($name in $componentNames) {
            $owned = Get-OwnedProcess $name
            $receiptPath = Join-Path $stateRoot "$name.json"
            $receipt = if (Test-Path -LiteralPath $receiptPath) { Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json }
            [pscustomobject]@{ Component = $name; Running = [bool]$owned;
                ProcessId = if ($owned) { $owned.ProcessId } else { $null };
                Ready = if ($owned) { Test-Endpoint $receipt.ReadyUrl } else { $false };
                Url = if ($receipt) { $receipt.ReadyUrl } else { $null } }
        }
        return
    }
    if ($Action -eq 'restart-minimax-relay') {
        Start-MiniMaxRelay -Restart
        return
    }
    $jar = Join-Path $projectRoot 'ai_consense_service\target\consense-service-1.0.0.jar'
    $releaseBackup = $null
    if ($Action -eq 'restart-web' -and $config.Model.Enabled) {
        $slots = @(Invoke-RestMethod -Uri "http://127.0.0.1:$($config.Model.Port)/slots" -TimeoutSec 5)
        if ($slots | Where-Object { $_.is_processing }) {
            throw 'Model is serving an active request; web restart was not started.'
        }
    }
    if ($Action -eq 'deploy-backend') {
        if (-not $BackendArtifact -or $ExpectedBackendSha256 -notmatch '^[A-Fa-f0-9]{64}$') {
            throw 'deploy-backend requires BackendArtifact and its exact ExpectedBackendSha256.'
        }
        $artifactPath = [IO.Path]::GetFullPath($BackendArtifact, $projectRoot)
        if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf) -or
            $artifactPath.Equals($jar, [StringComparison]::OrdinalIgnoreCase) -or
            (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash -ine $ExpectedBackendSha256) {
            throw 'Backend artifact is missing, is the active JAR, or does not match the expected hash.'
        }
        if ($config.Model.Enabled) {
            $slots = @(Invoke-RestMethod -Uri "http://127.0.0.1:$($config.Model.Port)/slots" -TimeoutSec 5)
            if ($slots | Where-Object { $_.is_processing }) { throw 'Model is serving an active request; backend deployment was not started.' }
        }
        if (-not (Get-OwnedProcess 'backend')) { throw 'deploy-backend requires an existing receipt-owned backend.' }
        $releaseDirectory = Join-Path $stateRoot ('backend-releases/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
        $null = New-Item -ItemType Directory -Path $releaseDirectory
        $releaseBackup = Join-Path $releaseDirectory 'before.jar'
        Copy-Item -LiteralPath $jar -Destination $releaseBackup
        # Stage a verified copy before stopping the receipt-owned backend, under the lifecycle mutex.
        $releaseArtifact = Join-Path $releaseDirectory 'after.jar'
        Copy-Item -LiteralPath $artifactPath -Destination $releaseArtifact
        if ((Get-FileHash -LiteralPath $releaseArtifact -Algorithm SHA256).Hash -ine $ExpectedBackendSha256) {
            throw 'Staged backend hash changed; backend was not stopped.'
        }
    }
    $rendererArguments = if ($Action -in @('start', 'restart-backend', 'restart-web', 'deploy-backend')) {
        @(Get-DraftingRendererArguments -Renderer $config.DraftingRenderer -LocalDirectory $localRoot)
    } else { @() }
    $stopMarker = Join-Path $stateRoot 'stopped'
    if (Test-Path -LiteralPath $stopMarker) { Remove-Item -LiteralPath $stopMarker }

    if ($config.Model.Enabled) {
        Start-Component -Name 'bonsai' -Executable $config.Model.Executable `
            -Arguments $config.Model.Arguments -Directory $config.Model.WorkingDirectory `
            -Environment @{ PATH = "$($config.Model.DllDirectory);$env:PATH" } `
            -Marker $config.Model.ModelPath -Port $config.Model.Port `
            -ReadyUrl "http://127.0.0.1:$($config.Model.Port)/health" -TimeoutSeconds 300
    }
    if ($Action -eq 'start-model') { return }

    if (-not (Test-Path -LiteralPath $jar)) { throw "Build the backend first: $jar" }
    $databasePath = (Join-Path $localRoot 'data\consense').Replace('\', '/')
    $backendEnvironment = @{
        SPRING_PROFILES_ACTIVE = 'h2'
        CONSENSE_H2_JDBC_URL = "jdbc:h2:file:$databasePath;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        CONSENSE_STORAGE_ROOT = (Join-Path $localRoot 'data\uploads')
        CONSENSE_ALLOWED_ORIGINS = $frontendOrigins -join ','
        CONSENSE_LLM_ENABLED = $config.Model.Enabled.ToString().ToLower()
        CONSENSE_MINIMAX_ENABLED = 'false'
        CONSENSE_OCR_ENABLED = 'false'
    }
    if ($config.MiniMaxCn -and $config.MiniMaxCn.Enabled) {
        $miniMaxKeyVariable = [string]$config.MiniMaxCn.ApiKeyEnvironmentVariable
        if ([string]::IsNullOrWhiteSpace($miniMaxKeyVariable) -or
            [string]::IsNullOrWhiteSpace([string]$config.MiniMaxCn.BaseUrl)) {
            throw 'MiniMaxCn requires BaseUrl and ApiKeyEnvironmentVariable.'
        }
        # Read the current persisted value, including variables added after this shell started.
        $miniMaxCredential = [Environment]::GetEnvironmentVariable($miniMaxKeyVariable, 'Machine')
        if ([string]::IsNullOrWhiteSpace($miniMaxCredential)) {
            $miniMaxCredential = [Environment]::GetEnvironmentVariable($miniMaxKeyVariable, 'User')
        }
        if ([string]::IsNullOrWhiteSpace($miniMaxCredential)) {
            $miniMaxCredential = [Environment]::GetEnvironmentVariable($miniMaxKeyVariable, 'Process')
        }
        if ([string]::IsNullOrWhiteSpace($miniMaxCredential)) {
            throw "MiniMax credential environment variable '$miniMaxKeyVariable' is not configured."
        }
        $backendEnvironment['CONSENSE_MINIMAX_ENABLED'] = 'true'
        $backendEnvironment['CONSENSE_MINIMAX_BASE_URL'] = [string]$config.MiniMaxCn.BaseUrl
        $backendEnvironment['MiniMaxCN_Token_Plan_API_Key'] = $miniMaxCredential.Trim()
        $miniMaxCredential = $null
    }
    # This Windows host rejects JDK AF_UNIX connect. An absent per-deployment
    # socket directory selects PipeImpl's built-in loopback TCP fallback.
    if (Test-Path -LiteralPath $config.JavaUnixSocketFallbackPath) {
        throw 'The NIO TCP fallback path must remain absent; inspect runtime.json.'
    }
    $backendArguments = @('-Xms256m', "-Xmx${backendMaxHeapMiB}m", '-Dfile.encoding=UTF-8',
        '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
        "-Djdk.net.unixdomain.tmpdir=$($config.JavaUnixSocketFallbackPath)", '-jar', $jar,
        "--server.address=$webBindAddress", "--server.port=$($config.BackendPort)",
        '--consense.vector.provider=memory')
    # Optional local OER configuration stays outside tracked code and can carry
    # reviewed publication/source identities without reparsing fixed references.
    if (-not [string]::IsNullOrWhiteSpace([string]$config.BackendAdditionalConfig)) {
        $additionalConfig = [IO.Path]::GetFullPath([string]$config.BackendAdditionalConfig, $projectRoot)
        $additionalBoundary = [IO.Path]::GetFullPath($localRoot) + [IO.Path]::DirectorySeparatorChar
        if (-not $additionalConfig.StartsWith($additionalBoundary, [StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-Path -LiteralPath $additionalConfig -PathType Leaf)) {
            throw 'BackendAdditionalConfig must be a saved file inside this project .local.'
        }
        $backendArguments += "--spring.config.additional-location=$([Uri]::new($additionalConfig).AbsoluteUri)"
    }
    if ($config.Model.Enabled) {
        $backendEnvironment['CONSENSE_LLM_BASE_URL'] = "http://127.0.0.1:$($config.Model.Port)"
        $backendArguments += @('--consense.llm.provider=openai', "--consense.llm.chat-model=$($config.Model.Id)",
            "--consense.llm.num-ctx=$($config.Model.ContextSize)",
            "--consense.llm.structured-max-tokens=$($config.Model.MaxOutputTokens)")
    }
    $backendArguments += $rendererArguments
    Start-MiniMaxRelay -Restart:($Action -eq 'restart-web')
    $backendDeploymentStarted = $false
    try {
        if ($Action -in @('restart-backend', 'restart-web', 'deploy-backend')) {
            $backendDeploymentStarted = $true
            Stop-Component 'backend'
            Wait-PortReleased -Port $config.BackendPort -Name 'Backend'
            if ($Action -eq 'deploy-backend') {
                Copy-Item -LiteralPath $releaseArtifact -Destination $jar
                if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ine $ExpectedBackendSha256) {
                    throw 'Installed backend JAR hash differs from the verified artifact.'
                }
            }
        }
        Start-Component -Name 'backend' -Executable (Join-Path $config.JavaHome 'bin\java.exe') `
            -Arguments $backendArguments -Directory (Join-Path $projectRoot 'ai_consense_service') `
            -Environment $backendEnvironment -Marker $jar -Port $config.BackendPort `
            -ReadyUrl "$backendUrl/api/projects"
    } catch {
        if ($releaseBackup -and $backendDeploymentStarted) {
            Stop-Component 'backend'
            Wait-PortReleased -Port $config.BackendPort -Name 'Backend'
            Copy-Item -LiteralPath $releaseBackup -Destination $jar
            if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ine
                (Get-FileHash -LiteralPath $releaseBackup -Algorithm SHA256).Hash) {
                throw 'Rollback JAR hash differs from its backup.'
            }
            Start-Component -Name 'backend' -Executable (Join-Path $config.JavaHome 'bin\java.exe') `
                -Arguments $backendArguments -Directory (Join-Path $projectRoot 'ai_consense_service') `
                -Environment $backendEnvironment -Marker $jar -Port $config.BackendPort `
                -ReadyUrl "$backendUrl/api/projects"
            Write-Warning 'New backend failed readiness; the previous JAR was restored.'
        }
        throw
    }

    $webRoot = Join-Path $projectRoot 'ai_consense_web'
    if (-not (Test-Path -LiteralPath (Join-Path $webRoot 'dist\index.html'))) { throw 'Build the frontend first.' }
    $viteEntry = Join-Path $webRoot 'node_modules\vite\bin\vite.js'
    if ($Action -eq 'restart-web') {
        Stop-Component 'frontend'
        Wait-PortReleased -Port $config.FrontendPort -Name 'Frontend'
    }
    Start-Component -Name 'frontend' -Executable $config.NodePath `
        -Arguments @($viteEntry, 'preview', '--host', $webBindAddress, '--port', "$($config.FrontendPort)", '--strictPort') `
        -Directory $webRoot -Environment @{ CONSENSE_API_TARGET = $backendUrl } `
        -Marker $viteEntry -Port $config.FrontendPort -ReadyUrl $frontendUrl
    Write-Host "Open $frontendUrl/#/drafting"
    $localTask = Get-ScheduledTask -TaskName 'ConSense-Local' -ErrorAction SilentlyContinue
    if ($localTask -and $localTask.State -ne 'Running' -and
        $localTask.Actions.Arguments.Contains((Join-Path $PSScriptRoot 'watch-local.ps1'))) {
        Start-ScheduledTask -TaskName 'ConSense-Local'
    }
}
finally {
    $deploymentMutex.ReleaseMutex()
    $deploymentMutex.Dispose()
}
