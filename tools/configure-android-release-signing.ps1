[CmdletBinding()]
param(
    [string]$Repository = "mikyan/WorldLoom",
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "../.worldloom-signing")
)

$ErrorActionPreference = "Stop"

function New-SigningPassword {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToHexString($bytes).ToLowerInvariant()
}

function Set-RepositorySecret {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value
    )

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = (Get-Command gh -ErrorAction Stop).Source
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.ArgumentList.Add("secret")
    $startInfo.ArgumentList.Add("set")
    $startInfo.ArgumentList.Add($Name)
    $startInfo.ArgumentList.Add("--repo")
    $startInfo.ArgumentList.Add($Repository)

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $null = $process.Start()
    $process.StandardInput.Write($Value)
    $process.StandardInput.Close()
    $standardOutput = $process.StandardOutput.ReadToEnd()
    $standardError = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "Failed to configure GitHub repository secret $Name`: $standardError$standardOutput"
    }
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$resolvedOutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$repositoryPrefix = $repositoryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $resolvedOutputDirectory.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Signing backup directory must remain inside the repository's ignored .worldloom-signing directory"
}
if ([IO.Path]::GetFileName($resolvedOutputDirectory.TrimEnd([IO.Path]::DirectorySeparatorChar)) -ne ".worldloom-signing") {
    throw "Signing backup directory must be named .worldloom-signing"
}

$null = Get-Command gh -ErrorAction Stop
$keytool = (Get-Command keytool -ErrorAction Stop).Source
& gh auth status | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "GitHub CLI is not authenticated"
}

$keystorePath = Join-Path $resolvedOutputDirectory "worldloom-android-release.p12"
$credentialsPath = Join-Path $resolvedOutputDirectory "android-release-credentials.env"
$certificatePath = Join-Path $resolvedOutputDirectory "worldloom-android-release.cer"
@($keystorePath, $credentialsPath, $certificatePath) | ForEach-Object {
    if (Test-Path -LiteralPath $_) {
        throw "Refusing to replace existing Android signing material: $_"
    }
}

if (-not (Test-Path -LiteralPath $resolvedOutputDirectory)) {
    New-Item -ItemType Directory -Path $resolvedOutputDirectory | Out-Null
}
$password = New-SigningPassword
$alias = "worldloom"
$env:WORLDLOOM_GENERATED_SIGNING_PASSWORD = $password
try {
    $keyGenerationArguments = @(
        "-genkeypair",
        "-keystore", $keystorePath,
        "-storetype", "PKCS12",
        "-alias", $alias,
        "-keyalg", "RSA",
        "-keysize", "4096",
        "-sigalg", "SHA256withRSA",
        "-validity", "10000",
        "-dname", "CN=Worldloom Android Release, O=Worldloom",
        "-storepass:env", "WORLDLOOM_GENERATED_SIGNING_PASSWORD",
        "-keypass:env", "WORLDLOOM_GENERATED_SIGNING_PASSWORD"
    )
    & $keytool @keyGenerationArguments
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed to create the Android release signing key"
    }

    $certificateExportArguments = @(
        "-exportcert",
        "-keystore", $keystorePath,
        "-alias", $alias,
        "-file", $certificatePath,
        "-storepass:env", "WORLDLOOM_GENERATED_SIGNING_PASSWORD"
    )
    & $keytool @certificateExportArguments
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed to export the Android release certificate"
    }
} finally {
    Remove-Item Env:WORLDLOOM_GENERATED_SIGNING_PASSWORD -ErrorAction SilentlyContinue
}

$credentialLines = @(
    "WORLDLOOM_ANDROID_KEYSTORE_FILE=$keystorePath",
    "WORLDLOOM_ANDROID_KEYSTORE_PASSWORD=$password",
    "WORLDLOOM_ANDROID_KEY_ALIAS=$alias",
    "WORLDLOOM_ANDROID_KEY_PASSWORD=$password"
)
[IO.File]::WriteAllLines($credentialsPath, $credentialLines, [Text.UTF8Encoding]::new($false))

$keystoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))
Set-RepositorySecret "ANDROID_RELEASE_KEYSTORE_BASE64" $keystoreBase64
Set-RepositorySecret "ANDROID_RELEASE_STORE_PASSWORD" $password
Set-RepositorySecret "ANDROID_RELEASE_KEY_ALIAS" $alias
Set-RepositorySecret "ANDROID_RELEASE_KEY_PASSWORD" $password

$certificateDigest = [Security.Cryptography.SHA256]::HashData([IO.File]::ReadAllBytes($certificatePath))
$fingerprint = [Convert]::ToHexString($certificateDigest).ToLowerInvariant()
[IO.File]::WriteAllText(
    (Join-Path $resolvedOutputDirectory "android-signing-cert.sha256"),
    "$fingerprint`n",
    [Text.UTF8Encoding]::new($false)
)

Write-Output "Configured stable Android release signing for $Repository."
Write-Output "Certificate SHA-256: $fingerprint"
Write-Output "Back up this ignored directory securely: $resolvedOutputDirectory"
