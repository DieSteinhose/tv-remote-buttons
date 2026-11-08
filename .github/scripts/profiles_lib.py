"""Shared helpers for the device profiles (used by the CI check and the report agent workflow).

profiles/devices.json (schema 2) is the source: remote profiles (the buttons of a remote) and TV
profiles (live TV app, program guide, quirks), which the app combines. profiles/profiles.json
(schema 1, one profile per TV and remote) is generated from it for app versions up to 1.6.
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEVICES = ROOT / "profiles" / "devices.json"
PROFILES_V1 = ROOT / "profiles" / "profiles.json"
DEVICES_MD = ROOT / "DEVICES.md"
BUTTON_TYPES_JAVA = ROOT / "src" / "de" / "diesteinhose" / "tvremotebuttons" / "ButtonTypes.java"

SPECIAL_TARGETS = {"@system", "@none", "@home", "@livetv", "@epg", "@ha"}
KEY_RE = re.compile(r"^[A-Z0-9_]+$")
ID_RE = re.compile(r"^[a-z0-9_]+$")
HEX_RE = re.compile(r"^0x[0-9a-f]{4}$")


def button_types():
    """Type ids defined in ButtonTypes.java."""
    return set(re.findall(r'new Type\("([a-z0-9_]+)"', BUTTON_TYPES_JAVA.read_text()))


def load():
    return json.loads(DEVICES.read_text())


def dump(data):
    """Canonical formatting (2 spaces, UTF-8, trailing newline)."""
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


def valid_target(t):
    if t in SPECIAL_TARGETS:
        return True
    if t.startswith(("http://", "https://", "intent:")):
        return True
    if re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", t):  # deep link
        return True
    return bool(re.match(r"^[A-Za-z0-9_.]+(/[A-Za-z0-9_.$]+)?$", t))  # package or package/class


def _keys_problems(pid, p, types):
    out = []
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


def problems(data):
    """List of problems in a parsed devices.json (empty = fine)."""
    out = []
    types = button_types()
    if data.get("schema") != 2:
        out.append("schema must be 2")
    remotes, tvs = data.get("remotes", []), data.get("tvs", [])
    ids = set()
    for p in remotes + tvs:
        pid = p.get("id", "?")
        if not ID_RE.match(pid):
            out.append(f"{pid}: id must match {ID_RE.pattern}")
        if pid in ids:
            out.append(f"{pid}: duplicate id (ids are unique across remotes and TVs)")
        ids.add(pid)
        for old in p.get("legacy", []):
            if not ID_RE.match(old):
                out.append(f"{pid}: bad legacy id {old}")
    remote_ids = {r.get("id") for r in remotes}
    tv_ids = {t.get("id") for t in tvs}
    for r in remotes:
        pid = r.get("id", "?")
        m = r.get("match", {})
        if not m.get("inputs"):
            out.append(f"{pid}: a remote needs match.inputs")
        for i in m.get("inputs", []):
            if not (i.get("name") or (i.get("vendor") and i.get("product"))):
                out.append(f"{pid}: an input needs a name or vendor+product")
            for f in ("vendor", "product"):
                if f in i and not HEX_RE.match(i[f]):
                    out.append(f"{pid}: input {f} must look like 0x1d5a")
        for t in m.get("tvs", []):
            if t not in tv_ids:
                out.append(f"{pid}: match.tvs refers to unknown TV {t}")
        if not r.get("keys"):
            out.append(f"{pid}: a remote needs keys")
        out += _keys_problems(pid, r, types)
    for t in tvs:
        pid = t.get("id", "?")
        m = t.get("match", {})
        if not m.get("brand") and not m.get("hardware"):
            out.append(f"{pid}: a TV needs match.brand or match.hardware")
        if "liveTv" in t and not re.match(r"^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$", t["liveTv"]):
            out.append(f"{pid}: liveTv must be package/class")
        for r in t.get("remotes", []):
            if r not in remote_ids:
                out.append(f"{pid}: remotes refers to unknown remote {r}")
        out += _keys_problems(pid, t, types)
    return out


def build_v1(data):
    """profiles.json (schema 1) for app versions up to 1.6: one profile per TV and its remotes."""
    remotes = {r["id"]: r for r in data.get("remotes", [])}
    profiles, paired = [], set()

    def v1(pid, title, notes, tv, remote):
        p = {"id": pid, "title": title}
        if notes:
            p["notes"] = notes
        match = {}
        if tv:
            match.update({k: v for k, v in tv.get("match", {}).items() if k in ("brand", "hardware")})
        if remote:
            match["remotes"] = remote["match"]["inputs"]
        p["match"] = match
        if tv and tv.get("liveTv"):
            p["liveTv"] = tv["liveTv"]
        if tv and tv.get("epg"):
            p["epg"] = True
        protect = (tv or {}).get("protect", []) + [k for k in (remote or {}).get("protect", [])
                                                   if k not in (tv or {}).get("protect", [])]
        if protect:
            p["protect"] = protect
        p["keys"] = (remote or {}).get("keys", [])
        min_app = max((tv or {}).get("minApp", 0), (remote or {}).get("minApp", 0))
        if min_app:
            p["minApp"] = min_app
        return p

    for tv in data.get("tvs", []):
        for rid in tv.get("remotes", []):
            r = remotes.get(rid)
            if not r:
                continue
            paired.add(rid)
            common = [x for x in tv.get("legacy", []) if x in r.get("legacy", [])]
            pid = common[0] if common else f"{tv['id']}__{rid}"
            notes = " ".join(x for x in (tv.get("notes"), r.get("notes")) if x)
            profiles.append(v1(pid, tv.get("title", tv["id"]), notes, tv, r))
    for rid, r in remotes.items():
        if rid not in paired:
            pid = (r.get("legacy") or [rid])[0]
            profiles.append(v1(pid, r.get("title", rid), r.get("notes"), None, r))
    return {"schema": 1, "generated": "from devices.json - do not edit", "profiles": profiles}
