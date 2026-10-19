#!/usr/bin/env python3
"""Collect local watch NFC evidence; never write settings or send NCI commands."""
import argparse
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="ADB serial, usually IP:connection-port")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--pull-code", action="store_true", help="Also copy accessible NFC APK/framework JAR")
    args = parser.parse_args()
    if args.output.exists() and any(args.output.iterdir()):
        parser.error("Output directory must be empty; existing evidence will not be overwritten")

    def adb(*command):
        return subprocess.run(["adb", "-s", args.serial, *command],
                              capture_output=True, text=True, timeout=45)

    state = adb("get-state")
    if state.returncode or state.stdout.strip() != "device":
        parser.error("ADB device is not connected: " + state.stderr.strip())
    args.output.mkdir(parents=True, exist_ok=True)
    commands = []

    def collect(filename, *command):
        result = adb(*command)
        (args.output / filename).write_text(result.stdout + "\nSTDERR:\n" + result.stderr)
        commands.append({"command": list(command), "exit_code": result.returncode,
                         "file": filename})
        return result.stdout if result.returncode == 0 else ""

    properties = {}
    for key in ["ro.product.manufacturer", "ro.product.model", "ro.product.device",
                "ro.hardware", "ro.build.fingerprint", "ro.build.version.sdk",
                "ro.build.version.release", "ro.build.version.security_patch"]:
        properties[key] = adb("shell", "getprop", key).stdout.strip()
    (args.output / "firmware.json").write_text(json.dumps(properties, indent=2, ensure_ascii=False))
    collect("features.txt", "shell", "pm", "list", "features")
    collect("nfc-service.txt", "shell", "dumpsys", "nfc")
    collect("emula-report.txt", "exec-out", "run-as", "dev.dunno.emula.watchtest",
            "cat", "files/nfc-diagnostics.txt")
    code_paths = {"/system/framework/framework.jar", "/system/framework/services.jar"}
    packages = {"com.android.nfc", "com.samsung.android.nfc"}
    discovered = collect("nfc-packages.txt", "shell", "pm", "list", "packages", "nfc")
    for line in discovered.splitlines():
        if line.startswith("package:"):
            package = line.removeprefix("package:")
            if package and all(c.isalnum() or c in "_." for c in package):
                packages.add(package)
    for package in sorted(packages):
        paths = collect(package + "-paths.txt", "shell", "pm", "path", package)
        collect(package + "-package.txt", "shell", "dumpsys", "package", package)
        for line in paths.splitlines():
            path = line.removeprefix("package:")
            if line.startswith("package:") and path.startswith(("/apex/", "/system/",
                                                                      "/system_ext/", "/product/")):
                code_paths.add(path)
    for directory in ["/system/framework", "/system_ext/framework", "/product/framework",
                      "/apex/com.android.nfc/javalib"]:
        listing = collect(directory.strip("/").replace("/", "-") + ".txt",
                          "shell", "ls", directory)
        for name in listing.splitlines():
            if "nfc" in name.lower() and name.endswith(".jar") and "/" not in name:
                code_paths.add(directory + "/" + name)
    if args.pull_code:
        code_dir = args.output / "code"
        code_dir.mkdir()
        for index, path in enumerate(sorted(code_paths)):
            target = code_dir / f"{index:02d}-{Path(path).name}"
            result = adb("pull", path, str(target))
            commands.append({"command": ["pull", path, str(target)],
                             "exit_code": result.returncode,
                             "output": result.stdout + result.stderr})
    (args.output / "commands.json").write_text(json.dumps(commands, indent=2, ensure_ascii=False))
    print(f"Evidence saved to {args.output.resolve()}; no settings or NFC configuration changed.")


if __name__ == "__main__":
    main()
