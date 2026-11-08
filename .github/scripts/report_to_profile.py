"""Turns a device report issue into a profile proposal for profiles/profiles.json.

Input:  path to the issue JSON (GitHub event payload or `gh api` output) as argv[1].
Output: profiles/profiles.json updated in place, plus outputs for the workflow
        (action=pr|covered|invalid, branch, title) and a markdown file for the PR or comment.

The issue body is untrusted. Only the JSON block written by the report worker is read, parsed as
data and written back with json; nothing from it is executed or used in shell commands.
"""
import json
import os
import re
import sys

import profiles_lib as lib

issue = json.load(open(sys.argv[1]))
issue = issue.get("issue", issue)
number = issue["number"]
out_md = os.environ.get("REPORT_MD", "report.md")


def output(**kw):
    with open(os.environ.get("GITHUB_OUTPUT", "/dev/null"), "a") as f:
        for k, v in kw.items():
            f.write(f"{k}={v}\n")


def finish(action, text, **kw):
    with open(out_md, "w") as f:
        f.write(text)
    output(action=action, **kw)
    print(action)
    sys.exit(0)


def clean(v, pattern=r"[^A-Za-z0-9 ._()+-]", n=60):
    return re.sub(r"\s+", " ", re.sub(pattern, " ", str(v or ""))).strip()[:n]


m = re.search(r"```json\n(.*?)\n```", issue.get("body") or "", re.S)
try:
    report = json.loads(m.group(1)) if m else None
except ValueError:
    report = None
mapping = report.get("mapping") if isinstance(report, dict) else None
if not isinstance(mapping, list) or not mapping or not isinstance(report.get("device"), dict):
    finish("invalid", "This report has no usable button mapping, so no profile was proposed.\n")

types = lib.button_types()
dev = report["device"]
keys, remotes = [], []
for e in mapping[:100]:
    if not isinstance(e, dict) or not lib.KEY_RE.match(str(e.get("key", ""))):
        continue
    if e.get("keycode") == 0:  # no keycode (only a scan code): cannot be part of a profile
        continue
    t = e.get("type") if e.get("type") in types else "other"
    if any(k["key"] == e["key"] for k in keys):
        continue
    keys.append({"key": e["key"], "type": t, "default": "@system"})
    r = {f: clean(e[f], r"[^A-Za-z0-9 ._()-]", 40) for f in ("remote", "vendor", "product") if e.get(f)}
    rem = {"name": r["remote"]} if r.get("remote") else {}
    for f in ("vendor", "product"):
        if re.match(r"^0x[0-9a-f]{4}$", r.get(f, "")):
            rem[f] = r[f]
    if rem and rem not in remotes:
        remotes.append(rem)
if not keys:
    finish("invalid", "The button mapping of this report contains no usable keys.\n")

data = lib.load()
by_id = {p["id"]: p for p in data["profiles"]}

# Already covered by the profile the app detected or used?
for pid in (report.get("profile"), report.get("detected")):
    p = by_id.get(pid) if isinstance(pid, str) else None
    if p and all(any(k.get("key") == n["key"] and k.get("type") == n["type"] for k in p["keys"]) for n in keys):
        finish("covered", f"All mapped buttons are already covered by the profile `{pid}`. Thanks!\n")

brand, product, model = clean(dev.get("brand")), clean(dev.get("product")), clean(dev.get("model"))
tv_model = clean(dev.get("tvModel"), r"[^A-Za-z0-9 ./_+-]", 40)  # vendor model number, e.g. 65OLED888/12 (newer app versions)
# Extend the profile the app used or detected; otherwise create one named after the TV
used = [x for x in (report.get("profile"), report.get("detected")) if isinstance(x, str) and x in by_id]
pid = used[0] if used else (
    re.sub(r"[^a-z0-9]+", "_", f"{brand}_{product or model}".lower()).strip("_")[:40] or f"report_{number}")
hardware = clean(dev.get("hardware"), r"[^A-Za-z0-9_-]", 20).lower()

if pid in by_id:
    p = by_id[pid]
    known = {k["key"] for k in p["keys"]}
    added = [k for k in keys if k["key"] not in known]
    p["keys"].extend(added)
    rs = p.setdefault("match", {}).setdefault("remotes", [])
    new_remotes = [r for r in remotes if r not in rs]
    rs.extend(new_remotes)
    title = f"Update profile {pid} from report #{number}"
    what = (f"Adds {len(added)} button(s) and {len(new_remotes)} remote(s) from report #{number} "
            f"to the existing profile `{pid}`.")
else:
    match = {"brand": [brand]} if brand else {}
    if hardware:
        match["hardware"] = hardware
    if remotes:
        match["remotes"] = remotes
    p = {"id": pid, "title": f"{brand} {product} ({model})".replace("()", "").strip(),
         "notes": f"From device report #{number}" + (f", tested: {tv_model}" if tv_model else "")
                  + f", Android {clean(dev.get('android'), n=10)}. Review the default targets.",
         "match": match, "keys": keys}
    data["profiles"].append(p)
    title = f"Add profile {pid} from report #{number}"
    what = f"Adds the profile `{pid}` generated from report #{number}."

errors = lib.problems(data)
if errors:
    finish("invalid", "The generated profile failed the checks:\n\n" + "\n".join(f"- {e}" for e in errors) + "\n")
lib.PROFILES.write_text(lib.dump(data))
finish("pr", f"""{what}

Closes #{number} when merged. Before merging, review:

- the button types (from the user's mapping),
- the default targets (all `@system` = factory behavior; e.g. use `@livetv`/`@epg` where the factory function is broken),
- the match rules (brand, hardware, remotes) so the profile does not catch unrelated TVs.

```json
{json.dumps(p, indent=2, ensure_ascii=False)}
```
""", branch=f"profile/report-{number}", title=title)
