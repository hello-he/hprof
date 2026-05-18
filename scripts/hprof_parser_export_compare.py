#!/usr/bin/env python3
"""
Export hprof_parser.py global diagnostics metrics as JSON (same schema as mem-analyze --export-compare-json).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO

DEFAULT_PARSER_DIR = os.path.expanduser(
    "~/workspaces/Android-App-Memory-Analysis/tools"
)


def load_parser_module(parser_dir: str):
    if parser_dir not in sys.path:
        sys.path.insert(0, parser_dir)
    from hprof_parser import HprofParser  # noqa: E402

    return HprofParser


def gc_root_by_type_name(parser) -> dict[str, int]:
    out: dict[str, int] = {}
    for root_type, obj_ids in parser.gc_root_types.items():
        name = parser.GC_ROOT_NAMES.get(root_type, f"UNKNOWN_{root_type}")
        out[name] = len(obj_ids)
    return dict(sorted(out.items(), key=lambda x: -x[1]))


def accumulation_rows(parser, top_n: int) -> list[dict]:
    points = [s for s in parser.leak_suspects if s.get("type") == "ACCUMULATION_POINT"]
    points.sort(key=lambda s: s.get("retained_size", 0), reverse=True)
    rows = []
    for s in points[:top_n]:
        shallow = s.get("shallow_size", 0) or 0
        retained = s.get("retained_size", 0) or 0
        ratio = (retained / shallow) if shallow else 0.0
        rows.append(
            {
                "className": s.get("class_name", "unknown"),
                "objectId": s.get("object_id", 0),
                "retainedBytes": int(retained),
                "shallowBytes": int(shallow),
                "retainedToShallowRatio": round(ratio, 1),
                "directDominatedCount": int(s.get("dominated_count", 0)),
            }
        )
    return rows


def static_holding_rows(parser, top_n: int) -> list[dict]:
    holdings = getattr(parser, "suspicious_holdings", []) or []
    holdings.sort(key=lambda s: s.get("retained_size", 0), reverse=True)
    rows = []
    for s in holdings[:top_n]:
        rows.append(
            {
                "category": s.get("type", "UNKNOWN"),
                "declaringClass": s.get("class_name", ""),
                "fieldName": s.get("field_name", ""),
                "targetClassName": "",
                "targetObjectId": int(s.get("object_id", 0)),
                "retainedBytes": int(s.get("retained_size", 0)),
                "shallowBytes": 0,
            }
        )
    return rows


def large_array_rows(parser, top_n: int) -> list[dict]:
    arrays = getattr(parser, "large_byte_arrays", []) or []
    arrays = sorted(arrays, key=lambda a: a.get("size", 0), reverse=True)
    rows = []
    for a in arrays[:top_n]:
        chain = a.get("holder_chain") or []
        primary = None
        if chain:
            h = chain[0]
            primary = f"{h.get('class_name', '')}.{h.get('field_name', '')}"
        rows.append(
            {
                "objectId": int(a.get("array_id", 0)),
                "arrayType": "byte[]",
                "sizeBytes": int(a.get("size", 0)),
                "primaryHolder": primary,
            }
        )
    return rows


def export_metrics(hprof_path: str, output_path: str, parser_dir: str, top_n: int) -> None:
    HprofParser = load_parser_module(parser_dir)
    parser = HprofParser(hprof_path, verbose=False)

    buf = StringIO()
    with redirect_stdout(buf), redirect_stderr(buf):
        ok = parser.parse(
            simple_mode=True,
            top_n=top_n,
            min_size_mb=0.1,
            output_file=None,
            deep_analysis=True,
            markdown=False,
        )
    if not ok:
        raise RuntimeError("hprof_parser.parse() failed")

    dup_groups = sum(1 for _ids in parser.duplicate_strings.values() if len(_ids) > 1)
    collections = getattr(parser, "large_collections", None) or []
    lru_listed = len(getattr(parser, "lru_cache_analysis", []) or [])

    gc_by_type = gc_root_by_type_name(parser)
    # Match mem-analyze: total = sum of per-type root records (not len(unique gc_roots))
    payload = {
        "source": "hprof_parser.py",
        "hprofPath": os.path.abspath(hprof_path),
        "gcRootTotal": sum(gc_by_type.values()),
        "gcRootByType": gc_by_type,
        "accumulationPointCount": len(
            [s for s in parser.leak_suspects if s.get("type") == "ACCUMULATION_POINT"]
        ),
        "accumulationPointsTop": accumulation_rows(parser, top_n),
        "staticHoldingCount": len(getattr(parser, "suspicious_holdings", []) or []),
        "staticHoldingsTop": static_holding_rows(parser, top_n),
        "largeArrayCount": len(getattr(parser, "large_byte_arrays", []) or []),
        "largeArraysTop": large_array_rows(parser, top_n),
        "duplicateStringGroupCount": dup_groups,
        "collectionRiskCount": len(collections),
        "lruCacheListedCount": lru_listed,
    }

    os.makedirs(os.path.dirname(os.path.abspath(output_path)) or ".", exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


def main():
    ap = argparse.ArgumentParser(description="Export hprof_parser metrics for compare with mem-analyze")
    ap.add_argument("-f", "--hprof", required=True, help="HPROF file path")
    ap.add_argument("-o", "--output", required=True, help="Output JSON path")
    ap.add_argument(
        "--parser-dir",
        default=os.environ.get("HPROF_PARSER_DIR", DEFAULT_PARSER_DIR),
        help="Directory containing hprof_parser.py",
    )
    ap.add_argument("--top", type=int, default=20, help="Top N samples per category")
    args = ap.parse_args()

    if not os.path.isfile(args.hprof):
        print(f"ERROR: hprof not found: {args.hprof}", file=sys.stderr)
        sys.exit(1)
    parser_py = os.path.join(args.parser_dir, "hprof_parser.py")
    if not os.path.isfile(parser_py):
        print(f"ERROR: hprof_parser.py not found: {parser_py}", file=sys.stderr)
        sys.exit(1)

    export_metrics(args.hprof, args.output, args.parser_dir, args.top)
    print(f"OK: {args.output}")


if __name__ == "__main__":
    main()
