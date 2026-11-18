# Native diagnostic only: reuse an already signed MSIX, no Gradle build or business data.
param([string]$ArtifactDirectory, [string]$Output, [switch]$InsidePackage)
$ErrorActionPreference = 'Stop'
if (-not $InsidePackage) {
    $cert = Join-Path (Split-Path (Split-Path (Split-Path $PSScriptRoot))) 'apps/pos/apps/desktop/packaging/windows/UkkeraPOS-Test-Signing.cer'
    if ((Get-FileHash $cert -Algorithm SHA256).Hash.ToLowerInvariant() -ne '998f39e61839e620384736fab82fec773e827314580fb69bfaccf66446026f6c') { throw 'Unexpected public certificate' }
    Import-Certificate -FilePath $cert -CertStoreLocation Cert:\LocalMachine\TrustedPeople | Out-Null
    $msix = @(Get-ChildItem -Path $ArtifactDirectory -Filter '*.msix' -Recurse)
    if ($msix.Count -ne 1) { throw 'One signed MSIX required' }
    Add-AppxPackage -Path $msix[0].FullName
    $probe = Join-Path $env:ProgramData 'UkkeraPOSTestProbe'
    New-Item -ItemType Directory -Force -Path $probe | Out-Null
    try {
        Stop-Service UkkeraPOSTestMaster -ErrorAction SilentlyContinue
        Start-Service UkkeraPOSTestMaster
        $limit = (Get-Date).AddSeconds(30)
        while (-not (Test-Path (Join-Path $probe 'service-probe.txt')) -and (Get-Date) -lt $limit) { Start-Sleep -Milliseconds 200 }
        $shell = (Get-Process -Id $PID).Path
        $script = $PSCommandPath
        Invoke-CommandInDesktopPackage -PackageFamilyName 'Ukkera.POS.Test_n9kkbga0qmnm6' -AppId 'UkkeraPOS' -Command $shell -Args "-NoProfile -ExecutionPolicy Bypass -File `"$script`" -InsidePackage -Output `"$Output`"" -PreventBreakaway
        $limit = (Get-Date).AddSeconds(40)
        while (-not (Test-Path $Output) -and (Get-Date) -lt $limit) { Start-Sleep -Milliseconds 200 }
        if (-not (Test-Path $Output)) { throw 'No native diagnostic result' }
        Get-Content -Raw $Output
    } finally {
        Stop-Service UkkeraPOSTestMaster -ErrorAction SilentlyContinue
        Get-AppxPackage -Name Ukkera.POS.Test | Remove-AppxPackage -ErrorAction SilentlyContinue
    }
    exit
}
try {
Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class PosConsoleQueryDiagnostic {
 [DllImport("kernel32.dll")] static extern IntPtr GetCurrentProcess();
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool CloseHandle(IntPtr h);
 [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr h);
 [DllImport("advapi32.dll",SetLastError=true)] static extern bool OpenProcessToken(IntPtr h,uint rights,out IntPtr token);
 [DllImport("advapi32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool ConvertStringSidToSidW(string sid,out IntPtr p);
 [DllImport("advapi32.dll",SetLastError=true)] static extern bool CreateRestrictedToken(IntPtr source,uint flags,uint disabledCount,IntPtr disabled,uint deletedCount,IntPtr deleted,uint restrictedCount,IntPtr restricted,out IntPtr result);
 [DllImport("advapi32.dll",SetLastError=true)] static extern bool DuplicateToken(IntPtr token,int level,out IntPtr copy);
 [DllImport("advapi32.dll",SetLastError=true)] static extern bool ImpersonateLoggedOnUser(IntPtr token);
 [DllImport("advapi32.dll",SetLastError=true)] static extern bool RevertToSelf();
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,ExactSpelling=true)] static extern int GetCurrentPackageFamilyName(ref uint length,StringBuilder text);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,ExactSpelling=true)] static extern int GetPackageFamilyName(IntPtr process,ref uint length,StringBuilder text);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,ExactSpelling=true)] static extern int GetPackageFamilyNameFromToken(IntPtr token,ref uint length,StringBuilder text);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr CreateFileW(string name,uint access,uint share,IntPtr security,uint disposition,uint flags,IntPtr template);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool WaitNamedPipeW(string name,uint wait);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool GetNamedPipeServerProcessId(IntPtr pipe,out uint pid);
 [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr OpenProcess(uint rights,bool inherit,uint pid);
 const string Family="Ukkera.POS.Test_n9kkbga0qmnm6";
 static void Check(bool ok) { if(!ok) throw new InvalidOperationException("NATIVE_"+Marshal.GetLastWin32Error()); }
 static string Observe() {
  if(!WaitNamedPipeW(@"\\.\pipe\UkkeraPOSTestMaster.setup",3000)) return "wait="+Marshal.GetLastWin32Error();
  IntPtr pipe=CreateFileW(@"\\.\pipe\UkkeraPOSTestMaster.setup",0x100083,0,IntPtr.Zero,3,0x110000,IntPtr.Zero);
  if(pipe==IntPtr.Zero || pipe==new IntPtr(-1)) return "pipe="+Marshal.GetLastWin32Error();
  try {
   uint pid; if(!GetNamedPipeServerProcessId(pipe,out pid)) return "pid="+Marshal.GetLastWin32Error();
   IntPtr process=OpenProcess(0x1000,false,pid); if(process==IntPtr.Zero) return "process="+Marshal.GetLastWin32Error();
   try {
    uint length=256; var data=new StringBuilder(256); int status=GetPackageFamilyName(process,ref length,data);
    string result="process=0 packageApi="+status+" packageMatch="+(status==0 && data.ToString()==Family);
    IntPtr token; if(!OpenProcessToken(process,8,out token)) return result+" token="+Marshal.GetLastWin32Error();
    try { length=256;data=new StringBuilder(256);status=GetPackageFamilyNameFromToken(token,ref length,data); return result+" token=0 tokenApi="+status+" tokenMatch="+(status==0 && data.ToString()==Family); }
    finally { CloseHandle(token); }
   } finally { CloseHandle(process); }
  } finally { CloseHandle(pipe); }
 }
 public static string Run() {
  uint length=256;var text=new StringBuilder(256);int current=GetCurrentPackageFamilyName(ref length,text);
  if(current!=0 || text.ToString()!=Family) return "context="+current+" matches=False";
  string control=Observe();IntPtr original=IntPtr.Zero,admin=IntPtr.Zero,disabled=IntPtr.Zero,restricted=IntPtr.Zero,copy=IntPtr.Zero;
  try {
   Check(OpenProcessToken(GetCurrentProcess(),10,out original));Check(ConvertStringSidToSidW("S-1-5-32-544",out admin));
   disabled=Marshal.AllocHGlobal(16);for(int i=0;i<16;i++) Marshal.WriteByte(disabled,i,0);Marshal.WriteIntPtr(disabled,admin);
   Check(CreateRestrictedToken(original,5,1,disabled,0,IntPtr.Zero,0,IntPtr.Zero,out restricted));Check(DuplicateToken(restricted,2,out copy));Check(ImpersonateLoggedOnUser(copy));
   string reduced; try { reduced=Observe(); } finally { Check(RevertToSelf()); }
   return "context=0 matches=True control=["+control+"] reducedImpersonationDiagnosticOnly=["+reduced+"]";
  } finally { if(copy!=IntPtr.Zero)CloseHandle(copy);if(restricted!=IntPtr.Zero)CloseHandle(restricted);if(disabled!=IntPtr.Zero)Marshal.FreeHGlobal(disabled);if(admin!=IntPtr.Zero)LocalFree(admin);if(original!=IntPtr.Zero)CloseHandle(original); }
 }
}
'@
    $result = [PosConsoleQueryDiagnostic]::Run()
} catch { $result = 'DIAGNOSTIC_FAILED ' + $_.Exception.GetType().Name }
$result | Set-Content $Output
