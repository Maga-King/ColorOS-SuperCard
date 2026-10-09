"""Optionally reproduce committed DEX assets; compare first, write only with --write."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


PROJECT = Path(__file__).resolve().parent.parent
ASSETS = PROJECT / "app/src/main/assets"
LIBRARIES = PROJECT / "tools/asset-patcher-libs"
DEX_NAME = re.compile(r"classes[0-9]*\.dex")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def executable(name: str, java_home: Path | None) -> Path:
    suffix = ".exe" if os.name == "nt" else ""
    if java_home is not None:
        candidate = java_home / "bin" / (name + suffix)
        if not candidate.is_file():
            raise FileNotFoundError(f"JAVA_HOME has no {name}: {candidate}")
        return candidate
    located = shutil.which(name)
    if not located:
        raise FileNotFoundError(f"Set JAVA_HOME or add {name} to PATH (JDK 17 or newer)")
    return Path(located)


def run(*arguments: object) -> None:
    result = subprocess.run([str(argument) for argument in arguments], check=False)
    result.check_returncode()


def dex_hashes(archive: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            if not DEX_NAME.fullmatch(entry.filename):
                raise ValueError(f"Unexpected non-DEX entry in {archive.name}: {entry.filename}")
            if entry.filename in result:
                raise ValueError(f"Duplicate DEX entry in {archive.name}")
            result[entry.filename] = hashlib.sha256(source.read(entry)).hexdigest()
    if "classes.dex" not in result:
        raise ValueError(f"Missing classes.dex: {archive}")
    return result


def compare(existing: Path, rebuilt: Path) -> dict:
    before, after = dex_hashes(existing), dex_hashes(rebuilt)
    names = sorted(set(before) | set(after))
    differences = [name for name in names if before.get(name) != after.get(name)]
    return {
        "asset": existing.name,
        "existingArchiveSha256": sha256(existing),
        "rebuiltArchiveSha256": sha256(rebuilt),
        "dexBytesIdentical": not differences,
        "differingDexEntries": differences,
        "existingDexSha256": before,
        "rebuiltDexSha256": after,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", choices=("bundled", "jadx"), default="bundled",
                        help="Default: verified original org.jf libraries; jadx is experimental")
    parser.add_argument("--jadx-jar", type=Path, default=os.environ.get("JADX_JAR"),
                        help="jadx 1.5.5 all-in-one jar; defaults to JADX_JAR")
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"),
                        help="JDK 17+ directory; defaults to JAVA_HOME, then PATH")
    parser.add_argument("--out", type=Path,
                        help="New or empty output directory; default is an OS temporary directory")
    parser.add_argument("--write", action="store_true",
                        help="Replace both committed assets only if every DEX SHA-256 matches")
    args = parser.parse_args()

    java_home = args.java_home.expanduser().resolve(strict=True) if args.java_home else None
    java, javac = executable("java", java_home), executable("javac", java_home)
    library_hashes: dict[str, str] = {}
    if args.backend == "bundled":
        manifest = json.loads((LIBRARIES / "manifest.json").read_text(encoding="utf-8"))
        jars = []
        for item in manifest["jars"]:
            name = item["name"]
            if Path(name).name != name or not name.endswith(".jar"):
                raise ValueError("Invalid bundled library name")
            jar = LIBRARIES / name
            actual_hash = sha256(jar)
            if actual_hash != item["sha256"]:
                raise ValueError(f"Bundled patch library checksum mismatch: {name}")
            library_hashes[name] = actual_hash
            jars.append(jar)
    else:
        if args.jadx_jar is None:
            parser.error("The jadx backend requires JADX_JAR or --jadx-jar")
        jadx = args.jadx_jar.expanduser().resolve(strict=True)
        with zipfile.ZipFile(jadx) as jar:
            if "com/android/tools/smali/dexlib2/dexbacked/DexBackedDexFile.class" not in jar.namelist():
                raise ValueError("JADX_JAR does not contain the required relocated dexlib2")
        jars = [jadx]
        library_hashes[jadx.name] = sha256(jadx)
    dependency_classpath = os.pathsep.join(str(jar) for jar in jars)
    source = PROJECT / "tools/PatchPlugin.java"
    jobs = [
        (ASSETS / "supercard-resources.apk", ASSETS / "supercard-code.zip", True),
        (ASSETS / "memory-plugin.apk", ASSETS / "memory-code.zip", False),
    ]
    for required in [source, *jars, *(path for input_apk, existing, _ in jobs
                                    for path in (input_apk, existing))]:
        if not required.is_file():
            raise FileNotFoundError(required)
    # The original org.jf build tolerates forApi(36). The relocated library in jadx 1.5.5
    # does not map that API to a writable header. Use the exact verified input DEX version,
    # as MergeDex.java does; do not guess a platform API or change the bytecode format.
    for input_apk, _, _ in jobs if args.backend == "jadx" else []:
        with zipfile.ZipFile(input_apk) as archive:
            dex_names = [name for name in archive.namelist() if DEX_NAME.fullmatch(name)]
            if not dex_names:
                raise ValueError(f"No input DEX files: {input_apk}")
            for name in dex_names:
                with archive.open(name) as dex:
                    if dex.read(8) != b"dex\n039\0":
                        raise ValueError(f"Only verified DEX 039 inputs are supported: {input_apk.name}/{name}")

    if args.out:
        out = args.out.expanduser().resolve()
        if out == ASSETS.resolve() or (out.exists() and any(out.iterdir())):
            raise ValueError("--out must be a new or empty directory, never the assets directory")
        out.mkdir(parents=True, exist_ok=True)
    else:
        out = Path(tempfile.mkdtemp(prefix="supercard-assets-"))
    print(f"Temporary output (retained for inspection): {out}", flush=True)

    report: dict = {
        "status": "building",
        "writesRequested": args.write,
        "assetsWritten": False,
        "patchSourceSha256": sha256(source),
        "backend": args.backend,
        "librarySha256": library_hashes,
        "temporarySourceAdaptations": (["relocated dexlib2 imports", "verified DEX 039 opcodes"]
                                       if args.backend == "jadx" else []),
        "inputs": {path.name: sha256(path) for path, _, _ in jobs},
        "comparison": [],
        "note": "ZIP timestamps are ignored; every uncompressed DEX byte must match.",
    }
    report_file = out / "asset-verification.json"
    try:
        relocated = out / "source/PatchPlugin.java"
        relocated.parent.mkdir()
        text = source.read_text(encoding="utf-8")
        if args.backend == "jadx":
            if "org.jf.dexlib2" not in text:
                raise ValueError("PatchPlugin source no longer has the expected original imports")
            text = text.replace("org.jf.dexlib2", "com.android.tools.smali.dexlib2")
            if "Opcodes.forApi(36)" not in text:
                raise ValueError("PatchPlugin source no longer has the expected opcode factory")
            text = text.replace("Opcodes.forApi(36)", "Opcodes.forDexVersion(39)")
        relocated.write_text(text, encoding="utf-8", newline="\n")
        classes = out / "classes"
        classes.mkdir()
        run(javac, "-encoding", "UTF-8", "--release", "17", "-cp", dependency_classpath,
            "-d", classes, relocated)
        classpath = os.pathsep.join((str(classes), dependency_classpath))
        for input_apk, existing, density in jobs:
            output = out / existing.name
            references = out / (existing.stem + "-references.txt")
            arguments = [java, "-cp", classpath, "PatchPlugin", input_apk, output, references]
            if density:
                arguments.append("--pay-density")
            run(*arguments)
            comparison = compare(existing, output)
            report["comparison"].append(comparison)
            label = "IDENTICAL DEX" if comparison["dexBytesIdentical"] else "DIFFERENT DEX"
            print(f"{existing.name}: {label}; entries={len(comparison['existingDexSha256'])}", flush=True)

        identical = all(item["dexBytesIdentical"] for item in report["comparison"])
        report["status"] = "identical" if identical else "mismatch"
        report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if not identical:
            print("Existing assets were preserved. The selected toolchain produced different DEX bytes.",
                  file=sys.stderr)
            print(f"Evidence: {report_file}")
            return 2

        if args.write:
            # Recheck the complete original archives before any write, to catch concurrent edits.
            for (_, existing, _), comparison in zip(jobs, report["comparison"]):
                if sha256(existing) != comparison["existingArchiveSha256"]:
                    raise RuntimeError("A committed asset changed during verification; refusing to write")
            staged: list[tuple[Path, Path]] = []
            try:
                for _, existing, _ in jobs:
                    descriptor, name = tempfile.mkstemp(prefix=".verified-", suffix=".zip", dir=ASSETS)
                    os.close(descriptor)
                    temporary = Path(name)
                    staged.append((temporary, existing))
                    shutil.copyfile(out / existing.name, temporary)
                for temporary, existing in staged:
                    os.replace(temporary, existing)
                report["assetsWritten"] = True
            finally:
                for temporary, _ in staged:
                    temporary.unlink(missing_ok=True)
            report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print("Verified assets replaced. Update archive checksum manifests if present.")
        else:
            print("Existing assets were preserved. Use --write only to replace verified equivalents.")
        print(f"Evidence: {report_file}")
        return 0
    except BaseException as failure:
        report["status"] = "failed"
        report["failureType"] = type(failure).__name__
        report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        raise


if __name__ == "__main__":
    sys.exit(main())
