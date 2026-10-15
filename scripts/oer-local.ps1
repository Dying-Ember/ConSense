#Requires -Version 7.4
[CmdletBinding()]
param(
    [ValidateSet('start','status','stop')][string]$Action = 'status',
    [ValidateSet('retrieval','raw-vlm','unified-vetting','unified-advisory')][string]$Component = 'unified-vetting',
    [string]$StateDirectory,
    [string]$ScopeManifest,
    [string]$ModelPublicationConfig
)
$ErrorActionPreference = 'Stop'
$oerProject = Split-Path -Parent $PSScriptRoot
$oerRoot = Join-Path $oerProject '.local/oer'
$oerState = Join-Path $oerRoot 'state'
$oerReceiptPath = Join-Path $oerState "$Component-process.json"
$oerPython = Join-Path $oerRoot 'venvs/retrieval-gpu/Scripts/python.exe'
$oerToolRoot = Join-Path $oerProject 'ai_consense_service/tools/vetting_eval'
$oerEvidence = Join-Path $oerProject '.local/state/oer-implementation-20261009'
$oerUnified = $Component.StartsWith('unified-')
$oerTargetModule = if ($oerUnified) { $Component.Substring('unified-'.Length) } else { 'vetting' }
$oerScript = Join-Path $oerToolRoot $(if ($oerUnified) { 'oer_unified_server.py' } elseif ($Component -eq 'retrieval') { 'oer_server.py' } else { 'mineru_raw_vlm_server.py' })
$oerPort = switch ($Component) { 'retrieval' { 8868 } 'raw-vlm' { 18083 } 'unified-vetting' { 8874 } 'unified-advisory' { 8875 } }
$oerHealth = "http://127.0.0.1:$oerPort/health"
New-Item -ItemType Directory -Path $oerState -Force | Out-Null

function Get-OerOwnedProcesses {
    if (-not (Test-Path -LiteralPath $oerReceiptPath)) { return @() }
    $saved = Get-Content -LiteralPath $oerReceiptPath -Raw | ConvertFrom-Json
    $result = @()
    foreach ($identity in $saved.Processes) {
        $candidate = Get-CimInstance Win32_Process -Filter "ProcessId=$($identity.ProcessId)" -ErrorAction SilentlyContinue
        if (-not $candidate) { continue }
        if ($candidate.ExecutablePath -ine $identity.Executable -or
            $candidate.CreationDate.ToUniversalTime().Ticks -ne ([datetime]$identity.CreatedUtc).ToUniversalTime().Ticks -or
            -not $candidate.CommandLine.Contains($saved.Script)) {
            throw 'Stored OER process identity no longer matches; refusing to stop or reuse that PID.'
        }
        $result += $candidate
    }
    return $result
}

function Test-OerHealth {
    try { return Invoke-RestMethod -Uri $oerHealth -TimeoutSec 5 }
    catch { return $null }
}

function Assert-OerCudaHealth($Health) {
    if ($Health.device -notmatch '^cuda(:[0-9]+)?$' -or
        ($Component -ne 'raw-vlm' -and
         ($Health.oer.recipe -ne 'scope-bound-dual2048-cuda-fp32-no-fixed-cache-v1' -or
          $Health.runtime.embedding.device -ne 'cuda' -or $Health.runtime.reranker.device -ne 'cuda' -or
          $Health.runtime.embedding.dtype -ne 'float32' -or $Health.runtime.reranker.dtype -ne 'float32' -or
          $Health.runtime.embedding.serialOffload -isnot [bool] -or $Health.runtime.embedding.serialOffload -ne $true -or
          $Health.runtime.reranker.serialOffload -isnot [bool] -or $Health.runtime.reranker.serialOffload -ne $true)) -or
        ($Component -eq 'raw-vlm' -and $Health.dtype -ne 'float32')) {
        throw 'Owned OER service is not the required CUDA FP32 runtime; refusing to reuse it.'
    }
    if ($Component -ne 'raw-vlm' -and $Health.oer.targetModule -ne $oerTargetModule) {
        throw 'Owned OER service target module differs; refusing to reuse its state.'
    }
    if ($oerUnified -and ($Health.oer.unifiedCanonical.artifactReadValidation -ne 'actual-cas-unique-per-validation-session-with-exit-rehash' -or
        $Health.oer.unifiedCanonical.artifactReadValidationScope -ne 'canonical_load')) {
        throw 'Unified OER is missing the mixed-source actual-CAS contract.'
    }
    if ($oerUnified) {
        $sourceKinds = $Health.oer.unifiedCanonical.sourceKinds
        if ($sourceKinds -isnot [System.Array] -or $sourceKinds.Count -notin @(4,5,6)) {
            throw 'Unified OER source kinds must be the complete legacy, family or normal observed set.'
        }
        $expectedKinds = @('scanned_pdf','native_docx_snapshot','native_docx_collection','native_pdf')
        $hasFamilyKind = $sourceKinds.Count -ge 5
        $hasNormalKind = $sourceKinds.Count -eq 6
        if ($hasFamilyKind) { $expectedKinds += 'native_pdf_source_family' }
        if ($hasNormalKind) { $expectedKinds += 'normal_observed' }
        $seenKinds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
        foreach ($kind in $sourceKinds) {
            if ($kind -isnot [string] -or $expectedKinds -cnotcontains $kind -or -not $seenKinds.Add($kind)) {
                throw 'Unified OER source kinds are unknown, duplicated or incomplete.'
            }
        }
        $familyAdvertised = @($Health.oer.PSObject.Properties.Name) -ccontains 'nativePdfSourceFamily'
        if (-not $hasFamilyKind -and $familyAdvertised) {
            throw 'Legacy unified OER cannot advertise a family capability without its source kind.'
        }
        if ($hasFamilyKind) {
            # Exact installed normal-family contract; composition never accepts quality or rendering.
            $expectedFamily = [ordered]@{
                adoptedCoverage='disjoint-physical-pages-required-v1'
                componentContract='complete-cold-actual-cas-native-pdf-public-roots'
                crossComponentRelations='unsupported'
                maximumMessageBytes=16777216
                producerKind='native_pdf_source_family'
                projectEndpoint='/project/native-pdf-family'
                projectionInputs='actual-cas-family-spec-only'
                schema='consense.native-pdf-source-family.v1'
                semanticAcceptanceByComposition=$false
                sourcePageRenderStatus='unsupported'
                sourceRevisionKind='derived_same_original_component_set'
                version='same-original-complete-native-pdf-components-v1'
                wholeSourceAccepted=$false
            }
            $familyCapability = $Health.oer.nativePdfSourceFamily
            $familyFields = @($familyCapability.PSObject.Properties.Name)
            if (-not $familyAdvertised -or $null -eq $familyCapability -or $familyFields.Count -ne $expectedFamily.Count) {
                throw 'Unified OER family capability is absent or does not have the exact installed fields.'
            }
            foreach ($name in $expectedFamily.Keys) {
                if ($familyFields -cnotcontains $name) {
                    throw 'Unified OER family capability field names differ from the installed contract.'
                }
                $expected = $expectedFamily[$name]
                $actual = $familyCapability.$name
                if (($expected -is [string] -and ($actual -isnot [string] -or $actual -cne $expected)) -or
                    ($expected -is [bool] -and ($actual -isnot [bool] -or $actual -ne $expected)) -or
                    ($expected -is [int] -and (($actual -isnot [int] -and $actual -isnot [long]) -or $actual -ne $expected))) {
                    throw 'Unified OER family capability differs from the exact installed contract.'
                }
            }
        }
        $normalAdvertised = @($Health.oer.PSObject.Properties.Name) -ccontains 'normalObserved'
        if (-not $hasNormalKind -and $normalAdvertised) {
            throw 'Unified OER cannot advertise a normal observed capability without its source kind.'
        }
        if ($hasNormalKind) {
            $expectedNormal = [ordered]@{
                schema='consense.normal-observed.v1';version='complete-bound-normal-observations-v1'
                producerKind='normal_observed';projectionEndpoint='/normal-observed'
                candidateGraphSchema='consense.canonical-candidate.v1'
                candidateGraphVersion='actual-normal-source-unit-whole-page-observations-v2'
                completeUnitPolicy='complete-bound-generation-all-units-in-source-order'
                nonTextUnitPolicy='retained-in-complete-graph-not-submitted-to-model'
                artifactReadValidation='complete-manifest-actual-cas-reconstruction-every-request'
                originalByteVerifier='java_current_source_reader';observerActor='application'
                sourceQualityAccepted=$false;semanticScopeVerified=$false;humanConfirmed=$false
                adoptionStatus='candidate_unverified';maximumMessageBytes=16777216
            }
            $normalCapability=$Health.oer.normalObserved
            $normalFields=@($normalCapability.PSObject.Properties.Name)
            if (-not $normalAdvertised -or $null -eq $normalCapability -or $normalFields.Count -ne $expectedNormal.Count) {
                throw 'Unified OER normal observed capability is absent or has unexpected fields.'
            }
            foreach ($name in $expectedNormal.Keys) {
                $expected=$expectedNormal[$name];$actual=$normalCapability.$name
                if ($normalFields -cnotcontains $name -or
                    ($expected -is [string] -and ($actual -isnot [string] -or $actual -cne $expected)) -or
                    ($expected -is [bool] -and ($actual -isnot [bool] -or $actual -ne $expected)) -or
                    ($expected -is [int] -and (($actual -isnot [int] -and $actual -isnot [long]) -or $actual -ne $expected))) {
                    throw 'Unified OER normal observed capability differs from the installed contract.'
                }
            }
        }
        foreach ($capabilityName in @('nativeCanonical','nativeDocumentCollection','nativePdfCanonical')) {
            $capability = $Health.oer.$capabilityName
            if ($capability.artifactReadValidation -ne 'actual-cas-unique-per-validation-session-with-exit-rehash' -or
                $capability.artifactReadValidationScope -ne 'canonical_load') {
                throw 'Unified nested native canonical-load capability differs from its actual registry.'
            }
        }
    }
}

function Resolve-OerModelPublication([string]$ConfigPath, [bool]$ExplicitConfig, [bool]$RawVlm) {
    if ($RawVlm) {
        return [pscustomobject]@{ Mode='not-applicable'; Environment=@{}; ConfigPath=$null; ConfigSha256=$null }
    }
    # Override inherited host values even in strict mode; the child must use
    # the exact mode recorded below rather than an unrelated system setting.
    $publicationEnvironment = @{}
    foreach ($kind in @('embedding','reranker')) {
        $prefix = 'CONSENSE_' + $kind.ToUpperInvariant() + '_CHECKPOINT_MANIFEST'
        $publicationEnvironment[$prefix] = ''
        $publicationEnvironment[$prefix + '_SHA256'] = ''
    }
    if (-not $ConfigPath) { $ConfigPath = Join-Path $oerRoot 'model-publications/runtime.json' }
    $ConfigPath = [IO.Path]::GetFullPath($ConfigPath, $oerProject)
    if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
        if ($ExplicitConfig) { throw 'Explicit ModelPublicationConfig is missing; no service was started.' }
        return [pscustomobject]@{ Mode='strict-checkpoint-validation'; Environment=$publicationEnvironment;
            ConfigPath=$null; ConfigSha256=$null }
    }
    $configSha = (Get-FileHash -LiteralPath $ConfigPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $publications = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    foreach ($kind in @('embedding','reranker')) {
        $publication = $publications.$kind
        if (-not $publication -or [string]::IsNullOrWhiteSpace([string]$publication.manifestPath) -or
            $publication.manifestSha256 -cnotmatch '^[a-f0-9]{64}$') {
            throw 'Model publication configuration requires both exact manifests and external SHA256 pins.'
        }
        $manifestPath = [IO.Path]::GetFullPath([string]$publication.manifestPath, $oerProject)
        if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or
            (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $publication.manifestSha256) {
            throw 'Model publication manifest is absent or differs from its explicit pin; no service was started.'
        }
        $prefix = 'CONSENSE_' + $kind.ToUpperInvariant() + '_CHECKPOINT_MANIFEST'
        $publicationEnvironment[$prefix] = $manifestPath
        $publicationEnvironment[$prefix + '_SHA256'] = [string]$publication.manifestSha256
    }
    if ((Get-FileHash -LiteralPath $ConfigPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $configSha) {
        throw 'Model publication configuration changed during preparation; no service was started.'
    }
    [pscustomobject]@{ Mode='immutable-publication'; Environment=$publicationEnvironment;
        ConfigPath=$ConfigPath; ConfigSha256=$configSha }
}

function Assert-OerModelPublicationReuse($Requested, $Saved) {
    if ($Requested.Mode -eq 'not-applicable') { return }
    foreach ($name in $Requested.Environment.Keys) {
        if ([string]$Saved.Environment.$name -cne [string]$Requested.Environment[$name]) {
            throw 'Requested model publication differs from the running runtime; explicitly stop and start this component to switch versions.'
        }
    }
}

function New-OerFrozenSource {
    $freezeRoot = Join-Path $oerRoot ('runtime/' + $Component + '/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
    $freezeCode = Join-Path $freezeRoot 'code'
    New-Item -ItemType Directory -Path $freezeCode -Force | Out-Null
    $files = @(Get-ChildItem -LiteralPath $oerToolRoot -Filter '*.py' -File) + @(Get-Item -LiteralPath (Join-Path $oerToolRoot 'samples.json'))
    $manifest = [ordered]@{}
    foreach ($file in $files) {
        $manifest[$file.Name] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    foreach ($file in $files) { Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $freezeCode $file.Name) }
    foreach ($file in $files) {
        if ($manifest[$file.Name] -ne (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -or
            $manifest[$file.Name] -ne (Get-FileHash -LiteralPath (Join-Path $freezeCode $file.Name) -Algorithm SHA256).Hash.ToLowerInvariant()) {
            throw 'OER source changed while freezing it; no process was started.'
        }
    }
    # server.py imports this sibling even when GPU Job coordination is disabled.
    $coordinatorRoot=Join-Path (Split-Path -Parent $oerToolRoot) 'offline_delivery'
    $frozenCoordinator=Join-Path $freezeRoot 'offline_delivery'
    New-Item -ItemType Directory -Path $frozenCoordinator | Out-Null
    foreach($dependency in @('coordinator.py','delivery.py','request_stage.py','gpu_stage.py')) {
        $source=Join-Path $coordinatorRoot $dependency
        $target=Join-Path $frozenCoordinator $dependency
        $sha=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
        Copy-Item -LiteralPath $source -Destination $target
        if ($sha -cne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -or
            $sha -cne (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()) {
            throw 'OER coordinator dependency changed while freezing it; no process was started.'
        }
        $manifest['../offline_delivery/'+$dependency]=$sha
    }
    $manifestPath = Join-Path $freezeRoot 'source-manifest.json'
    $manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    $scopeCopy = Join-Path $freezeRoot 'sl-technical-scope-manifest.json'
    $scopeSha = (Get-FileHash -LiteralPath $ScopeManifest -Algorithm SHA256).Hash.ToLowerInvariant()
    Copy-Item -LiteralPath $ScopeManifest -Destination $scopeCopy
    if ($scopeSha -ne (Get-FileHash -LiteralPath $scopeCopy -Algorithm SHA256).Hash.ToLowerInvariant() -or
        $scopeSha -ne (Get-FileHash -LiteralPath $ScopeManifest -Algorithm SHA256).Hash.ToLowerInvariant()) {
        throw 'OER scope changed while freezing it; no process was started.'
    }
    [ordered]@{ Script=(Join-Path $freezeCode (Split-Path -Leaf $oerScript)); ScopeManifest=$scopeCopy;
        ScopeManifestSha256=$scopeSha; SourceManifest=$manifestPath;
        SourceManifestSha256=(Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant() }
}

$owned = @(Get-OerOwnedProcesses)
if ($Action -eq 'status') {
    $savedPublication = if (Test-Path -LiteralPath $oerReceiptPath) { (Get-Content -LiteralPath $oerReceiptPath -Raw | ConvertFrom-Json).ModelPublication } else { $null }
    [ordered]@{ Component=$Component; OwnedProcessIds=@($owned | ForEach-Object ProcessId);
        ModelPublication=$savedPublication; Health=(Test-OerHealth) } | ConvertTo-Json -Depth 16
    exit
}
if ($Action -eq 'stop') {
    # Child and wrapper PIDs were recorded at launch; validate all identities
    # before stopping any. Never discover a process to stop from a port alone.
    foreach ($candidate in ($owned | Sort-Object ProcessId -Descending)) { Stop-Process -Id $candidate.ProcessId -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $oerReceiptPath) { Remove-Item -LiteralPath $oerReceiptPath }
    Write-Output "$Component stopped"
    exit
}
$modelPublication = Resolve-OerModelPublication -ConfigPath $ModelPublicationConfig `
    -ExplicitConfig $PSBoundParameters.ContainsKey('ModelPublicationConfig') -RawVlm ($Component -eq 'raw-vlm')
if ($modelPublication.Mode -eq 'strict-checkpoint-validation') {
    Write-Output 'Model publication is not configured; this runtime uses strict checkpoint byte validation.'
}
if ($owned.Count) {
    $savedRuntime = Get-Content -LiteralPath $oerReceiptPath -Raw | ConvertFrom-Json
    Assert-OerModelPublicationReuse -Requested $modelPublication -Saved $savedRuntime
    $existingHealth = Test-OerHealth
    if (-not $existingHealth) { throw 'Owned OER process is running without health; inspect its recorded logs.' }
    Assert-OerCudaHealth $existingHealth
    Write-Output "$Component is already running"
    exit
}
if (Get-NetTCPConnection -State Listen -LocalPort $oerPort -ErrorAction SilentlyContinue) { throw "Port $oerPort is already occupied; no process was stopped." }
if (-not (Test-Path -LiteralPath $oerPython) -or -not (Test-Path -LiteralPath $oerScript)) { throw 'Prepare the isolated OER environment and source first.' }
if (-not $ScopeManifest) { $ScopeManifest = Join-Path $oerProject '.local/state/sl-scope-bookmarks-20261009/sl-technical-scope-manifest.json' }
if (-not $StateDirectory) { $StateDirectory = Join-Path $oerRoot $(if ($oerUnified) { "$Component-state" } else { 'retrieval-state' }) }
$StateDirectory = [IO.Path]::GetFullPath($StateDirectory, $oerProject)
$localBoundary = [IO.Path]::GetFullPath((Join-Path $oerProject '.local')) + [IO.Path]::DirectorySeparatorChar
if (-not $StateDirectory.StartsWith($localBoundary, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'OER state must be a separate directory inside this project .local.'
}
foreach ($otherReceipt in (Get-ChildItem -LiteralPath $oerState -Filter '*-process.json' -File)) {
    if ($otherReceipt.FullName -ieq $oerReceiptPath) { continue }
    $other = Get-Content -LiteralPath $otherReceipt.FullName -Raw | ConvertFrom-Json
    if ($other.Environment.CONSENSE_RETRIEVAL_DATA -and [IO.Path]::GetFullPath($other.Environment.CONSENSE_RETRIEVAL_DATA) -ieq $StateDirectory) {
        throw 'Another OER component owns this state directory; choose a module-specific state.'
    }
}
$frozen = $null
if ($Component -ne 'raw-vlm') {
    $frozen = New-OerFrozenSource
    $oerScript = $frozen.Script
    $ScopeManifest = $frozen.ScopeManifest
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$stdout = Join-Path $oerState "$Component-$stamp.stdout.log"
$stderr = Join-Path $oerState "$Component-$stamp.stderr.log"
$arguments = @($oerScript)
if ($Component -eq 'raw-vlm') {
    $arguments += @('--model-receipt', (Join-Path $oerEvidence 'mineru-raw-model-files.json'), '--evidence', (Join-Path $oerEvidence 'mineru-raw-http'), '--port', "$oerPort")
} else { $arguments += @('--host', '127.0.0.1', '--port', "$oerPort") }
$argumentLine = ($arguments | ForEach-Object { '"' + [regex]::Replace([regex]::Replace($_, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"' }) -join ' '
$environment = @{
    PYTHONUTF8='1'; PYTHONDONTWRITEBYTECODE='1'; PYTHONUNBUFFERED='1'; HF_HOME=(Join-Path $oerRoot 'hf'); HF_HUB_OFFLINE='1'; TRANSFORMERS_OFFLINE='1';
    CONSENSE_WORKSPACE_ROOT=$oerProject; CONSENSE_RETRIEVAL_DATA=$StateDirectory; CONSENSE_OER_TARGET_MODULE=$oerTargetModule;
    CONSENSE_MODEL_DEVICE='cuda'; CONSENSE_MODEL_DTYPE='float32'; CONSENSE_MODEL_SERIAL_OFFLOAD='1';
    CONSENSE_RETRIEVAL_GRANULARITY='dual-structure-v1';
    CONSENSE_STRUCTURAL_EMBED_MAX_TOKENS='2048'; CONSENSE_STRUCTURAL_RERANK_MAX_TOKENS='2048';
    CONSENSE_STRUCTURAL_RERANK_STRICT='true'; CONSENSE_EMBED_BATCH_SIZE='2'; CONSENSE_RERANK_BATCH_SIZE='2';
    CONSENSE_SL_SCOPE_MANIFEST=$ScopeManifest;
    CONSENSE_SL_SCOPE_MANIFEST_SHA256=$(if ($frozen) { $frozen.ScopeManifestSha256 } else { '' })
}
foreach ($name in $modelPublication.Environment.Keys) {
    $environment[$name] = $modelPublication.Environment[$name]
}
$child = Start-Process -FilePath $oerPython -ArgumentList $argumentLine -WorkingDirectory $oerProject -WindowStyle Hidden -Environment $environment -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
Start-Sleep -Milliseconds 500
$launched = @(Get-CimInstance Win32_Process -Filter "ProcessId=$($child.Id)") + @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($child.Id)" | Where-Object { $_.CommandLine.Contains($oerScript) })
$identities = @($launched | Where-Object { $_ } | ForEach-Object { [ordered]@{ ProcessId=$_.ProcessId; Executable=$_.ExecutablePath; CreatedUtc=$_.CreationDate.ToUniversalTime().ToString('o') } })
[ordered]@{ Component=$Component; Script=$oerScript; Port=$oerPort; Processes=$identities; Stdout=$stdout; Stderr=$stderr;
    Environment=$environment; FrozenSource=$frozen; ModelPublication=[ordered]@{ Mode=$modelPublication.Mode;
        ConfigPath=$modelPublication.ConfigPath; ConfigSha256=$modelPublication.ConfigSha256 } } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $oerReceiptPath -Encoding utf8
$deadline = (Get-Date).AddSeconds(120)
while ((Get-Date) -lt $deadline) {
    $health = Test-OerHealth
    if ($health) { Assert-OerCudaHealth $health; Write-Output "$Component serving at $oerHealth; model/quality acceptance is separate"; exit }
    if (-not @(Get-OerOwnedProcesses).Count) { Get-Content -LiteralPath $stderr -Tail 20; throw 'OER process exited during startup' }
    Start-Sleep -Seconds 1
}
throw "OER startup health timeout; inspect $stderr"
