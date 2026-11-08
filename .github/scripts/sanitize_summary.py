"""Makes the agent's summary safe to post: no mentions, no raw HTML, limited length."""
import re
import sys

text = open(sys.argv[1]).read() if len(sys.argv) > 1 else ""
text = re.sub(r"@(?=[A-Za-z0-9_-])", "@​", text)      # no @mentions
text = text.replace("<", "&lt;").replace(">", "&gt;")       # no HTML
text = re.sub(r"https?://\S+", "(link removed)", text)      # no links
print((text.strip() or "The agent wrote no summary.")[:6000])
