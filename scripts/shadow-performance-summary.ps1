$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location (Join-Path $repoRoot "ml-inference-service")
try {
    $env:PYTHONPATH = "."
    python -m offline_evaluation.generate_current_shadow_summary @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
