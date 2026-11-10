# Disposable hosted-runner supporting diagnostic. Never installs a production service.
param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_REPOSITORY -ne 'kira-manga/test' -or $env:RUNNER_OS -ne 'Windows') {
    throw 'This diagnostic requires the explicitly authorized disposable Windows auxiliary runner.'
}
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'SCM diagnostic requires hosted-runner administrator.' }
$out = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force $out | Out-Null
$name = 'UkkeraPosSynthetic' + [Guid]::NewGuid().ToString('N')
$launcher = Join-Path $out 'service-launcher.exe'
$created = $false
try {
    $classpath = (Get-Content (Join-Path $out 'probe-classpath.txt') -Raw).Trim()
    if (-not $classpath -or $classpath.Contains('"')) { throw 'Invalid generated runtime classpath.' }
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
    if (-not (Test-Path $java)) { throw 'JDK 21 is unavailable.' }
    $shown = & sc.exe showsid $name 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Cannot derive synthetic SCM service SID.' }
    $sid = [regex]::Match(($shown -join "`n"), 'S-1-5-80-(?:\d+-){4}\d+').Value
    if (-not $sid) { throw 'Cannot read exact synthetic service SID.' }
    # Java is a child of the actual SCM service: its inherited token must pass the
    # genuine production service/account/impersonation observer before any key work.
    $argfile = Join-Path $out 'probe-java.args'
    $cp = $classpath.Replace('\', '/')
    $argText = '-Xmx256m -XX:ActiveProcessorCount=2 -cp "' + $cp + '" com.ukkera.pos.security.supporting.windowsservice.WindowsServiceSigningNativeProbe "' + $out.Replace('\', '/') + '" ' + $sid
    [IO.File]::WriteAllText($argfile, $argText, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllLines((Join-Path $out 'launcher-input.txt'), @($java, $argfile), [Text.UTF8Encoding]::new($false))
    $source = @'
using System;
using System.Diagnostics;
using System.IO;
using System.ServiceProcess;
using System.Threading;
public sealed class PosSyntheticService : ServiceBase {
    private Process child;
    public PosSyntheticService() { ServiceName = "__SERVICE_NAME__"; CanStop = true; AutoLog = false; }
    protected override void OnStart(string[] args) {
        string root = AppDomain.CurrentDomain.BaseDirectory;
        string[] input = File.ReadAllLines(Path.Combine(root, "launcher-input.txt"));
        child = new Process();
        child.StartInfo = new ProcessStartInfo(input[0], "@\"" + input[1] + "\"") {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = root
        };
        if (!child.Start()) throw new InvalidOperationException("Synthetic child launch failed");
        File.WriteAllText(Path.Combine(root, "child-pid.txt"), child.Id.ToString());
        new Thread(() => {
            try {
                var stdout = child.StandardOutput.ReadToEndAsync();
                var stderr = child.StandardError.ReadToEndAsync();
                child.WaitForExit();
                File.WriteAllText(Path.Combine(root, "child-stdout.txt"), stdout.Result);
                File.WriteAllText(Path.Combine(root, "child-stderr.txt"), stderr.Result);
                File.WriteAllText(Path.Combine(root, "child-exit.txt"), child.ExitCode.ToString());
            } catch (Exception e) {
                File.WriteAllText(Path.Combine(root, "launcher-failure.txt"), e.GetType().FullName);
            } finally { Stop(); }
        }) { IsBackground = true }.Start();
    }
    protected override void OnStop() {
        if (child != null && !child.HasExited) { child.Kill(); child.WaitForExit(10000); }
    }
    public static void Main() { ServiceBase.Run(new PosSyntheticService()); }
}
'@
    $source = $source.Replace('__SERVICE_NAME__', $name)
    $sourcePath = Join-Path $out 'service-launcher.cs'
    [IO.File]::WriteAllText($sourcePath, $source, [Text.UTF8Encoding]::new($false))
    $csc = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
    & $csc /nologo /target:exe "/out:$launcher" /reference:System.ServiceProcess.dll $sourcePath
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic service launcher compilation failed.' }
    & sc.exe create $name binPath= "`"$launcher`"" type= own start= demand obj= LocalSystem | Set-Content (Join-Path $out 'service-create.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic service creation failed.' }
    $created = $true
    & sc.exe sidtype $name unrestricted | Set-Content (Join-Path $out 'service-sidtype.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic service SID enable failed.' }
    [IO.File]::WriteAllText((Join-Path $out 'service-profile.txt'), "service=$name`nservice_sid=$sid`naccount_sid=S-1-5-18`n")
    Start-Service $name
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while (-not (Test-Path (Join-Path $out 'child-exit.txt'))) {
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Synthetic service child did not finish within 120 seconds.' }
        if (Test-Path (Join-Path $out 'launcher-failure.txt')) { throw 'Synthetic launcher failed.' }
        Start-Sleep -Milliseconds 500
    }
    if ((Get-Content (Join-Path $out 'child-exit.txt') -Raw).Trim() -ne '0') { throw 'Production native signing probe failed; inspect retained stages.' }
    if (-not ((Get-Content (Join-Path $out 'signing-result.txt') -Raw) -match '(?m)^status=PASS$')) { throw 'Missing positive native result.' }
} catch {
    [IO.File]::WriteAllText((Join-Path $out 'failure.txt'), $_.Exception.Message + "`n")
    throw
} finally {
    if ($created) {
        $service = Get-Service $name -ErrorAction SilentlyContinue
        if ($service -and $service.Status -ne 'Stopped') { Stop-Service $name -ErrorAction Continue }
        & sc.exe delete $name | Set-Content (Join-Path $out 'service-delete.txt')
        if ($LASTEXITCODE -ne 0) { throw 'Exact synthetic service removal failed.' }
    }
    # The classpath/launcher inputs are runner configuration, not portable evidence.
    foreach ($file in @('probe-classpath.txt', 'probe-java.args', 'launcher-input.txt', 'service-launcher.exe')) {
        Remove-Item -LiteralPath (Join-Path $out $file) -ErrorAction SilentlyContinue
    }
}
