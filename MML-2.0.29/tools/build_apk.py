#!/usr/bin/env python3
"""Build a dependency-free Android Java application with official SDK tools.

The generated APK is signed for local development. Set ANDROID_SDK_ROOT or
ANDROID_HOME to an SDK containing platform android-35 and build-tools 35.0.0.
A local development key is generated only when the selected key does not exist.
Never distribute a private signing key with the source or APK.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ElementTree


def run(args: list[str], *, env: dict[str, str] | None = None) -> None:
    subprocess.run(args, check=True, env=env)


def java8_options(compiler: list[str], platform: Path, lambda_stubs: Path, work: Path) -> list[str]:
    """Use release 8 normally; narrowly handle a reduced JDK without ct.sym.

    The probe is independent of application source. Only javac's explicit
    unsupported-release diagnostic selects the Android bootclasspath fallback;
    compiler crashes, source errors and unrelated tool failures remain failures.
    Actual application compilation is never retried with different API checks.
    """
    with tempfile.TemporaryDirectory(prefix="java8-probe-", dir=work) as directory:
        target = Path(directory)
        source = target / "MmlJava8Probe.java"
        source.write_text("final class MmlJava8Probe { Runnable value = () -> {}; }\n", encoding="ascii")
        result = subprocess.run(
            compiler + ["--release", "8", "-d", str(target), str(source)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if result.returncode == 0:
            return ["--release", "8"]
        if not re.search(r"(?m)^error: release version 8 not supported\s*$", result.stderr):
            sys.stderr.write(result.stdout + result.stderr)
            raise subprocess.CalledProcessError(result.returncode, result.args)
        if not lambda_stubs.is_file():
            raise FileNotFoundError("The official SDK core-lambda-stubs.jar is required: " + str(lambda_stubs))
        # android.jar intentionally omits javac's lambda metafactory symbols.
        # The official build-tools companion supplies exactly that compiler API.
        bootclasspath = str(lambda_stubs) + os.pathsep + str(platform)
        options = ["-source", "8", "-target", "8", "-bootclasspath", bootclasspath]
        print("Compiler has no Java 8 platform signatures; using the official Android "
              "android.jar and core-lambda-stubs.jar bootclasspath with Java 8 "
              "source and bytecode targets.", flush=True)
        run(compiler + options + ["-d", str(target), str(source)])
        return options


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--keystore", type=Path,
                        default=Path.home() / ".android" / "debug.keystore")
    parser.add_argument("--alias", default="androiddebugkey")
    parser.add_argument("--name")
    parser.add_argument("--instrumentation", action="store_true",
                        help="Build app/src/androidTest against build/manual/work/classes.")
    parser.add_argument("--app-classes", type=Path)
    args = parser.parse_args()
    project = args.project.resolve()
    source = project / "app" / "src" / ("androidTest" if args.instrumentation else "main")
    manifest = project / "app" / "src" / "main" / "AndroidManifest.xml"
    try:
        version = ElementTree.parse(manifest).getroot().get(
            "{http://schemas.android.com/apk/res/android}versionName", "development")
    except (OSError, ElementTree.ParseError) as error:
        parser.error("Cannot read the application manifest: %s" % error)
    if not args.name and (not version or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-" for c in version)):
        parser.error("Supply --name when manifest versionName contains filename-unsafe characters.")
    sdk_text = args.sdk or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk_text:
        parser.error("Set ANDROID_SDK_ROOT or supply --sdk.")
    sdk = Path(sdk_text).resolve()
    platform = sdk / "platforms" / "android-35" / "android.jar"
    candidates = [sdk / "build-tools" / "35.0.0", sdk / "build-tools" / "android-15"]
    build_tools = next((p for p in candidates if (p / "aapt2").is_file()), None)
    if build_tools is None or not platform.is_file():
        parser.error("Official Android platform 35 and build-tools 35.0.0 are required.")
    if not (source / "AndroidManifest.xml").is_file():
        parser.error("Source set must contain AndroidManifest.xml.")
    output = (args.output or project / "build" / ("instrumentation" if args.instrumentation else "manual")).resolve()
    output.mkdir(parents=True, exist_ok=True)
    work = output / "work"
    if work.exists():
        shutil.rmtree(work)
    for name in ("generated", "classes", "dex"):
        (work / name).mkdir(parents=True, exist_ok=True)
    compiled = work / "resources.zip"
    if (source / "res").is_dir():
        run([str(build_tools / "aapt2"), "compile", "--dir", str(source / "res"),
             "-o", str(compiled)])
    link = [str(build_tools / "aapt2"), "link", "-o", str(work / "resources.apk"),
            "--manifest", str(source / "AndroidManifest.xml"), "--java", str(work / "generated"),
            "-I", str(platform), "--min-sdk-version", "33", "--target-sdk-version", "35",
            "--auto-add-overlay"]
    if (source / "assets").is_dir():
        link.extend(["-A", str(source / "assets")])
    if compiled.exists():
        link.append(str(compiled))
    run(link)
    def java_sources(root: Path) -> list[Path]:
        # Workspace sync may stage files in hidden temporary directories.
        return sorted(p for p in root.rglob("*.java")
                      if p.is_file() and not any(part.startswith(".")
                                                 for part in p.relative_to(root).parts))

    sources = java_sources(source / "java")
    sources += java_sources(work / "generated")
    if not sources:
        parser.error("No Java sources found.")
    compiler = [shutil.which("javac")] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
    classpath = str(platform)
    app_classes = (args.app_classes or project / "build" / "manual" / "work" / "classes").resolve()
    if args.instrumentation:
        if not app_classes.is_dir():
            parser.error("Build main APK first, or supply --app-classes.")
        classpath += os.pathsep + str(app_classes)
    language_options = java8_options(compiler, platform, build_tools / "core-lambda-stubs.jar", work)
    run(compiler + language_options + ["-encoding", "UTF-8", "-g", "-Xlint:all", "-classpath",
                    classpath, "-d", str(work / "classes")] + [str(p) for p in sources])
    classes = work / "classes.jar"
    with zipfile.ZipFile(classes, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted((work / "classes").rglob("*.class")):
            archive.write(path, path.relative_to(work / "classes").as_posix())
    dex = [str(build_tools / "d8"), "--min-api", "33", "--lib", str(platform),
           "--output", str(work / "dex")]
    if args.instrumentation:
        dex.extend(["--classpath", str(app_classes.parent / "classes.jar")])
    run(dex + [str(classes)])
    unaligned = work / "unsigned-unaligned.apk"
    shutil.copy2(work / "resources.apk", unaligned)
    with zipfile.ZipFile(unaligned, "a", compression=zipfile.ZIP_STORED) as archive:
        for path in sorted((work / "dex").glob("*.dex")):
            archive.write(path, path.name)
    aligned = work / "unsigned-aligned.apk"
    run([str(build_tools / "zipalign"), "-f", "4", str(unaligned), str(aligned)])
    key = args.keystore.expanduser().resolve()
    key.parent.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.setdefault("MML_KEYSTORE_PASSWORD", "android")
    env.setdefault("MML_KEY_PASSWORD", env["MML_KEYSTORE_PASSWORD"])
    if not key.exists():
        run(["keytool", "-genkeypair", "-keystore", str(key), "-alias", args.alias,
             "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
             "-dname", "CN=MML development", "-storetype", "JKS",
             "-storepass:env", "MML_KEYSTORE_PASSWORD", "-keypass:env", "MML_KEY_PASSWORD"], env=env)
        key.chmod(0o600)
    suffix = "tests" if args.instrumentation else "development"
    apk = output / (args.name or "MML-%s-%s.apk" % (version, suffix))
    run([str(build_tools / "apksigner"), "sign", "--ks", str(key), "--ks-key-alias", args.alias,
         "--ks-pass", "env:MML_KEYSTORE_PASSWORD", "--key-pass", "env:MML_KEY_PASSWORD",
         "--out", str(apk), str(aligned)], env=env)
    run([str(build_tools / "apksigner"), "verify", "--verbose", str(apk)])
    print(str(apk))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        print("Build failed at tool exit status %d." % error.returncode, file=sys.stderr)
        raise SystemExit(error.returncode)
