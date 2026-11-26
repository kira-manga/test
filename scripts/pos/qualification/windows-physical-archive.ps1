# The protected exact-source workflow runs this only on a disposable Windows host.
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_REPOSITORY -ne 'kira-manga/test' -or $env:RUNNER_OS -ne 'Windows') {
    throw 'The NTFS archive qualification requires the disposable Windows runner.'
}
$stage = Join-Path $env:RUNNER_TEMP 'pos-qualification'
$inputs = Join-Path $stage 'inputs'
$source = Join-Path $stage 'source/apps/pos'
$logs = Join-Path $stage 'reopen-probe/same'
$raw = Join-Path $logs 'same.raw'
$archive = Join-Path $stage 'windows-archive'
$negative = Join-Path $stage 'windows-archive-no-service-sid'
$public = Join-Path $stage 'public'
$runService = Join-Path $env:GITHUB_WORKSPACE 'scripts/pos/windows/run-service-signing-probe.ps1'
$export = Join-Path $env:GITHUB_WORKSPACE 'scripts/pos/windows/export-service-probe-classpath.init.gradle'
New-Item -ItemType Directory -Force $logs,$archive,$negative,$public | Out-Null

try {
    $manager = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/sdkmanager.bat'
    if (-not (Test-Path $manager)) { throw 'The Android SDK manager is unavailable on the Windows runner.' }
    $platforms = Join-Path $env:ANDROID_HOME 'platforms'
    $newPlatform = Join-Path $platforms 'android-37.0'
    if (-not (Test-Path (Join-Path $newPlatform 'android.jar'))) {
        & $manager 'platforms;android-37.0' *> $raw
        if ($LASTEXITCODE -ne 0) { throw 'The pinned Android compile platform could not be installed.' }
    }
    $compilerPlatform = Join-Path $platforms 'android-37'
    if (-not (Test-Path $compilerPlatform)) {
        New-Item -ItemType Junction -Path $compilerPlatform -Target $newPlatform | Out-Null
    }
    Push-Location $source
    try {
        $classpath = Join-Path $archive 'probe-classpath.txt'
        $gradleArguments = @(
            '--no-daemon', '--max-workers=2',
            '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=1g',
            '-Pkotlin.compiler.execution.strategy=in-process',
            '-PposWindowsServiceProbe=archive', "-PposWindowsServiceProbeOutput=$classpath",
            '--init-script', $export, ':adapters:sales-store:exportWindowsServiceProbeClasspath', '--console=plain'
        )
        & ./gradlew.bat @gradleArguments *>> $raw
        if ($LASTEXITCODE -ne 0) { throw 'Compilation of the exact NTFS supporting probe failed.' }
    } finally { Pop-Location }
    Copy-Item -LiteralPath $classpath -Destination (Join-Path $negative 'probe-classpath.txt')
    & $runService -OutputDirectory $archive -ServiceSidType unrestricted -Probe archive *>> $raw
    & $runService -OutputDirectory $negative -ServiceSidType none -Probe archive *>> $raw

    $summary = [ordered]@{
        classification = 'Owned disposable Windows service NTFS archive qualification'
        source_revision = (Get-Content (Join-Path $inputs 'POS-REVISION') -Raw).Trim()
        source_tree = (Get-Content (Join-Path $inputs 'POS-TREE') -Raw).Trim()
        runner_os = 'Windows'
        service_archive_passed = $true
        service_without_enabled_sid_refused = $true
    }
    $summary | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $public 'windows-archive.json')
    Copy-Item -LiteralPath (Join-Path $inputs 'POS-REVISION') -Destination $public
    Write-Output 'POS_NTFS_SERVICE_ARCHIVE verified=true missing_sid_refused=true'
} catch {
    foreach ($probe in @($archive, $negative)) {
        foreach ($name in @('archive-stages.txt', 'failure.txt', 'child-stdout.txt', 'child-stderr.txt', 'launcher-failure.txt')) {
            $file = Join-Path $probe $name
            if (Test-Path $file) { Get-Content -LiteralPath $file | Add-Content -LiteralPath $raw }
        }
    }
    throw 'The protected Windows service archive qualification failed. Check the designated failure envelope.'
}
