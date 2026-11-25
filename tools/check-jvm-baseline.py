"""完整运行 JVM 测试，与原版同条件对照；旧失败公开保留，新失败禁止发布。"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BASE_COMMIT = "90a06764b0ef5d5eb882ad63e9835972a31ce2d6"
RESULTS = "app/build/test-results/testLiteArm64ReleaseUnitTest"


def read_cases(directory):
    files = sorted(Path(directory).glob("TEST-*.xml"))
    assert files, f"Missing JUnit reports: {directory}"
    cases = {}
    for file in files:
        for case in ET.parse(file).getroot().iter("testcase"):
            key = case.attrib["classname"] + "#" + case.attrib["name"]
            assert key not in cases, "Duplicate JUnit case: " + key
            failure = case.find("failure")
            if failure is None:
                failure = case.find("error")
            cases[key] = {
                "failure": failure.attrib.get("type", "failure") if failure is not None else None,
                "skipped": case.find("skipped") is not None,
            }
    assert cases, "Empty JUnit reports"
    return cases


def compare(current, baseline):
    assert baseline.keys() <= current.keys(), "Baseline test cases were removed"
    newly_skipped = [key for key, case in current.items() if case["skipped"] and not baseline.get(key, {}).get("skipped", False)]
    assert not newly_skipped, "Newly skipped tests: " + repr(newly_skipped)
    regressions = [key for key, case in current.items() if case["failure"] and case["failure"] != baseline.get(key, {}).get("failure")]
    assert not regressions, "New or changed JVM failures: " + repr(regressions)
    return [key for key, case in current.items() if case["failure"]]


def run(command, cwd, **kwargs):
    return subprocess.run(command, cwd=cwd, **kwargs)


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true", "CI only; no baseline builds on the user's device"
    assert shutil.which("git") and shutil.which("bash")
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    command = ["bash", "./gradlew", ":app:testLiteArm64ReleaseUnitTest", "-Pandroid12ApiCheck=true", "--stacktrace", "--console=plain"]
    current_run = run(command, ROOT)
    current = read_cases(ROOT / RESULTS)
    assert len(current) >= 500, "Full JVM suite was not run"
    compatibility = {key: case for key, case in current.items() if key.startswith(("dev.dsh.miuixbrowser.StreamsTest#", "dev.dsh.miuixbrowser.Android12CompatibilityTest#"))}
    assert len(compatibility) == 10 and all(not case["failure"] and not case["skipped"] for case in compatibility.values()), "Android 12 compatibility tests must all pass"

    base = ROOT / "原版测试基线"
    assert not base.exists(), "Use a fresh baseline worktree"
    run(["git", "fetch", "--depth=1", "origin", BASE_COMMIT], ROOT, check=True)
    run(["git", "worktree", "add", "--detach", str(base), "FETCH_HEAD"], ROOT, check=True)
    assert subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=base, text=True).strip() == BASE_COMMIT
    run(["bash", "tools/miuix-sync.sh"], base, check=True)

    # 原版 release 没启用单测；只加相同测试组件及同一处可编译的 messenger 夹具，不改生产代码/断言。
    gradle = base / "app/build.gradle.kts"
    code = gradle.read_text()
    assert code.startswith("plugins {")
    code = "import com.android.build.api.variant.HostTestBuilder\n\n" + code
    anchor = "}\n\n\ndependencies {\n"
    assert code.count(anchor) == 1
    block = """}

androidComponents {
    beforeVariants(selector().all()) { builder ->
        if (builder.name == "liteArm64Release") {
            builder.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
        }
    }
}


dependencies {
"""
    gradle.write_text(code.replace(anchor, block, 1))
    fixture = base / "app/src/test/java/dev/dsh/miuixbrowser/ext/StorageApiTest.kt"
    code = fixture.read_text()
    old = 'RuntimeApi { _, target, msg -> JSONObject().put("echo", msg).put("target", target ?: JSONObject.NULL) }'
    new = 'RuntimeApi(RuntimeMessenger { _, target, msg -> JSONObject().put("echo", msg).put("target", target ?: JSONObject.NULL) })'
    assert code.count(old) == 1
    fixture.write_text(code.replace(old, new, 1))

    baseline_run = run(command, base)
    baseline = read_cases(base / RESULTS)
    assert len(baseline) >= 500, "Full baseline suite was not run"
    old_failures = compare(current, baseline)
    summary = {
        "baseline_commit": BASE_COMMIT,
        "baseline_tests": len(baseline),
        "baseline_failures": sum(bool(case["failure"]) for case in baseline.values()),
        "current_tests": len(current),
        "current_failures": len(old_failures),
        "android12_tests_passed": len(compatibility),
        "new_failures": 0,
        "known_failure_tests": old_failures,
        "current_gradle_exit": current_run.returncode,
        "baseline_gradle_exit": baseline_run.returncode,
    }
    (dist / "JVM_BASELINE.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    for source, target in [(ROOT / RESULTS, dist / "JVM_CURRENT"), (base / RESULTS, dist / "JVM_UPSTREAM")]:
        shutil.copytree(source, target)
    report = f"JVM 基线对照：适配版 {len(current)} 项 / {len(old_failures)} 项旧失败；原版 {len(baseline)} 项 / {summary['baseline_failures']} 项失败。新增 Android 12 的 10 项测试全部通过，无新增失败。旧失败没有修复或掩盖，详见 JVM_BASELINE.json。\n"
    (dist / "JVM_BASELINE.txt").write_text(report)
    print(report)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as output:
            output.write("## JVM 原版对照\n\n" + report)


if __name__ == "__main__":
    main()
