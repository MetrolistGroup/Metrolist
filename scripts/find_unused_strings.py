#!/usr/bin/env python3
"""Conservatively audit Compose strings; dry-run by default, --apply to prune.

Any literal mention (including imports, comments and configuration)
protects a name. Literal dynamic prefixes protect every matching name. Generated
outputs and this auditor's tests are excluded; catalog declarations do not count
as references. Catalog XML references do count. No source files are rewritten.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
from xml.etree import ElementTree
from xml.parsers import expat

ROOT = Path(__file__).resolve().parents[1]
CATALOG = Path("shared/src/commonMain/composeResources")
KINDS = {"string", "plurals", "string-array"}
SKIP_DIRS = {".git", ".gradle", ".kotlin", ".idea", "build", "node_modules", "__pycache__", ".venv", "metroproto", "libopus"}
SKIP_FILES = {".git", "find_unused_strings.py", "test_find_unused_strings.py"}
PREFIX = re.compile(r'''["']([a-z][a-z0-9_]*)(?=\$|\{|%|\\\(|["']\s*\+|["']\s*\.\s*(?:format|formatted)\s*\()''')
XML_REFERENCE = re.compile(r"@(?:[\w.]+:)?(?:string|plurals|array)/([\w]+)")


def resource_names(path: Path) -> set[str]:
    names = [item.attrib["name"] for item in ElementTree.parse(path).getroot() if item.tag in KINDS]
    if len(names) != len(set(names)):
        raise ValueError(f"Duplicate resource names in {path}")
    return set(names)


def source_files(root: Path):
    for directory, dirs, files in os.walk(root, followlinks=False):
        dirs[:] = sorted(name for name in dirs if name not in SKIP_DIRS and not (Path(directory) / name).is_symlink())
        for name in sorted(files):
            path = Path(directory) / name
            if name not in SKIP_FILES and not path.is_symlink():
                yield path


def audit(root: Path) -> dict:
    catalogs = sorted((root / CATALOG).glob("values*/strings.xml"))
    base = resource_names(root / CATALOG / "values/strings.xml")
    orphans = {}
    for path in catalogs:
        extra = resource_names(path) - base
        if extra:
            orphans[str(path.relative_to(root))] = sorted(extra)
    references = {}
    prefixes = {}
    catalog_paths = set(catalogs)
    for path in source_files(root):
        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        if "\0" in content:
            continue
        relative = str(path.relative_to(root))
        # Do not let declarations or translation prose protect their own names.
        if path in catalog_paths:
            content = "\n".join(XML_REFERENCE.findall(content))
        for name in base - references.keys():
            if name in content:
                line = content[:content.index(name)].count("\n") + 1
                references[name] = f"{relative}:{line}"
        for match in PREFIX.finditer(content):
            prefix = match[1]
            if any(name.startswith(prefix) for name in base):
                line = content[:match.start()].count("\n") + 1
                prefixes.setdefault(prefix, []).append(f"{relative}:{line}")
    dynamic = {
        name: sorted(prefix for prefix in prefixes if name.startswith(prefix))
        for name in sorted(base)
        if any(name.startswith(prefix) for prefix in prefixes)
    }
    return {
        "base_count": len(base),
        "unused": sorted(base - references.keys() - dynamic.keys()),
        "references": dict(sorted(references.items())),
        "dynamic_keys": dynamic,
        "dynamic_prefixes": dict(sorted(prefixes.items())),
        "translation_orphans": orphans,
    }


def prune_xml(data: bytes, names: set[str]) -> bytes:
    """Use XML byte offsets to remove only elements, preserving all other bytes."""
    parser = expat.ParserCreate()
    depth = 0
    start = None
    spans = []

    def opened(tag, attrs):
        nonlocal depth, start
        if depth == 1 and tag in KINDS and attrs.get("name") in names:
            start = parser.CurrentByteIndex
        depth += 1

    def closed(tag):
        nonlocal depth, start
        depth -= 1
        if depth != 1 or start is None:
            return
        end = parser.CurrentByteIndex
        closing = re.match(rb"</" + tag.encode() + rb"\s*>", data[end:])
        if closing:
            end += closing.end()
        line_start = data.rfind(b"\n", 0, start) + 1
        line_end = data.find(b"\n", end)
        if line_end < 0:
            line_end = len(data)
        if not data[line_start:start].strip() and not data[end:line_end].strip():
            start = line_start
            end = min(line_end + 1, len(data))
        spans.append((start, end))
        start = None

    parser.StartElementHandler = opened
    parser.EndElementHandler = closed
    parser.Parse(data, True)
    for start, end in reversed(spans):
        data = data[:start] + data[end:]
    ElementTree.fromstring(data)
    return data


def apply(root: Path, names: set[str]) -> list[str]:
    changes = []
    for path in sorted((root / CATALOG).glob("values*/strings.xml")):
        original = path.read_bytes()
        updated = prune_xml(original, names)
        if original != updated:
            changes.append((path, updated))
    for path, updated in changes:
        path.write_bytes(updated)
    return [str(path.relative_to(root)) for path, _ in changes]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="remove only unreferenced base keys from all catalogs")
    args = parser.parse_args()
    report = audit(ROOT)
    report["mode"] = "apply" if args.apply else "dry-run"
    if args.apply:
        report["changed_files"] = apply(ROOT, set(report["unused"]))
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
