param(
    [Parameter(Mandatory)][ValidateSet('Install','Remove')][string]$Mode,
    [Parameter(Mandatory)][string]$Package
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$name = 'Ukkera.POS.Test'
$family = 'Ukkera.POS.Test_n9kkbga0qmnm6'
$service = 'UkkeraPOSTestMaster'
$verifiedPackage = (Resolve-Path -LiteralPath $Package).Path
if ((Split-Path -Leaf $verifiedPackage) -ne 'UkkeraPOS-Test.msix' -or
    (Split-Path -Parent $verifiedPackage) -ne $PSScriptRoot) { throw 'Unexpected signed payload location' }
$marker = Join-Path $PSScriptRoot 'registered-test-package.txt'

if ($Mode -eq 'Install') {
    if ((Test-Path -LiteralPath $marker) -or (Get-AppxPackage -Name $name) -or
        (Get-Service -Name $service -ErrorAction SilentlyContinue)) {
        throw 'A POS package or service is already installed. This fresh-install control will retain it.'
    }
    try {
        # Windows verifies the existing signed package and installs its actual
        # service using the original service manifest and executable identity.
        Add-AppxPackage -Path $verifiedPackage
        $app = Get-AppxPackage -Name $name
        if (!$app -or $app.PackageFamilyName -ne $family -or
            $null -eq (Get-Service -Name $service -ErrorAction SilentlyContinue)) {
            throw 'The native app and service registration did not complete'
        }
        [IO.File]::WriteAllText($marker, $app.PackageFullName)
        Write-Output 'POS_SERVICE_PACKAGE_REGISTERED'
    } catch {
        # Preflight guaranteed there was no existing same-name package here.
        # Only undo a registration positively observed on this installing user.
        $partial = Get-AppxPackage -Name $name
        if ($partial -and $partial.PackageFamilyName -eq $family) {
            Remove-AppxPackage -Package $partial.PackageFullName
        }
        throw
    }
} else {
    if (!(Test-Path -LiteralPath $marker)) { throw 'The original MSI registration record is missing' }
    $saved = (Get-Content -LiteralPath $marker -Raw).Trim()
    $app = Get-AppxPackage -Name $name
    if ($app -and $app.PackageFamilyName -eq $family -and $app.PackageFullName -eq $saved) {
        Remove-AppxPackage -Package $saved
    } elseif ($app -or (Get-Service -Name $service -ErrorAction SilentlyContinue)) {
        throw 'The installed package no longer matches this MSI registration. Original data is unchanged.'
    }
    Remove-Item -LiteralPath $marker
    Write-Output 'POS_SERVICE_PACKAGE_UNREGISTERED'
}
