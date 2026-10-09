"""校验仓库构建输入，下载并校验固定版本的 DEX 合包工具。"""
from pathlib import Path
import hashlib
import io
import json
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
JAR_SHA256 = '6cec60a76a54e18f21bf5f3aef0dcd3f8887d897a3336fd484f4284b20aa2348'
ARCHIVE_SHA256 = 'ad10a24507e0954cf21e2eb36ab643ad6487bcaa90bc87f31c57cd4692f17f13'
URL = 'https://github.com/skylot/jadx/releases/download/v1.5.5/jadx-gui-1.5.5-win.zip'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def main():
    lock = json.loads((ROOT / 'vendor/inputs.sha256.json').read_text(encoding='utf-8'))
    for relative, expected in lock.items():
        if digest((ROOT / relative).read_bytes()) != expected:
            raise SystemExit('构建输入校验失败：' + relative)
    target = ROOT / 'tools/cache/jadx-gui-1.5.5-all.jar'
    if not target.is_file() or digest(target.read_bytes()) != JAR_SHA256:
        request = urllib.request.Request(URL, headers={'User-Agent': 'ColorOS-SuperCard-build'})
        with urllib.request.urlopen(request, timeout=120) as response:
            archive = response.read()
        if digest(archive) != ARCHIVE_SHA256:
            raise SystemExit('jadx 下载校验失败')
        with zipfile.ZipFile(io.BytesIO(archive)) as source:
            candidates = [n for n in source.namelist() if n.endswith('/jadx-gui-1.5.5-all.jar')
                          or n == 'jadx-gui-1.5.5-all.jar']
            if len(candidates) != 1:
                raise SystemExit('jadx 压缩包结构不符合预期')
            jar = source.read(candidates[0])
        if digest(jar) != JAR_SHA256:
            raise SystemExit('jadx JAR 校验失败')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(jar)
    print('原包、补丁资产及 jadx 1.5.5 校验通过。')

if __name__ == '__main__':
    main()
