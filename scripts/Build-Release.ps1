param(
    [Parameter(Mandatory = $true)][string]$KeystorePath,
    [Parameter(Mandatory = $true)][string]$PasswordFilePath,
    [Parameter(Mandatory = $true)][string]$AndroidSdkPath
)

$ErrorActionPreference = 'Stop'
$apksigner = Join-Path $AndroidSdkPath 'build-tools\36.0.0\lib\apksigner.jar'
if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) {
    throw 'No se encontro apksigner en el SDK indicado.'
}
foreach ($path in @($KeystorePath, $PasswordFilePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw 'No se encontro uno de los archivos locales de firma.'
    }
}
$previousStore = $env:PAGAYA_RELEASE_STORE_FILE
$previousPassword = $env:PAGAYA_RELEASE_PASSWORD_FILE
$env:PAGAYA_RELEASE_STORE_FILE = (Resolve-Path -LiteralPath $KeystorePath).Path
$env:PAGAYA_RELEASE_PASSWORD_FILE = (Resolve-Path -LiteralPath $PasswordFilePath).Path
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    # La configuracion de Gradle contiene la contraseña: no persistirla en configuration cache.
    & .\gradlew.bat assembleRelease lintRelease --no-daemon --no-configuration-cache --no-build-cache --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'La compilacion o Lint de release fallo.' }
    $apk = Join-Path (Get-Location) 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $apk)) { throw 'No se genero el APK firmado esperado.' }
    $verification = & java -jar $apksigner verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'La firma del APK no es valida.' }
    $expectedCertificate = '721e8bbb18287eb0355c72d1d2c4bc1d89cd9c6d18d4677969541d59f5b2cc44'
    if (-not ($verification -match "Signer #1 certificate SHA-256 digest: $expectedCertificate")) {
        throw 'El certificado no coincide con la firma oficial.'
    }
    $metadata = Get-Content 'app/build/outputs/apk/release/output-metadata.json' -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne 'com.fielamornoble.pagaya' -or $metadata.elements.Count -ne 1) {
        throw 'Identidad o cantidad de APK inesperada.'
    }
    $version = $metadata.elements[0].versionName
    if ($version -notmatch '^[0-9A-Za-z.-]+$') { throw 'Nombre de version no valido.' }
    $destination = Join-Path (Get-Location) "artifacts/releases/$version"
    if (Test-Path -LiteralPath $destination) {
        throw 'La entrega ya existe. No se sobrescribira; conserva el artefacto previamente verificado.'
    }
    New-Item -ItemType Directory -Path $destination | Out-Null
    $fileName = "PagaYa-$version.apk"
    $copy = Join-Path $destination $fileName
    Copy-Item -LiteralPath $apk -Destination $copy
    $hash = (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()) {
        throw 'La copia del APK no coincide.'
    }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText((Join-Path $destination 'SHA256SUMS.txt'), "$hash  $fileName`n", $utf8)
    [IO.File]::WriteAllText((Join-Path $destination 'firma-verificada.txt'), ($verification -join "`n"), $utf8)
    $commit = & git rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo identificar el commit.' }
    $record = [ordered]@{
        applicationId = $metadata.applicationId
        versionName = $version
        versionCode = $metadata.elements[0].versionCode
        sha256 = $hash
        certificateSha256 = $expectedCertificate
        sourceCommit = $commit
    } | ConvertTo-Json
    [IO.File]::WriteAllText((Join-Path $destination 'entrega.json'), $record, $utf8)
    Write-Output "APK verificado y conservado: $copy"
    Write-Output "SHA-256: $hash"
} finally {
    Pop-Location
    $env:PAGAYA_RELEASE_STORE_FILE = $previousStore
    $env:PAGAYA_RELEASE_PASSWORD_FILE = $previousPassword
}
