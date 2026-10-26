$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Push-Location -LiteralPath $repositoryRoot
try {
    node scripts/bootstrap-local-model-promotion-keys.mjs --ensure
    if ($LASTEXITCODE -ne 0) { throw 'Local trust bootstrap failed' }
    mvn -pl model-promotion-policy-publisher -am -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Publisher build failed' }
    $jar = 'model-promotion-policy-publisher/target/model-promotion-policy-publisher-0.0.1-SNAPSHOT-exec.jar'
    java -jar $jar trust-check `
        --approval-trust 'deployment/.local/model-promotion-policy/approval/trusted-approvals.json' `
        --trusted-issuers 'deployment/.local/model-promotion-policy/trusted-issuers.json'
    if ($LASTEXITCODE -ne 0) { throw 'Trust registry validation failed' }
} finally {
    Pop-Location
}
