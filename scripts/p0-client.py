#!/usr/bin/env python3
"""Prepare or explicitly launch isolated official 26.3 clients for the P0 probe.

This never reads a launcher installation, account store, access token, or EULA.
The only connect target is the separate loopback development server.
Preparing the clients does not establish that a visual acceptance test passed.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
from pathlib import Path
import platform
import plistlib
import re
import shlex
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / ".local/vanilla/26.3"
JAVA_POINTER = ROOT / ".local/toolchains/java-home.txt"
FALLBACK_JAVA_HOME = ROOT / ".local/toolchains/jdk-25.0.4.1+1/Contents/Home"
VERSION_SHA1 = "702fe59163c6ee6578607daa85811d9bc9c7cc40"
VERSION_URL = f"https://piston-meta.mojang.com/v1/packages/{VERSION_SHA1}/26.3.json"
OFFICIAL_HOSTS = {
    "piston-meta.mojang.com", "piston-data.mojang.com",
    "libraries.minecraft.net", "resources.download.minecraft.net",
}
PROFILES = ("HRProbeA", "HRProbeB")
SERVER = "127.0.0.1:25585"
SERVERS = (SERVER, "127.0.0.1:25586")
RUNTIME_DIRECTORIES = ("game", "home", "tmp", "cache", "config", "data", "natives/java", "natives/jna", "natives/lwjgl", "natives/netty")


def java_executable() -> tuple[Path, str]:
    """Match p0-gradle.sh precedence, retaining the tested local JDK fallback."""
    for key in ("HARDRUSH_JAVA_HOME", "JAVA_HOME"):
        if os.environ.get(key):
            java_home, source = Path(os.environ[key]).expanduser(), key
            break
    else:
        if JAVA_POINTER.is_file() and (pointer := JAVA_POINTER.read_text().strip()):
            java_home, source = Path(pointer).expanduser(), ".local/toolchains/java-home.txt"
        else:
            java_home, source = FALLBACK_JAVA_HOME, "tested-local-fallback"
    if not java_home.is_absolute():
        java_home = ROOT / java_home
    return (java_home / "bin/java").resolve(), source


def confined(path: Path, anchor: Path = BASE) -> Path:
    """Reject symlink/path traversal outside the project cache or selected profile."""
    try:
        BASE.resolve().relative_to(ROOT)
        anchor.resolve().relative_to(BASE.resolve())
        path.resolve().relative_to(anchor.resolve())
    except ValueError:
        raise SystemExit(f"Refusing a path outside the isolated client area: {path}") from None
    return path


def runtime_environment(profile: str) -> dict[str, str]:
    environment = os.environ.copy()
    for key in ("CLASSPATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        environment.pop(key, None)
    directory = profile_path(profile)
    for key, child in (("TMPDIR", "tmp"), ("XDG_CACHE_HOME", "cache"),
                       ("XDG_CONFIG_HOME", "config"), ("XDG_DATA_HOME", "data")):
        environment[key] = str(confined(directory / child, directory))
    return environment


def digest(path: Path) -> str:
    confined(path)
    sha = hashlib.sha1()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def valid(path: Path, info: dict) -> bool:
    confined(path)
    return (path.is_file()
            and ("size" not in info or path.stat().st_size == info["size"])
            and digest(path) == info["sha1"])


def download(path: Path, info: dict) -> int:
    confined(path)
    if valid(path, info):
        return 0
    url = info["url"]
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in OFFICIAL_HOSTS:
        raise ValueError(f"Not an official HTTPS artifact URL: {url}")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = confined(path.with_name(path.name + ".part"))
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "HardRush-P0-local-probe/1"})
            with urllib.request.urlopen(request, timeout=90) as response, temporary.open("wb") as out:
                if urllib.parse.urlparse(response.url).hostname not in OFFICIAL_HOSTS:
                    raise ValueError("Artifact redirected outside official hosts")
                while chunk := response.read(1024 * 1024):
                    out.write(chunk)
            if not valid(temporary, info):
                raise ValueError(f"Artifact checksum mismatch: {path}")
            temporary.replace(path)
            return path.stat().st_size
        except Exception:
            temporary.unlink(missing_ok=True)
            if attempt == 2:
                raise
            time.sleep(attempt + 1)
    raise AssertionError("unreachable")


def allowed(rules: list[dict], features: dict | None = None) -> bool:
    features = features or {}
    result = not rules
    for rule in rules:
        target = rule.get("os", {})
        if target.get("name", "osx") != "osx":
            continue
        if "arch" in target and not re.fullmatch(target["arch"], "arm64"):
            continue
        if "version" in target and not re.fullmatch(target["version"], platform.release()):
            continue
        if any(features.get(key, False) != value for key, value in rule.get("features", {}).items()):
            continue
        result = rule["action"] == "allow"
    return result


def metadata() -> dict:
    version_path = BASE / "version.json"
    if not valid(version_path, {"sha1": VERSION_SHA1}):
        raise SystemExit("Missing or modified official version metadata; run prepare first.")
    return json.loads(version_path.read_text())


def libraries(version: dict) -> list[dict]:
    return [library for library in version["libraries"] if allowed(library.get("rules", []))]


def artifacts(version: dict, include_assets: bool = True) -> list[tuple[Path, dict]]:
    result = [(BASE / "client.jar", version["downloads"]["client"])]
    result.extend((BASE / "libraries" / lib["downloads"]["artifact"]["path"],
                   lib["downloads"]["artifact"]) for lib in libraries(version))
    log_file = version["logging"]["client"]["file"]
    result.append((BASE / "logging" / log_file["id"], log_file))
    index = version["assetIndex"]
    result.append((BASE / "assets/indexes" / f"{index['id']}.json", index))
    if include_assets:
        asset_index = json.loads(result[-1][0].read_text())
        # Multiple logical resources may refer to the same content-addressed file.
        unique = {item["hash"]: item for item in asset_index["objects"].values()}
        for sha, item in unique.items():
            result.append((BASE / "assets/objects" / sha[:2] / sha, {
                "sha1": sha,
                "size": item["size"],
                "url": f"https://resources.download.minecraft.net/{sha[:2]}/{sha}",
            }))
    return result


def profile_path(profile: str) -> Path:
    if profile not in PROFILES:
        raise ValueError("Only the two isolated probe profiles are allowed")
    return confined(BASE / "profiles" / profile)


def native_files(version: dict):
    for library in libraries(version):
        if not library["name"].endswith(":natives-macos-arm64"):
            continue
        jar = BASE / "libraries" / library["downloads"]["artifact"]["path"]
        with zipfile.ZipFile(jar) as archive:
            for entry in archive.infolist():
                if not entry.is_dir() and entry.filename.endswith((".dylib", ".jnilib")):
                    yield Path(entry.filename).name, archive.read(entry)


def prepare_profile(version: dict, profile: str) -> int:
    directory = profile_path(profile)
    for subdirectory in RUNTIME_DIRECTORIES:
        confined(directory / subdirectory, directory).mkdir(parents=True, exist_ok=True)
    extracted = 0
    for name, contents in native_files(version):
        confined(directory / "natives/java" / name, directory).write_bytes(contents)
        extracted += 1
    return extracted


def prepare(workers: int) -> None:
    confined(BASE).mkdir(parents=True, exist_ok=True)
    transferred = download(BASE / "version.json", {"url": VERSION_URL, "sha1": VERSION_SHA1})
    version = metadata()
    index = version["assetIndex"]
    transferred += download(BASE / "assets/indexes" / f"{index['id']}.json", index)
    pending = artifacts(version)
    print(f"Preparing {len(pending)} verified artifacts for {', '.join(PROFILES)}", flush=True)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(download, path, info) for path, info in pending]
        for done, future in enumerate(as_completed(futures), start=1):
            transferred += future.result()
            if done % 250 == 0 or done == len(futures):
                print(f"Verified {done}/{len(futures)}; downloaded this run {transferred / 1_000_000:.1f} MB", flush=True)
    native_counts = {profile: prepare_profile(version, profile) for profile in PROFILES}
    java, java_source = java_executable()
    manifest = {
        "minecraft": version["id"], "metadataUrl": VERSION_URL,
        "metadataSha1": VERSION_SHA1, "java": str(java), "javaSource": java_source,
        "javaPrecedence": ["HARDRUSH_JAVA_HOME", "JAVA_HOME", ".local/toolchains/java-home.txt", "tested-local-fallback"],
        "platform": "Darwin arm64", "mainClass": version["mainClass"],
        "clientMods": [], "resourcePacks": [], "server": SERVER,
        "profiles": list(PROFILES), "extractedNativeCount": native_counts,
        "artifactBytes": sum(path.stat().st_size for path, _ in pending),
        "downloadedBytesThisRun": transferred,
        "artifacts": [{"path": str(path.relative_to(BASE)), "sha1": info["sha1"],
                       "size": path.stat().st_size, "url": info["url"]} for path, info in pending],
        "validation": "Prepared and SHA-1 verified only; no window opened or visual acceptance performed.",
    }
    confined(BASE / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({key: manifest[key] for key in (
        "minecraft", "profiles", "artifactBytes", "downloadedBytesThisRun", "extractedNativeCount", "validation"
    )}, indent=2))


def verify() -> None:
    version = metadata()
    index = version["assetIndex"]
    index_path = BASE / "assets/indexes" / f"{index['id']}.json"
    if not valid(index_path, index):
        raise SystemExit("Asset index missing or modified; run prepare.")
    files = artifacts(version)
    failures = [str(path.relative_to(BASE)) for path, info in files if not valid(path, info)]
    if failures:
        raise SystemExit("Missing or modified artifacts:\n" + "\n".join(failures))
    java, java_source = java_executable()
    if not java.is_file() or not os.access(java, os.X_OK):
        raise SystemExit(f"Java selected by {java_source} is missing or not executable: {java}")
    for profile in PROFILES:
        directory = profile_path(profile)
        game = directory / "game"
        for current, folders, entries in os.walk(directory, followlinks=False):
            for name in folders + entries:
                confined(Path(current) / name, directory)
        if not game.is_dir() or not (directory / "home").is_dir():
            raise SystemExit(f"Isolated profile directories are missing: {directory}; run prepare.")
        for name, contents in native_files(version):
            native = directory / "natives/java" / name
            if not native.is_file() or native.read_bytes() != contents:
                raise SystemExit(f"Extracted native missing or modified: {native}; run prepare.")
        for folder in (game / "mods", game / "resourcepacks"):
            if folder.is_dir() and any(folder.iterdir()):
                raise SystemExit(f"Profile is no longer vanilla: {folder}")
    print(f"Verified {len(files)} official artifacts; both isolated profiles contain no mods/resource packs.")


def arguments(version: dict, category: str, replacements: dict, features: dict) -> list[str]:
    result = []
    for item in version["arguments"][category]:
        if isinstance(item, dict):
            if not allowed(item.get("rules", []), features):
                continue
            item = item["value"]
        for argument in [item] if isinstance(item, str) else item:
            def substitute(match: re.Match) -> str:
                name = match.group(1)
                if name not in replacements:
                    raise ValueError(f"Unsupported launch placeholder: {name}")
                return replacements[name]
            result.append(re.sub(r"\$\{([^}]+)\}", substitute, argument))
    return result


def command(profile: str, server: str = SERVER) -> list[str]:
    if server not in SERVERS:
        raise SystemExit("The vanilla probes may connect only to the two isolated loopback servers.")
    version = metadata()
    directory = profile_path(profile)
    offline_uuid = uuid.UUID(bytes=hashlib.md5(f"OfflinePlayer:{profile}".encode()).digest(), version=3)
    classpath = [str(BASE / "libraries" / lib["downloads"]["artifact"]["path"]) for lib in libraries(version)]
    classpath.append(str(BASE / "client.jar"))
    replacements = {
        "auth_player_name": profile, "version_name": version["id"],
        "game_directory": str(directory / "game"), "assets_root": str(BASE / "assets"),
        "assets_index_name": version["assetIndex"]["id"], "auth_uuid": str(offline_uuid),
        "auth_access_token": "0", "clientid": "", "auth_xuid": "", "version_type": version["type"],
        "natives_directory": str(directory / "natives"), "launcher_name": "HardRushP0",
        "launcher_version": "1", "classpath": os.pathsep.join(classpath),
        "resolution_width": "960", "resolution_height": "600",
        "quickPlayMultiplayer": server,
    }
    features = {"has_custom_resolution": True, "is_quick_play_multiplayer": True}
    log = version["logging"]["client"]
    log_arg = log["argument"].replace("${path}", str(BASE / "logging" / log["file"]["id"]))
    java, _ = java_executable()
    return ([str(java), "-Xms512M", "-Xmx2G", "-XX:+UseZGC", f"-Duser.home={directory / 'home'}",
             f"-Djava.io.tmpdir={directory / 'tmp'}", log_arg]
            + arguments(version, "jvm", replacements, features)
            + [version["mainClass"], "--offlineDeveloperMode"]
            + arguments(version, "game", replacements, features))


def prepare_app(profile: str, server: str = SERVER) -> None:
    """Build a native macOS bundle without executing its official Minecraft Main."""
    verify()
    java, java_source = java_executable()
    jpackage = java.with_name("jpackage")
    if not jpackage.is_file():
        raise SystemExit(f"The selected Java home must include jpackage: {jpackage}")
    directory = profile_path(profile)
    for child in RUNTIME_DIRECTORIES:
        confined(directory / child, directory).mkdir(parents=True, exist_ok=True)
    app_name = "HardRushProbe" + profile[-1]
    bundle_id = "dev.hardrush.p0." + profile.lower()
    destination = confined(BASE / "apps")
    destination.mkdir(parents=True, exist_ok=True)
    bundle = confined(destination / (app_name + ".app"))
    if bundle.exists():
        raise SystemExit(f"App bundle already exists; refusing to replace a possibly running app: {bundle}")
    input_directory = confined(BASE / "app-input")
    input_directory.mkdir(parents=True, exist_ok=True)
    launch = command(profile, server)
    classpath_offset = launch.index("-cp")
    sources = [Path(item) for item in launch[classpath_offset + 1].split(os.pathsep)]
    for source in sources:
        target = confined(input_directory / source.name)
        if target.exists():
            if digest(target) != digest(source):
                raise SystemExit(f"Stale or conflicting app input: {target}")
        else:
            os.link(source, target)
    main_class_offset = classpath_offset + 2
    java_options = launch[1:classpath_offset]
    # JNI launchers accept the equals form of long JVM options unambiguously.
    if "--add-exports" in java_options:
        offset = java_options.index("--add-exports")
        java_options[offset:offset + 2] = ["--add-exports=" + java_options[offset + 1]]
    package = [str(jpackage), "--type", "app-image", "--name", app_name,
               "--dest", str(destination), "--input", str(input_directory),
               "--main-jar", "client.jar", "--main-class", launch[main_class_offset],
               "--runtime-image", str(java.parent.parent), "--app-version", "1.0.0",
               "--mac-package-identifier", bundle_id, "--mac-package-name", profile,
               "--description", "HardRush P0 isolated official vanilla Minecraft probe"]
    for option in java_options:
        package.extend(("--java-options", json.dumps(option)))
    # JSON double quoting preserves spaces and empty values for jpackage's tokenizer.
    game_arguments = launch[main_class_offset + 1:]
    package.extend(("--arguments", " ".join(json.dumps(item) for item in game_arguments)))
    log = confined(BASE / (app_name + "-jpackage.log"))
    print(f"Building native app bundle {app_name}; no client will be launched.", flush=True)
    with log.open("w") as output:
        completed = subprocess.run(package, cwd=directory / "game", env=runtime_environment(profile),
                                   stdout=output, stderr=subprocess.STDOUT, check=False)
    if completed.returncode:
        raise SystemExit(f"jpackage failed with exit code {completed.returncode}; inspect {log}")
    plist_path = confined(bundle / "Contents/Info.plist")
    with plist_path.open("rb") as stream:
        plist = plistlib.load(stream)
    if plist["CFBundleIdentifier"] != bundle_id:
        raise SystemExit("Generated app bundle identity does not match the isolated probe")
    bundled_jars = bundle / "Contents/app"
    for source in sources:
        if digest(bundled_jars / source.name) != digest(source):
            raise SystemExit(f"Bundled official artifact changed: {source.name}")
    manifest = {
        "profile": profile, "appName": app_name, "bundleIdentifier": bundle_id,
        "bundle": str(bundle), "mainClass": launch[main_class_offset],
        "minecraft": "26.3", "javaSource": java_source, "runtimeCopiedFrom": str(java.parent.parent),
        "officialJarCount": len(sources), "allBundledJarHashesVerified": True,
        "clientMods": [], "resourcePacks": [], "server": server,
        "gameDirectory": str(directory / "game"), "offlineDeveloperMode": True,
        "validation": "App image generated and artifact hashes checked; this command did not launch a client.",
    }
    confined(BASE / (app_name + "-app.json")).write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="action", required=True)
    prepare_parser = subparsers.add_parser("prepare", help="Download official resources; do not launch.")
    prepare_parser.add_argument("--workers", type=int, choices=range(1, 17), default=8)
    subparsers.add_parser("verify", help="Verify all official artifact hashes and profile purity.")
    for action in ("show", "launch", "prepare-app", "launch-app"):
        child = subparsers.add_parser(action, help="Show, prepare, or explicitly run one isolated offline probe client.")
        child.add_argument("profile", choices=PROFILES)
        child.add_argument("--server", choices=SERVERS, default=SERVER,
                           help="Use 25585 for the P0 server or 25586 for the isolated debug server.")
    args = parser.parse_args()
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise SystemExit("This P0 launcher is restricted to Darwin arm64.")
    if args.action == "prepare":
        prepare(args.workers)
    elif args.action == "verify":
        verify()
    elif args.action == "show":
        print(shlex.join(command(args.profile, args.server)))
    elif args.action == "prepare-app":
        prepare_app(args.profile, args.server)
    else:
        verify()
        # No authenticated credentials are discovered, inherited from a launcher, or synthesized.
        # The token is the literal "0", usable only with the local development server's offline mode.
        directory = profile_path(args.profile)
        for subdirectory in RUNTIME_DIRECTORIES:
            confined(directory / subdirectory, directory).mkdir(parents=True, exist_ok=True)
        environment = runtime_environment(args.profile)
        print(f"Launching {args.profile} toward isolated loopback probe {args.server}; no account session is used.", flush=True)
        if args.action == "launch-app":
            name = "HardRushProbe" + args.profile[-1]
            bundle = confined(BASE / "apps" / (name + ".app"))
            executable = confined(bundle / "Contents/MacOS" / name)
            if not executable.is_file():
                raise SystemExit(f"Native launcher is missing; run prepare-app {args.profile} first.")
            # Explicit game arguments override jpackage's baked-in defaults;
            # keep the native app identity/window while selecting the local server.
            vanilla = command(args.profile, args.server)
            main_offset = vanilla.index("-cp") + 2
            for source in (Path(item) for item in vanilla[main_offset - 1].split(os.pathsep)):
                if digest(bundle / "Contents/app" / source.name) != digest(source):
                    raise SystemExit(f"Native app official artifact changed: {source.name}")
            launch = [str(executable), *vanilla[main_offset + 1:]]
        else:
            launch = command(args.profile, args.server)
        # Explicit cwd also keeps relative native-launcher and Log4j files in the profile.
        sys.exit(subprocess.call(launch, cwd=directory / "game", env=environment))


if __name__ == "__main__":
    main()
