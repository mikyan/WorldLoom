param(
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repositoryRoot
try {
    $secretMatches = git grep -n -I -E `
        'sk-[A-Za-z0-9_-]{20,}|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY' `
        -- . ':(exclude)**/build/**' 2>$null
    if ($LASTEXITCODE -eq 0) {
        throw "Potential committed secret material found:`n$($secretMatches -join "`n")"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "Secret scan could not complete"
    }

    $topicMatches = git grep -n -E `
        'contract\.(war-survival|station-ai)|DefinitionId\("(war|station)\.' `
        -- ':(glob)shared/*/src/commonMain/**' 2>$null
    if ($LASTEXITCODE -eq 0) {
        throw "Topic-specific branch material found in shared production Runtime:`n$($topicMatches -join "`n")"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "Topic-hardcoding scan could not complete"
    }

    if (-not $SkipTests) {
        & .\gradlew.bat `
            :shared:agent-runtime:desktopTest `
            :shared:application:desktopTest `
            :shared:world-package:desktopTest `
            :platform:secure-vault:desktopTest `
            --no-daemon
        if ($LASTEXITCODE -ne 0) {
            throw "Alpha security regression tests failed"
        }
    }
    Write-Host "Alpha audit passed: secrets, topic boundaries, tools, replay privacy, Agent isolation, world packages, and vault."
} finally {
    Pop-Location
}
exit 0
