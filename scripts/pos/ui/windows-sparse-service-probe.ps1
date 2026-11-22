# Receive only the bound test MSIX selected by the workflow. Work on a copy
# of its external application files on a fresh, disposable Windows runner.
param([Parameter(Mandatory)][string]$Package, [Parameter(Mandatory)][string]$Certificate)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$work = Join-Path $env:RUNNER_TEMP 'pos-ui'
$public = Join-Path $work 'public'
$name = 'Ukkera.POS.Test'
$service = 'UkkeraPOSTestMaster'
$family = 'Ukkera.POS.Test_n9kkbga0qmnm6'
$writeReport = Join-Path $public 'windows-sparse-service.json'
$report = [ordered]@{
    source_revision = (Get-Content -Raw (Join-Path $work 'pos-revision')).Trim()
    fixture = 'signed-retained-windows-msix-app-image'
    operator_msi_installed = $false
    actual_ui_package_identity = $false
    actual_service_registered = $false
    ordinary_token_console_connected = $false
    phase = 'preflight'
}
$kit = 'C:\Program Files (x86)\Windows Kits\10\bin'
$sdk = Get-ChildItem $kit -Directory | Where-Object { $_.Name -match '^10\.' } |
    Sort-Object { [version]$_.Name } -Descending |
    ForEach-Object { Join-Path $_.FullName 'x64' } |
    Where-Object { (Test-Path (Join-Path $_ 'makeappx.exe')) -and (Test-Path (Join-Path $_ 'mt.exe')) } |
    Select-Object -First 1
if (!$sdk) { throw 'The hosted Windows SDK has no required manifest tools' }

$hostOs = Get-CimInstance Win32_OperatingSystem
$hostDisk = Get-PSDrive -Name $env:SystemDrive.TrimEnd(':')
if ($hostOs.FreePhysicalMemory * 1024 -lt 7GB -or $hostDisk.Free -lt 6GB) {
    throw 'The hosted Windows runner did not meet the resource admission floor'
}
if (Get-AppxPackage -AllUsers -Name $name) { throw 'The test package is already present on this host' }
if (Get-Service -Name $service -ErrorAction SilentlyContinue) { throw 'The test Master service is already present on this host' }
if ([string]::IsNullOrEmpty($env:PFX_BASE64) -or [string]::IsNullOrEmpty($env:POS_MSIX_PFX_PASSWORD)) {
    throw 'The authorized testing repository lacks its existing Windows test signing configuration'
}
$bits = Join-Path $env:ProgramFiles ("POS Sparse Control " + [guid]::NewGuid().ToString('N'))
$meta = Join-Path $work 'external-identity'
$pfx = Join-Path $work 'test-signing.pfx'
$identity = Join-Path $work 'sparse-identity.msix'
$application = Join-Path $bits 'UkkeraPOS.exe'
$probeDir = Join-Path $env:ProgramData 'UkkeraPOSTestProbe'
if (Test-Path $probeDir) { throw 'The native test diagnostic location is already occupied' }
$trust = $null
try {
    $report.phase = 'unpack_external_application'
    & (Join-Path $sdk 'makeappx.exe') unpack /p $Package /d $bits /o *> (Join-Path $work 'unpack.log')
    if ($LASTEXITCODE -ne 0 -or !(Test-Path $application)) { throw 'The retained MSIX cannot be unpacked' }
    $report.phase = 'build_external_manifests'
    $metadata = Join-Path $bits 'AppxManifest.xml'
    $manifest = (Get-Content $metadata -Raw).Replace(
        'xmlns:rescap=', 'xmlns:uap10="http://schemas.microsoft.com/appx/manifest/uap/windows10/10" xmlns:rescap='
    ).Replace('IgnorableNamespaces="','IgnorableNamespaces="uap10 '
    ).Replace('</Properties>', '<uap10:AllowExternalContent>true</uap10:AllowExternalContent></Properties>'
    ).Replace('<rescap:Capability Name="runFullTrust"/>',
        '<rescap:Capability Name="runFullTrust"/><rescap:Capability Name="unvirtualizedResources"/>'
    ).Replace('Id="UkkeraPOS" Executable="UkkeraPOS.exe" EntryPoint="Windows.FullTrustApplication"',
        'Id="UkkeraPOS" Executable="UkkeraPOS.exe" uap10:TrustLevel="mediumIL" uap10:RuntimeBehavior="win32App"')
    if ($manifest -notmatch 'desktop6:Service Name="UkkeraPOSTestMaster"' -or
        $manifest -notmatch 'uap10:AllowExternalContent>true') { throw 'The retained native service declaration is missing' }
    New-Item -ItemType Directory -Path $meta | Out-Null
    [IO.File]::WriteAllText((Join-Path $meta 'AppxManifest.xml'), $manifest)
    Copy-Item -Recurse -LiteralPath (Join-Path $bits 'Assets') -Destination $meta

    $launcherManifest = Join-Path $work 'launcher.manifest'
    $manifestTool = Join-Path $sdk 'mt.exe'
    & $manifestTool -nologo "-inputresource:$application;#1" "-out:$launcherManifest" *> (Join-Path $work 'mt-read.log')
    if ($LASTEXITCODE -ne 0) { throw 'The retained launcher manifest could not be read' }
    [xml]$launcher = Get-Content -LiteralPath $launcherManifest -Raw
    if ($launcher.DocumentElement.LocalName -ne 'assembly') { throw 'Unexpected Win32 app manifest root' }
    if ($launcher.DocumentElement.SelectSingleNode("*[local-name()='msix']")) { throw 'The control unexpectedly already declares external package identity' }
    $binding = $launcher.CreateElement('msix', 'urn:schemas-microsoft-com:msix.v1')
    $binding.SetAttribute('publisher', 'CN=Ukkera POS Test Signing')
    $binding.SetAttribute('packageName', $name)
    $binding.SetAttribute('applicationId', 'UkkeraPOS')
    $null = $launcher.DocumentElement.AppendChild($binding)
    $launcher.Save($launcherManifest)
    & $manifestTool -nologo -manifest $launcherManifest "-outputresource:$application;#1" *> (Join-Path $work 'mt-write.log')
    if ($LASTEXITCODE -ne 0) { throw 'The wrapped external launcher manifest was refused' }

    $report.phase = 'pack_and_sign'
    & (Join-Path $sdk 'makeappx.exe') pack /d $meta /p $identity /o *> (Join-Path $work 'sparse-pack.log')
    if ($LASTEXITCODE -ne 0) { throw 'MakeAppx refused this external identity and service manifest' }
    if ((Get-FileHash -LiteralPath $Certificate -Algorithm SHA256).Hash -ne '998f39e61839e620384736fab82fec773e827314580fb69bfaccf66446026f6c') {
        throw 'The test certificate differs from the authorized POS signer'
    }
    [IO.File]::WriteAllBytes($pfx,[Convert]::FromBase64String($env:PFX_BASE64))
    $tool = Join-Path $sdk 'signtool.exe'
    foreach ($signed in @($application,$identity)) {
        & $tool sign /fd SHA256 /f $pfx /p $env:POS_MSIX_PFX_PASSWORD $signed *> (Join-Path $work 'sparse-sign.log')
        if ($LASTEXITCODE -ne 0) { throw 'The existing Windows test signer refused this probe package' }
    }
    $publicCert = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
        (Resolve-Path -LiteralPath $Certificate).Path)
    if (!(Test-Path -LiteralPath ("Cert:\LocalMachine\TrustedPeople\" + $publicCert.Thumbprint))) {
        $trust = Import-Certificate -FilePath $Certificate -CertStoreLocation Cert:\LocalMachine\TrustedPeople
    }
    New-Item -ItemType Directory -Path $probeDir | Out-Null
    & icacls.exe $probeDir /grant '*S-1-5-19:(OI)(CI)M' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The isolated service diagnostic directory could not be prepared' }
    $report.phase = 'register_external_package'
    Add-AppxPackage -Path $identity -ExternalLocation $bits
    $actual = Get-AppxPackage -Name $name
    if ($actual.PackageFamilyName -ne $family) { throw 'Registered external identity has an unexpected family' }

    $report.phase = 'measure_native_ui_identity'
    $path = Join-Path $work 'sparse-ui-probe.txt'
    $ui = Start-Process -FilePath $application -ArgumentList "--identity-probe=`"$path`"" -PassThru
    if (!$ui.WaitForExit(40000)) { throw 'Actual external application identity probe did not exit' }
    $report.actual_ui_package_identity = $ui.ExitCode -eq 0 -and
        (Get-Content -Raw -LiteralPath $path).StartsWith("DATA pfn=$family ")
    if (!$report.actual_ui_package_identity) { throw 'Actual external launcher identity was refused' }

    $report.phase = 'measure_packaged_service'
    $scm = Get-CimInstance Win32_Service -Filter "Name='$service'"
    $report.actual_service_registered = $null -ne $scm
    if (!$report.actual_service_registered -or $scm.StartName -ne 'NT AUTHORITY\LocalService') {
        throw 'External package did not register the required actual packaged LocalService service'
    }
    Start-Service $service
    $probe = Join-Path $work 'sparse-master-console-probe.txt'
    $report.phase = 'measure_ordinary_console'
    $client = Start-Process -FilePath $application -ArgumentList "--master-console-probe=`"$probe`"" -PassThru
    if (!$client.WaitForExit(75000)) { throw 'Actual ordinary console probe did not exit' }
    $expected = 'DATA platform=windows console=true ordinaryToken=true primaryToken=true integrity=MEDIUM packageClaim=true foreignClaimDenied=true pipeInstanceDenied=true aclMutationDenied=true processQuery=true processControlDenied=true tokenQuery=true tokenControlDenied=true'
    $report.ordinary_token_console_connected = $client.ExitCode -eq 0 -and (Get-Content -Raw -LiteralPath $probe).Trim() -eq $expected
    if (!$report.ordinary_token_console_connected) { throw 'External app did not complete the ordinary-token console handshake' }
    $report.phase = 'concluded'
} catch {
    $codes = [regex]::Matches($_.Exception.Message, '0x[0-9a-fA-F]{8}') | ForEach-Object { $_.Value }
    $report.native_error_codes = @($codes)
    $report.exception_type = $_.Exception.GetType().FullName
    throw "Windows external identity control stopped at $($report.phase)"
} finally {
    try {
        if (Get-AppxPackage -Name $name) {
            Stop-Service $service -ErrorAction SilentlyContinue
            Get-AppxPackage -Name $name | Remove-AppxPackage -ErrorAction Stop
        }
    } finally {
        Remove-Item -LiteralPath $pfx -Force -ErrorAction SilentlyContinue
        if ($null -ne $trust) { Remove-Item -LiteralPath ("Cert:\LocalMachine\TrustedPeople\" + $trust.Thumbprint) -ErrorAction SilentlyContinue }
        $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $writeReport
    }
}
