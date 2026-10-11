"""Build a dual-loader installer from verified bundles with separate gameplay records."""
import argparse
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest().upper()


def write_zip(path, files):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            archive.writestr(name, data)
    with zipfile.ZipFile(path) as archive:
        if archive.testzip():
            raise RuntimeError("ZIP CRC failed")
        for name, data in files.items():
            if digest(archive.read(name)) != digest(data):
                raise RuntimeError("ZIP content changed: " + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", default="v0.1.0-20261010")
    parser.add_argument("--version", default="0.1.1-20261011")
    parser.add_argument("--forge-bundle", type=Path, required=True)
    parser.add_argument("--fabric-bundle", type=Path, help="New verified Fabric bundle; otherwise retain the source release")
    parser.add_argument("--fabric-game-tested", action="store_true", help="Explicit user confirmation for the new Fabric pair")
    parser.add_argument("--forge-game-tested", action="store_true", help="Record explicit user confirmation of this Forge pair")
    parser.add_argument("--fabric-profile", type=Path, required=True)
    parser.add_argument("--game-exe", type=Path, default=Path("D:/Steam/steamapps/common/Sekiro/sekiro.exe"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    release = root / "dist/releases" / args.tag
    version = args.version
    folder = "SekiroCraft-Passthrough-" + args.tag.removeprefix("v")
    with zipfile.ZipFile(release / (folder + ".zip")) as archive:
        base = folder + "/dist/SekiroCraft-Passthrough/"
        verification = json.loads(archive.read(folder + "/build/verification.json"))
        if not verification.get("verified") or verification.get("protocol") != 3:
            raise RuntimeError("Release lacks a verified paired v3 build")
        expected = {item["name"].replace("\\", "/"): item["sha256"].upper() for item in verification["files"]}
        jar_name = "sekiro-minecraft-passthrough-0.1.0.jar"
        api_name = "fabric-api-0.92.12+1.20.1.jar"
        files = {}
        for name in ("dinput8.dll", "sekirobridge.ini", "MinHook-LICENSE.txt", "ImGui-LICENSE.txt", "SekiroTool-LICENSE.txt",
                     "THIRD_PARTY_NOTICES.md", "minecraft/" + jar_name, "minecraft/" + api_name):
            data = archive.read(base + name)
            if digest(data) != expected.get(name):
                raise RuntimeError("Unverified release bytes: " + name)
            files[Path(name).name] = data
    forge_native = "forge-dinput8.dll"
    def verified_bundle(path, receipt_name, jar_prefix):
        path = path if path.is_absolute() else root / path
        with zipfile.ZipFile(path) as archive:
            receipt = json.loads(archive.read(receipt_name))
            if receipt.get("protocol", receipt.get("abi")) != 3 or receipt.get("bossHudPositionIncluded", False):
                raise RuntimeError("Expected a verified v3 bundle without the local Boss HUD experiment")
            jars = [name for name in archive.namelist() if name.startswith(jar_prefix) and name.endswith(".jar") and "/" not in name]
            if len(jars) != 1:
                raise RuntimeError("Expected one loader-specific bridge JAR")
            jar = jars[0]
            selected = {name: archive.read(name) for name in ("dinput8.dll", jar)}
            from io import BytesIO
            with zipfile.ZipFile(BytesIO(selected[jar])) as mod:
                is_forge = jar_prefix.endswith("forge-")
                if (is_forge and "META-INF/mods.toml" not in mod.namelist()) or (not is_forge and "fabric.mod.json" not in mod.namelist()):
                    raise RuntimeError("Bridge JAR belongs to the wrong loader")
                if not is_forge and json.loads(mod.read("fabric.mod.json"))["id"] != "sekirobridge":
                    raise RuntimeError("Unexpected Fabric mod identity")
                if digest(mod.read("native/windows-x64/sekirobridge-jni.dll")).lower() != receipt["jniSha256"].lower():
                    raise RuntimeError("Embedded JNI differs from loader verification")
            for name, expected_hash in {"dinput8.dll": receipt["pairedNativeSha256"], jar: receipt["jarSha256"]}.items():
                if digest(selected[name]).lower() != expected_hash.lower():
                    raise RuntimeError("Payload differs from verification receipt: " + name)
            if digest(selected["dinput8.dll"]).lower() != "00368afc9073e42fe3ea2b73bc919f1789b9a51f6ffd5dd81ead2a2ac074ae92":
                raise RuntimeError("Native payload differs from the approved preview.6 core")
            if "boss_hud" in archive.read("sekirobridge.ini").decode("utf-8-sig"):
                raise RuntimeError("Unreleased Boss HUD config included")
            return receipt, jar, selected
    forge_path = args.forge_bundle if args.forge_bundle.is_absolute() else root / args.forge_bundle
    forge_receipt, forge_jar, forge_files = verified_bundle(forge_path, "forge-verification.json", "sekiro-minecraft-passthrough-forge-")
    if args.forge_game_tested and not forge_receipt.get("userGameTested"):
        raise RuntimeError("Forge user confirmation missing from bundle receipt")
    for name, data in forge_files.items():
        files[forge_native if name == "dinput8.dll" else name] = data
    fabric_receipt = verification
    fabric_version = "0.1.0"
    if args.fabric_bundle:
        fabric_receipt, new_jar, fabric_files = verified_bundle(args.fabric_bundle, "fabric-verification.json", "sekiro-minecraft-passthrough-")
        if args.fabric_game_tested and not fabric_receipt.get("userGameTested"):
            raise RuntimeError("Fabric user confirmation missing from bundle receipt")
        files.pop(jar_name)
        jar_name = new_jar
        files.update(fabric_files)
        fabric_version = fabric_receipt["version"]
    profile = args.fabric_profile.read_bytes()
    meta = json.loads(profile)
    if meta.get("inheritsFrom") != "1.20.1" or meta.get("mainClass") != "net.fabricmc.loader.impl.launch.knot.KnotClient" or "net.fabricmc:fabric-loader:0.19.5" not in [lib["name"] for lib in meta["libraries"]]:
        raise RuntimeError("Unexpected upstream Fabric profile")
    files["fabric-profile.json"] = profile
    files["简易教程.txt"] = (root / "installer/简易教程.txt").read_text(encoding="utf-8-sig").replace("\r\n", "\n").replace("\n", "\r\n").encode("utf-8-sig")
    fabric_tested = args.fabric_game_tested if args.fabric_bundle else True
    info = {"version": version, "sourceCommit": forge_receipt["sourceCommit"],
            "gameSha256": verification["gameSha256"], "jarName": jar_name, "apiName": api_name,
            "forgeJarName": forge_jar, "forgeNativeName": forge_native,
            "fabricSourceTag": args.tag if not args.fabric_bundle else fabric_receipt.get("releaseTag", "local-preview"),
            "fabricUnchanged": not bool(args.fabric_bundle), "fabricVersion": fabric_version, "forgeVersion": forge_receipt.get("version", "preview.6"),
            "fabricGameTested": fabric_tested,
            "forgeGameTested": args.forge_game_tested, "bossHudPositionIncluded": False,
            "gameTested": args.forge_game_tested and fabric_tested, "flickerResolved": False, "files": {name: digest(data) for name, data in files.items()}}
    files["bundle.json"] = (json.dumps(info, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    build = root / "build/installer"
    output = root / "dist/installer" / version
    build.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    payload = build / "payload.zip"
    write_zip(payload, files)
    compiler = Path("C:/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe")
    if not compiler.is_file():
        raise RuntimeError("Windows .NET Framework C# compiler is unavailable")
    executable = output / ("SekiroCraft-Setup-" + version + ".exe")
    references = ["System.dll", "System.Core.dll", "System.Windows.Forms.dll", "System.Drawing.dll", "System.Web.Extensions.dll", "System.IO.Compression.dll", "System.IO.Compression.FileSystem.dll"]
    common = [str(compiler), "/nologo", "/platform:x64", "/optimize+", "/codepage:65001"] + ["/reference:" + name for name in references]
    subprocess.check_call(common + ["/target:winexe", "/out:" + str(executable), "/win32manifest:" + str(root / "installer/setup.manifest"),
                                   "/resource:" + str(payload) + ",SekiroCraft.payload.zip", str(root / "installer/SetupEngine.cs"), str(root / "installer/SetupWindow.cs")])
    test_executable = build / "installer-tests.exe"
    subprocess.check_call(common + ["/target:exe", "/out:" + str(test_executable), str(root / "installer/SetupEngine.cs"), str(root / "tests/setup_engine_tests.cs")])
    subprocess.check_call([str(test_executable), str(payload), str(root / ".cache/setup-fixtures"), str(args.game_exe)])
    tutorial = output / "先读我-简易教程.txt"
    tutorial.write_bytes(files["简易教程.txt"])
    assets = {executable.name: executable.read_bytes(), tutorial.name: tutorial.read_bytes()}
    for name in ("MinHook-LICENSE.txt", "ImGui-LICENSE.txt", "SekiroTool-LICENSE.txt", "THIRD_PARTY_NOTICES.md"):
        assets["licenses/" + name] = files[name]
    archive_path = output / ("SekiroCraft-Installer-" + version + ".zip")
    write_zip(archive_path, assets)
    (output / "SHA256SUMS.txt").write_text("".join(digest(path.read_bytes()).lower() + "  " + path.name + "\n" for path in (executable, archive_path, tutorial)), encoding="utf-8")
    receipt = dict(info, installerGameTestPending=True, installerPublished=False, gamesLaunched=False, installerSourceUncommitted=True,
                   payloadSha256=digest(payload.read_bytes()), executableSha256=digest(executable.read_bytes()),
                   archiveSha256=digest(archive_path.read_bytes()), isolatedInstallTests="passed")
    (build / "verification.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), "executable": executable.name, "zip": archive_path.name, "installerTestPending": True}, ensure_ascii=False))


if __name__ == "__main__":
    main()
