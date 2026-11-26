#!/usr/bin/env python3
"""Change guard: checks the lines a change adds or modifies, compared with a base commit.

Four checks, each on the added and modified lines only (older lines are left alone), with one exception: in the
folders whose comments have been cleaned (CLEANED below), references in comments are checked in every file, on
every line, whether the change touches it or not.

1. References in comments. A comment or Javadoc in a Java file under platform/, mapping/ or jq/, a comment in a
   TypeScript, JavaScript or CSS file under ui/, or a `#` comment in a shell, YAML or Python file anywhere, must
   explain; it must not point at a task or a decision.
   A reference is an upper-case tag, a hyphen and a number, such as D-164, FIX-268, MAP-12b, P2-02D, P0-10 or ADR-0019,
   or a `#` followed by a number, such as #123. Names of standards (UTF-8, SHA-256, ISO-8601 and the like) are not
   references.
2. Hard-coded colours. In CSS, SCSS, TypeScript and JavaScript files (TSX and JSX included) a colour is written
   as an Ant Design token, `var(--ant-...)`. A hex colour, rgb(), rgba(), hsl(), hsla()
   or a CSS named colour is a finding: in CSS anywhere outside comments, in script files inside string literals
   (a named colour only as the value of a style key such as `color:` or of an SVG `fill=` or `stroke=`).
   Not checked: site/ (the project site has its own styles), test files and e2e/ (tests compare the colours
   the browser computes) and generated code.
3. Texts written in place. The UI shows its texts in the user's language, so a script file under ui/src/ holds
   no text of its own: it names the text by its key, such as `t('frame.quickOpen')`, and the text is in the files
   of translations, ui/locales/<language>/<module>.json. A finding is a string literal that reads as words
   (two words of letters in a row, or one capitalised word given to a property that shows text, such as `title`
   or `label`), and in TSX a text between tags. A line that ends with the comment `// untranslated`, and a reason
   after it, is left alone: a text no user sees, such as a developer's error. Not checked: test files, e2e/,
   generated code, the development server (server/) and the fakes that stand in for it (fake.ts, fake/,
   contract/).
4. Keys. A key a changed line of ui/src/ names with `t(...)`, `rich(...)` or `msg(...)`, and a key a changed line
   of a file of translations gives a text, must be a key of English, ui/locales/en/*.json; English is the text
   shown wherever a language lacks one.

The base is `--base <commit>`; the change is the commit `--head` (default HEAD), read from git, so the working
tree does not matter. Findings, one per line: `path:line: <what is wrong>`; the last line counts them.
Exit 0 clean, 1 findings, 2 a usage or git error.

Usage:
  python3 ci/change_guard.py --base origin/main   # from the repository root
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys

JAVA_ROOTS = ("platform/", "mapping/", "jq/")
# Folders whose comments have been cleaned of references: every comment in every file under
# them is checked, not only the lines a change touches. A cleanup adds its folders to this list.
CLEANED = ("platform/api/src/main/", "platform/core/src/main/", "platform/runtime/src/main/", "platform/testkit/src/main/",
           "platform/sim/src/main/", "platform/cli/src/main/", "platform/schemas/src/main/",
           "platform/components/http/src/main/", "platform/components/mock/src/main/",
           "platform/components/sftp/src/main/", "platform/steps/validate/src/main/", "platform/steps/xslt/src/main/",
           "mapping/engine/api/src/main/", "mapping/engine/ir/src/main/", "mapping/engine/mapping/src/main/",
           "mapping/engine/runtime/src/main/", "mapping/engine/serializer/src/main/",
           "mapping/engine/structure/src/main/", "mapping/engine/structure-import/src/main/",
           "mapping/engine/udf/src/main/", "mapping/engine/udf-api/src/main/", "mapping/engine/xdm/src/main/",
           "mapping/engine/xslt-export/src/main/",
           # the mapping studio's sources except src/mapping/generated, whose comments come from the contract's schemas
           "ui/src/mapping/api/", "ui/src/mapping/list/", "ui/src/mapping/model/", "ui/src/mapping/studio/",
           "jq/jq-core/src/main/", "jq/query-core/src/main/", "jq/jq-cli/src/main/", "jq/oracle/src/main/",
           "jq/pit-filter/src/main/", "jq/bench/jmh/src/main/")
UI_ROOT = "ui/"
SCRIPT_SUFFIXES = (".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs")
STYLE_SUFFIXES = (".css", ".scss")
HASH_SUFFIXES = (".sh", ".bash", ".yml", ".yaml", ".py")
COLOUR_SKIPPED = re.compile(r"^site/|(^|/)e2e/|(^|/)generated/|\.(test|spec)\.[a-z]+$")
UI_SOURCES = "ui/src/"
LOCALES = "ui/locales/"
ENGLISH = LOCALES + "en/"
TEXT_SKIPPED = re.compile(r"(^|/)e2e/|(^|/)generated/|\.(test|spec)\.[a-z]+$|(^|/)server/|(^|/)fake(/|\.)|(^|/)contract/")
UNTRANSLATED = re.compile(r"//\s*untranslated\b")
# two words of letters in a row, as prose has them
PROSE = re.compile(r"(?<![\w.#$/-])[A-Za-z]{2,}[,;:]? [A-Za-z]{2,}(?![\w(/-])")
# a capitalised word, the whole of a value given to a property that shows text
WORD_TEXT = re.compile(r"^[A-Z][a-z]+[.!?…]?$")
TEXT_KEY = re.compile(r"(?:\b(?:title|label|placeholder|aria-label|alt|description|message|tooltip|content|okText|cancelText"
                      r"|headerTitle|subTitle|emptyText|submitText|resetText|searchText|help|tab|addonBefore|addonAfter|text)"
                      r")\s*[:=]\s*\{?\s*$")
CODE_LIKE = re.compile(r"var\(--|[=<>\[\]{}|&\\]|^\s*[./@#]|://")
# a text between tags, after the `>` that closes a tag
JSX_AFTER_TAG = re.compile(r"(?<=[\w\"'}/])>([^<>{}]*)")
# a text after an expression, from its `}` up to the next tag or expression
JSX_AFTER_VALUE = re.compile(r"\}([^<>{}$`\"]*)(?=[<{])")
JSX_TAG = re.compile(r"</?[A-Za-z][\w.]*(?:\s|/?>)")
JS_WORDS = {"return", "export", "import", "const", "let", "var", "type", "interface", "case", "default", "else", "async",
            "await", "new", "throw", "typeof", "extends", "implements", "function", "if", "for", "while", "from", "as",
            "declare", "class", "enum", "yield", "break", "continue", "do", "try", "catch", "finally", "switch", "void",
            "delete", "in", "of", "readonly", "public", "private", "protected", "static", "abstract", "keyof",
            "instanceof", "satisfies"}
KEY_USE = re.compile(r"\b(?:t|rich|msg)\(\s*(['\"`])([A-Za-z][\w-]*(?:\.[\w-]+)+)\1")
LOCALE_KEY = re.compile(r'^\s*"((?:[^"\\]|\\.)+)"\s*:')

REFERENCE = re.compile(r"(?<![\w-])([A-Z][A-Z0-9]*-\d{1,4}[a-zA-Z]?)(?![\w-])|(?<![\w&#])(#\d+)(?!\w)")
# names of standards and formats that are written like a reference but explain themselves
STANDARDS = {"AES", "CESU", "CRC", "ECMA", "IEC", "IEEE", "ISO", "JSR", "MD", "PKCS", "RFC", "SHA", "TLS", "UTF", "X"}

HEX = re.compile(r"(?<![\w&])#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})(?![\w-])")
FUNCTION = re.compile(r"(?<![\w-])(?:rgba?|hsla?)\(", re.IGNORECASE)
NAMED = {
    "aliceblue", "antiquewhite", "aqua", "aquamarine", "azure", "beige", "bisque", "black", "blanchedalmond",
    "blue", "blueviolet", "brown", "burlywood", "cadetblue", "chartreuse", "chocolate", "coral", "cornflowerblue",
    "cornsilk", "crimson", "cyan", "darkblue", "darkcyan", "darkgoldenrod", "darkgray", "darkgreen", "darkgrey",
    "darkkhaki", "darkmagenta", "darkolivegreen", "darkorange", "darkorchid", "darkred", "darksalmon",
    "darkseagreen", "darkslateblue", "darkslategray", "darkslategrey", "darkturquoise", "darkviolet", "deeppink",
    "deepskyblue", "dimgray", "dimgrey", "dodgerblue", "firebrick", "floralwhite", "forestgreen", "fuchsia",
    "gainsboro", "ghostwhite", "gold", "goldenrod", "gray", "green", "greenyellow", "grey", "honeydew", "hotpink",
    "indianred", "indigo", "ivory", "khaki", "lavender", "lavenderblush", "lawngreen", "lemonchiffon", "lightblue",
    "lightcoral", "lightcyan", "lightgoldenrodyellow", "lightgray", "lightgreen", "lightgrey", "lightpink",
    "lightsalmon", "lightseagreen", "lightskyblue", "lightslategray", "lightslategrey", "lightsteelblue",
    "lightyellow", "lime", "limegreen", "linen", "magenta", "maroon", "mediumaquamarine", "mediumblue",
    "mediumorchid", "mediumpurple", "mediumseagreen", "mediumslateblue", "mediumspringgreen", "mediumturquoise",
    "mediumvioletred", "midnightblue", "mintcream", "mistyrose", "moccasin", "navajowhite", "navy", "oldlace",
    "olive", "olivedrab", "orange", "orangered", "orchid", "palegoldenrod", "palegreen", "paleturquoise",
    "palevioletred", "papayawhip", "peachpuff", "peru", "pink", "plum", "powderblue", "purple", "rebeccapurple",
    "red", "rosybrown", "royalblue", "saddlebrown", "salmon", "sandybrown", "seagreen", "seashell", "sienna",
    "silver", "skyblue", "slateblue", "slategray", "slategrey", "snow", "springgreen", "steelblue", "tan", "teal",
    "thistle", "tomato", "turquoise", "violet", "wheat", "white", "whitesmoke", "yellow", "yellowgreen",
}
WORD = re.compile(r"(?<![\w.#-])[A-Za-z]+(?![\w(-])")
# the code before a string literal that makes the string a colour value: a style key or an SVG colour attribute
STYLE_KEY = re.compile(r"(?:(?:[cC]olor|background|fill|stroke|border\w*|outline\w*|[sS]hadow)\s*:"
                       r"|(?:fill|stroke|stopColor|stop-color)\s*=\s*\{?)\s*$")
TOKEN = "var(--ant-...)"


def interpolation_end(text: str, i: int) -> int | None:
    """The index just after the `}` that closes the interpolation of a template whose body starts at `i`, past the
    quotes, templates and braces nested in it; None when it does not close on its own line."""
    depth = 1
    while i < len(text):
        c = text[i]
        if c == "\n":
            return None
        if c in "'\"":
            end = i + 1
            while end < len(text) and text[end] not in (c, "\n"):
                end += 2 if text[end] == "\\" else 1
            if end >= len(text) or text[end] != c:
                return None
            i = end + 1
            continue
        if c == "`":
            i += 1
            while i < len(text) and text[i] != "`":
                if text[i] == "\n":
                    return None
                if text[i] == "\\":
                    i += 2
                elif text.startswith("${", i):
                    end = interpolation_end(text, i + 2)
                    if end is None:
                        return None
                    i = end
                else:
                    i += 1
            if i >= len(text):
                return None
            i += 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return None


def segments(text: str, kind: str) -> dict[int, list[tuple[str, str]]]:
    """Splits a source file into (segment kind, text) per line number; kinds are code, comment and string.

    `kind` is java, script, style or hash. Java knows // and /* */ comments, "..." and '...' literals and \"\"\" text
    blocks; script knows // and /* */ comments, '...', "..." and `...` literals, a template's interpolations on one
    line kept in its literal, and regular-expression literals;
    style knows /* */ comments and '...' and "..." strings; hash (shell, YAML, Python) knows a # comment that starts
    a line or follows whitespace, '...' and "..." strings and Python's \"\"\" and \'\'\' text blocks. A '...' or
    "..." literal ends at the line's end.
    """
    lines: dict[int, list[tuple[str, str]]] = {}
    line, i, n = 1, 0, len(text)
    state, buf, quote = "code", [], ""
    triple = '"""'
    last_code = ""  # the last code character that is not whitespace, to tell a regex literal from a division

    def flush(new_state: str) -> None:
        nonlocal buf, state
        if buf:
            lines.setdefault(line, []).append((segment_kind(state), "".join(buf)))
        buf, state = [], new_state

    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "\n":
            if state in ("line", "string", "regex"):
                flush("code")
            else:
                flush(state)
            line += 1
            i += 1
            continue
        if state == "code":
            if kind == "hash" and c == "#" and (i == 0 or text[i - 1].isspace()):
                flush("line"); buf.append("#"); i += 1; continue
            if kind == "hash" and text.startswith(("\"\"\"", "\'\'\'"), i):
                flush("text"); triple = text[i:i + 3]; i += 3; continue
            if kind == "hash" and c in "'\"":
                flush("string"); quote = c; i += 1; continue
            if kind == "hash":
                buf.append(c); i += 1; continue
            if c == "/" and nxt == "/" and kind != "style":
                flush("line"); buf.append("//"); i += 2; continue
            if c == "/" and nxt == "*":
                flush("block"); buf.append("/*"); i += 2; continue
            if kind == "java" and text.startswith('"""', i):
                flush("text"); triple = '"""'; i += 3; continue
            if c in "'\"" or (c == "`" and kind == "script"):
                flush("template" if c == "`" else "string"); quote = c; i += 1; continue
            if c == "/" and kind == "script" and (last_code == "" or last_code in "(,=:[!&|?{};+-*%~^"):
                flush("regex"); i += 1; continue
            buf.append(c)
            if not c.isspace():
                last_code = c
            i += 1
            continue
        if state == "block":
            if c == "*" and nxt == "/":
                buf.append("*/"); flush("code"); i += 2; continue
            buf.append(c); i += 1; continue
        if state == "line":
            buf.append(c); i += 1; continue
        if state == "regex":
            if c == "\\":
                i += 2; continue
            if c == "/":
                flush("code"); last_code = "/"; i += 1; continue
            i += 1; continue
        # string, template, text block; a backslash before a line end continues the line, and the line count goes on
        if c == "\\":
            if nxt == "\n":
                buf.append(c); flush(state); line += 1; i += 2; continue
            buf.append(text[i:i + 2]); i += 2; continue
        if state == "text" and text.startswith(triple, i):
            flush("code"); last_code = '"'; i += 3; continue
        if state == "template" and text.startswith("${", i):
            end = interpolation_end(text, i + 2)
            if end is not None:
                buf.append(text[i:end]); i = end; continue
        if state in ("string", "template") and c == quote:
            flush("code"); last_code = c; i += 1; continue
        buf.append(c); i += 1
    flush("code")
    return lines


def segment_kind(state: str) -> str:
    return {"line": "comment", "block": "comment", "string": "string", "template": "string",
            "text": "string"}.get(state, "code")


def file_kind(path: str) -> str | None:
    if path.endswith(".java"):
        return "java"
    if path.endswith(SCRIPT_SUFFIXES):
        return "script"
    if path.endswith(STYLE_SUFFIXES):
        return "style"
    if path.endswith(HASH_SUFFIXES):
        return "hash"
    return None


def comment_checked(path: str, kind: str) -> bool:
    if kind == "java":
        return path.startswith(JAVA_ROOTS)
    if kind == "hash":
        return True
    return path.startswith(UI_ROOT)


def references(comment: str) -> list[str]:
    found = []
    for m in REFERENCE.finditer(comment):
        tag = m.group(1) or m.group(2)
        if m.group(1) and tag.split("-", 1)[0] in STANDARDS:
            continue
        found.append(tag)
    return found


def colours_in_style(code: str) -> list[str]:
    found = [m.group(0) for m in HEX.finditer(code)]
    found += [m.group(0) + ")" for m in FUNCTION.finditer(code)]
    for decl in re.finditer(r":([^;{}]*)", code):
        found += [w.group(0) for w in WORD.finditer(decl.group(1)) if w.group(0).lower() in NAMED]
    return found


def colours_in_string(value: str, code_before: str) -> list[str]:
    found = [m.group(0) for m in HEX.finditer(value)]
    found += [m.group(0) + ")" for m in FUNCTION.finditer(value)]
    if value.strip().lower() in NAMED and STYLE_KEY.search(code_before):
        found.append(value.strip())
    return found


def is_text(value: str, code_before: str) -> bool:
    """Whether a string literal reads as a text for people: words, not code, a key or a value."""
    words = re.sub(r"\$\{[^}]*\}", " ", value).strip()
    if CODE_LIKE.search(words):
        return False
    if PROSE.search(words):
        return True
    return bool(WORD_TEXT.match(words)) and bool(TEXT_KEY.search(code_before))


def jsx_texts(code: str, whole_line: bool) -> list[str]:
    """The texts between tags on one line of TSX code: after a tag's `>` or an expression's `}`, or the whole line
    when it is only words."""
    found = []
    for m in JSX_AFTER_TAG.finditer(code):
        words = m.group(1).strip()
        if re.match(r"[A-Za-z]", words) and re.search(r"\b[A-Za-z]{2,}\b", words) and not re.search(r"[=()|&\[\]]", words) \
                and not re.match(r"\w+\??:", words):
            found.append(words)
    if JSX_TAG.search(code):
        for m in JSX_AFTER_VALUE.finditer(code):
            words = m.group(1).strip()
            if PROSE.search(words) and not re.search(r"[=()|&\[\]]", words) and words.split(" ", 1)[0] not in JS_WORDS:
                found.append(words)
    line = re.sub(r"\{[^{}]*\}", " ", code).strip()
    if whole_line and not found and re.fullmatch(r"[A-Za-z][\w'’,.!?:;…\- ]*", line) \
            and (PROSE.search(line) or WORD_TEXT.match(line)) \
            and line.split(" ", 1)[0] not in JS_WORDS and not {"instanceof", "typeof"} & set(line.split(" ")) and not re.match(r"\w+\??:", line) and not line.endswith((",", ";")):
        found.append(line)
    return found


def text_checked(path: str) -> bool:
    return path.startswith(UI_SOURCES) and path.endswith(SCRIPT_SUFFIXES) and not TEXT_SKIPPED.search(path)


def texts_in(path: str, parts: list[tuple[str, str]]) -> list[str]:
    """The texts written in place on one line, given as its segments. In TSX the line is read again whole, without
    its comments, for the texts between tags: an apostrophe in such a text reads as the start of a string."""
    found = []
    code_before = ""
    for seg, value in parts:
        if seg == "string" and is_text(value, code_before):
            found.append(value.strip())
        if seg == "code":
            code_before = value
    if path.endswith((".tsx", ".jsx")):
        code = "".join(value if seg == "code" else "'" + re.sub(r"\$\{[^}]*\}", " ", value) + "'" if seg == "string" else ""
                       for seg, value in parts)
        found += [text for text in jsx_texts(code, all(seg != "string" for seg, _ in parts)) if text not in found]
    return found


def unknown_keys(path: str, line: str, parts: list[tuple[str, str]], english: set[str]) -> list[str]:
    """The keys one line names, or gives a text, that English lacks."""
    if path.startswith(LOCALES) and path.endswith(".json"):
        if path.startswith(ENGLISH):
            return []
        m = LOCALE_KEY.match(line)
        return [m.group(1)] if m and m.group(1) not in english else []
    code = "".join(value if seg == "code" else f"'{value}'" if seg == "string" else "" for seg, value in parts)
    return [m.group(2) for m in KEY_USE.finditer(code) if m.group(2) not in english]


def check(path: str, text: str, changed: set[int], whole: bool = False, english: set[str] | None = None) -> list[str]:
    """The findings on the `changed` lines of one file; with `whole`, references in comments on every line."""
    findings = []
    lines = text.split("\n")
    if path.startswith(LOCALES) and path.endswith(".json") and english is not None:
        for number in sorted(changed):
            for key in unknown_keys(path, lines[number - 1] if number <= len(lines) else "", [], english):
                findings.append(f"{path}:{number}: the key {key} is not a key of English: add it to {ENGLISH}")
        return findings
    kind = file_kind(path)
    if kind is None:
        return []
    comments = comment_checked(path, kind)
    colours = kind in ("script", "style") and not COLOUR_SKIPPED.search(path)
    texts = kind == "script" and text_checked(path)
    if not comments and not colours and not texts:
        return []
    per_line = segments(text, kind)
    for number in sorted(changed | set(per_line) if whole else changed):
        parts = per_line.get(number, [])
        refs, cols = [], []
        code_before = ""
        colours_here = colours and number in changed
        for seg, value in parts:
            if seg == "comment" and comments and (whole or number in changed):
                refs += references(value)
            elif colours_here and seg == "code" and kind == "style":
                cols += colours_in_style(value)
            elif colours_here and seg == "string" and kind == "script":
                cols += colours_in_string(value, code_before)
            elif colours_here and seg == "string" and kind == "style":
                cols += colours_in_style(value)
            if seg == "code":
                code_before = value
        if refs:
            findings.append(f"{path}:{number}: a reference in a comment ({', '.join(refs)}): "
                            f"say what the code does and why instead")
        if cols:
            findings.append(f"{path}:{number}: a hard-coded colour ({', '.join(cols)}): use an Ant Design token, "
                            f"{TOKEN}")
        source = lines[number - 1] if number <= len(lines) else ""
        if texts and number in changed and not UNTRANSLATED.search(source):
            for words in texts_in(path, parts):
                findings.append(f"{path}:{number}: a text written in place (\"{words}\"): give it a key in "
                                f"{ENGLISH}<module>.json and show it with t('<key>')")
        if texts and number in changed and english is not None:
            for key in unknown_keys(path, source, parts, english):
                findings.append(f"{path}:{number}: the key {key} is not a key of English: add it to {ENGLISH}")
    return findings


def git(*args: str) -> str:
    done = subprocess.run(["git", *args], capture_output=True)
    if done.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {done.stderr.decode(errors='replace').strip()}")
    return done.stdout.decode("utf-8", errors="replace")


def changed_lines(base: str, head: str) -> dict[str, set[int]]:
    """The added and modified line numbers of every file the change adds, modifies or renames, in `head`."""
    diff = git("diff", "--no-color", "--no-ext-diff", "--unified=0", "--find-renames", "--diff-filter=d",
               base, head)
    result: dict[str, set[int]] = {}
    path = None
    for raw in diff.split("\n"):
        if raw.startswith("+++ "):
            path = raw[6:] if raw.startswith("+++ b/") else None
            if path is not None:
                result.setdefault(path, set())
        elif raw.startswith("@@") and path is not None:
            m = re.match(r"@@ -\S+ \+(\d+)(?:,(\d+))? @@", raw)
            if m:
                start, count = int(m.group(1)), int(m.group(2) if m.group(2) is not None else 1)
                result[path].update(range(start, start + count))
    return {p: lines for p, lines in result.items() if lines}


def english_keys(head: str) -> set[str]:
    """Every key of the English files of translations in `head`."""
    keys: set[str] = set()
    for path in git("ls-tree", "-r", "-z", "--name-only", head, "--", ENGLISH).split("\0"):
        if path.endswith(".json"):
            try:
                keys.update(json.loads(git("show", f"{head}:{path}")))
            except ValueError:
                raise RuntimeError(f"{path} is not a JSON object of texts")
    return keys


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True, help="the commit the change is compared with")
    ap.add_argument("--head", default="HEAD", help="the commit of the change (default HEAD)")
    args = ap.parse_args(argv)
    try:
        changed = changed_lines(args.base, args.head)
        cleaned = {p for p in git("ls-tree", "-r", "-z", "--name-only", args.head, "--", *CLEANED).split("\0")
                   if p.startswith(CLEANED)}
        english = english_keys(args.head)
        findings = []
        for path in sorted(set(changed) | cleaned):
            if file_kind(path) is None and not path.startswith(LOCALES):
                continue
            findings += check(path, git("show", f"{args.head}:{path}"), changed.get(path, set()), path in cleaned,
                              english)
    except RuntimeError as e:
        print(f"CHANGE GUARD: {e}", file=sys.stderr)
        return 2
    for f in findings:
        print(f)
    if findings:
        print(f"change guard: {len(findings)} finding(s)")
        return 1
    print(f"change guard: clean, {len(changed)} changed files and {len(cleaned)} files of cleaned folders checked")
    return 0


if __name__ == "__main__":
    sys.exit(main())
