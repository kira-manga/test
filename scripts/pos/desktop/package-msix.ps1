# Builds and signs the Ukkera POS test MSIX from a jpackage application image.
# The signing PFX is supplied by the caller (CI secret); it is never written to the output.
# With -Probe (CI runner only) the package is installed, its OS identity is compared with the
# declared identity, the in-app native identity probe is executed and the package is removed.
param(
    [Parameter(Mandatory = $true)][string]$AppImage,
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$ManifestTemplate,
    [Parameter(Mandatory = $true)][string]$Certificate,
    [Parameter(Mandatory = $true)][string]$OutputDir,
    [string]$ExpectedFamilyName = 'Ukkera.POS.Test_n9kkbga0qmnm6',
    [switch]$Probe
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($Version -notmatch '^\d+\.\d+\.\d+\.0$') { throw "Version must be four-part with a zero revision: $Version" }
if (-not (Test-Path (Join-Path $AppImage 'UkkeraPOS.exe'))) { throw "UkkeraPOS.exe not found in $AppImage" }
$pfx = $env:POS_MSIX_PFX_PATH
$password = $env:POS_MSIX_PFX_PASSWORD
if (-not $pfx -or -not (Test-Path $pfx) -or -not $password) { throw 'Signing PFX/password are not configured' }

$kitRoot = 'C:\Program Files (x86)\Windows Kits\10\bin'
$bin = Get-ChildItem $kitRoot -Directory | Where-Object { $_.Name -match '^10\.' } |
    Sort-Object { [version]$_.Name } -Descending |
    ForEach-Object { Join-Path $_.FullName 'x64' } |
    Where-Object { Test-Path (Join-Path $_ 'makeappx.exe') } | Select-Object -First 1
if (-not $bin) { throw 'Windows SDK makeappx.exe was not found' }
$makeappx = Join-Path $bin 'makeappx.exe'
$signtool = Join-Path $bin 'signtool.exe'

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$diagnostics = Join-Path $OutputDir 'diagnostics'
New-Item -ItemType Directory -Force -Path $diagnostics | Out-Null
$stage = Join-Path ([System.IO.Path]::GetTempPath()) ("ukkera-msix-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $stage | Out-Null
try {
    Copy-Item -Path (Join-Path $AppImage '*') -Destination $stage -Recurse
    $manifest = (Get-Content -Raw -Path $ManifestTemplate).Replace('@VERSION@', $Version)
    [System.IO.File]::WriteAllText((Join-Path $stage 'AppxManifest.xml'), $manifest, (New-Object System.Text.UTF8Encoding($false)))

    Add-Type -AssemblyName System.Drawing
    $assets = Join-Path $stage 'Assets'
    New-Item -ItemType Directory -Path $assets | Out-Null
    foreach ($logo in @(@('StoreLogo.png', 50), @('Square150x150Logo.png', 150), @('Square44x44Logo.png', 44))) {
        $size = [int]$logo[1]
        $bitmap = New-Object System.Drawing.Bitmap $size, $size
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.Clear([System.Drawing.Color]::FromArgb(255, 23, 92, 153))
            $font = New-Object System.Drawing.Font 'Segoe UI', ([single]($size * 0.55)), ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
            $format = New-Object System.Drawing.StringFormat
            $format.Alignment = 'Center'; $format.LineAlignment = 'Center'
            $graphics.DrawString('U', $font, [System.Drawing.Brushes]::White, (New-Object System.Drawing.RectangleF 0, 0, $size, $size), $format)
            $bitmap.Save((Join-Path $assets $logo[0]), [System.Drawing.Imaging.ImageFormat]::Png)
        } finally { $graphics.Dispose(); $bitmap.Dispose() }
    }

    $msix = Join-Path $OutputDir "UkkeraPOS-Test-$Version.msix"
    & $makeappx pack /o /d $stage /p $msix *> (Join-Path $diagnostics 'makeappx.txt')
    if ($LASTEXITCODE -ne 0) { throw "makeappx failed ($LASTEXITCODE); see diagnostics/makeappx.txt" }
    & $signtool sign /fd SHA256 /f $pfx /p $password $msix *> (Join-Path $diagnostics 'signtool-sign.txt')
    if ($LASTEXITCODE -ne 0) { throw "signtool sign failed ($LASTEXITCODE)" }
    # The public certificate is published from the repository, not the artifact (key-file gate).
} finally {
    Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
}

$summary = [ordered]@{ msix = (Split-Path $msix -Leaf); version = $Version; sha256 = (Get-FileHash $msix -Algorithm SHA256).Hash.ToLowerInvariant(); bytes = (Get-Item $msix).Length }
if ($Probe) {
    Import-Certificate -FilePath $Certificate -CertStoreLocation Cert:\LocalMachine\TrustedPeople | Out-Null
    Add-AppxPackage -Path $msix
    try {
        $package = Get-AppxPackage -Name 'Ukkera.POS.Test'
        if (-not $package) { throw 'Installed package was not found' }
        $summary.os_family_name = $package.PackageFamilyName
        if ($package.PackageFamilyName -ne $ExpectedFamilyName) { throw "OS family name $($package.PackageFamilyName) differs from $ExpectedFamilyName" }
        $alias = Join-Path $env:LOCALAPPDATA 'Microsoft\WindowsApps\ukkera-pos-test.exe'
        $probeFile = Join-Path $diagnostics 'identity-probe.txt'
        $process = Start-Process -FilePath $alias -ArgumentList "--identity-probe=$probeFile" -Wait -PassThru
        $summary.probe_exit = $process.ExitCode
        $summary.probe = if (Test-Path $probeFile) { (Get-Content -Raw $probeFile).Trim() } else { 'NO_OUTPUT' }
        if ($process.ExitCode -ne 0 -or -not $summary.probe.StartsWith("DATA pfn=$ExpectedFamilyName ")) {
            throw "Native identity probe failed: exit $($process.ExitCode) $($summary.probe)"
        }
        # In-app Client target custody (TRAINING_2, synthetic scope): record the actual outcome.
        $targetFile = Join-Path $diagnostics 'client-target-probe.txt'
        $targetProcess = Start-Process -FilePath $alias -ArgumentList "--client-target-probe=$targetFile" -Wait -PassThru
        $summary.client_target_exit = $targetProcess.ExitCode
        $summary.client_target_probe = if (Test-Path $targetFile) { (Get-Content -Raw $targetFile).Trim() } else { 'NO_OUTPUT' }
        # Packaged-service identity is an experiment: record the actual outcome, never infer it.
        $probeDir = Join-Path $env:ProgramData 'UkkeraPOSTestProbe'
        New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
        & icacls.exe $probeDir /grant '*S-1-5-19:(OI)(CI)M' | Out-Null
        $summary.service_sid_type = (& sc.exe qsidtype UkkeraPOSTestMaster | Out-String).Trim()
        try {
            Start-Service -Name UkkeraPOSTestMaster
            $serviceFile = Join-Path $probeDir 'service-probe.txt'
            $deadline = (Get-Date).AddSeconds(90)
            while (-not (Test-Path $serviceFile) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
            $summary.service_probe = if (Test-Path $serviceFile) { (Get-Content -Raw $serviceFile).Trim() } else { 'NO_OUTPUT' }
            $summary.service_status = (Get-Service -Name UkkeraPOSTestMaster).Status.ToString()
            Stop-Service -Name UkkeraPOSTestMaster -ErrorAction Continue
        } catch {
            $summary.service_probe = "SERVICE_ERROR $($_.Exception.Message)"
        }
    } finally {
        $summary | ConvertTo-Json | Set-Content (Join-Path $diagnostics 'msix-summary.json')
        Get-AppxPackage -Name 'Ukkera.POS.Test' | Remove-AppxPackage -ErrorAction SilentlyContinue
    }
}
$summary | ConvertTo-Json | Set-Content (Join-Path $diagnostics 'msix-summary.json')
$summary | ConvertTo-Json
