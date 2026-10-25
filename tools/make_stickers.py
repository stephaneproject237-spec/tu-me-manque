#!/usr/bin/env python3
"""Dessine les stickers originaux de « Tu me manques » (SVG -> PNG 512x512 transparent).

Usage : python3 tools/make_stickers.py
Écrit dans web/stickers/ et app/src/main/assets/stickers/ (mêmes fichiers pour le site et pour Android).
"""
import os
import shutil
from playwright.sync_api import sync_playwright

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_WEB = os.path.join(ROOT, "web", "stickers")
OUT_APP = os.path.join(ROOT, "app", "src", "main", "assets", "stickers")

INK = "#4A2C3A"      # contour
PINK = "#FF5C8A"
PINK_D = "#E63E73"
PINK_L = "#FFC2D4"
YEL = "#FFD84D"
YEL_D = "#F2B705"
CHEEK = "#FF8FAE"
WHITE = "#FFFFFF"
FONT = "font-family=\"Inter, 'DejaVu Sans', sans-serif\" font-weight=\"800\""

HEART = "M0 78 C-96 8 -112 -48 -66 -74 C-32 -92 -6 -70 0 -48 C6 -70 32 -92 66 -74 C112 -48 96 8 0 78 Z"


def svg(body):
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">'
        f'<g stroke-linecap="round" stroke-linejoin="round">{body}</g></svg>'
    )


def heart(cx, cy, s=1.0, fill=PINK, rot=0, stroke=True):
    st = f'stroke="{INK}" stroke-width="{6 / s:.2f}"' if stroke else ""
    return f'<g transform="translate({cx} {cy}) rotate({rot}) scale({s})"><path d="{HEART}" fill="{fill}" {st}/></g>'


def eyes(cx, cy, gap=44, r=11, color=INK):
    return (
        f'<ellipse cx="{cx - gap}" cy="{cy}" rx="{r}" ry="{r * 1.35}" fill="{color}"/>'
        f'<ellipse cx="{cx + gap}" cy="{cy}" rx="{r}" ry="{r * 1.35}" fill="{color}"/>'
        f'<circle cx="{cx - gap + 4}" cy="{cy - 6}" r="4" fill="#fff"/><circle cx="{cx + gap + 4}" cy="{cy - 6}" r="4" fill="#fff"/>'
    )


def happy_eyes(cx, cy, gap=44, w=20):
    d = lambda x: f'M{x - w} {cy + 6} Q{x} {cy - 22} {x + w} {cy + 6}'
    return (f'<path d="{d(cx - gap)}" fill="none" stroke="{INK}" stroke-width="8"/>'
            f'<path d="{d(cx + gap)}" fill="none" stroke="{INK}" stroke-width="8"/>')


def cheeks(cx, cy, gap=70, rx=20, ry=12):
    return (f'<ellipse cx="{cx - gap}" cy="{cy}" rx="{rx}" ry="{ry}" fill="{CHEEK}" opacity=".75"/>'
            f'<ellipse cx="{cx + gap}" cy="{cy}" rx="{rx}" ry="{ry}" fill="{CHEEK}" opacity=".75"/>')


def smile(cx, cy, w=28, h=22, open_=False):
    if open_:
        return (f'<path d="M{cx - w} {cy} Q{cx} {cy + h * 2} {cx + w} {cy} Z" fill="#8A2A45" stroke="{INK}" stroke-width="6"/>'
                f'<path d="M{cx - w + 8} {cy + h} Q{cx} {cy + h * 1.6} {cx + w - 8} {cy + h}" fill="#FF8FAE" stroke="none"/>')
    return f'<path d="M{cx - w} {cy} Q{cx} {cy + h * 1.6} {cx + w} {cy}" fill="none" stroke="{INK}" stroke-width="8"/>'


def sparkle(cx, cy, s=1.0, color=YEL):
    return (f'<g transform="translate({cx} {cy}) scale({s})"><path d="M0 -26 Q4 -4 26 0 Q4 4 0 26 Q-4 4 -26 0 Q-4 -4 0 -26 Z" '
            f'fill="{color}" stroke="{INK}" stroke-width="4"/></g>')


def face(cx, cy, r=120, fill=YEL):
    return (f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{fill}" stroke="{INK}" stroke-width="7"/>'
            f'<ellipse cx="{cx - r * 0.35}" cy="{cy - r * 0.55}" rx="{r * 0.28}" ry="{r * 0.14}" fill="#fff" opacity=".45" transform="rotate(-25 {cx - r * 0.35} {cy - r * 0.55})"/>')


def tear(x, y, s=1.0):
    return f'<g transform="translate({x} {y}) scale({s})"><path d="M0 -22 Q22 10 0 26 Q-22 10 0 -22 Z" fill="#7FD3FF" stroke="{INK}" stroke-width="4"/></g>'


def banner(text, y=430, w=360, size=44, fill=WHITE, color=PINK_D):
    return (f'<rect x="{256 - w / 2}" y="{y - 40}" width="{w}" height="80" rx="40" fill="{fill}" stroke="{INK}" stroke-width="6"/>'
            f'<text x="256" y="{y + size * 0.35}" text-anchor="middle" font-size="{size}" {FONT} fill="{color}">{text}</text>')


STICKERS = {}


def add(key, label, body):
    STICKERS[key] = (label, svg(body))


# 01 -- coeur content
add("s01_coeur_content", "Cœur content",
    heart(256, 250, 1.75) + cheeks(256, 270, 62) + happy_eyes(256, 235, 42) + smile(256, 282, 24, 18, True)
    + sparkle(110, 110, 1.0) + sparkle(410, 140, 0.8) + sparkle(430, 400, 0.7))

# 02 -- yeux en coeur
add("s02_coeur_yeux", "Yeux en cœur",
    face(256, 256, 190) + heart(190, 220, 0.42, PINK_D) + heart(322, 220, 0.42, PINK_D)
    + smile(256, 320, 60, 42, True) + cheeks(256, 300, 125, 28, 16))

# 03 -- bisou
add("s03_bisou", "Bisou",
    face(230, 270, 175) + happy_eyes(230, 235, 62, 24) + cheeks(230, 300, 100, 26, 15)
    + f'<path d="M230 318 q-18 -10 -4 -24 q12 -8 20 4 q8 -12 20 -4 q14 14 -4 24 q-12 10 -16 4 q-4 6 -16 -4 Z" fill="{PINK_D}" stroke="{INK}" stroke-width="5"/>'
    + heart(410, 130, 0.55) + heart(450, 215, 0.32, PINK_L))

# 04 -- Tu me manques
add("s04_tu_me_manques", "Tu me manques",
    heart(256, 195, 1.45) + cheeks(256, 215, 58) + eyes(256, 190, 40, 11) + tear(200, 235, 1.0) + tear(312, 235, 1.0)
    + f'<path d="M232 252 Q256 234 280 252" fill="none" stroke="{INK}" stroke-width="8"/>'
    + banner("Tu me manques", 430, 440, 44))

# 05 -- moi aussi
add("s05_moi_aussi", "Moi aussi",
    heart(256, 205, 1.5) + cheeks(256, 225, 60)
    + f'<path d="M205 190 q20 -22 40 0" fill="none" stroke="{INK}" stroke-width="8"/>'
    + f'<ellipse cx="305" cy="195" rx="12" ry="16" fill="{INK}"/><circle cx="309" cy="189" r="4" fill="#fff"/>'
    + smile(256, 240, 26, 18, True) + heart(400, 105, 0.38, PINK_L) + heart(112, 120, 0.3, PINK_L)
    + banner("Moi aussi !", 430, 380, 46))

# 06 -- je t'aime
add("s06_je_taime", "Je t'aime",
    heart(256, 235, 2.0)
    + f'<text x="256" y="250" text-anchor="middle" font-size="50" {FONT} fill="#fff" stroke="{INK}" stroke-width="10" paint-order="stroke">Je t\'aime</text>'
    + sparkle(80, 90, 1.1) + sparkle(440, 110, 0.9) + sparkle(430, 430, 1.0) + sparkle(70, 420, 0.7))

# 07 -- bonne nuit
add("s07_bonne_nuit", "Bonne nuit",
    '<path d="M310 70 A170 170 0 1 0 420 330 A140 140 0 0 1 310 70 Z" fill="#FFE38A" stroke="' + INK + '" stroke-width="7"/>'
    + f'<path d="M235 245 q22 16 44 0" fill="none" stroke="{INK}" stroke-width="8"/>'
    + f'<path d="M205 290 q14 12 28 0" fill="none" stroke="{INK}" stroke-width="7"/>'
    + f'<circle cx="262" cy="305" r="12" fill="{INK}"/>'
    + f'<ellipse cx="205" cy="270" rx="18" ry="11" fill="{CHEEK}" opacity=".8"/>'
    + f'<text x="360" y="150" font-size="48" {FONT} fill="#9AA7FF" stroke="{INK}" stroke-width="3" paint-order="stroke">z</text>'
    + f'<text x="395" y="115" font-size="36" {FONT} fill="#9AA7FF" stroke="{INK}" stroke-width="3" paint-order="stroke">z</text>'
    + sparkle(110, 110, 0.8) + sparkle(420, 230, 0.7) + sparkle(90, 330, 0.6)
    + banner("Bonne nuit", 445, 380, 44, WHITE, "#5560C8"))

# 08 -- bonjour
rays = "".join(
    f'<path d="M256 256 L{256 + 215 * __import__("math").cos(a):.1f} {256 + 215 * __import__("math").sin(a):.1f}" stroke="{YEL_D}" stroke-width="22" />'
    for a in [i * 3.14159 / 6 for i in range(12)])
add("s08_bonjour", "Bonjour",
    rays + face(256, 256, 140, YEL) + happy_eyes(256, 235, 48, 20) + cheeks(256, 285, 82, 22, 13) + smile(256, 292, 34, 22, True)
    + banner("Bonjour", 450, 300, 46, WHITE, YEL_D.replace("F2B705", "D98F00")))

# 09 -- café
add("s09_cafe", "Café ?",
    f'<path d="M150 190 h200 v120 a90 90 0 0 1 -90 90 h-20 a90 90 0 0 1 -90 -90 Z" fill="#fff" stroke="{INK}" stroke-width="7"/>'
    + f'<path d="M350 215 h28 a42 42 0 0 1 0 84 h-28" fill="none" stroke="{INK}" stroke-width="22"/>'
    + f'<path d="M350 215 h28 a42 42 0 0 1 0 84 h-28" fill="none" stroke="#fff" stroke-width="8"/>'
    + f'<ellipse cx="250" cy="190" rx="100" ry="20" fill="#8B5A3C" stroke="{INK}" stroke-width="7"/>'
    + heart(215, 100, 0.35, PINK_D) + heart(290, 80, 0.28, PINK) + heart(250, 140, 0.2, PINK_L)
    + happy_eyes(250, 270, 38, 15) + cheeks(250, 295, 62, 16, 10) + smile(250, 295, 18, 14)
    + banner("Un café ?", 455, 320, 42))

# 10 -- fleurs
def flower(cx, cy, petal, center, r=34, n=6):
    import math
    out = ""
    for i in range(n):
        a = i * 2 * math.pi / n
        out += f'<circle cx="{cx + r * math.cos(a):.1f}" cy="{cy + r * math.sin(a):.1f}" r="{r * 0.82:.1f}" fill="{petal}" stroke="{INK}" stroke-width="5"/>'
    out += f'<circle cx="{cx}" cy="{cy}" r="{r * 0.62:.1f}" fill="{center}" stroke="{INK}" stroke-width="5"/>'
    return out

add("s10_fleurs", "Fleurs",
    f'<path d="M256 460 L256 250 M256 380 L150 270 M256 360 L362 250" stroke="#3FA66B" stroke-width="14" fill="none"/>'
    + '<path d="M256 330 q-60 -10 -80 -60 q60 -4 80 60 Z" fill="#59C98A" stroke="' + INK + '" stroke-width="5"/>'
    + flower(256, 200, PINK, YEL, 44, 7) + flower(140, 250, "#FFB347", "#fff", 34) + flower(372, 235, "#B58BFF", YEL, 36)
    + f'<path d="M190 440 L322 440 L300 500 L212 500 Z" fill="#FFB5C9" stroke="{INK}" stroke-width="6"/>'
    + sparkle(80, 120, 0.8) + sparkle(440, 120, 0.7))

# 11 -- sans toi
add("s11_sans_toi", "Sans toi…",
    f'<path d="M140 280 a60 60 0 0 1 20 -112 a80 80 0 0 1 150 -10 a65 65 0 0 1 60 122 Z" fill="#DCE6F5" stroke="{INK}" stroke-width="7"/>'
    + eyes(256, 215, 44, 11) + f'<path d="M226 262 Q256 238 286 262" fill="none" stroke="{INK}" stroke-width="8"/>'
    + tear(206, 250, 0.8) + cheeks(256, 245, 78, 18, 11)
    + "".join(f'<path d="M{x} {y} l-8 28" stroke="#5EB6F5" stroke-width="9"/>' for x, y in [(170, 320), (230, 345), (290, 320), (350, 345), (200, 385), (320, 390)])
    + heart(430, 330, 0.3, PINK_L)
    + banner("Sans toi…", 468, 300, 36))

# 12 -- étoile
import math
star = " ".join(f"{256 + (r) * math.cos(-math.pi / 2 + i * math.pi / 5):.1f},{250 + (r) * math.sin(-math.pi / 2 + i * math.pi / 5):.1f}"
                for i, r in enumerate([200, 92] * 5))
add("s12_etoile", "Étoile",
    f'<polygon points="{star}" fill="{YEL}" stroke="{INK}" stroke-width="8" stroke-linejoin="round"/>'
    + happy_eyes(256, 250, 42, 18) + cheeks(256, 290, 72, 18, 11) + smile(256, 292, 22, 16, True)
    + sparkle(70, 90, 0.9, "#fff") + sparkle(450, 110, 0.7, "#fff") + sparkle(430, 440, 0.8, "#fff"))

# 13 -- gâteau
add("s13_gateau", "Miam",
    f'<path d="M90 300 L256 150 L420 300 L420 380 Q256 430 90 380 Z" fill="#FFE2B8" stroke="{INK}" stroke-width="7"/>'
    + f'<path d="M90 300 L256 150 L420 300 Q256 350 90 300 Z" fill="#FFF3DA" stroke="{INK}" stroke-width="7"/>'
    + f'<path d="M90 300 Q256 350 420 300" fill="none" stroke="{PINK}" stroke-width="22"/>'
    + f'<circle cx="256" cy="130" r="38" fill="#E8344E" stroke="{INK}" stroke-width="6"/>'
    + f'<path d="M256 95 q-6 -26 18 -34" stroke="#3FA66B" stroke-width="9" fill="none"/>'
    + happy_eyes(256, 360, 56, 16) + cheeks(256, 380, 96, 18, 10) + smile(256, 380, 20, 14)
    + sparkle(80, 120, 0.8) + sparkle(440, 160, 0.7) + banner("Miam", 470, 200, 40))

# 14 -- rire aux larmes
add("s14_rire", "Mort de rire",
    face(256, 256, 190)
    + f'<path d="M150 215 q36 -40 72 0 M290 215 q36 -40 72 0" fill="none" stroke="{INK}" stroke-width="10"/>'
    + f'<path d="M150 295 Q256 470 362 295 Z" fill="#8A2A45" stroke="{INK}" stroke-width="7"/>'
    + f'<path d="M185 330 Q256 380 327 330 L330 300 L182 300 Z" fill="#fff"/>'
    + f'<path d="M205 395 Q256 440 307 395 Q256 360 205 395 Z" fill="#FF8FAE"/>'
    + tear(105, 250, 1.4) + tear(407, 250, 1.4) + tear(90, 200, 1.0) + tear(422, 200, 1.0))

# 15 -- pleure
add("s15_pleure", "Triste",
    face(256, 256, 190)
    + f'<path d="M130 200 L205 215 M382 200 L307 215" stroke="{INK}" stroke-width="9"/>'
    + eyes(256, 245, 66, 15) + f'<path d="M190 360 Q256 290 322 360" fill="none" stroke="{INK}" stroke-width="10"/>'
    + f'<path d="M180 275 q-18 70 -4 120" stroke="#7FD3FF" stroke-width="26" fill="none"/>'
    + f'<path d="M332 275 q18 70 4 120" stroke="#7FD3FF" stroke-width="26" fill="none"/>'
    + tear(176, 420, 1.3) + tear(336, 420, 1.3))

# 16 -- clin d'oeil
add("s16_clin_doeil", "Clin d'œil",
    face(256, 262, 185)
    + eyes(185, 230, 0, 14) + f'<path d="M290 232 q36 -26 72 0" fill="none" stroke="{INK}" stroke-width="10"/>'
    + cheeks(256, 300, 112, 24, 14) + smile(256, 320, 70, 40, True)
    + heart(410, 120, 0.5) + heart(460, 190, 0.3, PINK_L))

# 17 -- ours câlin
def bear():
    brown, light = "#B97A4B", "#F1C9A0"
    return (f'<circle cx="150" cy="130" r="58" fill="{brown}" stroke="{INK}" stroke-width="7"/><circle cx="362" cy="130" r="58" fill="{brown}" stroke="{INK}" stroke-width="7"/>'
            f'<circle cx="150" cy="130" r="28" fill="{light}"/><circle cx="362" cy="130" r="28" fill="{light}"/>'
            f'<circle cx="256" cy="230" r="150" fill="{brown}" stroke="{INK}" stroke-width="7"/>'
            f'<ellipse cx="256" cy="280" rx="70" ry="54" fill="{light}" stroke="{INK}" stroke-width="5"/>'
            f'<ellipse cx="256" cy="262" rx="26" ry="18" fill="{INK}"/>'
            + eyes(256, 205, 56, 12)
            + f'<path d="M256 280 v12 M230 300 Q256 322 282 300" stroke="{INK}" stroke-width="7" fill="none"/>'
            + cheeks(256, 255, 100, 20, 12))
add("s17_ours_calin", "Câlin",
    bear() + heart(256, 400, 0.78, PINK) + banner("Gros câlin", 468, 290, 36))

# 18 -- bisous
add("s18_bisous", "Bisous",
    f'<path d="M80 270 Q150 190 256 232 Q362 190 432 270 Q380 400 256 400 Q132 400 80 270 Z" fill="#E8344E" stroke="{INK}" stroke-width="8"/>'
    + f'<path d="M80 270 Q150 300 256 296 Q362 300 432 270" fill="none" stroke="{INK}" stroke-width="7"/>'
    + f'<path d="M150 255 q50 -20 90 0" stroke="#FF8FA3" stroke-width="12" fill="none"/>'
    + heart(400, 120, 0.5) + heart(120, 130, 0.38, PINK_L) + sparkle(450, 330, 0.7)
    + banner("Bisous", 460, 260, 44))

# 19 -- coeur brisé
add("s19_coeur_brise", "Aïe",
    heart(256, 235, 1.8, PINK)
    + f'<path d="M256 150 L226 205 L275 235 L238 290 L262 340" fill="none" stroke="{INK}" stroke-width="9"/>'
    + f'<rect x="300" y="170" width="86" height="34" rx="14" fill="#FFD9B0" stroke="{INK}" stroke-width="5" transform="rotate(-28 343 187)"/>'
    + eyes(196, 250, 0, 11) + eyes(318, 250, 0, 11) + tear(178, 295, 0.8)
    + f'<path d="M190 345 Q256 315 322 345" fill="none" stroke="{INK}" stroke-width="8"/>'
    + banner("Aïe…", 468, 220, 40))

# 20 -- fête
add("s20_bravo", "Bravo",
    f'<path d="M130 400 L256 110 L382 400 Z" fill="{PINK}" stroke="{INK}" stroke-width="7"/>'
    + f'<path d="M165 320 L347 320 M195 250 L317 250" stroke="#fff" stroke-width="12" opacity=".8"/>'
    + f'<circle cx="256" cy="105" r="22" fill="{YEL}" stroke="{INK}" stroke-width="6"/>'
    + happy_eyes(256, 345, 40, 15) + smile(256, 365, 20, 14, True)
    + "".join(f'<rect x="{x}" y="{y}" width="16" height="30" rx="4" fill="{c}" stroke="{INK}" stroke-width="3" transform="rotate({r} {x + 8} {y + 15})"/>'
              for x, y, c, r in [(70, 140, "#7FD3FF", 20), (410, 150, YEL, -25), (95, 280, "#9AF0B0", -30), (400, 290, "#FFB347", 35), (255, 30, PINK_L, 10)])
    + sparkle(80, 60, 0.8) + sparkle(440, 60, 0.7) + banner("Bravo !", 468, 260, 44))


def main():
    os.makedirs(OUT_WEB, exist_ok=True)
    os.makedirs(OUT_APP, exist_ok=True)
    for d in (OUT_WEB, OUT_APP):
        for f in os.listdir(d):
            if f.endswith(".png"):
                os.remove(os.path.join(d, f))
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 512, "height": 512})
        for key, (label, markup) in STICKERS.items():
            page.set_content(f'<html><body style="margin:0;background:transparent">{markup}</body></html>')
            path = os.path.join(OUT_WEB, key + ".png")
            page.screenshot(path=path, omit_background=True, clip={"x": 0, "y": 0, "width": 512, "height": 512})
            shutil.copy(path, os.path.join(OUT_APP, key + ".png"))
        browser.close()
    # Liste lisible par le site (noms affichés)
    import json
    with open(os.path.join(OUT_WEB, "stickers.json"), "w", encoding="utf-8") as f:
        json.dump([{"id": k, "label": v[0]} for k, v in STICKERS.items()], f, ensure_ascii=False, indent=1)
    print(len(STICKERS), "stickers écrits")


if __name__ == "__main__":
    main()
