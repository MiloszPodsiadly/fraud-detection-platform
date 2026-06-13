[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet("up", "down", "clean", "ps")]
    [string]$Action = "up"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot "deployment\.env"
$envExampleFile = Join-Path $repoRoot "deployment\.env.example"
$generatedSummaryFile = Join-Path $repoRoot "deployment\local-generated\shadow-performance\current-summary.json"
$composeArgs = @(
    "compose",
    "--env-file", "deployment/.env",
    "-f", "deployment/docker-compose.yml",
    "-f", "deployment/docker-compose.dev.yml",
    "-f", "deployment/docker-compose.oidc.yml",
    "-f", "deployment/docker-compose.service-identity-mtls.yml",
    "-f", "deployment/docker-compose.trust-authority-jwt.yml",
    "-f", "deployment/docker-compose.hardened.yml",
    "-f", "deployment/docker-compose.shadow-performance-generated.yml"
)

function Invoke-DockerCompose {
    param([string[]]$CommandArgs)

    & docker @($composeArgs + $CommandArgs)
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed with exit code $LASTEXITCODE."
    }
}

function Invoke-ShadowPerformanceSummaryGeneration {
    $previousPythonPath = $env:PYTHONPATH
    Push-Location (Join-Path $repoRoot "ml-inference-service")
    try {
        $env:PYTHONPATH = "."
        & python -m offline_evaluation.generate_current_shadow_summary
        if ($LASTEXITCODE -ne 0) {
            throw "Shadow Performance Summary generation failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        if ($null -eq $previousPythonPath) {
            Remove-Item Env:\PYTHONPATH -ErrorAction SilentlyContinue
        }
        else {
            $env:PYTHONPATH = $previousPythonPath
        }
        Pop-Location
    }
}

function Find-GitBash {
    $candidates = [System.Collections.Generic.List[string]]::new()
    $gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($null -ne $gitCommand) {
        $gitRoot = Split-Path -Parent (Split-Path -Parent $gitCommand.Source)
        $candidates.Add((Join-Path $gitRoot "bin\bash.exe"))
    }

    if ($env:ProgramFiles) {
        $candidates.Add((Join-Path $env:ProgramFiles "Git\bin\bash.exe"))
    }
    if (${env:ProgramFiles(x86)}) {
        $candidates.Add((Join-Path ${env:ProgramFiles(x86)} "Git\bin\bash.exe"))
    }
    if ($env:LOCALAPPDATA) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA "Programs\Git\bin\bash.exe"))
    }

    $bashCommand = Get-Command bash.exe -ErrorAction SilentlyContinue
    if ($null -ne $bashCommand -and $bashCommand.Source -notmatch "\\Windows\\System32\\bash\.exe$") {
        $candidates.Add($bashCommand.Source)
    }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    throw "Git for Windows is required for local fixture generation. Install Git for Windows and rerun scripts\app.cmd up."
}

if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath $envExampleFile -Destination $envFile
}

Push-Location $repoRoot
try {
    switch ($Action) {
        "up" {
            $gitBash = Find-GitBash
            & $gitBash "./scripts/bootstrap-local-fixtures.sh"
            if ($LASTEXITCODE -ne 0) {
                throw "Local fixture generation failed with exit code $LASTEXITCODE."
            }
            Invoke-ShadowPerformanceSummaryGeneration
            if (-not (Test-Path -LiteralPath $generatedSummaryFile)) {
                throw "Generated Shadow Performance Summary not found. Run: make shadow-performance-summary"
            }
            Invoke-DockerCompose @("up", "--build", "-d")
        }
        "down" {
            Invoke-DockerCompose @("down")
        }
        "clean" {
            Invoke-DockerCompose @("down", "-v")
        }
        "ps" {
            Invoke-DockerCompose @("ps")
        }
    }
}
finally {
    Pop-Location
}
