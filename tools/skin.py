#!/usr/bin/env python3
"""Habille le code de « Tu me manques » aux couleurs de « Casque » (noir / gris / bleu, sans cœurs).

Les couleurs roses et prune sont converties automatiquement en bleu et gris froids ;
les autres (blanc, vert, rouge d'erreur, orange…) restent intactes. Le code d'origine n'est jamais modifié.
"""
import colorsys, os, re, shutil, sys

BLUE_HUE = 214 / 360.0
COOL_HUE = 218 / 360.0


def conv(r, g, b):
    """r,g,b 0-255 -> r,g,b 0-255 (couleurs roses -> bleu / gris froid)."""
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    deg = h * 360
    if s < 0.06 or not (298 <= deg <= 349):
        return r, g, b
    if l < 0.30:                       # fonds prune -> noir / gris très sombre légèrement bleuté
        h2, s2, l2 = COOL_HUE, min(0.18, s * 0.28), l * 0.82
    elif s >= 0.55:                    # accents roses -> bleu
        h2, s2, l2 = BLUE_HUE, min(1.0, s * 0.92), l * 0.97
    else:                              # roses atténués, textes -> gris froid
        h2, s2, l2 = COOL_HUE, s * 0.30, l
    r2, g2, b2 = colorsys.hls_to_rgb(h2, l2, s2)
    return round(r2 * 255), round(g2 * 255), round(b2 * 255)


def hexfix(m):
    s = m.group(1)
    if len(s) == 3:
        r, g, b = (int(c * 2, 16) for c in s)
        r, g, b = conv(r, g, b)
        out = "%02X%02X%02X" % (r, g, b)
        return "#" + out
    r, g, b = int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16)
    r, g, b = conv(r, g, b)
    return "#%02X%02X%02X%s" % (r, g, b, s[6:])


def kt_color(m):
    a, rgb = m.group(1), m.group(2)
    r, g, b = int(rgb[0:2], 16), int(rgb[2:4], 16), int(rgb[4:6], 16)
    r, g, b = conv(r, g, b)
    return "Color(0x%s%02X%02X%02X)" % (a, r, g, b)


def rgba_fix(m):
    fn, a, b_, c, rest = m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)
    try:
        r, g, b = conv(int(float(a)), int(float(b_)), int(float(c)))
    except ValueError:
        return m.group(0)
    return "%s(%d,%d,%d%s)" % (fn, r, g, b, rest)


RE_HEX = re.compile(r"#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3})(?![0-9a-zA-Z_])")
RE_KT = re.compile(r"Color\(0x([0-9a-fA-F]{2})([0-9a-fA-F]{6})\)")
RE_RGBA = re.compile(r"(rgba?)\(\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*((?:,\s*[\d.]+\s*)?)\)")


def hex_any(m):
    s = m.group(1)
    if len(s) == 8:      # #AARRGGBB (Android xml) ou #RRGGBBAA (css) : on traite comme RRGGBBAA seulement en css
        return m.group(0)
    return hexfix(m)


# Mots et symboles remplacés (ordre important).
WORDS = [
    ("Vous n'êtes plus partenaires.", "Vous n'êtes plus amis."),
    ("Tu me manques aussi 💗", "Message reçu"),
    ("Tu me manques 💗", "Message"),
    ("Je t'invite sur « Tu me manques » 💗", "Je t'invite sur « Casque »"),
    ("« Tu me manques »", "« Casque »"),
    ("Tu me manques", "Casque"),
    ("Ton partenaire", "Ami(e)"),
    ("ton partenaire", "ton ami(e)"),
    ("partenaire", "ami(e)"),
    ("💗", "🎧"),
    ("💕", "🎧"),
    ("❤️", "🎧"),
    ("♥", "●"),
]


def skin_kt(s):
    s = RE_KT.sub(kt_color, s)
    s = RE_HEX.sub(hex_any, s)
    return s


def skin_css(s):
    s = RE_RGBA.sub(rgba_fix, s)
    s = RE_HEX.sub(hexfix, s)
    return s


def words(s):
    for a, b in WORDS:
        s = s.replace(a, b)
    return s


def process(src_root, dst_root, exts, fn):
    for dp, _, files in os.walk(src_root):
        for f in files:
            p = os.path.join(dp, f)
            rel = os.path.relpath(p, src_root)
            out = os.path.join(dst_root, rel)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            if f.rsplit(".", 1)[-1] in exts:
                with open(p, encoding="utf-8") as fh:
                    t = fh.read()
                with open(out, "w", encoding="utf-8") as fh:
                    fh.write(fn(t))
            else:
                shutil.copy2(p, out)


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "app", "build", "skin")
    shutil.rmtree(out, ignore_errors=True)
    # Code Kotlin partagé
    process(os.path.join(root, "app", "src", "shared", "java"), os.path.join(out, "java"), {"kt"}, lambda t: words(skin_kt(t)))
    # Pages web utilisées dans l'appli (appels, éditeur photo)
    call = os.path.join(out, "assets", "call")
    os.makedirs(call, exist_ok=True)
    for f in ["appel.js", "appel.css", "photoedit.js", "ios.js", "photoedit.css", "emoji.js"]:
        with open(os.path.join(root, "web", f), encoding="utf-8") as fh:
            t = fh.read()
        with open(os.path.join(call, f), "w", encoding="utf-8") as fh:
            fh.write(words(skin_css(t)))
    edit = os.path.join(root, "app", "src", "main", "assets", "call", "edit.html")
    if os.path.exists(edit):
        with open(edit, encoding="utf-8") as fh:
            t = fh.read()
        with open(os.path.join(call, "edit.html"), "w", encoding="utf-8") as fh:
            fh.write(words(skin_css(t)))
    print("skin ok ->", out)


if __name__ == "__main__":
    main()
