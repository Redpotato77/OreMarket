#!/usr/bin/env bash
# OreMarket standalone i18n check script (same ruleset as gradle checkI18n / I18nGuard.java).
# Usage: scripts/check_i18n.sh [project root]
# Exit code: 0 = all language tables compliant; 1 = violations found (printed item by item).
set -u
ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"

python3 - "$ROOT" <<'PY'
import json, re, sys, os

root = sys.argv[1]
langs = ["zh_cn", "en_us", "zh_hk", "zh_tw"]
lang_dir = os.path.join(root, "src", "main", "resources", "assets", "oremarket", "lang")

CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
EN_FORBIDDEN = re.compile(r"[\u3000-\u303f\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uac00-\ud7af\uf900-\ufaff\uff00-\uffef\u2014\u2018\u2019\u201c\u201d\u2026]")
PRINTF = re.compile(r"%(?:[0-9]+\$)?[-+ 0#]*(?:\.[0-9]+)?[0-9]*[a-zA-Z]|%%")
CURLY = re.compile(r"\{[A-Za-z0-9_]*\}")
RUN = re.compile(r"[A-Za-z][A-Za-z0-9_]*")

# zh_cn letter whitelist (kept in sync with I18nGuard.TECHNICAL_WORDS):
# technical identifiers only -- config key names, class names, placeholders, ore codes, file extensions.
WL = set("""Ctrl G END ID INCREMENTAL ON_DEMAND FULL K X N M B T NaN OreMarket
MarketScreen ServerTickEvent StockRegistry config oremarket toml json zh_cn event
marketTick marketTickInterval tick min max quantity withPnl rebuild render
savedData startingCoins highestLevel templates shortMarginRatio shortLiquidationLine
firstDayUpChance firstDayDownChance firstDayFlatChance
COAL IRON GOLD DIAMOND EMERALD REDSTONE LAPIS QUARTZ COPPER NETHERITE
 WARNING Debug enableDebugLog""".split())

problems = []
tables = {}
for lang in langs:
    path = os.path.join(lang_dir, lang + ".json")
    if not os.path.isfile(path):
        problems.append(f"missing language file: {path}")
        continue
    try:
        with open(path, encoding="utf-8") as f:
            tables[lang] = json.load(f)
    except Exception as e:
        problems.append(f"invalid JSON {path}: {e}")

base = tables.get("zh_cn")
if base is not None:
    for lang in langs[1:]:
        t = tables.get(lang)
        if t is None:
            continue
        if set(t) != set(base):
            missing = sorted(set(base) - set(t))[:8]
            extra = sorted(set(t) - set(base))[:8]
            problems.append(f"{lang} key set differs from zh_cn (missing={missing}, extra={extra})")

# en_us: ASCII printable text only (newlines/tabs excepted) -- CJK ideographs, Chinese punctuation,
# and typographic symbols such as arrows / multiply-divide / mathematical minus are all violations.
en = tables.get("en_us")
if en:
    for k, v in en.items():
        if isinstance(v, str):
            bad = next((c for c in v if (ord(c) < 0x20 and c not in "\n\r\t") or ord(c) > 0x7e), None)
            if bad is not None:
                problems.append(f"en_us contains non-ASCII at {k}: U+{ord(bad):04X} {bad!r}")

# zh_hk / zh_tw: simplified residue = ideographs used by zh_cn that the other traditional table never uses.
def cjk_chars(t):
    return {c for v in t.values() if isinstance(v, str) for c in v if CJK.fullmatch(c)} if t else set()

zh, hk, tw = tables.get("zh_cn"), tables.get("zh_hk"), tables.get("zh_tw")
absent_tw = cjk_chars(zh) - cjk_chars(tw)
absent_hk = cjk_chars(zh) - cjk_chars(hk)
for t, forbidden, name in ((hk, absent_tw, "zh_hk"), (tw, absent_hk, "zh_tw")):
    if not t:
        continue
    for k, v in t.items():
        if isinstance(v, str):
            hits = [c for c in v if c in forbidden]
            if hits:
                problems.append(f"{name} simplified residue at {k}: {''.join(hits[:6])}")

# zh_cn: every letter run must be in the whitelist.
if zh:
    for k, v in zh.items():
        if not isinstance(v, str):
            continue
        cleaned = CURLY.sub(" ", PRINTF.sub(" ", v))
        for tok in RUN.findall(cleaned):
            if tok not in WL and not (len(tok) == 1 and tok.isupper()):
                problems.append(f"zh_cn non-whitelisted letters at {k}: {tok}")

# Java player-facing copy must never put non-ASCII characters straight into string/char literals; baseline Chinese text belongs in the language tables.
# This gate checks literals only; comment language is a separate WARN below (comments must be English).
JAVA_STRING = re.compile(r'"(?:[^"\\\\\n]|\\\\.)*"')
JAVA_CHAR = re.compile(r"'(?:\\\\.|[^'\\\\])'")

def scan_java_literals():
    java_root = os.path.join(root, "src", "main", "java")
    if not os.path.isdir(java_root):
        return
    for dirpath, _, files in os.walk(java_root):
        for fn in files:
            if not fn.endswith(".java"):
                continue
            path = os.path.join(dirpath, fn)
            try:
                lines = open(path, encoding="utf-8").read().splitlines()
            except Exception as exc:
                problems.append(f"cannot read Java source {path}: {exc}")
                continue
            in_block = False
            for line_no, original in enumerate(lines, 1):
                line = original
                if in_block:
                    end = line.find("*/")
                    if end < 0:
                        continue
                    line = line[end + 2:]
                    in_block = False
                while True:
                    start = line.find("/*")
                    if start < 0:
                        break
                    end = line.find("*/", start + 2)
                    if end < 0:
                        line = line[:start]
                        in_block = True
                        break
                    line = line[:start] + line[end + 2:]
                line = line.split("//", 1)[0]
                for literal in JAVA_STRING.findall(line):
                    inner = literal[1:-1]
                    if any(ord(c) < 0x20 or ord(c) > 0x7e for c in inner):
                        problems.append(f"Java string contains non-ASCII at {os.path.relpath(path, root)}:{line_no}")
                for literal in JAVA_CHAR.findall(line):
                    inner = literal[1:-1]
                    if any(ord(c) < 0x20 or ord(c) > 0x7e for c in inner):
                        problems.append(f"Java char literal contains non-ASCII at {os.path.relpath(path, root)}:{line_no}: {literal}")

scan_java_literals()

# ---------------- log key-value discipline (same ruleset as I18nGuard.scanLogKeyDiscipline) ----------------
import glob
zh_keys = set(base) if base else None
PUSH_CALL = re.compile(r"LogService\.push\(|LogService\.pushAll\(")
# real translation keys: at least two segments after oremarket. (oremarket.text.0578 / oremarket.level.log.unlock).
# single-segment literals like oremarket.toml are file names, not keys; skipped.
KEY_FULL = re.compile(r'"(oremarket(?:\.[A-Za-z0-9_\-]+){2,})"')
# runtime key-prefix literals with a trailing dot (e.g. "oremarket.event.rarity.").
KEY_PREFIX = re.compile(r'"(oremarket(?:\.[A-Za-z0-9_\-]+)+\.)[^"]*"')

def strip_comments(text):
    """Strip comments while preserving offsets: comment characters become spaces so rules scan code only."""
    out = list(text)
    i, n = 0, len(text)
    in_block = False
    while i < n:
        c = text[i]
        if in_block:
            if text.startswith("*/", i):
                out[i] = out[i + 1] = " "
                i += 2
                in_block = False
                continue
            if c != "\n":
                out[i] = " "
            i += 1
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            if j < 0:
                j = n
            for k in range(i, j):
                out[k] = " "
            i = j
            continue
        if text.startswith("/*", i):
            out[i] = out[i + 1] = " "
            i += 2
            in_block = True
            continue
        i += 1
    return "".join(out)

def balanced(text, open_paren):
    depth = 0
    for i in range(open_paren, min(len(text), open_paren + 4000)):
        if text[i] == '(':
            depth += 1
        elif text[i] == ')':
            depth -= 1
            if depth == 0:
                return text[open_paren + 1:i]
    return None

for path in glob.glob(os.path.join(root, "src", "main", "java", "**", "*.java"), recursive=True):
    rel = os.path.relpath(path, root).replace(os.sep, "/")
    try:
        text_raw = open(path, encoding="utf-8").read()
    except Exception as exc:
        problems.append(f"cannot read Java source {path}: {exc}")
        continue
    # The guard's own source legitimately contains literals like LogService.push( / L.tr(...); exempt it from all three rules.
    if rel.endswith("I18nGuard.java"):
        continue
    text = strip_comments(text_raw)
    # 1) server-side/common code must not reference the client translation helper (L.tr / client.L) -- dedicated servers would crash.
    if "/client/" not in rel:
        for i, ln in enumerate(text.splitlines(), 1):
            if "L.tr(" in ln or "client.L" in ln:
                problems.append(f"server-side code calls client translation helper at {rel}:{i}")
    # 2) LogService.push/pushAll must not carry hand-assembled text (use pushKey with key + args).
    for m in PUSH_CALL.finditer(text):
        call = balanced(text, m.end() - 1)
        if call is None:
            problems.append(f"unbalanced LogService.push call in {rel}")
            continue
        if "RawText." in call or "LogFormatter." in call or "+" in call:
            line_no = 1 + text.count("\n", 0, m.start())
            problems.append(f"LogService.push carries hand-assembled text (use pushKey): {rel}:{line_no}")
    # 3) every translation key referenced by source must be registered in zh_cn (the key-set parity rule keeps all four tables);
    #    dotted prefix literals pass when some registered key starts with them.
    if zh_keys is not None:
        prefixes = [m.group(1) for m in KEY_PREFIX.finditer(text)]
        for m in KEY_FULL.finditer(text):
            key = m.group(1)
            if key in zh_keys:
                continue
            if any(p.startswith(key + ".") for p in prefixes):
                continue
            line_no = 1 + text.count("\n", 0, m.start())
            problems.append(f"translation key not registered in zh_cn: {key} at {rel}:{line_no}")
        for p in prefixes:
            if not any(k.startswith(p) for k in zh_keys):
                line_no = 1 + text.count("\n", 0, text.find('"' + p))
                problems.append(f"translation key prefix matches nothing in zh_cn: {p}")

# ---------------- comment language discipline: src/main/java comments must be English ----------------
# Development WARN only (never fails the build); same rule as I18nGuard.scanCommentLanguage.
CJK_IDEO = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
comment_warns = set()
for path in glob.glob(os.path.join(root, "src", "main", "java", "**", "*.java"), recursive=True):
    rel = os.path.relpath(path, root).replace(os.sep, "/")
    try:
        raw = open(path, encoding="utf-8").read()
    except Exception:
        continue
    code = strip_comments(raw)
    for i, ch in enumerate(raw[:min(len(raw), len(code))]):
        if ch != " " and code[i] == " " and CJK_IDEO.match(ch):
            comment_warns.add(f"{rel}:{1 + raw.count(chr(10), 0, i)}")
for w in sorted(comment_warns):
    print("[check_i18n] WARN Chinese-comment:", w)

if problems:
    for p in problems:
        print("[check_i18n] FAIL:", p)
    sys.exit(1)
print("[check_i18n] OK:", sum(len(t) for t in tables.values() if t), "entries scanned")
PY
