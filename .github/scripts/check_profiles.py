"""CI check for profiles/devices.json: structure, known types/targets, canonical formatting, the
generated profiles/profiles.json for older apps, and a section per profile in DEVICES.md.

--fix formats devices.json and regenerates profiles.json.
"""
import sys

import profiles_lib as lib

fix = "--fix" in sys.argv
text = lib.DEVICES.read_text()
data = lib.load()
errors = lib.problems(data)

if text != lib.dump(data):
    if fix:
        lib.DEVICES.write_text(lib.dump(data))
        print("formatted devices.json")
    else:
        errors.append("devices.json is not canonically formatted - run: python3 .github/scripts/check_profiles.py --fix")

v1 = lib.dump(lib.build_v1(data))
if not lib.PROFILES_V1.exists() or lib.PROFILES_V1.read_text() != v1:
    if fix:
        lib.PROFILES_V1.write_text(v1)
        print("regenerated profiles.json")
    else:
        errors.append("profiles.json is out of date - run: python3 .github/scripts/check_profiles.py --fix")

devices_md = lib.DEVICES_MD.read_text() if lib.DEVICES_MD.exists() else ""
for p in data.get("remotes", []) + data.get("tvs", []):
    if f"### {p.get('id')}\n" not in devices_md:
        errors.append(f"{p.get('id')}: missing section '### {p.get('id')}' in DEVICES.md")

for e in errors:
    print("::error file=profiles/devices.json::" + e)
print(f"{len(data.get('remotes', []))} remotes, {len(data.get('tvs', []))} TVs, {len(errors)} problems")
sys.exit(1 if errors else 0)
