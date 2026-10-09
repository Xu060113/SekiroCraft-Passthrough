"""Package already verified DLL/JAR bytes plus the recorded runtime, without running games."""
import argparse
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path


def sha(data, algorithm="sha256"):
    return hashlib.new(algorithm, data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def write_zip(path, files):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            archive.writestr(name, data)
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise RuntimeError("Archive CRC verification failed")
        for name, data in files.items():
            if sha(archive.read(name)) != sha(data):
                raise RuntimeError(f"Archive content differs: {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fabric-api", type=Path, required=True)
    parser.add_argument("--tag", default="v0.1.0-20261008")
    parser.add_argument("--authorized-on", help="Date of the user's publication authorization (YYYY-MM-DD)")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=root):
        raise RuntimeError("Commit the authorized source and documents before packaging")
    verified = json.loads((root / "build/verification.json").read_text(encoding="utf-8-sig"))
    if not verified.get("verified") or verified.get("protocol") != 3:
        raise RuntimeError("A verified paired protocol v3 build is required")
    runtime_bytes = (root / "config/minecraft-runtime.json").read_bytes()
    runtime = json.loads(runtime_bytes.decode("utf-8-sig"))
    api = next(mod for mod in runtime["mods"] if mod["id"] == "fabric-api")
    api_bytes = args.fabric_api.read_bytes()
    if sha(api_bytes) != api["sha256"]:
        raise RuntimeError("Fabric API differs from the recorded upstream version")
    package = root / "dist/SekiroCraft-Passthrough"
    expected = {entry["name"].replace("\\", "/"): entry["sha256"].lower()
                for entry in verified["files"]}
    assets = {}
    for name in ("dinput8.dll", "sekirobridge.ini", "MinHook-LICENSE.txt", "ImGui-LICENSE.txt",
                 "SekiroTool-LICENSE.txt", "minecraft/sekiro-minecraft-passthrough-0.1.0.jar"):
        data = (package / name).read_bytes()
        if sha(data) != expected.get(name):
            raise RuntimeError(f"Asset differs from verified build: {name}")
        assets[name] = data
    assets[f"minecraft/{api['filename']}"] = api_bytes
    for name in ("README.md", "THIRD_PARTY_NOTICES.md", "CHANGELOG.md"):
        assets[name] = (root / name).read_bytes()
    for document in sorted((root / "docs").glob("*.md")):
        assets[f"docs/{document.name}"] = document.read_bytes()
    for name in ("switch-sekiro.ps1", "prepare-minecraft.ps1", "update-installed.ps1"):
        assets[f"scripts/{name}"] = (root / "scripts" / name).read_bytes()
    assets["config/minecraft-runtime.json"] = runtime_bytes
    manifest = dict(verified, sourceCommit=commit, sourceDirty=False,
                    verifiedBuildBaseCommit=verified["sourceCommit"],
                    verifiedBuildWasDirty=verified["sourceDirty"],
                    runtime=runtime, releaseTag=args.tag,
                    userPublicationAuthorizedOn=args.authorized_on or runtime["recordedOn"])
    manifest["files"] = [{"name": name.replace("/", "\\"), "sha256": sha(data).upper()}
                         for name, data in sorted(assets.items())]
    folder = "SekiroCraft-Passthrough-" + args.tag.removeprefix("v")
    bundle = {f"{folder}/dist/SekiroCraft-Passthrough/{name}": data for name, data in assets.items()}
    for name, data in assets.items():
        if name.startswith(("docs/", "scripts/", "config/")) or name in ("README.md", "THIRD_PARTY_NOTICES.md", "CHANGELOG.md"):
            bundle[f"{folder}/{name}"] = data
        if name.endswith("-LICENSE.txt"):
            bundle[f"{folder}/licenses/{name}"] = data
    bundle[f"{folder}/build/verification.json"] = json_bytes(manifest)
    index = {
        "formatVersion": 1, "game": "minecraft", "versionId": args.tag.removeprefix("v"),
        "name": "SekiroCraft Passthrough", "summary": "Minecraft 1.20.1 Fabric companion for Sekiro",
        "dependencies": {"minecraft": runtime["minecraft"], "fabric-loader": runtime["fabricLoader"]},
        "files": [{"path": "mods/" + mod["filename"],
                   "hashes": {key: mod[key] for key in ("sha1", "sha512")},
                   "env": {"client": "required", "server": "unsupported"},
                   "downloads": [mod["download"]], "fileSize": mod["bytes"]} for mod in runtime["mods"]],
    }
    jar_name = "sekiro-minecraft-passthrough-0.1.0.jar"
    jar = assets[f"minecraft/{jar_name}"]
    pack = {"modrinth.index.json": json_bytes(index), f"client-overrides/mods/{jar_name}": jar,
            "client-overrides/SekiroCraft-SETUP.md": assets["docs/MODPACK.md"]}
    output = root / "dist/releases" / args.tag
    output.mkdir(parents=True, exist_ok=True)
    write_zip(output / f"{folder}.zip", bundle)
    write_zip(output / f"{folder}.mrpack", pack)
    (output / jar_name).write_bytes(jar)
    checksums = "".join(f"{sha(file.read_bytes())}  {file.name}\n"
                        for file in sorted(output.iterdir()) if file.suffix in (".zip", ".mrpack", ".jar"))
    (output / "SHA256SUMS.txt").write_text(checksums, encoding="utf-8", newline="\n")
    print(json.dumps({"tag": args.tag, "sourceCommit": commit, "output": str(output),
                      "dllSha256": sha(assets["dinput8.dll"]), "jarSha256": sha(jar),
                      "files": [file.name for file in sorted(output.iterdir())]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
