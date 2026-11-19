$ErrorActionPreference = "SilentlyContinue"

function Write-Decision {
    param(
        [string]$Decision,
        [string]$Reason = ""
    )
    if ($Reason) {
        $obj = [ordered]@{ decision = $Decision; reason = $Reason }
    } else {
        $obj = [ordered]@{ decision = $Decision }
    }
    [Console]::Out.Write(($obj | ConvertTo-Json -Compress))
    exit 0
}

$raw = [Console]::In.ReadToEnd()
if (-not $raw -or -not $raw.Trim()) {
    Write-Decision -Decision "allow"
}

try {
    $payload = $raw | ConvertFrom-Json
} catch {
    Write-Decision -Decision "allow"
}

if ($payload.terminationReason -ne "NO_TOOL_CALL") {
    Write-Decision -Decision "allow"
}
if ($payload.continuationActive -eq $true) {
    Write-Decision -Decision "allow"
}
if ($null -ne $payload.executionNum -and [int]$payload.executionNum -gt 1) {
    Write-Decision -Decision "allow"
}
if ($payload.PSObject.Properties["fullyIdle"] -and $payload.fullyIdle -eq $false) {
    Write-Decision -Decision "allow"
}

$repoRoot = if ($payload.cwd -and (Test-Path -LiteralPath $payload.cwd)) {
    $payload.cwd
} else {
    (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

$dirty = & git -C $repoRoot status --porcelain -- app/src/main 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Decision -Decision "allow"
}
if (-not ($dirty | Where-Object { $_ -and $_.Trim() -ne "" })) {
    Write-Decision -Decision "allow"
}

. (Join-Path $PSScriptRoot "e2e_digest.ps1")
$currentDigest = Get-AppMainDigest -RepoRoot $repoRoot

$maestroDir = Join-Path $repoRoot ".maestro"
$yamlFiles = if (Test-Path -LiteralPath $maestroDir) {
    Get-ChildItem -LiteralPath $maestroDir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -in @(".yaml", ".yml") }
} else {
    @()
}

$lastRunPath = Join-Path $maestroDir ".last_run.json"
if ((Test-Path -LiteralPath $lastRunPath) -and $yamlFiles.Count -gt 0) {
    try {
        $lastRun = (Get-Content -LiteralPath $lastRunPath -Raw -Encoding UTF8) | ConvertFrom-Json
        if ($lastRun.passed -eq $true -and $lastRun.appMainDigest -eq $currentDigest) {
            Write-Decision -Decision "allow"
        }
    } catch {
        # Fall through to continue prompt if record is unreadable.
    }
}

Write-Decision -Decision "continue" -Reason "Detected unverified changes under app/src/main/. Per AGENTS.md, add or update the matching .maestro/*.yaml flow and run ``powershell -NoProfile -ExecutionPolicy Bypass -File .agents/scripts/run_maestro_e2e.ps1`` until all Maestro tests pass before finishing."
