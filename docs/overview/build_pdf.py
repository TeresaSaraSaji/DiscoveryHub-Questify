#!/usr/bin/env python3
"""Render content.py to a print-ready HTML document, then to PDF via headless Chrome."""

import html
import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from content import BYLINE, FOOTER, SECTIONS, SUBTITLE, TITLE  # noqa: E402

HERE = Path(__file__).parent
HTML_OUT = HERE / "DiscoveryHub-Technical-Overview.html"
PDF_OUT = HERE / "DiscoveryHub-Technical-Overview.pdf"

CHROME_CANDIDATES = [
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
    shutil.which("google-chrome") or "",
    shutil.which("chromium") or "",
]


def inline(text):
    """`code`, **bold** and *italic* inside an already-escaped string."""
    out = html.escape(text)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    # Bold first, non-greedy, so a nested *italic* inside a **bold** span still matches.
    out = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", out)
    out = re.sub(r"(?<!\*)\*([^*]+)\*(?!\*)", r"<em>\1</em>", out)
    return out


def render_block(kind, payload):
    if kind == "lede":
        return f'<p class="lede">{inline(payload)}</p>'
    if kind == "para":
        return f"<p>{inline(payload)}</p>"
    if kind == "note":
        return f'<div class="note">{inline(payload)}</div>'
    if kind == "code":
        return f'<pre class="code">{html.escape(payload)}</pre>'
    if kind == "diagram":
        return f'<pre class="diagram">{html.escape(payload)}</pre>'
    if kind == "bullets":
        items = "".join(f"<li>{inline(b)}</li>" for b in payload)
        return f"<ul>{items}</ul>"
    if kind == "table":
        headers, rows = payload
        blank_header = not any(h.strip() for h in headers)
        head = ""
        if not blank_header:
            head = "<thead><tr>" + "".join(
                f"<th>{inline(h)}</th>" for h in headers) + "</tr></thead>"
        body = "".join(
            "<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>" for r in rows)
        cls = "grid" if blank_header else ""
        return f'<table class="{cls}">{head}<tbody>{body}</tbody></table>'
    raise ValueError(f"unknown block type {kind!r}")


CSS = """
@page { size: A4; margin: 16mm 15mm 18mm 15mm; }
* { box-sizing: border-box; }
body {
  font-family: -apple-system, "Helvetica Neue", Arial, sans-serif;
  font-size: 10.2pt; line-height: 1.5; color: #1a1d21; margin: 0;
  -webkit-print-color-adjust: exact; print-color-adjust: exact;
}
code, pre { font-family: "SF Mono", Menlo, Consolas, monospace; }

/* cover */
.cover { height: 247mm; display: flex; flex-direction: column; justify-content: center;
         page-break-after: always; border-left: 5px solid #1f4e79; padding-left: 14mm; }
.cover h1 { font-size: 40pt; margin: 0; letter-spacing: -1.2px; color: #14304a; }
.cover .sub { font-size: 15pt; color: #37506a; margin-top: 10px; font-weight: 500; }
.cover .by  { font-size: 10.5pt; color: #667a8c; margin-top: 26px; }
.cover .rule { width: 70px; border-top: 3px solid #c9a227; margin: 26px 0; }
.cover .meta { font-size: 9pt; color: #8a97a3; margin-top: 40px; line-height: 1.8; }

/* contents */
.toc { page-break-after: always; }
.toc h2 { font-size: 19pt; color: #14304a; margin: 0 0 16px; }
.toc ol { padding-left: 22px; }
.toc li { margin: 5px 0; font-size: 10.5pt; }
.toc a { color: #1f4e79; text-decoration: none; }

/* sections */
section { page-break-before: always; }
h2 { font-size: 17pt; color: #14304a; margin: 0 0 4px; letter-spacing: -0.3px;
     border-bottom: 2px solid #c9a227; padding-bottom: 7px; }
.num { color: #a8b4bf; font-weight: 600; margin-right: 9px; }
p { margin: 9px 0; }
.lede { font-size: 11.6pt; color: #2c3e50; font-weight: 500; line-height: 1.45;
        border-left: 3px solid #c9d4de; padding-left: 12px; margin: 12px 0 14px; }
ul { margin: 9px 0; padding-left: 19px; }
li { margin: 5px 0; }
strong { color: #14304a; }
code { background: #f1f4f7; padding: 1px 4px; border-radius: 3px; font-size: 8.9pt;
       color: #9c2c4e; }

pre.code { background: #17222e; color: #dbe6f0; padding: 11px 13px; border-radius: 5px;
           font-size: 8.5pt; line-height: 1.45; overflow: hidden; white-space: pre;
           page-break-inside: avoid; }
pre.diagram { background: #f7f9fb; border: 1px solid #dde5ec; color: #2c3e50;
              padding: 11px; border-radius: 5px; font-size: 8pt; line-height: 1.3;
              white-space: pre; page-break-inside: avoid; }

table { border-collapse: collapse; width: 100%; margin: 11px 0; font-size: 9pt;
        page-break-inside: avoid; }
th { background: #1f4e79; color: #fff; text-align: left; padding: 6px 8px;
     font-weight: 600; font-size: 8.8pt; }
td { border-bottom: 1px solid #e3e9ee; padding: 6px 8px; vertical-align: top; }
tr:nth-child(even) td { background: #f8fafc; }
table.grid td { border-bottom: 1px solid #e3e9ee; }
table.grid td:nth-child(odd) { color: #5a6b7b; width: 22%; }
table.grid td:nth-child(even) { font-weight: 600; color: #14304a; width: 28%; }

.note { background: #fffaf0; border-left: 4px solid #c9a227; padding: 9px 12px;
        margin: 12px 0; font-size: 9.6pt; page-break-inside: avoid; }
.note strong { color: #7a5c00; }
"""


def main():
    parts = [
        "<!doctype html><html><head><meta charset='utf-8'>",
        f"<title>{html.escape(TITLE)} — {html.escape(SUBTITLE)}</title>",
        f"<style>{CSS}</style></head><body>",
        "<div class='cover'>",
        f"<h1>{html.escape(TITLE)}</h1>",
        "<div class='rule'></div>",
        f"<div class='sub'>{html.escape(SUBTITLE)}</div>",
        f"<div class='by'>{html.escape(BYLINE)}</div>",
        "<div class='meta'>Verified against the code and a running stack.<br>"
        "Where a document in the repository disagrees with the code, the code won "
        "and the disagreement is called out.</div>",
        "</div>",
        "<div class='toc'><h2>Contents</h2><ol>",
    ]
    for s in SECTIONS:
        parts.append(f"<li><a href=\"#{s['id']}\">{html.escape(s['title'])}</a></li>")
    parts.append("</ol></div>")

    for i, s in enumerate(SECTIONS, 1):
        parts.append(f"<section id=\"{s['id']}\">")
        parts.append(f"<h2><span class='num'>{i:02d}</span>{html.escape(s['title'])}</h2>")
        for kind, payload in s["blocks"]:
            parts.append(render_block(kind, payload))
        parts.append("</section>")

    parts.append("</body></html>")
    HTML_OUT.write_text("\n".join(parts), encoding="utf-8")
    print(f"html  {HTML_OUT}  ({HTML_OUT.stat().st_size:,} bytes)")

    chrome = next((c for c in CHROME_CANDIDATES if c and Path(c).exists()), None)
    if not chrome:
        sys.exit("No Chrome/Chromium found; the HTML is written, print it manually.")

    subprocess.run([
        chrome, "--headless=new", "--disable-gpu", "--no-sandbox",
        "--no-pdf-header-footer", "--virtual-time-budget=10000",
        f"--print-to-pdf={PDF_OUT}", HTML_OUT.as_uri(),
    ], check=True, capture_output=True)
    print(f"pdf   {PDF_OUT}  ({PDF_OUT.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
