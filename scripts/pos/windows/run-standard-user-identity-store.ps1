# Run the unchanged native store cases under a fresh ordinary account on a
# disposable hosted Windows machine. No production known-folder/ACL guard changes.
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_REPOSITORY -ne 'kira-manga/test' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted') {
    throw 'This executor requires the authorized disposable GitHub-hosted runner'
}
$repository = (Get-Location).Path
$output = Join-Path $repository 'native-security-output'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$runKey = [Guid]::NewGuid().ToString('N')
$account = 'posdpapi' + $runKey.Substring(0, 8)
$workRoot = Join-Path $env:SystemDrive ('pos-standard-' + $runKey)
if (Test-Path -LiteralPath $workRoot) { throw 'Disposable source root already exists' }
$createdAccount = $false
$createdRoot = $false
$process = $null
$sid = $null
try {
    # This secret remains in process memory and is never passed on a command line,
    # written to disk, echoed or included in uploaded diagnostics.
    $password = ConvertTo-SecureString ([Guid]::NewGuid().ToString('N') + 'aA1!') -AsPlainText -Force
    $user = New-LocalUser -Name $account -Password $password -AccountNeverExpires -PasswordNeverExpires
    $createdAccount = $true
    $sid = $user.SID.Value
    Add-LocalGroupMember -SID 'S-1-5-32-545' -Member $user
    New-Item -ItemType Directory -Path $workRoot | Out-Null
    $createdRoot = $true
    # Only this newly created source/output directory receives a test-account grant.
    # OS-known folders and their owners/DACLs remain unchanged.
    & icacls.exe $workRoot '/inheritance:r' '/grant:r' ('*' + $sid + ':(OI)(CI)F') '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Disposable source-root ACL setup failed' }
    $archive = Join-Path $workRoot 'source.zip'
    & git archive --format=zip ('--output=' + $archive) HEAD apps/pos scripts/pos SOURCE-REVISION.txt
    if ($LASTEXITCODE -ne 0) { throw 'Tracked-only source export failed' }
    Expand-Archive -LiteralPath $archive -DestinationPath $workRoot
    Remove-Item -LiteralPath $archive
    # Pass only public toolchain locations to the new logon's native environment.
    [ordered]@{ java_home=$env:JAVA_HOME; android_home=$env:ANDROID_HOME; android_sdk_root=$env:ANDROID_SDK_ROOT } |
        ConvertTo-Json | Set-Content (Join-Path $workRoot 'toolchain.json')
    $child = Join-Path $workRoot 'run-frozen-store.ps1'
    @'
param([Parameter(Mandatory=$true)][string]$ExpectedSid)
$ErrorActionPreference = 'Stop'
$out = Join-Path $PSScriptRoot 'native-result'
New-Item -ItemType Directory -Path $out -Force | Out-Null
try {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    if ($identity.User.Value -ne $ExpectedSid) { throw 'Child did not enter the exact synthetic account' }
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Native profile qualification requires a nonadministrator token'
    }
    # Start-Process can inherit the administrator's environment even with a
    # loaded ordinary profile. Resolve this token's real OS profile first; never
    # redirect a known folder or reuse the parent's LOCALAPPDATA value.
    $nativeTemp = Join-Path $PSScriptRoot 'native-temp'
    New-Item -ItemType Directory -Path $nativeTemp -Force | Out-Null
    $env:TEMP = $nativeTemp
    $env:TMP = $nativeTemp
    Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class PosOrdinaryProfile {
    [DllImport("userenv.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool GetUserProfileDirectoryW(IntPtr token, StringBuilder path, ref uint size);
    [DllImport("shell32.dll", ExactSpelling = true)]
    private static extern int SHGetKnownFolderPath(ref Guid folder, uint flags, IntPtr token, out IntPtr path);
    [DllImport("ole32.dll", ExactSpelling = true)]
    private static extern void CoTaskMemFree(IntPtr path);
    public static string Profile(IntPtr token) {
        var path = new StringBuilder(32768);
        uint size = (uint)path.Capacity;
        if (!GetUserProfileDirectoryW(token, path, ref size))
            throw new InvalidOperationException("OS profile lookup failed: " + Marshal.GetLastWin32Error());
        return path.ToString();
    }
    public static string Known(string id) {
        var folder = new Guid(id);
        IntPtr path;
        // The child already proved its exact ordinary identity. NULL requests the
        // current process profile without cross-token impersonation privileges.
        int status = SHGetKnownFolderPath(ref folder, 0, IntPtr.Zero, out path);
        try {
            if (status < 0 || path == IntPtr.Zero)
                throw new InvalidOperationException("OS known-folder lookup failed: " + status);
            return Marshal.PtrToStringUni(path);
        } finally { if (path != IntPtr.Zero) CoTaskMemFree(path); }
    }
}
"@
    $profile = [PosOrdinaryProfile]::Profile($identity.Token)
    $env:USERPROFILE = $profile
    $local = [PosOrdinaryProfile]::Known('F1B32785-6FBA-4FCF-9D55-7B8E7F157091')
    $env:LOCALAPPDATA = $local
    $env:APPDATA = [PosOrdinaryProfile]::Known('3EB685DB-65F9-4CF6-A03A-E3EF65729F3D')
    if ([string]::IsNullOrWhiteSpace($profile) -or [string]::IsNullOrWhiteSpace($local)) {
        throw 'Ordinary account OS-known folders are unavailable'
    }
    $localItem = Get-Item -LiteralPath $local
    if (-not $localItem.PSIsContainer -or ($localItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Ordinary account LocalAppData must already be an ordinary directory'
    }
    if ((Get-Acl -LiteralPath $local).GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $ExpectedSid) {
        throw 'Ordinary account LocalAppData owner does not match; no owner normalization is permitted'
    }
    [ordered]@{
        context='fresh synthetic local standard-user account'
        administrative_token=$false
        local_app_data='existing genuine OS-known-folder path'
        local_app_data_owner_adjusted=$false
        local_app_data_owner_matches=$true
        production_owner_and_dacl_checks='unchanged'
        credentials_logged=$false
    } | ConvertTo-Json | Set-Content (Join-Path $out 'profile.json')
    $toolchain = Get-Content (Join-Path $PSScriptRoot 'toolchain.json') -Raw | ConvertFrom-Json
    $env:JAVA_HOME = $toolchain.java_home
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
    $env:ANDROID_HOME = $toolchain.android_home
    $env:ANDROID_SDK_ROOT = $toolchain.android_sdk_root
    $env:GRADLE_USER_HOME = Join-Path $profile '.gradle'
    Set-Location (Join-Path $PSScriptRoot 'apps/pos')
    & .\gradlew.bat --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m' '-Pkotlin.compiler.execution.strategy=in-process' :core:security:jvmTest --tests 'com.ukkera.pos.security.independent.windowsdpapi.WindowsDpapiSecureIdentityStoreNativeTest' --console=plain *> (Join-Path $out 'gradle.txt')
    $testStatus = $LASTEXITCODE
    if ($testStatus -ne 0) { throw "Native store tests exited $testStatus" }
    exit 0
} catch {
    $_.Exception.Message | Set-Content (Join-Path $out 'failure.txt')
    exit 1
}
'@ | Set-Content -LiteralPath $child
    if ((Get-Service -Name seclogon).Status -ne 'Running') { Start-Service -Name seclogon }
    # NULL environment plus LOGON_WITH_PROFILE constructs the new user's native
    # environment/profile. Do not inherit the hosted administrator's shell paths.
    Add-Type -TypeDefinition @"
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security;
using System.Text;
public static class PosOrdinaryLogon {
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
    private struct StartupInfo {
        public int cb;
        public string reserved, desktop, title;
        public int x, y, xSize, ySize, xCountChars, yCountChars, fillAttribute, flags;
        public short showWindow, reserved2Size;
        public IntPtr reserved2, stdin, stdout, stderr;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessInfo { public IntPtr process, thread; public uint processId, threadId; }
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CreateProcessWithLogonW(string user, string domain, IntPtr password,
        uint logonFlags, string app, StringBuilder command, uint creationFlags,
        IntPtr environment, string directory, ref StartupInfo startup, out ProcessInfo process);
    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);
    private static string Quote(string text) {
        if (text.Contains("\"") || text.EndsWith("\\"))
            throw new InvalidOperationException("Unsupported native command argument");
        return "\"" + text + "\"";
    }
    public static Process Launch(string user, string domain, SecureString password,
        string executable, string script, string sid, string directory) {
        var startup = new StartupInfo();
        startup.cb = Marshal.SizeOf(typeof(StartupInfo));
        var info = new ProcessInfo();
        IntPtr secret = Marshal.SecureStringToGlobalAllocUnicode(password);
        try {
            var command = new StringBuilder(Quote(executable) +
                " -NoLogo -NoProfile -NonInteractive -File " + Quote(script) + " -ExpectedSid " + Quote(sid));
            if (!CreateProcessWithLogonW(user, domain, secret, 1, executable,
                command, 0, IntPtr.Zero, directory, ref startup, out info))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "Ordinary native logon failed");
            var process = Process.GetProcessById((int)info.processId);
            var handle = process.Handle;
            return process;
        } finally {
            Marshal.ZeroFreeGlobalAllocUnicode(secret);
            if (info.thread != IntPtr.Zero) CloseHandle(info.thread);
            if (info.process != IntPtr.Zero) CloseHandle(info.process);
        }
    }
}
"@
    $hostExecutable = (Get-Process -Id $PID).Path
    $process = [PosOrdinaryLogon]::Launch($account, $env:COMPUTERNAME, $password, $hostExecutable, $child, $sid, $workRoot)
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Ordinary-account native execution exited $($process.ExitCode)" }
} catch {
    $_.Exception.Message | Set-Content (Join-Path $output 'failure.txt')
    throw
} finally {
    if ($createdRoot) {
        $nativeOutput = Join-Path $workRoot 'native-result'
        if (Test-Path -LiteralPath $nativeOutput) {
            Copy-Item -LiteralPath $nativeOutput -Destination (Join-Path $output 'ordinary-account') -Recurse
        }
        foreach ($report in @('test-results/jvmTest', 'reports/tests/jvmTest')) {
            $source = Join-Path $workRoot ('apps/pos/core/security/build/' + $report)
            if (Test-Path -LiteralPath $source) {
                $destination = Join-Path $repository ('apps/pos/core/security/build/' + $report)
                New-Item -ItemType Directory -Path $destination -Force | Out-Null
                Copy-Item -Path (Join-Path $source '*') -Destination $destination -Recurse -Force
            }
        }
    }
    if ($createdAccount -and ($null -eq $process -or $process.HasExited)) {
        # Only the exact fresh synthetic account/SID can be removed. No enumeration
        # of registry records or deletion of any existing owner profile is used.
        $current = Get-LocalUser -Name $account
        if ($current.SID.Value -ne $sid -or $account -notmatch '^posdpapi[0-9a-f]{8}$') {
            throw 'Synthetic-account cleanup identity mismatch'
        }
        $profile = Get-CimInstance Win32_UserProfile -Filter ("SID = '" + $sid + "'")
        if ($null -ne $profile -and $profile.Loaded) {
            'Synthetic profile still loaded; account and source root retained for disposable runner teardown. No process interrupted.' | Set-Content (Join-Path $output 'ordinary-account-cleanup.txt')
        } else {
            if ($null -ne $profile) { $profile | Remove-CimInstance }
            Remove-LocalUser -Name $account
            'Exact synthetic standard account/profile removed after child completion.' | Set-Content (Join-Path $output 'ordinary-account-cleanup.txt')
            if ($createdRoot) { Remove-Item -LiteralPath $workRoot -Recurse -Force }
        }
    }
}
