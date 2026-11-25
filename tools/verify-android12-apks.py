"""发布前检查 APK 的安装门槛、release 类型和固定证书；只处理公开信息。"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def certificate_digest(output):
    values = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", output)
    assert len(values) == 1, "APK must have exactly one signing certificate"
    return values[0].lower()


def main():
    expected = Path("docs/android12-signing.sha256").read_text().strip().lower()
    assert re.fullmatch(r"[0-9a-f]{64}", expected), "invalid pinned certificate digest"
    assert certificate_digest("Signer #1 certificate SHA-256 digest: " + "A" * 64) == "a" * 64
    dist = Path(sys.argv[1] if len(sys.argv) > 1 else "dist")
    build_tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    apks = sorted(dist.glob("*.apk"))
    assert apks, "no APKs to verify"
    for apk in apks:
        signature = subprocess.check_output([
            str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs",
            "--min-sdk-version", "31", "--max-sdk-version", "32", str(apk),
        ], text=True)
        assert certificate_digest(signature) == expected, f"unexpected signing key: {apk.name}"
        badging = subprocess.check_output([str(build_tools / "aapt2"), "dump", "badging", str(apk)], text=True)
        print("APK badging:", apk.name, "\n" + badging, flush=True)
        (dist / (apk.name + ".badging.txt")).write_text(badging)
        assert re.search(r"^sdkVersion:'31'$", badging, re.M), f"APK does not support API 31: {apk.name}"
        assert "application-debuggable" not in badging, f"debug APK must not be released: {apk.name}"
        assert "package: name='dev.dsh.miuixbrowser'" in badging, f"unexpected package: {apk.name}"
        version = os.environ.get("APP_VERSION_CODE")
        if version:
            assert f"versionCode='{version}'" in badging, f"unexpected version: {apk.name}"
        print(f"Verified Android 12/12L release signature: {apk.name}")
    shutil.copyfile("docs/android12-signing.sha256", dist / "SIGNING_CERT_SHA256.txt")


if __name__ == "__main__":
    main()
