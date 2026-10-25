#!/usr/bin/env python3
"""Génère la liste d'emojis par catégorie : web/emoji.js et app/.../Emojis.kt (mêmes données)."""
import os, json
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
def rng(*r):
    out = []
    for a, b in r:
        out += [chr(c) for c in range(a, b + 1)]
    return out
CATS = [
 ("😀", "Visages", rng((0x1F600,0x1F64F)) + rng((0x1F910,0x1F92F)) + rng((0x1F970,0x1F978)) + ["🥹","🫠","🫡","🫢","🫣","🫤","🥲"]),
 ("❤️", "Cœurs", ["❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❣️","💕","💞","💓","💗","💖","💘","💝","💟","❤️‍🔥","💋","😘","😍","🥰","😻","💑","💏","🌹","🌷","🌸","💐","🎁","💌","💍","✨","🌙","⭐","🔥"]),
 ("👍", "Gestes", ["👍","👎","👌","🤌","🤏","✌️","🤞","🫰","🤟","🤘","🤙","👈","👉","👆","👇","☝️","👋","🤚","🖐️","✋","🖖","👏","🙌","🫶","👐","🤲","🤝","🙏","💪","🫂","🤗","🙈","🙉","🙊"]),
 ("🐻", "Animaux", rng((0x1F400,0x1F43E)) + ["🦋","🐝","🦄","🦊","🦁","🐯","🐼","🦉","🐧","🐢","🐬","🐳"]),
 ("🍕", "Repas", rng((0x1F345,0x1F37F)) + ["☕","🥐","🥗","🥘","🍲","🥤","🧁","🍰","🍫"]),
 ("⚽", "Activités", ["⚽","🏀","🏈","⚾","🎾","🏐","🎱","🏓","🎮","🎲","🎯","🎵","🎶","🎤","🎧","🎸","🎹","🥁","🎬","🎨","📚","✈️","🚗","🏖️","🏠","🎉","🎊","🎈","🎂","🏆","🥇","🌍","🌈","☀️","⛅","🌧️","⚡","❄️","🌊"]),
]
cats = []
for icon, name, lst in CATS:
    seen, res = set(), []
    for e in lst:
        if e not in seen and e.strip():
            seen.add(e); res.append(e)
    cats.append({"icon": icon, "name": name, "emojis": res})
with open(os.path.join(ROOT, "web", "emoji.js"), "w", encoding="utf-8") as f:
    f.write("// Généré par tools/make_emoji.py — ne pas modifier à la main.\nwindow.EMOJI_CATS = " + json.dumps(cats, ensure_ascii=False) + ";\n")
kt = ["package com.tumemanque.app", "", "// Généré par tools/make_emoji.py — ne pas modifier à la main.",
      "data class EmojiCat(val icon: String, val name: String, val emojis: List<String>)", "",
      "object Emojis {", "    val cats: List<EmojiCat> = listOf("]
for c in cats:
    items = ", ".join('"' + e + '"' for e in c["emojis"])
    kt.append(f'        EmojiCat("{c["icon"]}", "{c["name"]}", listOf({items})),')
kt += ["    )", "}", ""]
d = os.path.join(ROOT, "app", "src", "main", "java", "com", "tumemanque", "app")
open(os.path.join(d, "Emojis.kt"), "w", encoding="utf-8").write("\n".join(kt))
print([(c["name"], len(c["emojis"])) for c in cats])
