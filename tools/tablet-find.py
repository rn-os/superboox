#!/usr/bin/env python3
"""Finds nodes in a uiautomator dump for tablet-test.sh. Never taps anything itself.

  tablet-find.py <dump.xml> <mode> [selector ...] [--pkg P]
  modes:
    list                         every node of the package: text | desc | bounds
    find  text|desc|rid|textprefix|descprefix <value>
                                 prints "cx cy left top right bottom" of the one match (exit 3 none, 4 several)
    has   <same selector>        exit 0 if any match, else 3
    packages                     the packages that have nodes in the dump
    bottomright <desc prefix> <desc>...   exit 0 if the node with the desc prefix has the
                                 right-most right edge and the lowest bottom edge of itself plus the nodes with the given descs
"""
import re
import sys
import xml.etree.ElementTree as ET

args = sys.argv[1:]
pkg = "com.superboox.quickaccess"
if "--pkg" in args:
    i = args.index("--pkg")
    pkg = args[i + 1]
    del args[i:i + 2]
path, mode, rest = args[0], args[1], args[2:]
nodes = []
for n in ET.parse(path).iter("node"):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds", ""))
    if not m:
        continue
    l, t, r, b = map(int, m.groups())
    nodes.append({"pkg": n.get("package", ""), "text": n.get("text", ""), "desc": n.get("content-desc", ""),
                  "rid": n.get("resource-id", ""), "b": (l, t, r, b), "clickable": n.get("clickable") == "true"})


def match(n, kind, value):
    return {
        "text": n["text"] == value, "desc": n["desc"] == value, "rid": n["rid"] == value,
        "textprefix": n["text"].startswith(value), "descprefix": n["desc"].startswith(value),
    }[kind]


def matches(kind, value):
    return [n for n in nodes if n["pkg"] == pkg and match(n, kind, value) and n["b"][2] > n["b"][0] and n["b"][3] > n["b"][1]]


if mode == "packages":
    print("\n".join(sorted({n["pkg"] for n in nodes})))
elif mode == "list":
    for n in nodes:
        if n["pkg"] == pkg and (n["text"] or n["desc"]):
            print(f'{n["text"]} | {n["desc"]} | {n["b"]}')
elif mode in ("find", "has"):
    found = matches(rest[0], rest[1])
    if mode == "has":
        sys.exit(0 if found else 3)
    if not found:
        sys.exit(3)
    if len(found) > 1:
        print(f"{len(found)} matches for {rest[0]}={rest[1]}", file=sys.stderr)
        sys.exit(4)
    l, t, r, b = found[0]["b"]
    print((l + r) // 2, (t + b) // 2, l, t, r, b)
elif mode == "bottomright":
    prefix, descs = rest[0], rest[1:]
    me = matches("descprefix", prefix)
    if len(me) != 1:
        print(f"{len(me)} nodes for {prefix}", file=sys.stderr)
        sys.exit(3)
    others = [n for d in descs for n in matches("desc", d)]
    allb = [me[0]["b"]] + [n["b"] for n in others]
    ok = me[0]["b"][2] >= max(b[2] for b in allb) and me[0]["b"][3] >= max(b[3] for b in allb)
    print(f"previous={me[0]['b']} others={len(others)} {'bottom-right' if ok else 'NOT bottom-right'}")
    sys.exit(0 if ok else 1)
else:
    sys.exit(2)
