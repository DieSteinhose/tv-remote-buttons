"""Extracts the report JSON from a device report issue for the report agent.

Input:  path to the issue JSON (`gh api` output) as argv[1].
Output: .agent/report.json (the parsed report, re-serialized), .agent/issue.txt (issue number)
        and the workflow output valid=true|false.

The issue body is untrusted: only the JSON block written by the report worker is parsed as data.
"""
import json
import os
import re
import sys
from pathlib import Path

issue = json.load(open(sys.argv[1]))
issue = issue.get("issue", issue)
m = re.search(r"```json\n(.*?)\n```", issue.get("body") or "", re.S)
try:
    report = json.loads(m.group(1)) if m else None
except ValueError:
    report = None
valid = (isinstance(report, dict) and isinstance(report.get("device"), dict)
         and isinstance(report.get("mapping"), list) and len(report["mapping"]) > 0)

out = Path(".agent")
out.mkdir(exist_ok=True)
if valid:
    (out / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
(out / "issue.txt").write_text(f"{issue['number']}\n")
with open(os.environ.get("GITHUB_OUTPUT", "/dev/null"), "a") as f:
    f.write(f"valid={'true' if valid else 'false'}\n")
print("valid" if valid else "invalid")
