[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'This diagnostic requires Windows PowerShell Core.' }
if (Test-Path (Join-Path $OutputDirectory 'capabilities.json')) { throw 'Preserve the existing diagnostic output.' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

# Read-only capability queries. No key enumeration, creation, import/export or private operation.
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class PosCngCapabilityNative {
    [StructLayout(LayoutKind.Sequential)]
    public struct AlgorithmName {
        public IntPtr Name;
        public UInt32 Class;
        public UInt32 Operations;
        public UInt32 Flags;
    }
    [DllImport("ncrypt.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    public static extern int NCryptOpenStorageProvider(out IntPtr provider, string name, UInt32 flags);
    [DllImport("ncrypt.dll", ExactSpelling = true)]
    public static extern int NCryptEnumAlgorithms(IntPtr provider, UInt32 operations, out UInt32 count, out IntPtr names, UInt32 flags);
    [DllImport("ncrypt.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    public static extern int NCryptIsAlgSupported(IntPtr provider, string algorithm, UInt32 flags);
    [DllImport("ncrypt.dll", ExactSpelling = true)]
    public static extern int NCryptFreeBuffer(IntPtr buffer);
    [DllImport("ncrypt.dll", ExactSpelling = true)]
    public static extern int NCryptFreeObject(IntPtr handle);
    public static string Status(int value) { return "0x" + unchecked((UInt32)value).ToString("x8"); }
}
'@

$providers = [Collections.Generic.List[object]]::new()
foreach ($name in @('Microsoft Software Key Storage Provider', 'Microsoft Platform Crypto Provider')) {
    $provider = [IntPtr]::Zero
    $buffer = [IntPtr]::Zero
    $record = [ordered]@{ provider = $name; open_status = $null; enumeration_status = $null;
        algorithms = @(); advertised_support = @(); buffer_free_status = $null; provider_free_status = $null }
    try {
        $status = [PosCngCapabilityNative]::NCryptOpenStorageProvider([ref]$provider, $name, 0)
        $record.open_status = [PosCngCapabilityNative]::Status($status)
        if ($status -ne 0) { continue }
        if ($provider -eq [IntPtr]::Zero) { throw 'Successful provider open returned a null handle.' }
        $count = [uint32]0
        $status = [PosCngCapabilityNative]::NCryptEnumAlgorithms($provider, 0, [ref]$count, [ref]$buffer, 0x40)
        $record.enumeration_status = [PosCngCapabilityNative]::Status($status)
        if ($status -eq 0) {
            if ($count -gt 4096 -or ($count -gt 0 -and $buffer -eq [IntPtr]::Zero)) { throw 'Invalid algorithm enumeration bounds.' }
            $size = [Runtime.InteropServices.Marshal]::SizeOf([type][PosCngCapabilityNative+AlgorithmName])
            $algorithms = [Collections.Generic.List[object]]::new()
            for ($index = 0; $index -lt $count; $index++) {
                $entry = [Runtime.InteropServices.Marshal]::PtrToStructure(
                    [IntPtr]::Add($buffer, $index * $size), [type][PosCngCapabilityNative+AlgorithmName])
                if ($entry.Name -eq [IntPtr]::Zero) { throw 'Invalid algorithm name pointer.' }
                $algorithm = [Runtime.InteropServices.Marshal]::PtrToStringUni($entry.Name)
                if ($algorithm.Length -gt 256) { throw 'Invalid algorithm name length.' }
                $algorithms.Add([ordered]@{ name = $algorithm; algorithm_class = $entry.Class;
                    operations = $entry.Operations; flags = $entry.Flags })
            }
            $record.algorithms = @($algorithms.ToArray())
        }
        $queries = [Collections.Generic.List[object]]::new()
        foreach ($algorithm in @('AES', 'SHA256', 'HMAC', 'SP800_108_CTR_HMAC', 'ECDSA_P256')) {
            $status = [PosCngCapabilityNative]::NCryptIsAlgSupported($provider, $algorithm, 0x40)
            $queries.Add([ordered]@{ algorithm = $algorithm; status = [PosCngCapabilityNative]::Status($status);
                advertised = ($status -eq 0) })
        }
        $record.advertised_support = @($queries.ToArray())
    } finally {
        if ($buffer -ne [IntPtr]::Zero) { $record.buffer_free_status = [PosCngCapabilityNative]::Status([PosCngCapabilityNative]::NCryptFreeBuffer($buffer)) }
        if ($provider -ne [IntPtr]::Zero) { $record.provider_free_status = [PosCngCapabilityNative]::Status([PosCngCapabilityNative]::NCryptFreeObject($provider)) }
        $providers.Add($record)
    }
}
$result = [ordered]@{
    status = 'CAPABILITY_DIAGNOSTIC_ONLY_NOT_QUALIFICATION'
    os_version = [Environment]::OSVersion.Version.ToString()
    process_architecture = [Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture.ToString()
    powershell_version = $PSVersionTable.PSVersion.ToString()
    providers = @($providers.ToArray())
    keys_created_or_enumerated = 0
    private_operations = 0
    limits = @('Reports advertised algorithms only, not working/persistent/nonexporting AES-GCM or HMAC providers.',
        'A KDF advertisement is not the existing raw HMAC or AES-GCM contract.',
        'No key generation, import/export, key enumeration, persistence, restart, installed identity, custody or authority qualification.')
}
$result | ConvertTo-Json -Depth 10 | Set-Content -Encoding utf8NoBOM (Join-Path $OutputDirectory 'capabilities.json')
Write-Host 'Read-only CNG capability diagnostic recorded; no key/provider qualification claimed.'
