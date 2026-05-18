#!/usr/bin/env python3
"""
Run mem-analyze (JAR) and hprof_parser.py on the same HPROF, then diff comparable metrics.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from typing import Any

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MEM_ANALYZE_ROOT = os.path.dirname(SCRIPT_DIR)
DEFAULT_JAR = os.path.join(MEM_ANALYZE_ROOT, "build/libs/mem-analyze-1.0.0-all.jar")
DEFAULT_PARSER_DIR = os.path.expanduser(
    "~/workspaces/Android-App-Memory-Analysis/tools"
)
EXPORT_PY = os.path.join(SCRIPT_DIR, "hprof_parser_export_compare.py")


def rel_pct_diff(a: int, b: int) -> float:
    if a == 0 and b == 0:
        return 0.0
    denom = max(abs(a), abs(b), 1)
    return abs(a - b) / denom * 100.0


def check_count(label: str, py_val: int, jar_val: int, tol_pct: float, issues: list[str]) -> None:
    diff = abs(py_val - jar_val)
    pct = rel_pct_diff(py_val, jar_val)
    status = "OK" if diff == 0 or pct <= tol_pct else "DIFF"
    print(f"  [{status}] {label}: python={py_val}, jar={jar_val}, diff={diff} ({pct:.1f}%)")
    if status == "DIFF":
        issues.append(f"{label}: python={py_val}, jar={jar_val}")


def check_gc_roots(py: dict, jar: dict, issues: list[str]) -> None:
    py_types = py.get("gcRootByType") or {}
    jar_types = jar.get("gcRootByType") or {}
    all_types = sorted(set(py_types) | set(jar_types))
    print("  GC Root by type:")
    for t in all_types:
        pv = int(py_types.get(t, 0))
        jv = int(jar_types.get(t, 0))
        if pv != jv:
            issues.append(f"gcRoot.{t}: python={pv}, jar={jv}")
            print(f"    [DIFF] {t}: python={pv}, jar={jv}")
        else:
            print(f"    [OK]   {t}: {pv}")


def top_class_names(rows: list[dict], key: str = "className") -> list[str]:
    return [r.get(key, "") for r in rows if r.get(key)]


def check_top_overlap(
    label: str, py_rows: list[dict], jar_rows: list[dict], min_overlap: int, issues: list[str]
) -> None:
    py_names = top_class_names(py_rows)
    jar_names = top_class_names(jar_rows)
    overlap = len(set(py_names) & set(jar_names))
    print(f"  [{ 'OK' if overlap >= min_overlap else 'WARN' }] {label} top class overlap: {overlap} "
          f"(python {py_names[:5]} vs jar {jar_names[:5]})")
    if overlap < min_overlap:
        issues.append(f"{label} top overlap only {overlap} (expected >= {min_overlap})")


def jar_has_class_dollar_static(jar: dict) -> bool:
    for row in jar.get("staticHoldingsTop") or []:
        fn = row.get("fieldName") or ""
        if "$class$" in fn:
            return True
    return False


def run_jar_export(hprof: str, out_json: str, jar_path: str) -> None:
    cmd = [
        "java",
        "-jar",
        jar_path,
        "-f",
        hprof,
        "--export-compare-json",
        out_json,
    ]
    print(">>", " ".join(cmd))
    subprocess.run(cmd, check=True)


def run_python_export(hprof: str, out_json: str, parser_dir: str, top_n: int) -> None:
    cmd = [
        sys.executable,
        EXPORT_PY,
        "-f",
        hprof,
        "-o",
        out_json,
        "--parser-dir",
        parser_dir,
        "--top",
        str(top_n),
    ]
    print(">>", " ".join(cmd))
    subprocess.run(cmd, check=True)


def main():
    ap = argparse.ArgumentParser(description="Compare mem-analyze vs hprof_parser on one HPROF")
    ap.add_argument("-f", "--hprof", required=True, help="HPROF file path")
    ap.add_argument("-o", "--output-dir", help="Directory for JSON + report (default: temp)")
    ap.add_argument("--jar", default=os.environ.get("MEM_ANALYZE_JAR", DEFAULT_JAR))
    ap.add_argument(
        "--parser-dir",
        default=os.environ.get("HPROF_PARSER_DIR", DEFAULT_PARSER_DIR),
    )
    ap.add_argument("--top", type=int, default=20)
    ap.add_argument(
        "--count-tolerance-pct",
        type=float,
        default=2.0,
        help="Relative tolerance %% for count metrics (gc root total may differ slightly)",
    )
    ap.add_argument("--skip-jar", action="store_true", help="Only run python export (jar json must exist)")
    ap.add_argument("--skip-python", action="store_true", help="Only run jar export")
    args = ap.parse_args()

    if not os.path.isfile(args.hprof):
        print(f"ERROR: hprof not found: {args.hprof}", file=sys.stderr)
        sys.exit(1)

    out_dir = args.output_dir
    cleanup = False
    if not out_dir:
        out_dir = tempfile.mkdtemp(prefix="hprof-compare-")
        cleanup = False  # keep for inspection

    os.makedirs(out_dir, exist_ok=True)
    py_json = os.path.join(out_dir, "python_metrics.json")
    jar_json = os.path.join(out_dir, "jar_metrics.json")
    report_path = os.path.join(out_dir, "compare_report.txt")

    if not args.skip_python:
        run_python_export(args.hprof, py_json, args.parser_dir, args.top)
    if not args.skip_jar:
        if not os.path.isfile(args.jar):
            print(f"ERROR: jar not found: {args.jar}", file=sys.stderr)
            print("Build: cd mem-analyze && ./gradlew shadowJar -x test", file=sys.stderr)
            sys.exit(1)
        run_jar_export(args.hprof, jar_json, args.jar)

    with open(py_json, encoding="utf-8") as f:
        py: dict[str, Any] = json.load(f)
    with open(jar_json, encoding="utf-8") as f:
        jar: dict[str, Any] = json.load(f)

    issues: list[str] = []
    lines: list[str] = []

    def log(s: str = "") -> None:
        print(s)
        lines.append(s)

    log("=" * 60)
    log(f"HPROF: {args.hprof}")
    log(f"Python: {py.get('source')}")
    log(f"JAR:    {jar.get('source')}")
    log("=" * 60)
    log("\n## Count metrics\n")

    py_gc_sum = sum((py.get("gcRootByType") or {}).values())
    jar_gc_sum = sum((jar.get("gcRootByType") or {}).values())
    check_count(
        "gcRootTotal (sum of by-type)",
        int(py_gc_sum),
        int(jar_gc_sum),
        0.5,
        issues,
    )
    check_count(
        "accumulationPointCount (dominator impl may differ)",
        int(py.get("accumulationPointCount", 0)),
        int(jar.get("accumulationPointCount", 0)),
        50.0,
        issues,
    )
    py_static = int(py.get("staticHoldingCount", 0))
    jar_static = int(py.get("staticHoldingCount", 0))
    static_diff = abs(py_static - jar_static)
    if static_diff <= 2:
        print(
            f"  [OK] staticHoldingCount: python={py_static}, jar={jar_static}, "
            f"diff={static_diff} (small-count tolerance)"
        )
    else:
        check_count("staticHoldingCount", py_static, jar_static, 10.0, issues)
    check_count(
        "largeArrayCount (python=byte[] only; jar=all primitive)",
        int(py.get("largeArrayCount", 0)),
        int(jar.get("largeArrayCount", 0)),
        80.0,
        issues,
    )
    check_count(
        "duplicateStringGroupCount",
        int(py.get("duplicateStringGroupCount", 0)),
        int(jar.get("duplicateStringGroupCount", 0)),
        15.0,
        issues,
    )
    check_count(
        "collectionRiskCount (python=large only; jar=waste/empty rules)",
        int(py.get("collectionRiskCount", 0)),
        int(jar.get("collectionRiskCount", 0)),
        80.0,
        issues,
    )

    log("\n## GC Root breakdown\n")
    check_gc_roots(py, jar, issues)

    log("\n## Top sample overlap\n")
    check_top_overlap(
        "accumulationPoints",
        py.get("accumulationPointsTop") or [],
        jar.get("accumulationPointsTop") or [],
        min(3, args.top // 2),
        issues,
    )

    if jar_has_class_dollar_static(jar):
        issues.append("JAR static holdings contain $class$ field (should be filtered)")
        log("\n  [FAIL] JAR staticHoldingsTop contains \\$class\\$ field")
    else:
        log("\n  [OK] JAR static holdings have no \\$class\\$ in top samples")

    log("\n" + "=" * 60)
    if issues:
        log(f"RESULT: {len(issues)} issue(s)")
        for i in issues:
            log(f"  - {i}")
        exit_code = 1
    else:
        log("RESULT: PASS (within tolerance)")
        exit_code = 0
    log("=" * 60)
    log(f"\nArtifacts: {out_dir}")
    log(f"  {py_json}")
    log(f"  {jar_json}")

    with open(report_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"\nReport: {report_path}")

    sys.exit(exit_code)


if __name__ == "__main__":
    main()
