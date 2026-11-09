# Genuine HKCU/DPAPI qualification on the disposable hosted Windows account.
# Normalize only the owner of that account's existing LocalAppData directory for
# this run, then restore it. No DACL, production guard, registry data or test oracle changes.
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_REPOSITORY -ne 'kira-manga/test' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted') {
    throw 'This executor requires the authorized disposable GitHub-hosted runner'
}
$localFolder = [Environment]::GetFolderPath('LocalApplicationData')
if ([string]::IsNullOrWhiteSpace($localFolder)) { throw 'Hosted account has no genuine LocalAppData' }
$folder = Get-Item -LiteralPath $localFolder
if (-not $folder.PSIsContainer -or ($folder.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'Hosted LocalAppData must be an existing ordinary directory'
}
$currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
$originalAcl = Get-Acl -LiteralPath $localFolder
$originalOwner = $originalAcl.GetOwner([Security.Principal.SecurityIdentifier])
$originalDacl = $originalAcl.GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]::Access)
$ownerChanged = $false
try {
    if ($originalOwner.Value -ne $currentIdentity.User.Value) {
        # Hosted admin profiles may have Administrators as owner. The production
        # guard correctly refuses those profiles. Configure this disposable account
        # explicitly; do not relax the guard or rewrite/redirect a known folder.
        $ownerOnly = [Security.AccessControl.DirectorySecurity]::new()
        $ownerOnly.SetOwner($currentIdentity.User)
        Set-Acl -LiteralPath $localFolder -AclObject $ownerOnly
        $ownerChanged = $true
    }
    $configuredAcl = Get-Acl -LiteralPath $localFolder
    if ($configuredAcl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $currentIdentity.User.Value) {
        throw 'Disposable LocalAppData owner was not configured for the actual account'
    }
    if ($configuredAcl.GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]::Access) -cne $originalDacl) {
        throw 'LocalAppData DACL changed unexpectedly'
    }
    $isAdmin = ([Security.Principal.WindowsPrincipal]::new($currentIdentity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    @"
execution_context=disposable hosted current-user account
administrative_token=$isAdmin
local_app_data=existing genuine OS-known-folder path
fixture_setup=directory owner only, nonrecursive, restored after execution
owner_adjusted=$ownerChanged
dacl=unchanged
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
} catch {
    # A failed precheck is still retained even when Gradle never starts.
    $_.Exception.Message | Set-Content native-security-output\failure.txt
    throw
} finally {
    if ($ownerChanged) {
        $restoreOwner = [Security.AccessControl.DirectorySecurity]::new()
        $restoreOwner.SetOwner($originalOwner)
        Set-Acl -LiteralPath $localFolder -AclObject $restoreOwner
        $restoredAcl = Get-Acl -LiteralPath $localFolder
        if ($restoredAcl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $originalOwner.Value -or
            $restoredAcl.GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]::Access) -cne $originalDacl) {
            throw 'LocalAppData owner/DACL restoration did not match the original'
        }
    }
    'original directory owner and unchanged DACL verified' | Set-Content native-security-output\profile-restoration.txt
    # No raw store files are uploaded. Tests remove their exact synthetic values.
}
