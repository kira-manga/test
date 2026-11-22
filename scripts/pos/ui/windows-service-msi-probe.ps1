# Isolated hosted control. The real installer is built and exercised locally
# on the runner. Only fixed verification facts leave the runner.
param([Parameter(Mandatory)][string]$Package, [Parameter(Mandatory)][string]$Certificate)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$work = Join-Path $env:RUNNER_TEMP 'pos-ui'
$fixture = Join-Path $env:GITHUB_WORKSPACE 'scripts/pos/ui'
$reportPath = Join-Path $work 'public/windows-service-msi.json'
$report = [ordered]@{
    source_revision = (Get-Content -Raw (Join-Path $work 'pos-revision')).Trim()
    packaging_control = 'WiX MSI hosting a separately Windows-authenticated retained MSIX payload'
    phase = 'preflight'
    installed = $false
    medium_integrity_console = $false
    removed = $false
}
$msi = Join-Path $work 'UkkeraPOS-Test-Service.msi'
$pfx = Join-Path $work 'pos-test-signing.pfx'
$name = 'Ukkera.POS.Test'
$service = 'UkkeraPOSTestMaster'
$family = 'Ukkera.POS.Test_n9kkbga0qmnm6'
$nativeDir = Join-Path $env:ProgramData 'UkkeraPOSTestProbe'
$trustAdded = $null

function Invoke-Msi([string]$operation, [string]$label) {
    $log = Join-Path $work "msi-$label.log"
    $child = Start-Process -FilePath 'msiexec.exe' -ArgumentList "$operation `"$msi`" /qn /norestart /l*v `"$log`"" -PassThru
    if (!$child.WaitForExit(150000)) { throw "Windows Installer did not settle the $label control" }
    $report["${label}_exit"] = $child.ExitCode
    if ($child.ExitCode -ne 0) {
        if (Test-Path -LiteralPath $log) {
            $report["${label}_terminal"] = @(Get-Content -LiteralPath $log |
                Select-String -AllMatches '0x[0-9A-Fa-f]{8}|POS_SERVICE_PACKAGE_(?:UN)?REGISTERED|Return value 3' |
                ForEach-Object { $_.Matches.Value } | Sort-Object -Unique)
        }
        throw "Windows Installer refused the $label control"
    }
}

try {
    $runner = Get-CimInstance Win32_OperatingSystem
    $report.available_memory_bytes = $runner.FreePhysicalMemory * 1024
    $report.available_disk_bytes = (Get-PSDrive -Name $env:SystemDrive.TrimEnd(':')).Free
    if ($report.available_memory_bytes -lt 7GB -or $report.available_disk_bytes -lt 6GB) { throw 'Resource admission refused' }
    if (Get-AppxPackage -AllUsers -Name $name) { throw 'Unexpected original test package' }
    if ((Get-Service $service -ErrorAction SilentlyContinue) -or (Test-Path -LiteralPath $nativeDir)) {
        throw 'Unexpected original service or native probe state'
    }
    if ((Get-FileHash -LiteralPath $Certificate -Algorithm SHA256).Hash -ne '998f39e61839e620384736fab82fec773e827314580fb69bfaccf66446026f6c') {
        throw 'The public signing identity is not the existing test certificate'
    }
    if ([string]::IsNullOrEmpty($env:PFX_BASE64) -or [string]::IsNullOrEmpty($env:POS_MSIX_PFX_PASSWORD)) {
        throw 'The existing test signing configuration is unavailable'
    }

    $report.phase = 'build_and_sign_msi'
    $wix = Join-Path $env:WIX 'bin'
    $sdkRoot = 'C:\Program Files (x86)\Windows Kits\10\bin'
    $sdk = Get-ChildItem $sdkRoot -Directory | Where-Object { $_.Name -match '^10\.' } |
        Sort-Object { [version]$_.Name } -Descending | ForEach-Object { Join-Path $_.FullName 'x64' } |
        Where-Object { Test-Path (Join-Path $_ 'signtool.exe') } | Select-Object -First 1
    $publicCert = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
        (Resolve-Path -LiteralPath $Certificate).Path)
    [IO.File]::WriteAllBytes($pfx,[Convert]::FromBase64String($env:PFX_BASE64))
    $secret = ConvertTo-SecureString $env:POS_MSIX_PFX_PASSWORD -AsPlainText -Force
    $privateCert = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
        $pfx, $secret, [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
    if ($privateCert.Thumbprint -ne $publicCert.Thumbprint) { throw 'The private signer does not match the existing public identity' }
    if (!(Test-Path -LiteralPath ("Cert:\LocalMachine\TrustedPeople\" + $publicCert.Thumbprint))) {
        $trustAdded = Import-Certificate -FilePath $Certificate -CertStoreLocation Cert:\LocalMachine\TrustedPeople
    }
    $report.phase = 'sign_lifecycle'
    $lifecycle = Join-Path $work 'PackageLifecycle.ps1'
    Copy-Item -LiteralPath (Join-Path $fixture 'windows-service-package-lifecycle.ps1') -Destination $lifecycle
    $authenticode = Set-AuthenticodeSignature -LiteralPath $lifecycle -Certificate $privateCert -HashAlgorithm SHA256
    if ($authenticode.Status -ne 'Valid') { throw 'The copied MSI lifecycle script could not be signed by the existing test signer' }
    $report.phase = 'compile_msi'
    $object = Join-Path $work 'service.wixobj'
    & (Join-Path $wix 'candle.exe') -nologo -arch x64 "-dPackage=$Package" "-dLifecycle=$lifecycle" -out $object (Join-Path $fixture 'windows-service-msi.wxs')
    if ($LASTEXITCODE -ne 0) { throw 'WiX could not compile the service-aware MSI definition' }
    & (Join-Path $wix 'light.exe') -nologo -ext WixUtilExtension -out $msi $object
    if ($LASTEXITCODE -ne 0) { throw 'WiX could not link the service-aware MSI definition' }
    $report.phase = 'sign_msi'
    & (Join-Path $sdk 'signtool.exe') sign /fd SHA256 /f $pfx /p $env:POS_MSIX_PFX_PASSWORD $msi *> (Join-Path $work 'msi-sign.log')
    if ($LASTEXITCODE -ne 0) { throw 'The MSI could not be signed by the established test signer' }
    $report.msi_sha256 = (Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash.ToLowerInvariant()
    $report.signed_payload_sha256 = (Get-FileHash -LiteralPath $Package -Algorithm SHA256).Hash.ToLowerInvariant()

    New-Item -ItemType Directory -Path $nativeDir | Out-Null
    & icacls.exe $nativeDir /grant '*S-1-5-19:(OI)(CI)M' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The bounded probe directory was unavailable' }
    $report.phase = 'install_msi'
    Invoke-Msi '/i' 'install'
    $actual = Get-AppxPackage -Name $name
    $report.installed = $actual.PackageFamilyName -eq $family
    if (!$report.installed) { throw 'MSI install returned without a registered application identity' }

    $report.phase = 'observe_console'
    $role = Get-CimInstance Win32_Service -Filter "Name='$service'"
    $report.application_family = $actual.PackageFamilyName
    $report.master_service = (Get-Service -Name $service).Status.ToString()
    if ($role.StartName -ne 'NT AUTHORITY\LocalService') { throw 'Master service account differs from installed contract' }
    $alias = Join-Path $env:LOCALAPPDATA 'Microsoft/WindowsApps/ukkera-pos-test.exe'
    $probe = Join-Path $work 'msi-master-console-probe.txt'
    $client = Start-Process -FilePath $alias -ArgumentList "--master-console-probe=`"$probe`"" -PassThru
    if (!$client.WaitForExit(75000)) { throw 'The actual ordinary-role native console probe did not return' }
    $reported = (Get-Content -LiteralPath $probe -Raw).Trim()
    $expected = 'DATA platform=windows console=true ordinaryToken=true primaryToken=true integrity=MEDIUM packageClaim=true foreignClaimDenied=true pipeInstanceDenied=true aclMutationDenied=true processQuery=true processControlDenied=true tokenQuery=true tokenControlDenied=true'
    $report.medium_integrity_console = $client.ExitCode -eq 0 -and $reported -eq $expected
    if (!$report.medium_integrity_console) { throw 'The actual installed console did not establish the expected native claims' }
    $report.phase = 'remove_msi'
    Invoke-Msi '/x' 'remove'
    $report.removed = $null -eq (Get-AppxPackage -Name $name) -and
        $null -eq (Get-Service $service -ErrorAction SilentlyContinue)
    if (!$report.removed) { throw 'The actual package or service remained after MSI uninstall' }
    $report.phase = 'concluded'
} catch {
    $report.exception_type = $_.Exception.GetType().FullName
    $report.native_error_codes = @([regex]::Matches($_.Exception.Message, '0x[0-9A-Fa-f]{8}') | ForEach-Object { $_.Value })
    throw "Service-aware Windows MSI control stopped at $($report.phase)"
} finally {
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportPath
    Remove-Item -LiteralPath $pfx -Force -ErrorAction SilentlyContinue
    if ($null -ne $trustAdded -and ($report.removed -or !$report.installed)) {
        Remove-Item -LiteralPath ("Cert:\LocalMachine\TrustedPeople\" + $trustAdded.Thumbprint) -ErrorAction SilentlyContinue
    }
}
