"""Build a dual-loader installer, freezing Fabric to its existing paired release."""
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
    parser.add_argument("--version", default="0.1.0-20261011")
    parser.add_argument("--forge-bundle", type=Path, default=Path("dist/SekiroCraft-Forge-1.20.1-preview.6.zip"))
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
    forge_jar = "sekiro-minecraft-passthrough-forge-0.1.0-forge-preview.6.jar"
    forge_native = "forge-dinput8.dll"
    # These are the exact preview.6 bytes tested by the user, before the local
    # Boss HUD position experiment. Never use a newly built dist DLL here.
    forge_hashes = {
        "dinput8.dll": "00368afc9073e42fe3ea2b73bc919f1789b9a51f6ffd5dd81ead2a2ac074ae92",
        forge_jar: "13bd5c99562d2fa8fa62e2593bad356e44bacd7c54dccb6fc7dee64b74f36ce2",
    }
    forge_path = args.forge_bundle if args.forge_bundle.is_absolute() else root / args.forge_bundle
    with zipfile.ZipFile(forge_path) as archive:
        forge_receipt = json.loads(archive.read("forge-verification.json"))
        if forge_receipt.get("protocol", forge_receipt.get("abi")) != 3:
            raise RuntimeError("Forge bundle protocol must be v3")
        for name, expected_hash in forge_hashes.items():
            data = archive.read(name)
            if digest(data).lower() != expected_hash:
                raise RuntimeError("Forge payload differs from the tested preview.6 pair: " + name)
            files[forge_native if name == "dinput8.dll" else name] = data
    profile = args.fabric_profile.read_bytes()
    meta = json.loads(profile)
    if meta.get("inheritsFrom") != "1.20.1" or meta.get("mainClass") != "net.fabricmc.loader.impl.launch.knot.KnotClient" or "net.fabricmc:fabric-loader:0.19.5" not in [lib["name"] for lib in meta["libraries"]]:
        raise RuntimeError("Unexpected upstream Fabric profile")
    files["fabric-profile.json"] = profile
    files["简易教程.txt"] = (root / "installer/简易教程.txt").read_text(encoding="utf-8-sig").replace("\r\n", "\n").replace("\n", "\r\n").encode("utf-8-sig")
    info = {"version": version + "-setup-preview", "sourceCommit": verification["sourceCommit"],
            "gameSha256": verification["gameSha256"], "jarName": jar_name, "apiName": api_name,
            "forgeJarName": forge_jar, "forgeNativeName": forge_native,
            "fabricSourceTag": args.tag, "fabricUnchanged": True, "forgeVersion": "preview.6",
            "forgeGameTested": args.forge_game_tested, "bossHudPositionIncluded": False,
            "gameTested": args.forge_game_tested, "flickerResolved": False, "files": {name: digest(data) for name, data in files.items()}}
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
    archive_path = output / ("SekiroCraft-一键安装-" + version + ".zip")
    write_zip(archive_path, assets)
    (output / "SHA256SUMS.txt").write_text("".join(digest(path.read_bytes()).lower() + "  " + path.name + "\n" for path in (executable, archive_path, tutorial)), encoding="utf-8")
    receipt = dict(info, installerGameTestPending=True, installerPublished=False, gamesLaunched=False, installerSourceUncommitted=True,
                   payloadSha256=digest(payload.read_bytes()), executableSha256=digest(executable.read_bytes()),
                   archiveSha256=digest(archive_path.read_bytes()), isolatedInstallTests="passed")
    (build / "verification.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), "executable": executable.name, "zip": archive_path.name, "installerTestPending": True}, ensure_ascii=False))


if __name__ == "__main__":
    main()
