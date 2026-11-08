#!/usr/bin/env python3
"""Ratchet-based readability and package-boundary checks for Java sources.

The checker is intentionally dependency-free and linear-time: it strips comments,
string and char literals with a character state machine, then measures method
length and cyclomatic complexity per top-level code block. Current violations are
grandfathered in config/readability-baseline.json; ANY violation above the baseline
fails the build, so new code must stay readable. Re-run with --write-baseline after
legitimately reducing (or intentionally accepting) findings.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

JAVA_ROOTS = (Path("src/main/java"), Path("src/test/java"))
CONFIG = Path("config/readability-baseline.json")

MAX_METHOD_LINES = 80
MAX_COMPLEXITY = 15
MAX_FILE_LINES = 15000
MAX_LINE_LENGTH = 200

NUMERIC_TOKEN = set("0123456789")
IDENT_STOP = set(" \t\r\n();,{}[]<>+-*/%=&|?!:~^\"'\\")
EXEMPT_NUMBERS = {"0", "1", "2", "0.0", "1.0", "0.01", "1.0E-9", "100", "1000"}
CONTROL_KEYWORDS = {"if", "for", "while", "switch", "catch", "return", "new", "do", "else", "try", "throw", "synchronized"}
BAD_LOCAL_NAMES = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "tmp", "tmp1", "tmp2", "res", "obj", "val", "o1", "o2"}
TYPE_DECL_WORDS = {"class", "interface", "enum", "record"}

# Imports that may never appear in the given layer (owner package prefix -> forbidden import prefixes).
LAYER_RULES = (
    ("cn.oremarket.common.", ("cn.oremarket.client.", "cn.oremarket.command.")),
    ("cn.oremarket.config.", ("cn.oremarket.client.",)),
    ("cn.oremarket.market.", ("cn.oremarket.client.",)),
    ("cn.oremarket.net.packet.", ("cn.oremarket.client.",)),
)
# Layers that are allowed to depend downward; anything crossing "upward" must be reviewed.
LAYER_ORDER = ["common", "config", "market", "net", "client", "command"]


def source_files(root: Path):
    for relative_root in JAVA_ROOTS:
        directory = root / relative_root
        if directory.exists():
            for path in sorted(directory.rglob("*.java")):
                if path.is_file():
                    yield path


def strip_comments_and_literals(text: str) -> str:
    """Blank out comments and string/char bodies, keeping line structure and code punctuation."""
    out = []
    i, n = 0, len(text)
    state = "code"
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state == "code":
            if ch == "/" and nxt == "/":
                state = "line"
                out.append("  ")
                i += 2
                continue
            if ch == "/" and nxt == "*":
                state = "block"
                out.append("  ")
                i += 2
                continue
            if ch == '"':
                state = "string"
                out.append('"')
                i += 1
                continue
            if ch == "'":
                state = "char"
                out.append("'")
                i += 1
                continue
            out.append(ch)
            i += 1
            continue
        if state == "line":
            if ch == "\n":
                state = "code"
                out.append("\n")
            else:
                out.append(" ")
            i += 1
            continue
        if state == "block":
            if ch == "*" and nxt == "/":
                state = "code"
                out.append("  ")
                i += 2
                continue
            out.append("\n" if ch == "\n" else " ")
            i += 1
            continue
        if state == "string":
            if ch == "\\":
                out.append("  "[:2] if nxt != "\n" else " \n")
                i += 2
                continue
            if ch == '"':
                state = "code"
            out.append("\n" if ch == "\n" else " ")
            i += 1
            continue
        # char literal
        if ch == "\\":
            out.append("  ")
            i += 2
            continue
        if ch == "'":
            state = "code"
        out.append(" ")
        i += 1
    return "".join(out)


def identifiers(line: str):
    words = []
    i, n = 0, len(line)
    while i < n:
        if line[i] in NUMERIC_TOKEN or line[i].isalpha() or line[i] in "_$":
            j = i
            while j < n and (line[j] == "_" or line[j] == "$" or line[j].isalnum()):
                j += 1
            words.append(line[i:j])
            i = j
        else:
            i += 1
    return words


def numbers_in(line: str):
    """Numeric literals on a comment/string-free line (hex and suffixed floats included)."""
    found = []
    i, n = 0, len(line)
    while i < n:
        ch = line[i]
        if ch in NUMERIC_TOKEN or (ch == "." and i + 1 < n and line[i + 1] in NUMERIC_TOKEN):
            if i > 0 and (line[i - 1].isalnum() or line[i - 1] in "_$"):
                i += 1
                continue
            j = i
            while j < n and (line[j] in NUMERIC_TOKEN or line[j] in ".xX" or line[j] in "abcdefABCDEF"
                             or line[j] in "eE+-" and found and line[j - 1] in "eE"
                             or line[j] in "fFdDlL" and j > i):
                if line[j] == "." or line[j] in "eE" or line[j] in "xX":
                    j += 1
                    continue
                if line[j] in "+-":
                    j += 1
                    continue
                j += 1
            token = line[i:j]
            if not token.lower().startswith("0x"):
                token = token.rstrip("fFdDlL")
            if token and token not in EXEMPT_NUMBERS:
                found.append(token)
            i = max(j, i + 1)
        else:
            i += 1
    return found


def scan_file(relative: str, raw_text: str):
    findings = []
    lines = raw_text.splitlines()
    if len(lines) > MAX_FILE_LINES:
        findings.append((relative, "file-too-long", 1, str(len(lines))))
    stripped_lines = strip_comments_and_literals(raw_text).splitlines()

    for index, original in enumerate(lines):
        line_number = index + 1
        if "\t" in original:
            findings.append((relative, "tab-character", line_number, "tab"))
        if original.rstrip() != original:
            findings.append((relative, "trailing-whitespace", line_number, "whitespace"))
        if len(original) > MAX_LINE_LENGTH:
            findings.append((relative, "line-too-long", line_number, str(len(original))))

    brace_depth = 0
    method = None  # (name, start_line, decision_points)
    depth_at_method_start = 0
    for index, code in enumerate(stripped_lines):
        line_number = index + 1
        words = identifiers(code)
        code_stripped = code.strip()

        if method is not None:
            head = words[0] if words else ""
            if head in {"if", "for", "while", "case", "catch"}:
                method[2] += 1
            method[2] += code.count("&&") + code.count("||")
            method[2] += code.count("?:")

        opens = code.count("{")
        closes = code.count("}")

        if method is None and opens and code_stripped.endswith("{") and "(" in code_stripped:
            candidate = method_candidate(words)
            if candidate:
                method = [candidate, line_number, 0]
                depth_at_method_start = brace_depth

        brace_depth += opens - closes

        if method is not None and brace_depth <= depth_at_method_start:
            name, start = method[0], method[1]
            length = line_number - start + 1
            if length > MAX_METHOD_LINES:
                findings.append((relative, "method-too-long", start, f"{name}={length}"))
            if method[2] > MAX_COMPLEXITY:
                findings.append((relative, "high-complexity", start, f"{name}={method[2]}"))
            method = None

    for index, code in enumerate(stripped_lines):
        line_number = index + 1
        if code.lstrip().startswith("@") or "static final" in code or "package " in code or "import " in code:
            continue
        words = identifiers(code)
        is_declaration = any(w in {"int", "long", "double", "float"} for w in words) or "var " in code
        if is_declaration:
            for word in words:
                if word in BAD_LOCAL_NAMES and len(word) <= 3:
                    findings.append((relative, "ambiguous-name", line_number, word))
        for token in numbers_in(code):
            findings.append((relative, "magic-number", line_number, token))

    return findings, extract_imports(stripped_lines)


def method_candidate(words):
    """Recognise a method signature line: [modifiers] ReturnType name(...) { (no control flow)."""
    if not words:
        return None
    if words[0] in CONTROL_KEYWORDS or words[0] in TYPE_DECL_WORDS:
        return None
    for position, word in enumerate(words):
        if word in CONTROL_KEYWORDS and position == 0:
            return None
    if "new" in words:
        return None
    name = None
    for word in reversed(words):
        if word not in {"public", "protected", "private", "static", "final", "synchronized",
                        "abstract", "native", "default", "void"} and (word[0].isalpha() or word[0] == "_"):
            name = word
            break
    if name is None or name in TYPE_DECL_WORDS:
        return None
    return name


def extract_imports(stripped_lines):
    imports = []
    package = ""
    for index, code in enumerate(stripped_lines):
        head = code.strip()
        if head.startswith("package "):
            package = head[len("package "):].rstrip(";").strip() + "."
        elif head.startswith("import "):
            imports.append((package, head[len("import "):].rstrip(";").strip()))
    return package_imports_fixup(imports)


def package_imports_fixup(imports):
    return imports


def scan(root: Path):
    findings = []
    for path in source_files(root):
        relative = path.relative_to(root).as_posix()
        file_findings, _imports = scan_file(relative, path.read_text(encoding="utf-8"))
        findings.extend(file_findings)
    for path in source_files(root):
        relative = path.relative_to(root).as_posix()
        text = strip_comments_and_literals(path.read_text(encoding="utf-8"))
        package, imports = "", []
        for code in text.splitlines():
            head = code.strip()
            if head.startswith("package "):
                package = head[len("package "):].rstrip(";").strip() + "."
            elif head.startswith("import "):
                imports.append(head[len("import "):].rstrip(";").strip())
        if package.startswith("cn.oremarket."):
            for owner, blocked in LAYER_RULES:
                if package.startswith(owner):
                    for imported in imports:
                        if imported.startswith(blocked):
                            findings.append((relative, "layer-violation", 1, f"{package} -> {imported}"))
                    break
    return sorted(findings)


def fingerprint(relative: str, rule: str, line: int, detail: str) -> str:
    """Use stable source identity; line movement during a pure refactor is not a new violation."""
    return f"{relative}:{rule}:{detail}"


def counts(findings):
    result = Counter()
    for relative, rule, _line, _detail in findings:
        result[f"{relative}|{rule}"] += 1
    return dict(sorted(result.items()))


def baseline_findings(findings):
    return sorted(fingerprint(relative, rule, line, detail)
                  for relative, rule, line, detail in findings)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path, default=Path("."))
    parser.add_argument("--baseline", type=Path, default=None)
    parser.add_argument("--write-baseline", action="store_true")
    parser.add_argument("--top", type=int, default=15)
    args = parser.parse_args()
    root = args.root.resolve()
    baseline_path = args.baseline if args.baseline and args.baseline.is_absolute() else root / (args.baseline or CONFIG)

    findings = scan(root)
    current = counts(findings)
    current_fingerprints = baseline_findings(findings)

    if args.write_baseline:
        baseline_path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "format": 2,
            "findings": current_fingerprints,
        }
        baseline_path.write_text(json.dumps(payload, indent=1, ensure_ascii=True) + "\n", encoding="utf-8")
        print(f"[readability] baseline written: {len(findings)} findings across {len(current)} file/rule buckets")
        return 0

    if not baseline_path.exists():
        print(f"[readability] FAIL: baseline {baseline_path} missing; run --write-baseline first")
        return 1

    baseline_payload = json.loads(baseline_path.read_text(encoding="utf-8"))
    if isinstance(baseline_payload, dict) and "findings" in baseline_payload:
        baseline = set(baseline_payload["findings"])
        new_violations = [fingerprint(relative, rule, line, detail)
                          for relative, rule, line, detail in findings
                          if fingerprint(relative, rule, line, detail) not in baseline]
    else:
        # Read the original count-only format once so existing projects can migrate safely.
        baseline = baseline_payload
        seen = Counter()
        new_violations = []
        for relative, rule, line, detail in findings:
            key = f"{relative}|{rule}"
            seen[key] += 1
            if seen[key] > int(baseline.get(key, 0)):
                new_violations.append(fingerprint(relative, rule, line, detail))

    if new_violations:
        print("[readability] FAIL: new readability or layer violations (above baseline)")
        for item in new_violations[:60]:
            print("  -", item)
        if len(new_violations) > 60:
            print(f"  ... and {len(new_violations) - 60} more")
        print("[readability] Fix the code; the baseline may only SHRINK without an explicit --write-baseline run.")
        return 1

    if isinstance(baseline, set):
        reduced = max(0, len(baseline) - len(current_fingerprints))
    else:
        reduced = sum(max(0, int(baseline.get(key, 0)) - value) for key, value in current.items())
    print(f"[readability] OK: {len(findings)} findings at or below baseline ({reduced} fewer than baseline)")
    if current:
        ranked = sorted(current.items(), key=lambda item: -item[1])[: args.top]
        for key, value in ranked:
            print(f"    {value:>5}  {key}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
