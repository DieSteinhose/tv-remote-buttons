"""Shared helpers for profiles/profiles.json (used by the CI check and the report workflow)."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROFILES = ROOT / "profiles" / "profiles.json"
BUTTON_TYPES_JAVA = ROOT / "src" / "io" / "github" / "diesteinhose" / "tvremotebuttons" / "ButtonTypes.java"

SPECIAL_TARGETS = {"@system", "@none", "@home", "@livetv", "@epg", "@ha"}
KEY_RE = re.compile(r"^[A-Z0-9_]+$")
ID_RE = re.compile(r"^[a-z0-9_]+$")


def button_types():
    """Type ids defined in ButtonTypes.java."""
    return set(re.findall(r'new Type\("([a-z0-9_]+)"', BUTTON_TYPES_JAVA.read_text()))


def load():
    return json.loads(PROFILES.read_text())


def dump(data):
    """Canonical formatting of profiles.json (2 spaces, UTF-8, trailing newline)."""
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


def valid_target(t):
    if t in SPECIAL_TARGETS:
        return True
    if t.startswith(("http://", "https://", "intent:")):
        return True
    if re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", t):  # deep link
        return True
    return bool(re.match(r"^[A-Za-z0-9_.]+(/[A-Za-z0-9_.$]+)?$", t))  # package or package/class


def problems(data):
    """List of problems in a parsed profiles.json (empty = fine)."""
    out = []
    types = button_types()
    if data.get("schema") != 1:
        out.append("schema must be 1")
    ids = set()
    for p in data.get("profiles", []):
        pid = p.get("id", "?")
        if not ID_RE.match(pid):
            out.append(f"{pid}: id must match {ID_RE.pattern}")
        if pid in ids:
            out.append(f"{pid}: duplicate id")
        ids.add(pid)
        m = p.get("match", {})
        if not m.get("brand") and not m.get("hardware") and not m.get("remotes"):
            out.append(f"{pid}: match needs brand, hardware or remotes")
        for r in m.get("remotes", []):
            if not (r.get("name") or (r.get("vendor") and r.get("product"))):
                out.append(f"{pid}: a remote needs a name or vendor+product")
            for f in ("vendor", "product"):
                if f in r and not re.match(r"^0x[0-9a-f]{4}$", r[f]):
                    out.append(f"{pid}: remote {f} must look like 0x1d5a")
        for k in p.get("protect", []):
            if not (isinstance(k, int) or KEY_RE.match(str(k))):
                out.append(f"{pid}: bad protected key {k}")
        seen = set()
        for k in p.get("keys", []):
            key = k.get("key", k.get("code"))
            if key is None or not (isinstance(key, int) or KEY_RE.match(str(key))):
                out.append(f"{pid}: bad key {key}")
            if key in seen:
                out.append(f"{pid}: duplicate key {key}")
            seen.add(key)
            if k.get("type") not in types:
                out.append(f"{pid}: key {key}: unknown type {k.get('type')} (known: {', '.join(sorted(types))})")
            if not valid_target(k.get("default", "@system")):
                out.append(f"{pid}: key {key}: bad default target {k.get('default')}")
    return out
