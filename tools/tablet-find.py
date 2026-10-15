#!/usr/bin/env python3
"""Finds nodes in the panel dump (TestHooks `dump_ui` JSON) for tablet-test.sh. Never taps anything itself.

  tablet-find.py <dump.json> <mode> [selector ...]
  modes:
    list                         every node: text | desc | bounds
    find  <kind> <value>         prints "cx cy left top right bottom" of the one match (exit 3 none, 4 several)
    has   <kind> <value>         exit 0 if any match, else 3
    bottomright <desc prefix> <desc>...   exit 0 if the node with the desc prefix has the right-most right edge and
                                 the lowest bottom edge of itself plus the nodes with the given descs
  kinds: text, desc, textprefix, descprefix, label (text or desc), labelprefix.
  Several matches: the clickable one if exactly one is, else ambiguous.
"""
import json
import sys

path, mode, rest = sys.argv[1], sys.argv[2], sys.argv[3:]
nodes = json.load(open(path))


def match(n, kind, v):
    t, d = n["text"], n["desc"]
    return {
        "text": t == v, "desc": d == v, "textprefix": t.startswith(v), "descprefix": d.startswith(v),
        "label": v in (t, d), "labelprefix": t.startswith(v) or d.startswith(v),
    }[kind]


def matches(kind, v):
    return [n for n in nodes if match(n, kind, v)]


if mode == "list":
    for n in nodes:
        print(f'{n["text"]} | {n["desc"]} | {n["b"]}')
elif mode in ("find", "has"):
    found = matches(rest[0], rest[1])
    if mode == "has":
        sys.exit(0 if found else 3)
    if not found:
        sys.exit(3)
    if len(found) > 1:
        click = [n for n in found if n["clickable"]]
        if len(click) != 1:
            print(f"{len(found)} matches for {rest[0]}={rest[1]}", file=sys.stderr)
            sys.exit(4)
        found = click
    l, t, r, b = found[0]["b"]
    print((l + r) // 2, (t + b) // 2, l, t, r, b)
elif mode == "bottomright":
    prefix, descs = rest[0], rest[1:]
    me = matches("descprefix", prefix)
    if len(me) != 1:
        print(f"{len(me)} nodes for {prefix}", file=sys.stderr)
        sys.exit(3)
    allb = [me[0]["b"]] + [n["b"] for d in descs for n in matches("desc", d)]
    ok = me[0]["b"][2] >= max(b[2] for b in allb) and me[0]["b"][3] >= max(b[3] for b in allb)
    print(f"previous={me[0]['b']} others={len(allb) - 1} {'bottom-right' if ok else 'NOT bottom-right'}")
    sys.exit(0 if ok else 1)
else:
    sys.exit(2)
