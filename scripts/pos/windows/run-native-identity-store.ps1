# Genuine HKCU/DPAPI qualification on a disposable hosted Windows account.
# Only a fresh synthetic directory and the account's temporary known-folder mapping
# are configured. No production guard, registry value, or historical input changes.
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_REPOSITORY -ne 'kira-manga/test' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted') {
    throw 'This executor requires the authorized disposable GitHub-hosted runner'
}
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class PosNativeKnownFolder {
    [DllImport("shell32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    public static extern int SHSetKnownFolderPath(ref Guid folder, uint flags, IntPtr token, string path);
}
'@
$folderId = [Guid]'F1B32785-6FBA-4FCF-9D55-7B8E7F157091'
$originalFolder = [Environment]::GetFolderPath('LocalApplicationData')
if ([string]::IsNullOrWhiteSpace($originalFolder)) { throw 'Hosted account has no genuine LocalAppData' }
$fixtureRoot = Join-Path $originalFolder ('ukkera-pos-wdpapi-profile-' + $env:GITHUB_RUN_ID)
if (Test-Path $fixtureRoot) { throw 'Preserve an existing fixture; do not reuse its state' }
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
$currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
$security = [Security.AccessControl.DirectorySecurity]::new()
$security.SetOwner($currentIdentity.User)
$security.SetAccessRuleProtection($true, $false)
$security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
    $currentIdentity.User, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
Set-Acl -LiteralPath $fixtureRoot -AclObject $security
$originalEnvironmentFolder = $env:LOCALAPPDATA
$mapped = $false
try {
    $result = [PosNativeKnownFolder]::SHSetKnownFolderPath([ref]$folderId, 0, [IntPtr]::Zero, $fixtureRoot)
    if ($result -ne 0) { throw ('Temporary known-folder setup failed: 0x{0:X8}' -f $result) }
    $mapped = $true
    $env:LOCALAPPDATA = $fixtureRoot
    $isAdmin = ([Security.Principal.WindowsPrincipal]::new($currentIdentity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    @"
execution_context=disposable hosted current-user account
administrative_token=$isAdmin
local_app_data=OS-known-folder mapping to exact synthetic user-owned directory
owner_checks=production checks unchanged
scope=no ordinary-nonadministrator-profile or ACL/other-user qualification
"@ | Set-Content native-security-output\profile.txt
    Push-Location apps\pos
    try {
        & .\gradlew.bat --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m' -Pkotlin.compiler.execution.strategy=in-process :core:security:jvmTest --tests 'com.ukkera.pos.security.independent.windowsdpapi.WindowsDpapiSecureIdentityStoreNativeTest' --console=plain *> ..\..\native-security-output\gradle.txt
        $nativeStatus = $LASTEXITCODE
    } finally { Pop-Location }
    Get-Content native-security-output\gradle.txt -Tail 100
    if ($nativeStatus -ne 0) { throw "Native store tests exited $nativeStatus" }
} finally {
    $env:LOCALAPPDATA = $originalEnvironmentFolder
    if ($mapped) {
        $restore = [PosNativeKnownFolder]::SHSetKnownFolderPath([ref]$folderId, 0, [IntPtr]::Zero, $originalFolder)
        if ($restore -ne 0) { throw ('Known-folder restoration failed: 0x{0:X8}' -f $restore) }
        'original known-folder mapping restored' | Set-Content native-security-output\profile-restoration.txt
    }
    # Leave exact synthetic files on this disposable runner for diagnostics until
    # runner destruction. They are not uploaded. The test deletes its exact values.
}
