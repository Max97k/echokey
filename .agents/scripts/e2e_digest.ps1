function Get-AppMainDigest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot
    )

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $head = ((& git -C $RepoRoot rev-parse HEAD 2>$null) -join "`n").Trim()
        if ($LASTEXITCODE -ne 0) { $head = "NO_HEAD" }

        $diffLines = & git -C $RepoRoot diff HEAD -- app/src/main 2>$null
        $diffText = if ($diffLines) { ($diffLines -join "`n") } else { "" }

        $untracked = & git -C $RepoRoot ls-files --others --exclude-standard -- app/src/main 2>$null |
            Where-Object { $_ -and $_.Trim() -ne "" } |
            Sort-Object

        $sb = [System.Text.StringBuilder]::new()
        [void]$sb.AppendLine("HEAD:$head")
        [void]$sb.AppendLine("DIFF:")
        [void]$sb.AppendLine($diffText)
        [void]$sb.AppendLine("UNTRACKED:")

        foreach ($rel in $untracked) {
            $normRel = $rel.Replace('\', '/')
            $full = Join-Path $RepoRoot $rel
            if (Test-Path -LiteralPath $full -PathType Leaf) {
                $bytes = [System.IO.File]::ReadAllBytes($full)
                $fileHash = [System.BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant()
                [void]$sb.AppendLine("${normRel}:${fileHash}")
            }
        }

        $payloadBytes = [System.Text.Encoding]::UTF8.GetBytes($sb.ToString())
        return [System.BitConverter]::ToString($sha.ComputeHash($payloadBytes)).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}
