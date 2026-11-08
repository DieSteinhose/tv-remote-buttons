// Receives device reports from the TV Remote Buttons app (POST /report, JSON) and files each one
// as a GitHub issue with the label "device-report". The app needs no account or token: the
// GitHub token (env.GITHUB_TOKEN) only exists here. IP addresses are used for rate limiting only.

const MAX_BYTES = 20000;
const MAX_KEYS = 200;
const LABEL = "device-report";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== "/report") return reply({ error: "not found" }, 404);
    if (request.method !== "POST") return reply({ error: "use POST" }, 405);

    if (env.LIMITER) {
      const ip = request.headers.get("cf-connecting-ip") || "unknown";
      const { success } = await env.LIMITER.limit({ key: ip });
      if (!success) return reply({ error: "too many reports, try again later" }, 429);
    }

    const text = await request.text();
    if (text.length > MAX_BYTES) return reply({ error: "report too large" }, 413);
    let report;
    try {
      report = JSON.parse(text);
    } catch {
      return reply({ error: "invalid JSON" }, 400);
    }
    const problem = validate(report);
    if (problem) return reply({ error: problem }, 400);

    const res = await fetch(`https://api.github.com/repos/${env.REPO}/issues`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.GITHUB_TOKEN}`,
        Accept: "application/vnd.github+json",
        "User-Agent": "tv-remote-buttons-worker",
        "X-GitHub-Api-Version": "2022-11-28",
      },
      body: JSON.stringify({ title: title(report), body: body(report), labels: [LABEL] }),
    });
    if (!res.ok) {
      console.log("GitHub", res.status, await res.text());
      return reply({ error: "could not store report" }, 502);
    }
    const issue = await res.json();
    return reply({ issue: issue.number, url: issue.html_url });
  },
};

function reply(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json" } });
}

function validate(r) {
  if (!r || typeof r !== "object" || Array.isArray(r)) return "report must be an object";
  if (!r.device || typeof r.device !== "object") return "device missing";
  if (!Array.isArray(r.keys) || r.keys.length > MAX_KEYS) return "keys missing or too many";
  if (r.remotes !== undefined && !Array.isArray(r.remotes)) return "remotes must be a list";
  for (const k of r.keys) {
    if (!k || typeof k !== "object" || !Number.isInteger(k.keycode)) return "every key needs a keycode";
  }
  return null;
}

/** Plain text for a code span: no backticks, pipes or line breaks, limited length. */
function cell(v, max = 80) {
  if (v === undefined || v === null || v === "") return "";
  const s = String(v).replace(/[`|\r\n]/g, " ").trim().slice(0, max);
  return s ? "`" + s + "`" : "";
}

/** Title text without characters that could act as markup; limited length. */
function plain(v, max = 60) {
  return String(v ?? "").replace(/[`<>@\[\]\r\n]/g, " ").replace(/\s+/g, " ").trim().slice(0, max);
}

function title(r) {
  const d = r.device;
  const name = [d.brand, d.product, d.model ? `(${d.model})` : ""].map((x) => plain(x, 40)).filter(Boolean).join(" ");
  return `Device report: ${name || "unknown device"}`.slice(0, 120);
}

function body(r) {
  const d = r.device;
  const lines = [];
  lines.push(`**TV:** ${cell(d.brand)} ${cell(d.product)} ${cell(d.model)} · Android ${cell(d.android)} (SDK ${cell(d.sdk)}) · hardware ${cell(d.hardware)} · manufacturer ${cell(d.manufacturer)}`);
  lines.push(`**App:** ${cell(r.app)} · profile ${cell(r.profile) || "none"} · detected ${cell(r.detected) || "none"}`);
  const remotes = (r.remotes || []).slice(0, 10).map((x) => `${cell(x && x.name)} (vendor ${cell(x && x.vendor)}, product ${cell(x && x.product)})`);
  lines.push(`**Remotes:** ${remotes.join(", ") || "none found"}`);
  lines.push("");
  lines.push("| Keycode | Key | Type | Own name | Scan code | Remote | Source |");
  lines.push("|---|---|---|---|---|---|---|");
  for (const k of r.keys) {
    lines.push(`| ${cell(k.keycode)} | ${cell(k.key)} | ${cell(k.type)} | ${cell(k.name)} | ${cell(k.scan)} | ${cell(k.remote)} | ${cell(k.source)} |`);
  }
  if (r.note) {
    lines.push("", "**Note:**", "```text", String(r.note).replace(/`/g, "'").slice(0, 2000), "```");
  }
  const raw = JSON.stringify(r, null, 2).replace(/`/g, "'");
  lines.push("", "<details><summary>Raw report</summary>", "", "```json", raw, "```", "", "</details>");
  return lines.join("\n");
}
