"""将同一次编译的 APK 与 root 服务脚本封装为可安装模块。"""
import argparse
from pathlib import Path
import zipfile

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--code', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    prop = (root / 'root-backend/module.prop').read_text(encoding='utf-8')
    lines = [('version=' + args.version) if s.startswith('version=') else
             ('versionCode=' + str(args.code)) if s.startswith('versionCode=') else s
             for s in prop.splitlines()]
    files = {'module.prop': ('\n'.join(lines) + '\n').encode(),
             'service.sh': (root / 'root-backend/service.sh').read_bytes().replace(b'\r\n', b'\n'),
             'backend.apk': args.apk.read_bytes(),
             'customize.sh': b'#!/system/bin/sh\nset_perm "$MODPATH/service.sh" 0 0 0755\nset_perm "$MODPATH/backend.apk" 0 0 0600\n'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'w') as target:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.create_system = 3
            entry.external_attr = (0o100755 if name.endswith('.sh') else 0o100600) << 16
            target.writestr(entry, data)
    print('Root 后端模块已生成：', args.output)

if __name__ == '__main__':
    main()
