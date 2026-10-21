"""Run an unmodified official release in an isolated offline QA profile (fake model key only)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid
import urllib.request
import zipfile


def get(url):
    with urllib.request.urlopen(url, timeout=45) as response:
        return response.read()


def digest(path, algorithm="sha1"):
    return hashlib.new(algorithm, path.read_bytes()).hexdigest()


def artifact(cache, gradle, name, url, sha1=None):
    target = cache / "libraries" / name
    if target.is_file() and (not sha1 or digest(target) == sha1):
        return target
    candidates = list((gradle / "modules-2/files-2.1").glob("*/*/*/*/" + name))
    candidate = next((p for p in candidates if not sha1 or digest(p) == sha1), None)
    data = candidate.read_bytes() if candidate else get(url)
    if sha1 and hashlib.sha1(data).hexdigest() != sha1:
        raise ValueError("checksum mismatch: " + name)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    return target


def allowed(lib):
    result = not lib.get("rules")
    for rule in lib.get("rules", []):
        system = rule.get("os", {})
        if system.get("name", "windows") == "windows" and system.get("arch", "x86_64") in ("x86_64", "amd64"):
            result = rule["action"] == "allow"
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("java", "cache", "official", "fixture"):
        p.add_argument("--" + name, required=True, type=Path)
    p.add_argument("--prepare", action="store_true")
    for name in ("version", "server", "body", "owner"):
        p.add_argument("--" + name)
    p.add_argument("--output", type=Path)
    p.add_argument("--model-port", type=int)
    args = p.parse_args()
    cache = args.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    gradle = Path.home() / ".gradle/caches"
    info = json.loads((gradle / "fabric-loom/1.21.1/mojang_minecraft_info.json").read_text())
    profile_path = cache / "fabric-profile-0.18.1.json"
    if not profile_path.is_file():
        profile_path.write_bytes(get("https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.18.1/profile/json"))
    profile = json.loads(profile_path.read_text())
    classpath = []
    natives = cache / "natives"
    natives.mkdir(exist_ok=True)
    for lib in info["libraries"]:
        if not allowed(lib):
            continue
        download = lib["downloads"].get("artifact")
        if download:
            jar = artifact(cache, gradle, Path(download["path"]).name, download["url"], download["sha1"])
            classpath.append(jar)
            with zipfile.ZipFile(jar) as z:
                for entry in z.infolist():
                    if entry.filename.endswith(".dll"):
                        data = z.read(entry)
                        target = natives / Path(entry.filename).name
                        if not target.is_file() or target.read_bytes() != data:
                            target.write_bytes(data)
    for lib in profile["libraries"]:
        group, name, version = lib["name"].split(":")
        path = group.replace(".", "/") + f"/{name}/{version}/{name}-{version}.jar"
        classpath.append(artifact(cache, gradle, f"{name}-{version}.jar", lib["url"].rstrip("/") + "/" + path))
    client = gradle / "fabric-loom/1.21.1/minecraft-client.jar"
    if digest(client) != info["downloads"]["client"]["sha1"]:
        raise ValueError("cached Minecraft client does not match Mojang manifest")
    classpath.append(client)
    if args.prepare:
        print(f"Prepared {len(classpath)} runtime libraries from Mojang/Fabric metadata in {cache}")
        return

    expected = {"0.1.4": "2ab47063de770c9baf86d2beedeb4530f6bfa89f7f4dba30babcbcb22217d4a6",
                "0.1.4.1": "ccc0806fd04cf3404dbc39f7579a4d7c20cd1362d06dce358850f5fdaf711a41"}
    release = args.official / f"numen-fabric-1.21.1-{args.version}.jar"
    if digest(release, "sha256") != expected[args.version]:
        raise ValueError("official release checksum mismatch")
    args.output.mkdir(parents=True, exist_ok=True)
    game = args.output / "game"
    mods = game / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    for source in (release, args.fixture, cache / "fabric-api-0.116.7+1.21.1.jar"):
        (mods / source.name).write_bytes(source.read_bytes())
    if digest(cache / "fabric-api-0.116.7+1.21.1.jar", "sha256") != "08018cc48c97415a38016a00dbd5a2c7a460ba6b7a051690a8cb3dbb5e8482a4":
        raise ValueError("Fabric API checksum mismatch")
    providers = game / "config/numen/providers.json"
    providers.parent.mkdir(parents=True, exist_ok=True)
    providers.write_text(json.dumps({"entries": [{"id": "fixture-model", "name": "Local QA endpoint",
        "provider": "openai", "model": "e2e-fake", "api_key": "e2e-key", "base_url": f"http://127.0.0.1:{args.model_port}/v1",
        "reasoning_effort": "", "proxy": "", "ctx": 64000}]}), encoding="utf-8")
    (game / "options.txt").write_text("onboardAccessibility:false\npauseOnLostFocus:false\nrenderDistance:2\nsimulationDistance:2\nmaxFps:30\nenableVsync:false\nsoundCategory_master:0.0\nlang:zh_cn\n", encoding="utf-8")
    md5 = bytearray(hashlib.md5(("OfflinePlayer:" + args.owner).encode()).digest())
    md5[6] = (md5[6] & 15) | 48
    md5[8] = (md5[8] & 63) | 128
    command = [str(args.java), "-Xmx1024M", "-Xms256M", "-Dfabric.development=false",
        "-Djava.library.path=" + str(natives), "-Dorg.lwjgl.system.SharedLibraryExtractPath=" + str(natives),
        "-Dnumen.test.output=" + str(args.output.resolve()), "-Dnumen.test.server=" + args.server,
        "-Dnumen.test.body=" + args.body, "-cp", os.pathsep.join(map(str, classpath)), profile["mainClass"],
        "--username", args.owner, "--uuid", uuid.UUID(bytes=bytes(md5)).hex, "--accessToken", "0", "--version", "1.21.1",
        "--gameDir", str(game.resolve()), "--assetsDir", str(gradle / "fabric-loom/assets"),
        "--assetIndex", "1.21.1-17", "--userType", "legacy", "--width", "960", "--height", "540"]
    result = subprocess.run(command, cwd=game, timeout=240)
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
