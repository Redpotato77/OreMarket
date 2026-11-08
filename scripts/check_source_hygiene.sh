#!/usr/bin/env bash
# Check source comments and metadata for repository hygiene.
# Usage: scripts/check_source_hygiene.sh [project root]
set -u

ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
python3 - "$ROOT" <<'PY'
import os
import re
import sys

root = os.path.abspath(sys.argv[1])
roots = [
    os.path.join(root, "src", "main", "java"),
    os.path.join(root, "src", "test", "java"),
]
files = []
for base in roots:
    for directory, _, names in os.walk(base):
        for name in names:
            if name.endswith(".java"):
                files.append(os.path.join(directory, name))

cjk = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
trace_patterns = [
    re.compile(r"\bthis round\b", re.I),
    re.compile(r"\blast round\b", re.I),
    re.compile(r"\bround (?:one|two|three|four|five|six|seven|eight|nine|ten|\d+)\b", re.I),
    re.compile(r"\bfix \d+(?:\.\d+)+\b", re.I),
    re.compile(r"\bself-audit\b", re.I),
    re.compile(r"\bperformance audit\b", re.I),
    re.compile(r"\bmemory-leak audit\b", re.I),
    re.compile(r"\bre-audit\b", re.I),
    re.compile(r"\bwhite-box\b", re.I),
    re.compile(r"\btop directive\b", re.I),
    re.compile(r"\baddendum\b", re.I),
    re.compile(r"\bdefect F\d*\b", re.I),
]

problems = []
for path in sorted(files):
    rel = os.path.relpath(path, root).replace(os.sep, "/")
    try:
        text = open(path, encoding="utf-8").read()
    except OSError as exc:
        problems.append(f"{rel}: cannot read file: {exc}")
        continue
    for line_no, line in enumerate(text.splitlines(), 1):
        if cjk.search(line):
            problems.append(f"{rel}:{line_no}: CJK character in Java source")
        for pattern in trace_patterns:
            if pattern.search(line) and "Math.round" not in line:
                problems.append(f"{rel}:{line_no}: forbidden revision wording: {line.strip()}")
                break

if problems:
    for problem in problems:
        print("[source-hygiene] FAIL:", problem)
    sys.exit(1)
print(f"[source-hygiene] OK: scanned {len(files)} Java files")
PY
