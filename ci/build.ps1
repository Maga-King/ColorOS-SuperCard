param([switch]$SkipReference)
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    python tools/prepare_build.py
    if ($LASTEXITCODE -ne 0) { throw '构建输入校验失败' }
    python tools/rebuild_assets.py
    if ($LASTEXITCODE -ne 0) { throw '补丁 DEX 再生与现有资产不一致' }
    & ./gradlew.bat :app:clean :app:assembleDebug --no-daemon --console plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle 构建失败' }
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
    python tools/package_backend.py --apk $apk --version $version.name --code $version.code --output "dist/SuperCard-Backend-$($version.name).zip"
    if ($LASTEXITCODE -ne 0) { throw 'Root 后端打包失败' }
    python -c "from pathlib import Path; import hashlib; p=Path('dist'); (p/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n' for f in sorted(p.iterdir()) if f.suffix in ('.apk','.zip')),encoding='utf-8')"
    if ($LASTEXITCODE -ne 0) { throw '产物哈希生成失败' }
} finally { Pop-Location }
