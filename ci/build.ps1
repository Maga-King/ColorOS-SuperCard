param([switch]$SkipReference, [switch]$Offline)
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $prepareArgs = @('tools/prepare_build.py')
    if ($Offline) { $prepareArgs += '--offline' }
    python @prepareArgs
    if ($LASTEXITCODE -ne 0) { throw '构建输入校验失败' }
    python tools/test_pay_users.py
    if ($LASTEXITCODE -ne 0) { throw 'PAY 用户路由离线测试失败' }
    python tools/rebuild_assets.py
    if ($LASTEXITCODE -ne 0) { throw '补丁 DEX 再生与现有资产不一致' }
    $gradleArgs = @(':app:clean', ':app:assembleDebug', '--no-daemon', '--console', 'plain')
    if ($Offline) { $gradleArgs += '--offline' }
    # Windows PowerShell 5 treats compiler stderr notes as terminating errors.
    # Gradle's exit code determines success; keep its diagnostics in the log.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & ./gradlew.bat @gradleArgs
        $gradleExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($gradleExit -ne 0) { throw 'Gradle 构建失败' }
    $version = Get-Content -Raw ci/version.json | ConvertFrom-Json
    python tools/unified-prototype/build.py --version-code $version.code --version-name "$($version.name)-unified" --sign
    if ($LASTEXITCODE -ne 0) { throw '统一 APK 合包失败' }
    New-Item -ItemType Directory -Force dist | Out-Null
    $apk = "dist/SuperCard-$($version.name)-unified.apk"
    Copy-Item -LiteralPath tools/unified-prototype/out/supercard-unified-prototype.apk -Destination $apk
    Copy-Item -LiteralPath tools/unified-prototype/out/resource-verification.json -Destination dist/resource-verification.json
    python tools/compare_apk.py fingerprint $apk --output dist/apk-fingerprint.json --quiet > $null
    if ($LASTEXITCODE -ne 0) { throw 'APK 指纹生成失败' }
    if (-not $SkipReference) {
        python tools/compare_apk.py compare $apk --reference ci/reference-apk.json --output dist/comparison.json --quiet > $null
        if ($LASTEXITCODE -ne 0) { throw '云端 APK 与本地基准内容不一致' }
    }
    python -c "from pathlib import Path; import hashlib,sys; p=Path(sys.argv[1]); (p.parent/'SHA256SUMS.txt').write_text(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n',encoding='utf-8')" $apk
    if ($LASTEXITCODE -ne 0) { throw '产物哈希生成失败' }
} finally { Pop-Location }
