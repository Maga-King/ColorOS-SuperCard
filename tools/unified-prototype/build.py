"""Offline single-APK packaging prototype. Never invokes adb/su or edits main sources."""
from __future__ import annotations
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent.parent
SDK = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
           or str(Path.home() / 'AppData/Local/Android/Sdk'))
BT = SDK / 'build-tools' / '36.0.0'
ANDROID = SDK / 'platforms/android-36/android.jar'
EXE = '.exe' if os.name == 'nt' else ''
java_home = os.environ.get('JAVA_HOME')
JAVA = Path(java_home) / 'bin' / ('java' + EXE) if java_home else Path(shutil.which('java') or 'java')
JAVAC = JAVA.with_name('javac' + EXE)
JADX = Path(os.environ.get('JADX_JAR', str(PROJECT / 'tools/cache/jadx-gui-1.5.5-all.jar')))
A = '{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', A[1:-1])

def run(*args: object, quiet: bool = False) -> str:
    result = subprocess.run([str(a) for a in args], check=False, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT)
    try: output = result.stdout.decode('utf-8')
    except UnicodeDecodeError: output = result.stdout.decode('gb18030', errors='replace')
    if result.returncode:
        print(output, file=sys.stderr)
        result.check_returncode()
    if not quiet and output.strip(): print(output.rstrip())
    return output

def u16(data: bytes, off: int) -> int: return struct.unpack_from('<H', data, off)[0]
def u32(data: bytes, off: int) -> int: return struct.unpack_from('<I', data, off)[0]
def put32(data: bytearray, off: int, value: int): struct.pack_into('<I', data, off, value)

def chunks(data: bytes, start: int, end: int):
    while start < end:
        typ, header, size = struct.unpack_from('<HHI', data, start)
        if header < 8 or size < header or start + size > end:
            raise ValueError(f'Invalid resource chunk at {start}')
        yield typ, data[start:start + size]
        start += size
    if start != end: raise ValueError('Invalid resource boundary')

def pool_strings(pool: bytes) -> list[str]:
    count, styles, flags, data_start, _ = struct.unpack_from('<IIIII', pool, 8)
    utf8 = bool(flags & 0x100)
    def length(off: int, wide: bool):
        if wide:
            n = u16(pool, off)
            return (((n & 0x7fff) << 16) | u16(pool, off + 2), off + 4) if n & 0x8000 else (n, off + 2)
        n = pool[off]
        return (((n & 0x7f) << 8) | pool[off + 1], off + 2) if n & 0x80 else (n, off + 1)
    result = []
    for i in range(count):
        off = data_start + u32(pool, u16(pool, 2) + 4 * i)
        n, off = length(off, not utf8)
        if utf8: n, off = length(off, False)
        result.append(pool[off:off + n * (1 if utf8 else 2)].decode('utf-8' if utf8 else 'utf-16le'))
    return result

def append_pool(original: bytes, extra: list[str]) -> bytes:
    """Preserve all original indices, string bytes and spans; append only new strings."""
    header = u16(original, 2)
    count, styles, flags, strings_start, styles_start = struct.unpack_from('<IIIII', original, 8)
    if header != 28: raise ValueError('Unexpected string-pool header')
    old_offsets = original[header:header + count * 4]
    style_offsets = original[header + count * 4:header + (count + styles) * 4]
    string_data = bytearray(original[strings_start:styles_start or len(original)])
    style_data = original[styles_start:] if styles_start else b''
    new_offsets = bytearray()
    def length(n: int, wide: bool) -> bytes:
        if wide: return struct.pack('<H', n) if n < 0x8000 else struct.pack('<HH', (n >> 16) | 0x8000, n & 0xffff)
        return bytes([n]) if n < 0x80 else bytes([(n >> 8) | 0x80, n & 0xff])
    for value in extra:
        new_offsets += struct.pack('<I', len(string_data))
        utf16len = len(value.encode('utf-16le')) // 2
        if flags & 0x100:
            raw = value.encode('utf-8')
            string_data += length(utf16len, False) + length(len(raw), False) + raw + b'\0'
        else: string_data += length(utf16len, True) + value.encode('utf-16le') + b'\0\0'
    string_data += b'\0' * (-len(string_data) % 4)
    result = bytearray(original[:header])
    new_start = header + (count + len(extra) + styles) * 4
    struct.pack_into('<IIIII', result, 8, count + len(extra), styles, flags & ~1,
                     new_start, new_start + len(string_data) if styles else 0)
    result += old_offsets + new_offsets + style_offsets + string_data + style_data
    put32(result, 4, len(result))
    assert pool_strings(result)[:count] == pool_strings(original)
    return bytes(result)

def rebase_package_strings(package: bytes, amount: int) -> bytes:
    """Only the four host drawable file entries may contain global string indices."""
    result = bytearray(package)
    offset = u16(package, 2)
    for typ, part in chunks(package, offset, len(package)):
        if typ == 0x201:  # RES_TABLE_TYPE_TYPE
            if part[9] != 0: raise ValueError('Unexpected sparse/offset16 host resource table')
            count, entries_start = struct.unpack_from('<II', part, 12)
            header = u16(part, 2)
            for i in range(count):
                entry_offset = u32(part, header + i * 4)
                if entry_offset == 0xffffffff: continue
                entry = entries_start + entry_offset
                size, flags = struct.unpack_from('<HH', part, entry)
                if flags & 1: raise ValueError('Unexpected complex host drawable entry')
                value = entry + size
                if part[value + 3] == 3:  # TYPE_STRING
                    put32(result, offset + value + 4, u32(part, value + 4) + amount)
        offset += len(part)
    return bytes(result)

def merge_resources(original: bytes, extra: bytes) -> tuple[bytes, dict]:
    old_parts = list(chunks(original, u16(original, 2), len(original)))
    new_parts = list(chunks(extra, u16(extra, 2), len(extra)))
    old_pool = next(part for typ, part in old_parts if typ == 1)
    new_pool = next(part for typ, part in new_parts if typ == 1)
    packages = [part for typ, part in old_parts if typ == 0x200]
    host_packages = [part for typ, part in new_parts if typ == 0x200]
    if len(packages) != 1 or u32(packages[0], 8) != 0x7f:
        raise ValueError('Expected original single 0x7f package')
    if len(host_packages) != 1 or u32(host_packages[0], 8) != 0x7e:
        raise ValueError('Expected independent host 0x7e package')
    count = u32(old_pool, 8)
    merged_pool = append_pool(old_pool, pool_strings(new_pool))
    appended = rebase_package_strings(host_packages[0], count)
    result = bytearray(original[:u16(original, 2)])
    for typ, part in old_parts: result += merged_pool if typ == 1 else part
    result += appended
    put32(result, 4, len(result)); put32(result, 8, 2)
    # Every original package byte, including type/entry IDs and references,
    # stays unchanged. Its global string indices are a preserved prefix.
    original_package_hash = hashlib.sha256(packages[0]).hexdigest()
    final_packages = [part for typ, part in chunks(result, u16(result, 2), len(result)) if typ == 0x200]
    assert final_packages[0] == packages[0]
    return bytes(result), {'originalPackageChunkSha256': original_package_hash,
                           'originalPackageChunkByteIdentical': True,
                           'originalGlobalStringCount': count,
                           'originalGlobalStringsPrefixIdentical': True,
                           'hostGlobalStringsAppended': len(pool_strings(new_pool)),
                           'resourcePackages': {'com.vivo.card': '0x7f', 'dev.local.supercardhost': '0x7e'}}

def manifest(out: Path, version: int, version_name: str) -> None:
    host = ET.parse(PROJECT / 'app/src/main/AndroidManifest.xml').getroot()
    source = ET.parse(PROJECT / 'vendor/supercard/AndroidManifest.xml').getroot()
    host.set('package', 'dev.local.supercardhost')
    host.set(A + 'versionCode', str(version)); host.set(A + 'versionName', version_name)
    uses_sdk = ET.Element('uses-sdk', {A + 'minSdkVersion': '31', A + 'targetSdkVersion': '36'})
    host.insert(0, uses_sdk)
    app = host.find('application'); original_app = source.find('application')
    app.set(A + 'label', '超级卡包')
    app.set(A + 'icon', '@*com.vivo.card:drawable/supercard_icon')
    if not app.get(A + 'name'): app.set(A + 'name', 'com.vivo.card.SuperCardApplication')
    app.set(A + 'hardwareAccelerated', 'true'); app.set(A + 'supportsRtl', 'true')
    app.set(A + 'extractNativeLibs', 'false')
    # Manifest package changed, so shorthand components must be fully qualified.
    for component in app:
        name = component.get(A + 'name', '')
        if name.startswith('.'): component.set(A + 'name', 'dev.local.supercardhost' + name)
    inventory = []
    for component in original_app:
        name = component.get(A + 'name', '')
        keep = component.tag == 'meta-data' or (component.tag == 'activity' and
                    (name.startswith('com.vivo.card.setting.') or name == 'com.vivo.card.ui.BaseActivity'))
        inventory.append({'tag': component.tag, 'name': name, 'included': keep,
                          'authorities': component.get(A + 'authorities', '')})
        if keep:
            copied = copy.deepcopy(component)
            for node in copied.iter():
                for key, value in node.attrib.items():
                    if value.startswith('@') and not value.startswith(('@android:', '@*android:')):
                        node.set(key, '@*com.vivo.card:' + value[1:])
            app.append(copied)
    if not any(node.get(A + 'name') == 'android.permission.QUERY_ALL_PACKAGES'
               for node in host.findall('uses-permission')):
        host.insert(1, ET.Element('uses-permission', {A + 'name': 'android.permission.QUERY_ALL_PACKAGES'}))
    settings = next(node for node in app.findall('activity')
                    if node.get(A + 'name') == 'com.vivo.card.setting.CardSettingActivity')
    for activity in app.findall('activity'):
        for entry in list(activity.findall('intent-filter')):
            if any(category.get(A + 'name') == 'android.intent.category.LAUNCHER'
                   for category in entry.findall('category')):
                activity.remove(entry)
                settings.append(entry)
    # Original authorities/services remain out of the shell; only audited settings
    # activities are added. The host's private transfer boundaries stay identical.
    (out / 'manifest-inventory.json').write_text(json.dumps(inventory, ensure_ascii=False, indent=2), encoding='utf-8')
    ET.indent(host); ET.ElementTree(host).write(out / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)

def certificate(apk: Path) -> str:
    text = run(JAVA, '-jar', BT / 'lib/apksigner.jar', 'verify', '--print-certs', apk)
    match = re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', text)
    if not match: raise ValueError('APK has no verified signing certificate')
    return match.group(1)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host-apk', type=Path, default=PROJECT / 'app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--version-code', type=int, default=2)
    parser.add_argument('--version-name', default='0.2-unified')
    parser.add_argument('--sign', action='store_true', help='Sign offline with the existing debug certificate and verify identity')
    args = parser.parse_args()
    out = HERE / 'out'; out.mkdir(exist_ok=True)
    for path in [args.host_apk, ANDROID, JAVA, JAVAC, JADX]:
        if not path.is_file(): raise FileNotFoundError(path)
    original = PROJECT / 'app/src/main/assets/supercard-resources.apk'
    # Use the DEX asset from this exact host build, not a concurrently edited
    # source asset. Rebuilding the host is the caller's explicit preceding step.
    snapshot = out / 'input-host.apk'
    shutil.copyfile(args.host_apk, snapshot)
    args.host_apk = snapshot
    patched = out / 'input-supercard-code.zip'
    with zipfile.ZipFile(args.host_apk) as host_input:
        patched.write_bytes(host_input.read('assets/supercard-code.zip'))
    manifest(out, args.version_code, args.version_name)
    compiled = out / 'host-res.zip'
    run(BT / ('aapt2' + EXE), 'compile', '--dir', PROJECT / 'app/src/main/res', '-o', compiled)
    resource_apk = out / 'host-res.apk'
    run(BT / ('aapt2' + EXE), 'link', '-o', resource_apk, '--manifest', out / 'AndroidManifest.xml',
        '-I', ANDROID, '-I', original, '--package-id', '0x7e', '--allow-reserved-package-id', compiled)
    java_out = out / 'java'; java_out.mkdir(exist_ok=True)
    dex_out = out / 'dex'; dex_out.mkdir(exist_ok=True)
    for previous in dex_out.glob('classes*.dex'):
        if previous.resolve().parent != dex_out.resolve(): raise ValueError('Unexpected output path')
        previous.unlink()
    run(JAVAC, '-encoding', 'UTF-8', '--release', '17', '-cp', JADX, '-d', java_out,
        HERE / 'MergeDex.java', HERE / 'ApiLinkAudit.java')
    run(JAVA, '-cp', str(java_out) + os.pathsep + str(JADX), 'ApiLinkAudit', args.host_apk, patched, out)
    run(JAVA, '-cp', str(java_out) + os.pathsep + str(JADX), 'MergeDex', args.host_apk, patched, dex_out)
    files: dict[str, bytes] = {}
    with zipfile.ZipFile(original) as source:
        old_resources = source.read('resources.arsc')
        for entry in source.infolist():
            if entry.filename.startswith(('res/', 'assets/', 'lib/', 'META-INF/')) and not re.fullmatch(
                    r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', entry.filename, re.IGNORECASE):
                files[entry.filename] = source.read(entry)
    with zipfile.ZipFile(resource_apk) as resources:
        merged, verification = merge_resources(old_resources, resources.read('resources.arsc'))
        files['resources.arsc'] = merged
        files['AndroidManifest.xml'] = resources.read('AndroidManifest.xml')
        for entry in resources.infolist():
            if entry.filename.startswith('res/'):
                if entry.filename in files: raise ValueError('Resource path collision: ' + entry.filename)
                files[entry.filename] = resources.read(entry)
    conflicts = []
    with zipfile.ZipFile(args.host_apk) as host:
        for entry in host.infolist():
            if entry.filename == 'assets/supercard-resources.apk':
                continue  # Debug-only fallback; the full original table is already outside.
            if entry.filename.startswith(('assets/', 'lib/', 'META-INF/')) and not re.fullmatch(
                    r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', entry.filename, re.IGNORECASE):
                if entry.filename.startswith('META-INF/services/') and entry.filename in files:
                    lines = files[entry.filename].decode('utf-8').splitlines() + host.read(entry).decode('utf-8').splitlines()
                    files[entry.filename] = ('\n'.join(dict.fromkeys(lines)) + '\n').encode('utf-8')
                    continue
                if entry.filename in files and files[entry.filename] != host.read(entry):
                    conflicts.append(entry.filename)
                files[entry.filename] = host.read(entry)
    # Archive resources remain isolated; plugin class loaders must opt in before
    # uninstalling the formerly installed packages. No flattened 0x7f merge.
    # The memory archive is already included from the exact host input above.
    if 'assets/memory-plugin.apk' not in files: raise ValueError('Missing embedded memory plugin')
    scopes = ['com.android.systemui', 'dev.local.supercardhost', 'com.coloros.smartsidebar',
              'com.oplus.aimemory', 'com.finshell.wallet']
    files['META-INF/xposed/scope.list'] = ('\n'.join(scopes) + '\n').encode()
    for dex in sorted(dex_out.glob('classes*.dex')): files[dex.name] = dex.read_bytes()
    unsigned = out / 'supercard-unified-unsigned.apk'
    with zipfile.ZipFile(unsigned, 'w') as target:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_STORED if name.endswith('.so') or name == 'resources.arsc' else zipfile.ZIP_DEFLATED
            entry.create_system = 0
            target.writestr(entry, data)
    aligned = out / 'supercard-unified-aligned.apk'
    run(BT / ('zipalign' + EXE), '-f', '-P', '16', '4', unsigned, aligned)
    run(BT / ('zipalign' + EXE), '-c', '-P', '16', '4', aligned)
    verification['assetPathCollisionsHostWins'] = conflicts
    verification['excludedDebugFallbackAssets'] = ['assets/supercard-resources.apk']
    verification['hostInputSha256'] = hashlib.sha256(args.host_apk.read_bytes()).hexdigest()
    verification['originalInputSha256'] = hashlib.sha256(original.read_bytes()).hexdigest()
    verification['patchedCardInputSha256'] = hashlib.sha256(patched.read_bytes()).hexdigest()
    verification['status'] = 'unified-package-built; device-functional-validation-is-separate'
    verification['vectorCommit'] = 'ddeed8ca1ffedfaba7f4341b911089906862d5c4'
    if args.sign:
        signed = out / 'supercard-unified-prototype.apk'
        os.environ.setdefault('SUPERCARD_STORE_PASSWORD', 'android')
        os.environ.setdefault('SUPERCARD_KEY_PASSWORD', 'android')
        run(JAVA, '-jar', BT / 'lib/apksigner.jar', 'sign', '--ks',
            os.environ.get('SUPERCARD_KEYSTORE', str(Path.home() / '.android/debug.keystore')),
            '--ks-key-alias', os.environ.get('SUPERCARD_KEY_ALIAS', 'androiddebugkey'),
            '--ks-pass', 'env:SUPERCARD_STORE_PASSWORD', '--key-pass', 'env:SUPERCARD_KEY_PASSWORD',
            '--out', signed, aligned)
        old_cert, new_cert = certificate(args.host_apk), certificate(signed)
        if old_cert != new_cert: raise ValueError('Prototype certificate does not match current host build')
        verification['signingCertificateSha256'] = new_cert
        verification['sameCertificateAsHostInput'] = True
    (out / 'resource-verification.json').write_text(json.dumps(verification, ensure_ascii=False, indent=2), encoding='utf-8')
    dump = run(BT / ('aapt2' + EXE), 'dump', 'resources', aligned, quiet=True)
    (out / 'resources-dump.txt').write_text(dump, encoding='utf-8')
    badging = run(BT / ('aapt' + EXE), 'dump', 'badging', aligned)
    (out / 'badging.txt').write_text(badging, encoding='utf-8')
    print('Unified APK:', signed if args.sign else aligned)

if __name__ == '__main__': main()
