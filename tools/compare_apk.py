#!/usr/bin/env python3
"""Compare APK ZIP payloads independently of signatures and ZIP packaging metadata."""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import sys
import tempfile
import warnings
import zipfile
import zlib

SCHEMA_VERSION = 1
PAYLOAD_FORMAT = "apk-uncompressed-entries-v1"
SIGNING_ENTRY = re.compile(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", re.IGNORECASE)
SHA256 = re.compile(r"[0-9a-f]{64}")


class ComparisonError(ValueError):
    pass


def hash_stream(stream) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    while chunk := stream.read(1024 * 1024):
        digest.update(chunk)
        size += len(chunk)
    return digest.hexdigest(), size


def payload_sha(entries: list[dict]) -> str:
    # Input APK paths, timestamps, compression and signing metadata are absent.
    canonical = json.dumps(entries, ensure_ascii=True, sort_keys=True,
                           separators=(",", ":")).encode("ascii")
    return hashlib.sha256(PAYLOAD_FORMAT.encode("ascii") + b"\n" + canonical).hexdigest()


def fingerprint(apk: Path) -> dict:
    entries = []
    excluded = []
    with apk.open("rb") as source:
        before = apk.stat()
        file_sha, file_size = hash_stream(source)
        source.seek(0)
        with zipfile.ZipFile(source) as archive:
            seen = set()
            # Reject duplicates even when the duplicate is a signature entry.
            for info in archive.infolist():
                if info.filename in seen:
                    raise ComparisonError("ZIP 存在重复项：" + info.filename)
                seen.add(info.filename)
            for info in archive.infolist():
                if SIGNING_ENTRY.fullmatch(info.filename):
                    excluded.append(info.filename)
                    continue
                with archive.open(info) as content:
                    digest, size = hash_stream(content)
                if size != info.file_size:
                    raise ComparisonError("ZIP 项解压大小不符：" + info.filename)
                entries.append({"name": info.filename, "size": size, "sha256": digest})
        after = apk.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise ComparisonError("读取期间 APK 被修改，请重新生成指纹")
    entries.sort(key=lambda entry: entry["name"])
    return {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "apkFingerprint",
        "algorithm": "sha256",
        "payloadFormat": PAYLOAD_FORMAT,
        "source": {"path": apk.name, "fileSha256": file_sha, "size": file_size},
        "entryCount": len(entries),
        "excludedSigningEntries": sorted(excluded),
        "entries": entries,
        "payloadSha256": payload_sha(entries),
    }


def unique_json_object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ComparisonError("指纹 JSON 存在重复字段：" + name)
        result[name] = value
    return result


def read_reference(path: Path) -> dict:
    reference = json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique_json_object)
    if not isinstance(reference, dict) or reference.get("schemaVersion") != SCHEMA_VERSION \
            or reference.get("kind") != "apkFingerprint" or reference.get("algorithm") != "sha256" \
            or reference.get("payloadFormat") != PAYLOAD_FORMAT:
        raise ComparisonError("指纹版本或格式不兼容")
    entries = reference.get("entries")
    if not isinstance(entries, list):
        raise ComparisonError("指纹缺少 entries 列表")
    names = set()
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"name", "size", "sha256"}:
            raise ComparisonError("指纹项结构无效")
        name, size, digest = entry["name"], entry["size"], entry["sha256"]
        if not isinstance(name, str) or not name or name in names:
            raise ComparisonError("指纹 ZIP 项名无效或重复")
        if SIGNING_ENTRY.fullmatch(name):
            raise ComparisonError("指纹错误地包含已排除的签名项：" + name)
        if type(size) is not int or size < 0 or not isinstance(digest, str) or not SHA256.fullmatch(digest):
            raise ComparisonError("指纹项大小或 SHA256 无效：" + name)
        names.add(name)
    if entries != sorted(entries, key=lambda entry: entry["name"]):
        raise ComparisonError("指纹 entries 未按项名排序")
    if type(reference.get("entryCount")) is not int or reference.get("entryCount") != len(entries) \
            or reference.get("payloadSha256") != payload_sha(entries):
        raise ComparisonError("指纹条目数量或 payloadSha256 校验失败")
    excluded = reference.get("excludedSigningEntries")
    if not isinstance(excluded, list) or any(not isinstance(name, str) or not SIGNING_ENTRY.fullmatch(name)
                                             for name in excluded) \
            or excluded != sorted(set(excluded)):
        raise ComparisonError("指纹中的签名项排除清单无效")
    source = reference.get("source")
    if not isinstance(source, dict) or not isinstance(source.get("fileSha256"), str) \
            or not SHA256.fullmatch(source["fileSha256"]):
        raise ComparisonError("指纹缺少有效的原 APK 文件 SHA256")
    return reference


def describe(value: dict) -> dict:
    return {"source": value["source"], "entryCount": value["entryCount"],
            "payloadSha256": value["payloadSha256"],
            "excludedSigningEntries": value["excludedSigningEntries"]}


def compare(left: dict, right: dict) -> dict:
    left_entries = {entry["name"]: entry for entry in left["entries"]}
    right_entries = {entry["name"]: entry for entry in right["entries"]}
    only_left = sorted(left_entries.keys() - right_entries.keys())
    only_right = sorted(right_entries.keys() - left_entries.keys())
    changed = []
    for name in sorted(left_entries.keys() & right_entries.keys()):
        first, second = left_entries[name], right_entries[name]
        if first != second:
            changed.append({"name": name,
                            "left": {"size": first["size"], "sha256": first["sha256"]},
                            "right": {"size": second["size"], "sha256": second["sha256"]}})
    return {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "apkComparison",
        "payloadFormat": PAYLOAD_FORMAT,
        "equal": not (only_left or only_right or changed),
        "sameFileSha256": left["source"]["fileSha256"] == right["source"]["fileSha256"],
        "left": describe(left), "right": describe(right),
        "differences": {"onlyLeft": only_left, "onlyRight": only_right, "changed": changed},
    }


def emit(value: dict, output: Path | None):
    data = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if output is not None:
        output.write_text(data, encoding="utf-8")
    print(data, end="")


def self_test() -> dict:
    checks = []
    with tempfile.TemporaryDirectory(prefix="supercard-apk-compare-") as temporary:
        root = Path(temporary)
        left, right = root / "left.apk", root / "right.apk"
        def make_zip(path, *, signature, metadata=b"same", duplicate=None, reverse=False):
            items = [("classes.dex", b"known-code"), ("META-INF/xposed/module.prop", b"staticScope=true"),
                     ("META-INF/build-info.txt", metadata), ("META-INF/services/example.RSA", b"not-a-signature"),
                     ("META-INF/MANIFEST.MF", signature), ("META-INF/CERT.SF", signature)]
            if reverse: items.reverse()
            with zipfile.ZipFile(path, "w") as archive:
                for name, content in items:
                    info = zipfile.ZipInfo(name, (2026 if reverse else 2020, 1, 1, 0, 0, 0))
                    archive.writestr(info, content, compress_type=zipfile.ZIP_DEFLATED if reverse else zipfile.ZIP_STORED)
                if duplicate:
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore", UserWarning)
                        archive.writestr(duplicate, b"duplicate")
        def invoke(argv):
            with contextlib.redirect_stdout(io.StringIO()) as stdout, contextlib.redirect_stderr(io.StringIO()):
                code = main(argv)
            return code, json.loads(stdout.getvalue())
        make_zip(left, signature=b"signature-one")
        make_zip(right, signature=b"signature-two", reverse=True)
        first, second = fingerprint(left), fingerprint(right)
        assert compare(first, second)["equal"] and first["source"]["fileSha256"] != second["source"]["fileSha256"]
        checks.append("签名、ZIP 顺序、时间和压缩不同，解压内容相同：通过")
        reference = root / "reference.json"
        reference.write_text(json.dumps(first), encoding="utf-8")
        code, result = invoke(["compare", str(right), "--reference", str(reference)])
        assert code == 0 and result["equal"]
        checks.append("参考指纹验证相同内容且退出码为 0：通过")
        shutil.copyfile(left, right)
        assert fingerprint(right)["payloadSha256"] == first["payloadSha256"]
        checks.append("外部 APK 文件路径不参与 payload 哈希：通过")
        make_zip(right, signature=b"signature-two", metadata=b"changed", reverse=True)
        code, result = invoke(["compare", str(left), str(right)])
        assert code == 1 and [item["name"] for item in result["differences"]["changed"]] == ["META-INF/build-info.txt"]
        checks.append("非签名 META-INF 元数据变化被检出且退出码为 1：通过")
        for duplicate in ("classes.dex", "META-INF/CERT.SF"):
            make_zip(right, signature=b"signature-two", duplicate=duplicate)
            code, result = invoke(["compare", str(left), str(right)])
            assert code == 2 and result["kind"] == "error"
        checks.append("普通项与被排除签名项重复均被拒绝：通过")
        first["payloadSha256"] = "0" * 64
        reference.write_text(json.dumps(first), encoding="utf-8")
        code, result = invoke(["compare", str(left), "--reference", str(reference)])
        assert code == 2 and result["kind"] == "error"
        checks.append("损坏参考指纹被拒绝：通过")
    return {"schemaVersion": SCHEMA_VERSION, "kind": "selfTest", "passed": True, "checks": checks}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="按 ZIP 项解压内容 SHA256 比较 APK；不验证签名或实机功能。")
    commands = parser.add_subparsers(dest="command", required=True)
    make = commands.add_parser("fingerprint", help="生成可供 CI 使用的本地参考指纹")
    make.add_argument("apk", type=Path)
    make.add_argument("--output", type=Path, help="同时写入 JSON 文件")
    make.add_argument("--quiet", action="store_true", help="不向 stderr 输出成功摘要")
    check = commands.add_parser("compare", help="比较两个 APK，或用参考指纹验证 APK")
    check.add_argument("left", type=Path, help="左侧 APK；使用 --reference 时为待验证 APK")
    check.add_argument("right", type=Path, nargs="?")
    check.add_argument("--reference", type=Path, help="fingerprint 子命令生成的参考 JSON")
    check.add_argument("--output", type=Path, help="同时写入 JSON 报告")
    check.add_argument("--quiet", action="store_true", help="不向 stderr 输出成功摘要")
    commands.add_parser("self-test", help="在两个临时 ZIP 上运行一致、差异及错误用例")
    args = parser.parse_args(argv)
    if args.command == "compare" and (args.right is None) == (args.reference is None):
        parser.error("compare 必须提供第二个 APK 或 --reference，二者只能选一个")
    try:
        if args.command == "self-test":
            result = self_test()
            emit(result, None)
            print("自测通过：" + str(len(result["checks"])) + " 组检查。", file=sys.stderr)
            return 0
        if args.command == "fingerprint":
            result = fingerprint(args.apk)
            emit(result, args.output)
            if not args.quiet:
                print(f"已生成指纹：{result['entryCount']} 项；payload SHA256：{result['payloadSha256']}。", file=sys.stderr)
            return 0
        if args.reference is not None:
            left, right = read_reference(args.reference), fingerprint(args.left)
        else:
            left, right = fingerprint(args.left), fingerprint(args.right)
        result = compare(left, right)
        if args.reference is not None:
            result["reference"] = {"path": args.reference.name,
                                   "fileSha256": hashlib.sha256(args.reference.read_bytes()).hexdigest()}
        emit(result, args.output)
        if result["equal"]:
            suffix = "文件本体 SHA256 也一致" if result["sameFileSha256"] else "文件本体 SHA256 不同"
            if not args.quiet:
                print("产物内容一致（排除指定签名项）；" + suffix + "。", file=sys.stderr)
            return 0
        differences = result["differences"]
        print(f"产物内容不一致：仅左侧 {len(differences['onlyLeft'])} 项，仅右侧 {len(differences['onlyRight'])} 项，"
              f"内容变化 {len(differences['changed'])} 项。", file=sys.stderr)
        return 1
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, zlib.error, NotImplementedError) as error:
        result = {"schemaVersion": SCHEMA_VERSION, "kind": "error", "equal": False,
                  "errorType": type(error).__name__, "error": str(error)}
        # An unwritable report path must still produce JSON on stdout and exit 2.
        try:
            emit(result, getattr(args, "output", None))
        except OSError:
            emit(result, None)
        print("对比失败：" + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    sys.exit(main())
