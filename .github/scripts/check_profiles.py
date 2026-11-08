"""CI check for profiles/profiles.json: structure, known types/targets, canonical formatting."""
import sys

import profiles_lib as lib

text = lib.PROFILES.read_text()
data = lib.load()
errors = lib.problems(data)
if text != lib.dump(data):
    errors.append("profiles.json is not canonically formatted - run: python3 .github/scripts/check_profiles.py --fix")
    if "--fix" in sys.argv:
        lib.PROFILES.write_text(lib.dump(data))
        errors.pop()
        print("formatted profiles.json")
for e in errors:
    print("::error file=profiles/profiles.json::" + e)
print(f"{len(data.get('profiles', []))} profiles, {len(errors)} problems")
sys.exit(1 if errors else 0)
