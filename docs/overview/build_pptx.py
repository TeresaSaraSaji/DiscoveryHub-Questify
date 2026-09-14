#!/usr/bin/env python3
"""Render content.py to an editable 16:9 PowerPoint deck.

Slides are laid out by a small flow engine: blocks are measured, placed down the slide, and
overflow starts a continuation slide. Nothing is allowed to run off the bottom.

Run with a python that has python-pptx installed:
    /tmp/deckvenv/bin/python build_pptx.py
"""

import re
import sys
from pathlib import Path

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.util import Emu, Inches, Pt

sys.path.insert(0, str(Path(__file__).parent))
from content import BYLINE, FOOTER, SECTIONS, SUBTITLE, TITLE  # noqa: E402

OUT = Path(__file__).parent / "DiscoveryHub-Technical-Overview.pptx"

# palette
NAVY = RGBColor(0x14, 0x30, 0x4A)
BLUE = RGBColor(0x1F, 0x4E, 0x79)
GOLD = RGBColor(0xC9, 0xA2, 0x27)
INK = RGBColor(0x1A, 0x1D, 0x21)
SLATE = RGBColor(0x37, 0x50, 0x6A)
MUTED = RGBColor(0x8A, 0x97, 0xA3)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
CODEBG = RGBColor(0x17, 0x22, 0x2E)
CODEFG = RGBColor(0xDB, 0xE6, 0xF0)
DIAGBG = RGBColor(0xF7, 0xF9, 0xFB)
NOTEBG = RGBColor(0xFF, 0xFA, 0xF0)
NOTEINK = RGBColor(0x5C, 0x45, 0x00)
ROWALT = RGBColor(0xF4, 0xF7, 0xFA)
LINE = RGBColor(0xDD, 0xE5, 0xEC)

SANS = "Helvetica Neue"
MONO = "Menlo"

SW, SH = 13.333, 7.5
ML, MR = 0.62, 0.62
CW = SW - ML - MR
TOP = 1.24
BOTTOM = 7.02

BODY_PT = 13.0
LINE_IN = 0.215          # rendered height of one body line
CPL = 132                # characters per line at BODY_PT across the full content width


# ---------------------------------------------------------------- inline markup

TOKEN = re.compile(r"(\*\*.+?\*\*|`[^`]+`|(?<!\*)\*[^*]+\*(?!\*))")


def runs_for(text):
    """Split '**bold**, `code`, *italic*' into (text, bold, italic, mono) tuples."""
    out = []
    for piece in TOKEN.split(text):
        if not piece:
            continue
        if piece.startswith("**") and piece.endswith("**"):
            inner = piece[2:-2]
            # a nested *italic* inside bold keeps the bold and adds italic
            for sub in TOKEN.split(inner):
                if not sub:
                    continue
                if sub.startswith("*") and sub.endswith("*") and not sub.startswith("**"):
                    out.append((sub[1:-1], True, True, False))
                elif sub.startswith("`") and sub.endswith("`"):
                    out.append((sub[1:-1], True, False, True))
                else:
                    out.append((sub, True, False, False))
        elif piece.startswith("`") and piece.endswith("`"):
            out.append((piece[1:-1], False, False, True))
        elif piece.startswith("*") and piece.endswith("*"):
            out.append((piece[1:-1], False, True, False))
        else:
            out.append((piece, False, False, False))
    return out


def plain(text):
    return re.sub(r"[*`]", "", text)


def wrapped_lines(text, chars_per_line):
    """Line count after wrapping, counting explicit newlines."""
    total = 0
    for para in plain(text).split("\n"):
        total += max(1, -(-len(para) // max(10, chars_per_line)))
    return total


# ---------------------------------------------------------------- measuring

def measure(kind, payload):
    if kind == "lede":
        return wrapped_lines(payload, 112) * 0.29 + 0.18
    if kind == "para":
        return wrapped_lines(payload, CPL) * LINE_IN + 0.12
    if kind == "note":
        return wrapped_lines(payload, 126) * LINE_IN + 0.28
    if kind in ("code", "diagram"):
        return len(payload.split("\n")) * 0.175 + 0.28
    if kind == "bullets":
        return sum(wrapped_lines(b, CPL - 6) * LINE_IN + 0.06 for b in payload) + 0.08
    if kind == "table":
        headers, rows = payload
        h = 0.34 if any(x.strip() for x in headers) else 0.0
        for r in rows:
            h += row_height(r, headers)
        return h + 0.16
    raise ValueError(kind)


def col_widths(headers, rows):
    ncol = len(headers) if any(h.strip() for h in headers) else len(rows[0])
    weights = []
    for i in range(ncol):
        cells = [plain(r[i]) for r in rows if i < len(r)]
        if any(h.strip() for h in headers) and i < len(headers):
            cells.append(plain(headers[i]))
        longest = max((len(c) for c in cells), default=8)
        weights.append(max(7, min(longest, 78)))
    total = sum(weights)
    return [CW * w / total for w in weights]


def row_height(row, headers):
    widths = _WIDTH_CACHE.get(id(headers))
    lines = 1
    for i, cell in enumerate(row):
        w = widths[i] if widths and i < len(widths) else CW / max(1, len(row))
        cpl = max(8, int(w * 10.6))          # chars per inch at the table font size
        lines = max(lines, wrapped_lines(cell, cpl))
    return lines * 0.175 + 0.13


_WIDTH_CACHE = {}


# ---------------------------------------------------------------- drawing

def textbox(slide, x, y, w, h):
    box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = box.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    return box, tf


def fill_para(para, text, size, color, bold=False, space_after=2):
    para.space_after = Pt(space_after)
    para.line_spacing = 1.06
    for txt, b, i, mono in runs_for(text):
        run = para.add_run()
        run.text = txt
        f = run.font
        f.size = Pt(size)
        f.name = MONO if mono else SANS
        f.bold = b or bold
        f.italic = i
        f.color.rgb = RGBColor(0x9C, 0x2C, 0x4E) if mono else color
    return para


def draw(kind, payload, slide, y):
    h = measure(kind, payload)

    if kind == "lede":
        bar = slide.shapes.add_shape(1, Inches(ML), Inches(y), Inches(0.045), Inches(h - 0.1))
        bar.fill.solid(); bar.fill.fore_color.rgb = GOLD; bar.line.fill.background()
        bar.shadow.inherit = False
        _, tf = textbox(slide, ML + 0.17, y, CW - 0.17, h)
        fill_para(tf.paragraphs[0], payload, 14.5, SLATE, bold=True)

    elif kind == "para":
        _, tf = textbox(slide, ML, y, CW, h)
        fill_para(tf.paragraphs[0], payload, BODY_PT, INK)

    elif kind == "note":
        box = slide.shapes.add_shape(1, Inches(ML), Inches(y), Inches(CW), Inches(h - 0.08))
        box.fill.solid(); box.fill.fore_color.rgb = NOTEBG
        box.line.color.rgb = GOLD; box.line.width = Pt(0.75)
        box.shadow.inherit = False
        bar = slide.shapes.add_shape(1, Inches(ML), Inches(y), Inches(0.055), Inches(h - 0.08))
        bar.fill.solid(); bar.fill.fore_color.rgb = GOLD; bar.line.fill.background()
        bar.shadow.inherit = False
        _, tf = textbox(slide, ML + 0.2, y + 0.11, CW - 0.38, h - 0.3)
        fill_para(tf.paragraphs[0], payload, 12.0, NOTEINK)

    elif kind in ("code", "diagram"):
        dark = kind == "code"
        box = slide.shapes.add_shape(1, Inches(ML), Inches(y), Inches(CW), Inches(h - 0.08))
        box.fill.solid(); box.fill.fore_color.rgb = CODEBG if dark else DIAGBG
        if dark:
            box.line.fill.background()
        else:
            box.line.color.rgb = LINE; box.line.width = Pt(0.75)
        box.shadow.inherit = False
        _, tf = textbox(slide, ML + 0.16, y + 0.1, CW - 0.32, h - 0.28)
        tf.word_wrap = False
        for idx, ln in enumerate(payload.split("\n")):
            p = tf.paragraphs[0] if idx == 0 else tf.add_paragraph()
            p.space_after = Pt(0); p.line_spacing = 1.0
            r = p.add_run(); r.text = ln or " "
            r.font.size = Pt(10.0 if dark else 9.0)
            r.font.name = MONO
            r.font.color.rgb = CODEFG if dark else SLATE

    elif kind == "bullets":
        _, tf = textbox(slide, ML, y, CW, h)
        for idx, item in enumerate(payload):
            p = tf.paragraphs[0] if idx == 0 else tf.add_paragraph()
            dot = p.add_run(); dot.text = "▪  "
            dot.font.size = Pt(BODY_PT); dot.font.color.rgb = GOLD; dot.font.name = SANS
            fill_para(p, item, BODY_PT, INK, space_after=5)

    elif kind == "table":
        headers, rows = payload
        has_head = any(x.strip() for x in headers)
        widths = _WIDTH_CACHE[id(headers)]
        nrow = len(rows) + (1 if has_head else 0)
        ncol = len(widths)
        gfx = slide.shapes.add_table(nrow, ncol, Inches(ML), Inches(y),
                                     Inches(CW), Inches(h - 0.16))
        tbl = gfx.table
        tbl.first_row = has_head
        tbl.horz_banding = False
        for i, w in enumerate(widths):
            tbl.columns[i].width = Inches(w)

        def cell_text(cell, text, size, color, bold=False, align=PP_ALIGN.LEFT):
            cell.margin_left = cell.margin_right = Inches(0.07)
            cell.margin_top = cell.margin_bottom = Inches(0.035)
            cell.vertical_anchor = MSO_ANCHOR.TOP
            tf = cell.text_frame
            tf.word_wrap = True
            p = tf.paragraphs[0]
            p.alignment = align
            p.line_spacing = 1.04
            for txt, b, i, mono in runs_for(text):
                r = p.add_run(); r.text = txt
                r.font.size = Pt(size)
                r.font.name = MONO if mono else SANS
                r.font.bold = b or bold
                r.font.italic = i
                r.font.color.rgb = color

        off = 0
        if has_head:
            for c, htxt in enumerate(headers):
                cell = tbl.cell(0, c)
                cell.fill.solid(); cell.fill.fore_color.rgb = BLUE
                cell_text(cell, htxt, 10.5, WHITE, bold=True)
            tbl.rows[0].height = Inches(0.30)
            off = 1
        for r, row in enumerate(rows):
            tbl.rows[r + off].height = Inches(row_height(row, headers))
            for c in range(ncol):
                cell = tbl.cell(r + off, c)
                cell.fill.solid()
                cell.fill.fore_color.rgb = ROWALT if r % 2 else WHITE
                txt = row[c] if c < len(row) else ""
                lead = (not has_head and c % 2 == 1)
                cell_text(cell, txt, 10.5, NAVY if lead else INK, bold=lead)

    return h


# ---------------------------------------------------------------- slides

def add_slide(prs):
    return prs.slides.add_slide(prs.slide_layouts[6])


def slide_header(slide, number, title, cont=False):
    _, tf = textbox(slide, ML, 0.36, CW, 0.6)
    p = tf.paragraphs[0]
    n = p.add_run(); n.text = f"{number:02d}   "
    n.font.size = Pt(22); n.font.bold = True; n.font.color.rgb = RGBColor(0xC3, 0xCE, 0xD8)
    n.font.name = SANS
    t = p.add_run(); t.text = title + ("  (cont.)" if cont else "")
    t.font.size = Pt(22); t.font.bold = True; t.font.color.rgb = NAVY; t.font.name = SANS

    rule = slide.shapes.add_shape(1, Inches(ML), Inches(1.05), Inches(CW), Inches(0.028))
    rule.fill.solid(); rule.fill.fore_color.rgb = GOLD
    rule.line.fill.background(); rule.shadow.inherit = False


def slide_footer(slide, page):
    _, tf = textbox(slide, ML, SH - 0.42, CW, 0.25)
    p = tf.paragraphs[0]
    r = p.add_run(); r.text = f"{FOOTER}"
    r.font.size = Pt(8.5); r.font.color.rgb = MUTED; r.font.name = SANS
    _, tf2 = textbox(slide, SW - MR - 1.0, SH - 0.42, 1.0, 0.25)
    p2 = tf2.paragraphs[0]; p2.alignment = PP_ALIGN.RIGHT
    r2 = p2.add_run(); r2.text = str(page)
    r2.font.size = Pt(8.5); r2.font.color.rgb = MUTED; r2.font.name = SANS


def split_table(payload, avail):
    """Split a table into (fits_now, remainder) so a long one flows onto the next slide."""
    headers, rows = payload
    head_h = 0.34 if any(x.strip() for x in headers) else 0.0
    used = head_h + 0.16
    cut = 0
    for r in rows:
        rh = row_height(r, headers)
        if used + rh > avail:
            break
        used += rh
        cut += 1
    if cut == 0:
        return None, payload
    if cut == len(rows):
        return payload, None
    return (headers, rows[:cut]), (headers, rows[cut:])


def build():
    prs = Presentation()
    prs.slide_width, prs.slide_height = Inches(SW), Inches(SH)

    # cache column widths per table so measuring and drawing agree
    for s in SECTIONS:
        for kind, payload in s["blocks"]:
            if kind == "table":
                headers, rows = payload
                _WIDTH_CACHE[id(headers)] = col_widths(headers, rows)

    # ---- title slide
    s0 = add_slide(prs)
    band = s0.shapes.add_shape(1, Inches(0), Inches(0), Inches(SW), Inches(SH))
    band.fill.solid(); band.fill.fore_color.rgb = NAVY
    band.line.fill.background(); band.shadow.inherit = False
    accent = s0.shapes.add_shape(1, Inches(0), Inches(0), Inches(0.13), Inches(SH))
    accent.fill.solid(); accent.fill.fore_color.rgb = GOLD
    accent.line.fill.background(); accent.shadow.inherit = False

    _, tf = textbox(s0, 1.15, 2.35, 10.8, 1.3)
    r = tf.paragraphs[0].add_run(); r.text = TITLE
    r.font.size = Pt(60); r.font.bold = True; r.font.color.rgb = WHITE; r.font.name = SANS

    rule = s0.shapes.add_shape(1, Inches(1.18), Inches(3.72), Inches(1.0), Inches(0.045))
    rule.fill.solid(); rule.fill.fore_color.rgb = GOLD
    rule.line.fill.background(); rule.shadow.inherit = False

    _, tf = textbox(s0, 1.15, 4.02, 10.8, 0.6)
    r = tf.paragraphs[0].add_run(); r.text = SUBTITLE
    r.font.size = Pt(21); r.font.color.rgb = RGBColor(0xCF, 0xDC, 0xE8); r.font.name = SANS

    _, tf = textbox(s0, 1.15, 4.75, 10.8, 0.4)
    r = tf.paragraphs[0].add_run(); r.text = BYLINE
    r.font.size = Pt(13); r.font.color.rgb = RGBColor(0x93, 0xA8, 0xBC); r.font.name = SANS

    # ---- contents
    sc = add_slide(prs)
    slide_header(sc, 0, "Contents")
    half = -(-len(SECTIONS) // 2)
    for col, chunk in enumerate((SECTIONS[:half], SECTIONS[half:])):
        _, tf = textbox(sc, ML + col * (CW / 2 + 0.1), TOP, CW / 2 - 0.2, 5.5)
        for j, sec in enumerate(chunk):
            p = tf.paragraphs[0] if j == 0 else tf.add_paragraph()
            p.space_after = Pt(6)
            n = p.add_run(); n.text = f"{SECTIONS.index(sec) + 1:02d}  "
            n.font.size = Pt(12); n.font.bold = True; n.font.color.rgb = GOLD; n.font.name = SANS
            t = p.add_run(); t.text = sec["title"]
            t.font.size = Pt(12); t.font.color.rgb = INK; t.font.name = SANS
    slide_footer(sc, 2)

    # ---- content slides
    page = 3
    for num, sec in enumerate(SECTIONS, 1):
        blocks = list(sec["blocks"])
        cont = False
        while blocks:
            slide = add_slide(prs)
            slide_header(slide, num, sec["title"], cont)
            y = TOP
            placed = 0
            while blocks:
                kind, payload = blocks[0]
                avail = BOTTOM - y
                h = measure(kind, payload)
                if h <= avail:
                    y += draw(kind, payload, slide, y) + 0.10
                    blocks.pop(0)
                    placed += 1
                    continue
                # Too tall for what is left. Split a table only if it cannot fit on a slide
                # of its own — otherwise move it whole, rather than orphaning a row or two.
                if kind == "table" and avail > 1.0 and h > (BOTTOM - TOP):
                    head, rest = split_table(payload, avail)
                    if head:
                        draw("table", head, slide, y)
                        blocks[0] = ("table", rest)
                        placed += 1
                    break
                if placed == 0:
                    # a single block taller than a whole slide: draw it anyway
                    y += draw(kind, payload, slide, y) + 0.10
                    blocks.pop(0)
                    placed += 1
                break
            slide_footer(slide, page)
            page += 1
            cont = True

    prs.save(OUT)
    return prs


def check(prs):
    """No shape may run off the bottom or the right of a slide."""
    problems = []
    for i, slide in enumerate(prs.slides, 1):
        for sh in slide.shapes:
            if sh.top is None or sh.height is None:
                continue
            bottom = Emu(sh.top + sh.height).inches
            right = Emu(sh.left + sh.width).inches
            if sh.top == 0 and bottom <= SH + 0.01:
                continue                      # full-bleed background, intentional
            if bottom > SH - 0.06:
                problems.append(f"slide {i}: {sh.shape_type} overflows bottom by "
                                f"{bottom - SH:+.2f}in")
            if right > SW + 0.02:
                problems.append(f"slide {i}: {sh.shape_type} overflows right by "
                                f"{right - SW:+.2f}in")
    return problems


if __name__ == "__main__":
    prs = build()
    n = len(prs.slides.__iter__.__self__._sldIdLst)
    print(f"pptx  {OUT}  ({OUT.stat().st_size:,} bytes, {n} slides)")
    issues = check(prs)
    if issues:
        print(f"\n{len(issues)} layout problem(s):")
        for p in issues[:25]:
            print("  " + p)
    else:
        print("layout ok — nothing runs off a slide")
