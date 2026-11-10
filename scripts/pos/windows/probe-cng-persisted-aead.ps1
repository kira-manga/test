# Disposable native capability diagnostic. Not an acceptance/qualification harness.
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'This diagnostic requires Windows.' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
public static class PosCngPersistedAeadProbe {
    [StructLayout(LayoutKind.Sequential)] struct CipherPadding {
        public UInt32 Size, Flags; public IntPtr IV; public UInt32 IVSize;
        public IntPtr Other; public UInt32 OtherSize;
    }
    [StructLayout(LayoutKind.Sequential)] struct AuthInfo {
        public UInt32 Size, Version; public IntPtr Nonce; public UInt32 NonceSize;
        public IntPtr AAD; public UInt32 AADSize; public IntPtr Tag; public UInt32 TagSize;
        public IntPtr Mac; public UInt32 MacSize, AADTotal; public UInt64 DataTotal; public UInt32 Flags;
    }
    [DllImport("ncrypt.dll", CharSet=CharSet.Unicode, ExactSpelling=true)] static extern int NCryptOpenStorageProvider(out IntPtr p, string name, UInt32 flags);
    [DllImport("ncrypt.dll", CharSet=CharSet.Unicode, ExactSpelling=true)] static extern int NCryptCreatePersistedKey(IntPtr p, out IntPtr k, string algorithm, string name, UInt32 spec, UInt32 flags);
    [DllImport("ncrypt.dll", CharSet=CharSet.Unicode, ExactSpelling=true)] static extern int NCryptOpenKey(IntPtr p, out IntPtr k, string name, UInt32 spec, UInt32 flags);
    [DllImport("ncrypt.dll", CharSet=CharSet.Unicode, ExactSpelling=true)] static extern int NCryptSetProperty(IntPtr k, string name, byte[] value, UInt32 size, UInt32 flags);
    [DllImport("ncrypt.dll", CharSet=CharSet.Unicode, ExactSpelling=true)] static extern int NCryptGetProperty(IntPtr k, string name, byte[] value, UInt32 size, out UInt32 written, UInt32 flags);
    [DllImport("ncrypt.dll", ExactSpelling=true)] static extern int NCryptFinalizeKey(IntPtr k, UInt32 flags);
    [DllImport("ncrypt.dll", ExactSpelling=true)] static extern int NCryptEncrypt(IntPtr k, byte[] input, UInt32 inputSize, ref CipherPadding padding, byte[] output, UInt32 outputSize, out UInt32 written, UInt32 flags);
    [DllImport("ncrypt.dll", ExactSpelling=true)] static extern int NCryptDecrypt(IntPtr k, byte[] input, UInt32 inputSize, ref CipherPadding padding, byte[] output, UInt32 outputSize, out UInt32 written, UInt32 flags);
    [DllImport("ncrypt.dll", ExactSpelling=true)] static extern int NCryptFreeObject(IntPtr handle);
    [DllImport("ncrypt.dll", ExactSpelling=true)] static extern int NCryptDeleteKey(IntPtr handle, UInt32 flags);
    public static string Status(int v) { return "0x"+unchecked((UInt32)v).ToString("x8"); }
    static bool Step(Dictionary<string,object> r, string stage, int status) { r[stage]=Status(status); return status==0; }
    static int Property(IntPtr k, string n, byte[] b) { return NCryptSetProperty(k,n,b,(UInt32)b.Length,0); }
    static void ObserveProperty(Dictionary<string,object> r, IntPtr k, string name, bool unicode) {
        byte[] b=new byte[512]; UInt32 size;
        int status=NCryptGetProperty(k,name,b,(UInt32)b.Length,out size,0);
        r[name+"_status"]=Status(status);
        if(status==0 && size<=b.Length) r[name]=unicode ? (object)Encoding.Unicode.GetString(b,0,(int)size).TrimEnd('\0') : (size==4 ? (object)BitConverter.ToUInt32(b,0) : (object)size);
        Array.Clear(b,0,b.Length);
    }
    static int Crypt(IntPtr key, bool decrypt, byte[] input, byte[] nonce, byte[] aad, byte[] tag, UInt32 paddingFlags, out byte[] result) {
        // Keep all parameter bytes in one allocation as the SDK documents. The
        // Microsoft AziHSM NCrypt sample reports sizeof(AuthInfo), not the size
        // of the pointed-to nonce/AAD/tag, in cbOtherInfo. No key bytes are used.
        // microsoft/AziHSM-Guest@4c2f37d3 samples/cpp/include/Utils/CryptoUtils.h
        int header=Marshal.SizeOf<AuthInfo>(); int total=checked(header+nonce.Length+aad.Length+tag.Length);
        IntPtr block=Marshal.AllocHGlobal(total); byte[] zero=new byte[total]; Marshal.Copy(zero,0,block,total);
        byte[] output=new byte[input.Length+32]; result=null;
        try {
            IntPtr n=IntPtr.Add(block,header), a=IntPtr.Add(n,nonce.Length), t=IntPtr.Add(a,aad.Length);
            Marshal.Copy(nonce,0,n,nonce.Length); Marshal.Copy(aad,0,a,aad.Length); Marshal.Copy(tag,0,t,tag.Length);
            var auth=new AuthInfo { Size=(UInt32)header, Version=1, Nonce=n, NonceSize=(UInt32)nonce.Length,
                AAD=a, AADSize=(UInt32)aad.Length, Tag=t, TagSize=(UInt32)tag.Length };
            Marshal.StructureToPtr(auth,block,false);
            var padding=new CipherPadding { Size=(UInt32)Marshal.SizeOf<CipherPadding>(), Flags=paddingFlags, Other=block, OtherSize=(UInt32)header };
            UInt32 written;
            int status=decrypt ? NCryptDecrypt(key,input,(UInt32)input.Length,ref padding,output,(UInt32)output.Length,out written,0x10)
                               : NCryptEncrypt(key,input,(UInt32)input.Length,ref padding,output,(UInt32)output.Length,out written,0x10);
            if(status==0) {
                if(written>output.Length) throw new InvalidOperationException("Invalid native output bounds.");
                result=new byte[written]; Buffer.BlockCopy(output,0,result,0,(int)written);
                Marshal.Copy(t,tag,0,tag.Length);
            }
            return status;
        } finally { Array.Clear(output,0,output.Length); Marshal.Copy(zero,0,block,total); Marshal.FreeHGlobal(block); }
    }
    public static Dictionary<string,object> Run() {
        var r=new Dictionary<string,object>(); IntPtr provider=IntPtr.Zero,key=IntPtr.Zero;
        string name="ukkera-pos-disposable-aead-probe-"+Guid.NewGuid().ToString("N"); bool created=false;
        byte[] plain=Encoding.UTF8.GetBytes("Synthetic capability diagnostic only"), nonce=new byte[12], aad=Encoding.UTF8.GetBytes("POS capability diagnostic"), tag=new byte[16];
        byte[] encrypted=null,decrypted=null; RandomNumberGenerator.Fill(nonce);
        r["status"]="CAPABILITY_DIAGNOSTIC_ONLY_NOT_QUALIFICATION";
        r["key_material_imported_or_exported"]=false; r["key_namespace_enumerated"]=false;
        try {
            if(!Step(r,"open_provider",NCryptOpenStorageProvider(out provider,"Microsoft Software Key Storage Provider",0))) return r;
            // No overwrite flag. Cleanup is restricted to this exact successfully created name.
            if(!Step(r,"create_AES",NCryptCreatePersistedKey(provider,out key,"AES",name,0,0))) return r;
            created=true;
            if(!Step(r,"set_length_256",Property(key,"Length",BitConverter.GetBytes(256)))) return r;
            if(!Step(r,"set_export_policy_zero",Property(key,"Export Policy",BitConverter.GetBytes(0)))) return r;
            if(!Step(r,"set_symmetric_key_usage",Property(key,"Key Usage",BitConverter.GetBytes(1)))) return r;
            Step(r,"set_GCM_before_finalize",Property(key,"Chaining Mode",Encoding.Unicode.GetBytes("ChainingModeGCM\0")));
            if(!Step(r,"finalize",NCryptFinalizeKey(key,0))) return r;
            ObserveProperty(r,key,"Length",false); ObserveProperty(r,key,"Export Policy",false);
            ObserveProperty(r,key,"Key Usage",false); ObserveProperty(r,key,"Algorithm Name",true);
            ObserveProperty(r,key,"Chaining Mode",true);
            if(!Step(r,"set_GCM_after_finalize",Property(key,"Chaining Mode",Encoding.Unicode.GetBytes("ChainingModeGCM\0")))) {
                Step(r,"set_CBC_control_after_finalize",Property(key,"Chaining Mode",Encoding.Unicode.GetBytes("ChainingModeCBC\0")));
                ObserveProperty(r,key,"Chaining Mode",true);
                return r;
            }
            int free=NCryptFreeObject(key); r["free_before_reopen"]=Status(free);
            if(free!=0) return r; key=IntPtr.Zero;
            if(!Step(r,"reopen_existing",NCryptOpenKey(provider,out key,name,0,0x40))) return r;
            ObserveProperty(r,key,"Export Policy",false);
            ObserveProperty(r,key,"Chaining Mode",true);
            // Chaining mode is per opened handle, as in .NET's NCrypt cipher owner.
            // Reapply it to the reopened key; a prior handle's setting is not evidence.
            if(!Step(r,"set_GCM_reopened_handle",Property(key,"Chaining Mode",Encoding.Unicode.GetBytes("ChainingModeGCM\0")))) return r;
            ObserveProperty(r,key,"Chaining Mode",true);
            // Microsoft's NCrypt authenticated-mode sample uses OTHER_PADDING.
            // The descriptor length and all pointed-to bytes have distinct sizes.
            UInt32 descriptorFlags=2;
            if(!Step(r,"encrypt_struct_sized_descriptor",Crypt(key,false,plain,nonce,aad,tag,descriptorFlags,out encrypted))) return r;
            if(!Step(r,"decrypt",Crypt(key,true,encrypted,nonce,aad,tag,descriptorFlags,out decrypted))) return r;
            r["roundtrip_equal"]=CryptographicOperations.FixedTimeEquals(plain,decrypted);
            tag[0]^=1; byte[] tampered=null;
            try { r["modified_tag_decrypt"]=Status(Crypt(key,true,encrypted,nonce,aad,tag,descriptorFlags,out tampered)); r["modified_tag_returned_plaintext"]=tampered!=null; }
            finally { if(tampered!=null) Array.Clear(tampered,0,tampered.Length); }
        } finally {
            Array.Clear(plain,0,plain.Length);Array.Clear(nonce,0,nonce.Length);Array.Clear(aad,0,aad.Length);Array.Clear(tag,0,tag.Length);
            if(encrypted!=null)Array.Clear(encrypted,0,encrypted.Length); if(decrypted!=null)Array.Clear(decrypted,0,decrypted.Length);
            if(created && key==IntPtr.Zero && provider!=IntPtr.Zero) r["cleanup_open_exact"]=Status(NCryptOpenKey(provider,out key,name,0,0x40));
            if(created && key!=IntPtr.Zero) {
                int deleted=NCryptDeleteKey(key,0x40);r["delete_exact_created_key"]=Status(deleted);
                if(deleted==0)key=IntPtr.Zero;
            }
            if(key!=IntPtr.Zero)r["free_key"]=Status(NCryptFreeObject(key));
            if(provider!=IntPtr.Zero)r["free_provider"]=Status(NCryptFreeObject(provider));
        }
        return r;
    }
}
'@
$result = [PosCngPersistedAeadProbe]::Run()
$result['limits'] = @('Synthetic native capability observations, not independent frozen qualification or a production provider.',
    'No cross-process/restart, installed identity, key origin/exclusivity, ACL, crash durability, native custody or authority qualification.',
    'Export policy readback does not prove all export/duplication paths are forbidden. No key material is imported or exported.',
    'GCM or serialization failure is an observation of this invocation; it does not alone prove every native implementation impossible.')
$result | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8NoBOM (Join-Path $OutputDirectory 'persisted-aead-capability.json')
if ($result.ContainsKey('cleanup_open_exact') -and $result['cleanup_open_exact'] -ne '0x00000000') {
    throw 'Exact synthetic native key cleanup could not reopen the created key; inspect retained status.'
}
if ($result.ContainsKey('delete_exact_created_key') -and $result['delete_exact_created_key'] -ne '0x00000000') {
    throw 'Exact synthetic native key cleanup failed; inspect retained status.'
}
Write-Host 'Disposable persisted AES-GCM capability observations recorded; no provider qualification claimed.'
