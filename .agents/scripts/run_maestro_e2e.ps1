[CmdletBinding()]
param(
    [string]$AvdName = "akeyboard_api36",
    [string]$Flow = ".maestro",
    [switch]$SkipBuild,
    [switch]$KeepEmulator
)

$ErrorActionPreference = "Continue"

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "D:\Android\jdk-17" }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "D:\Android\Sdk" }
if (-not $env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = "D:\Android\Sdk" }
if (-not $env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME = "D:\Android\avd" }
$env:MAESTRO_CLI_NO_ANALYTICS = "1"
$env:MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED = "true"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
. (Join-Path $PSScriptRoot "e2e_digest.ps1")

function Resolve-ToolPath {
    param(
        [string[]]$Candidates,
        [string]$FallbackPath
    )
    foreach ($name in $Candidates) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    if ($FallbackPath -and (Test-Path -LiteralPath $FallbackPath)) {
        return $FallbackPath
    }
    return $null
}

$adbExe = Resolve-ToolPath -Candidates @("adb.exe", "adb") -FallbackPath (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
$emulatorExe = Resolve-ToolPath -Candidates @("emulator.exe", "emulator") -FallbackPath (Join-Path $env:ANDROID_SDK_ROOT "emulator\emulator.exe")
$androidExe = Resolve-ToolPath -Candidates @("android.exe", "android") -FallbackPath (Join-Path $env:ANDROID_SDK_ROOT "cmdline-tools\latest\bin\android.exe")
$maestroBat = Resolve-ToolPath -Candidates @("maestro.bat", "maestro") -FallbackPath "C:\Users\b\AppData\Local\maestro\maestro\bin\maestro.bat"

if (-not $adbExe) { throw "adb executable not found." }
if (-not $emulatorExe) { throw "emulator executable not found." }
if (-not $maestroBat) { throw "maestro.bat not found." }

function Get-OnlineEmulatorSerial {
    $lines = & $adbExe devices 2>$null
    foreach ($line in $lines) {
        if ($line -match '^\s*(emulator-\d+)\s+device\b') {
            return $Matches[1]
        }
    }
    return $null
}

function Start-HeadlessEmulator {
    param(
        [string]$Name,
        [string[]]$ExtraArgs
    )
    $outLog = Join-Path $env:TEMP "akeyboard-emulator.out.log"
    $errLog = Join-Path $env:TEMP "akeyboard-emulator.err.log"
    $argList = @("-avd", $Name, "-no-window", "-no-audio", "-no-snapshot-save") + $ExtraArgs
    Write-Host "Starting headless emulator: $emulatorExe $($argList -join ' ')"
    $proc = Start-Process -FilePath $emulatorExe -ArgumentList $argList -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Hidden -PassThru

    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) {
            return @{ Process = $proc; Serial = $null; OutLog = $outLog; ErrLog = $errLog }
        }
        $lines = & $adbExe devices 2>$null
        foreach ($line in $lines) {
            if ($line -match '^\s*(emulator-\d+)\s+(device|offline)\b') {
                return @{ Process = $proc; Serial = $Matches[1]; OutLog = $outLog; ErrLog = $errLog }
            }
        }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for emulator serial to appear in adb devices."
}

$startedByScript = $false
$emuProc = $null
$serial = $null
$maestroExit = 1

Push-Location $repoRoot
try {
    if (-not $SkipBuild) {
        Write-Host "Building debug APK..."
        & .\gradlew.bat assembleDebug --console=plain
        if ($LASTEXITCODE -ne 0) {
            throw "gradlew assembleDebug failed with exit code $LASTEXITCODE"
        }
    }

    $apkPath = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path -LiteralPath $apkPath)) {
        throw "Debug APK not found at $apkPath"
    }

    $serial = Get-OnlineEmulatorSerial
    if ($serial) {
        Write-Host "Reusing running emulator: $serial"
    } else {
        $startedByScript = $true
        $attempt = Start-HeadlessEmulator -Name $AvdName -ExtraArgs @()
        if ($attempt.Process.HasExited -or -not $attempt.Serial) {
            $logTail = @()
            if (Test-Path $attempt.OutLog) { $logTail += (Get-Content $attempt.OutLog -Tail 30 -ErrorAction SilentlyContinue) }
            if (Test-Path $attempt.ErrLog) { $logTail += (Get-Content $attempt.ErrLog -Tail 30 -ErrorAction SilentlyContinue) }
            Write-Warning "Initial emulator launch exited early (exit=$($attempt.Process.ExitCode)); retrying with -gpu swiftshader_indirect. Log: $($logTail -join ' | ')"
            $attempt = Start-HeadlessEmulator -Name $AvdName -ExtraArgs @("-gpu", "swiftshader_indirect")
            if ($attempt.Process.HasExited -or -not $attempt.Serial) {
                throw "Headless emulator failed to launch even with -gpu swiftshader_indirect (exit=$($attempt.Process.ExitCode)). Check $($attempt.OutLog) and $($attempt.ErrLog)."
            }
        }
        $emuProc = $attempt.Process
        $serial = $attempt.Serial

        & $adbExe -s $serial wait-for-device
        $bootDeadline = (Get-Date).AddSeconds(180)
        $booted = $false
        while ((Get-Date) -lt $bootDeadline) {
            $prop = ((& $adbExe -s $serial shell getprop sys.boot_completed 2>$null) -join "").Trim()
            if ($prop -eq "1") {
                $booted = $true
                break
            }
            Start-Sleep -Seconds 2
        }
        if (-not $booted) {
            throw "Timed out waiting for sys.boot_completed=1 on $serial"
        }

        $pmDeadline = (Get-Date).AddSeconds(60)
        $pmReady = $false
        while ((Get-Date) -lt $pmDeadline) {
            $pmOut = ((& $adbExe -s $serial shell pm path android 2>$null) -join "").Trim()
            if ($pmOut -like "package:*") {
                $pmReady = $true
                break
            }
            Start-Sleep -Seconds 2
        }
        if (-not $pmReady) {
            throw "Timed out waiting for package manager readiness on $serial"
        }

        & $adbExe -s $serial shell settings put global window_animation_scale 0 | Out-Null
        & $adbExe -s $serial shell settings put global transition_animation_scale 0 | Out-Null
        & $adbExe -s $serial shell settings put global animator_duration_scale 0 | Out-Null
        & $adbExe -s $serial shell wm dismiss-keyguard 2>$null | Out-Null
        & $adbExe -s $serial shell input keyevent 82 2>$null | Out-Null
    }

    Write-Host "Installing APK on $serial..."
    $installed = $false
    if ($androidExe) {
        & $androidExe install --device $serial --apks $apkPath
        if ($LASTEXITCODE -eq 0) { $installed = $true }
    }
    if (-not $installed) {
        Write-Host "Falling back to adb install -r -d..."
        & $adbExe -s $serial install -r -d $apkPath
        if ($LASTEXITCODE -ne 0) {
            throw "APK install failed on $serial"
        }
    }

    $flowTarget = if ([System.IO.Path]::IsPathRooted($Flow)) { $Flow } else { Join-Path $repoRoot $Flow }
    if (-not (Test-Path -LiteralPath $flowTarget)) {
        throw "Maestro flow target does not exist: $flowTarget"
    }

    Write-Host "Running Maestro tests ($flowTarget) on $serial..."
    & $maestroBat --device $serial test --no-ansi $flowTarget
    $maestroExit = $LASTEXITCODE

    $head = ((& git -C $repoRoot rev-parse HEAD 2>$null) -join "`n").Trim()
    if ($LASTEXITCODE -ne 0) { $head = "" }
    $digest = Get-AppMainDigest -RepoRoot $repoRoot

    $maestroDir = Join-Path $repoRoot ".maestro"
    if (-not (Test-Path -LiteralPath $maestroDir)) {
        New-Item -ItemType Directory -Force -Path $maestroDir | Out-Null
    }
    $record = [ordered]@{
        passed        = ($maestroExit -eq 0)
        timestamp     = (Get-Date).ToString("o")
        avd           = $AvdName
        flow          = $Flow
        gitHead       = $head
        appMainDigest = $digest
    }
    $recordPath = Join-Path $maestroDir ".last_run.json"
    [System.IO.File]::WriteAllText($recordPath, ($record | ConvertTo-Json -Depth 4) + "`n", [System.Text.UTF8Encoding]::new($false))
} finally {
    if ($startedByScript -and -not $KeepEmulator -and $serial) {
        Write-Host "Stopping headless emulator $serial..."
        & $adbExe -s $serial emu kill 2>$null | Out-Null
        if ($emuProc) {
            $killDeadline = (Get-Date).AddSeconds(20)
            while ((Get-Date) -lt $killDeadline -and -not $emuProc.HasExited) {
                Start-Sleep -Milliseconds 500
            }
            if (-not $emuProc.HasExited) {
                Stop-Process -Id $emuProc.Id -Force -ErrorAction SilentlyContinue
            }
        }
    }
    Pop-Location
}

exit $maestroExit
