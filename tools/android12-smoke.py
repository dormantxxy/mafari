"""只允许在 GitHub Actions 的 API 31 模拟器运行，绝不改动用户真机。"""
import http.server
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time

PACKAGE = "dev.dsh.miuixbrowser"


def adb(*args, check=True, input=None):
    result = subprocess.run(["adb", *args], input=input, capture_output=True, text=True, timeout=60, check=check)
    return result.stdout.strip()


def alive():
    assert adb("shell", "pidof", PACKAGE, check=False), "Mafari process exited"
    crash = adb("logcat", "-d", "-b", "crash")
    assert PACKAGE not in crash, crash


def launch(*extra):
    result = adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", *extra)
    print(result)
    assert "Status: ok" in result, "MainActivity did not start"
    time.sleep(8)
    alive()


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true", "CI emulator only; never run on a user's device"
    assert shutil.which("adb"), "adb is required"
    assert adb("shell", "getprop", "ro.kernel.qemu") == "1", "emulator required"
    assert adb("shell", "getprop", "ro.build.version.sdk") == "31", "Android 12 / API 31 required"
    apk = Path(sys.argv[1])
    assert apk.is_file() and "-lite-x86_64.apk" in apk.name, "lite x86_64 release APK required"
    print(adb("root"))
    adb("wait-for-device")
    print(adb("install", "-r", str(apk)))
    adb("logcat", "-c")
    server = None
    try:
        launch()  # 没有 RuntimeShader 的 Android 12 必须能显示首次引导页。
        for activity in ["AutofillAuthActivity", "AutofillPickActivity"]:
            result = adb("shell", "am", "start", "-W", "-n", PACKAGE + "/." + activity)
            assert "Status: ok" in result, activity + " did not start"
            time.sleep(1)
            alive()  # 缺失 extras 应安全取消；旧版 typed Parcelable 调用会在 API 31 崩溃。
        adb("shell", "am", "force-stop", PACKAGE)
        # 仅在一次性的 CI 模拟器设置引导完成的测试夹具，继续覆盖真正的 BrowserTab/WebView 初始化。
        uid = adb("shell", "stat", "-c", "%u", "/data/user/0/" + PACKAGE)
        assert re.fullmatch(r"\d+", uid) and int(uid) >= 10000
        directory = "/data/user/0/" + PACKAGE + "/shared_prefs"
        filename = directory + "/mafari_onboarding.xml"
        fixture = '<?xml version="1.0" encoding="utf-8"?><map><int name="onboarding_version" value="1" /></map>\n'
        adb("shell", f"mkdir -p {directory} && cat > {filename} && chown {uid}:{uid} {directory} {filename} && chmod 660 {filename} && restorecon -RF {directory}", input=fixture)

        requested = threading.Event()
        class Page(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                self.wfile.write(b"<!doctype html><title>Android 12 smoke</title><h1>Mafari Android 12</h1>")
                if self.path == "/android12":
                    requested.set()
            def log_message(self, *_):
                pass
        server = http.server.HTTPServer(("127.0.0.1", 0), Page)
        port = str(server.server_port)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        adb("reverse", "tcp:" + port, "tcp:" + port)
        launch("-a", "android.intent.action.VIEW", "-d", "http://127.0.0.1:" + port + "/android12")
        assert requested.wait(30), "WebView did not request the local smoke page"
        alive()
        Path("dist/ANDROID12_SMOKE.txt").write_text("PASS: API 31 lite x86_64 release first launch, autofill entry cancellation, and local WebView page request; no app crash.\n")
        print("PASS: Android 12 first launch and WebView page request")
    finally:
        if server:
            server.shutdown()
        Path("dist/ANDROID12_LOGCAT.txt").write_text(adb("logcat", "-d", "-t", "2000"))
        image = subprocess.check_output(["adb", "exec-out", "screencap", "-p"], timeout=30)
        Path("dist/ANDROID12_SCREEN.png").write_bytes(image)


if __name__ == "__main__":
    main()
