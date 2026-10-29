#!/usr/bin/env python3
"""Génère les icônes fines (style SF Symbols) des rubriques pour le web (player.js) et Android (RubricIcons.kt)."""
import json, re, pathlib
ROOT = pathlib.Path(__file__).resolve().parent.parent
BLUE, INDIGO, PURPLE, PINK, RED, ORANGE, YELLOW, GREEN, TEAL, MINT = "#0A84FF", "#5E5CE6", "#BF5AF2", "#FF375F", "#FF453A", "#FF9F0A", "#FFD60A", "#30D158", "#40C8E0", "#63E6E2"
CIRCLE = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z"
ICONS = {
 "bolt": ("M13 3L5 13.5h6L10 21l8-10.5h-6z", ORANGE),
 "compass": (CIRCLE + " M15.5 8.5l-2 5-5 2 2-5z", TEAL),
 "palette": ("M12 3a9 9 0 0 0 0 18c1.2 0 2-.8 2-1.800 0-1.200-1-1.500-1-2.500 0-1 .8-1.700 1.800-1.700H17a4 4 0 0 0 4-4C21 6.500 17 3 12 3z M7.500 11h.01 M10 7.500h.01 M14.500 7.500h.01", PINK),
 "smile": (CIRCLE + " M8.500 14a4 4 0 0 0 7 0 M9 9.500h.01 M15 9.500h.01", YELLOW),
 "drama": ("M5 4h14v7a7 7 0 0 1-14 0z M9 9.500h.01 M15 9.500h.01 M9.500 14c1.500 1 3.500 1 5 0", PURPLE),
 "ghost": ("M5 20V11a7 7 0 0 1 14 0v9l-2.500-2-2 2-2.500-2-2.500 2-2-2z M9.500 11h.01 M14.500 11h.01", INDIGO),
 "rocket": ("M12 3c4 2 6 6 5 11l-3 3h-4l-3-3C6 9 8 5 12 3z M9 17l-3 3 M15 17l3 3 M12 9.500h.01", BLUE),
 "eye": ("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z", INDIGO),
 "heart": ("M12 20s-7-4.500-7-10a4 4 0 0 1 7-2.500A4 4 0 0 1 19 10c0 5.500-7 10-7 10z", PINK),
 "search": ("M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z M20 20l-4-4", TEAL),
 "wand": ("M5 19L15 9 M15 4l1 2 2 1-2 1-1 2-1-2-2-1 2-1z M19 14l.6 1.400L21 16l-1.400.6L19 18l-.6-1.400L17 16l1.400-.6z", PURPLE),
 "shield": ("M12 3l8 3v6c0 5-3.500 8-8 9-4.500-1-8-4-8-9V6z", RED),
 "star": ("M12 3.500l2.600 5.400 5.900.8-4.300 4.100 1 5.900L12 16.900l-5.200 2.800 1-5.900L3.500 9.700l5.900-.8z", YELLOW),
 "film": ("M4 5h16v14H4z M8 5v14 M16 5v14 M4 9.500h4 M4 14.500h4 M16 9.500h4 M16 14.500h4", ORANGE),
 "globe": (CIRCLE + " M3 12h18 M12 3c3 3 3 15 0 18 M12 3c-3 3-3 15 0 18", GREEN),
 "cross": ("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M12 8v8 M8 12h8", RED),
 "scale": ("M12 3v18 M6 21h12 M5 7h14 M5 7l-3 7a3 3 0 0 0 6 0z M19 7l-3 7a3 3 0 0 0 6 0z", INDIGO),
 "tv": ("M4 7h16v11H4z M9 3l3 4 3-4", BLUE),
 "clock": (CIRCLE + " M12 7v5l3 2", TEAL),
 "note": ("M9 18V5l11-2v13 M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0z M20 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z", PINK),
 "flame": ("M12 3c1 3.500 5 5.500 5 10a5 5 0 0 1-10 0c0-2 1-3 2-4 .3 1.500 1 2 2 2 0-2-.5-5 1-8z", ORANGE),
 "doc": ("M7 3h7l5 5v13H7z M14 3v5h5 M9.500 13h6 M9.500 17h6", BLUE),
 "ball": (CIRCLE + " M12 8l3.500 2.500-1.300 4h-4.400l-1.300-4z M12 3v5 M15.500 10.500L20 9 M14.200 14.500l2.800 3.500 M9.800 14.500L7 18 M8.500 10.500L4 9", GREEN),
 "mic": ("M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z M5 11a7 7 0 0 0 14 0 M12 18v3", PINK),
 "shuffle": ("M16 3h5v5 M4 20L21 3 M21 16v5h-5 M15 15l6 6 M4 4l5 5", PURPLE),
 "headphones": ("M4 14v-2a8 8 0 0 1 16 0v2 M4 14h3v6H5a1 1 0 0 1-1-1z M20 14h-3v6h2a1 1 0 0 0 1-1z", INDIGO),
 "users": ("M9 11a3.500 3.500 0 1 0 0-7 3.500 3.500 0 0 0 0 7z M2.500 20a6.500 6.500 0 0 1 13 0 M16 4.300a3.500 3.500 0 0 1 0 6.400 M18 14a6 6 0 0 1 3.500 6", TEAL),
 "landmark": ("M3 10l9-6 9 6 M5 10v8 M9.500 10v8 M14.500 10v8 M19 10v8 M3 20h18", ORANGE),
 "leaf": ("M5 19c0-9 5-14 15-14 0 10-5 15-14 14 M5 19c3-5 6-8 10-10", GREEN),
 "flask": ("M9 3h6 M10 3v6l-5 10a1.500 1.500 0 0 0 1.400 2h11.200a1.500 1.500 0 0 0 1.400-2L14 9V3 M7.500 15h9", TEAL),
 "plane": ("M21 3L3 11l7 3 3 7z M10 14L21 3", BLUE),
 "coin": (CIRCLE + " M12 7v10 M15 9.500c-.5-1-1.600-1.500-3-1.500-1.700 0-3 .8-3 2s1.300 1.700 3 2 3 .8 3 2-1.300 2-3 2c-1.400 0-2.500-.5-3-1.500", YELLOW),
 "help": (CIRCLE + " M9.500 9.500a2.500 2.500 0 1 1 3.500 2.300c-.7.400-1 1-1 1.700 M12 16.500h.01", PURPLE),
 "target": (CIRCLE + " M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z M12 12h.01", RED),
 "trophy": ("M8 4h8v5a4 4 0 0 1-8 0z M8 6H5v2a3 3 0 0 0 3 3 M16 6h3v2a3 3 0 0 1-3 3 M12 13v4 M8.500 20h7 M10 17h4", YELLOW),
 "news": ("M4 5h13v14H6a2 2 0 0 1-2-2z M17 9h3v8a2 2 0 0 1-2 2 M7.500 9h6 M7.500 13h6", BLUE),
 "cook": ("M6 11a4 4 0 0 1 2-7 4 4 0 0 1 8 0 4 4 0 0 1 2 7z M7 11v9h10v-9", ORANGE),
 "gamepad": ("M7 8h10a4 4 0 0 1 4 4v3a3 3 0 0 1-5 2l-1-2H9l-1 2a3 3 0 0 1-5-2v-3a4 4 0 0 1 4-4z M8 11v3 M6.500 12.500h3 M16 11.500h.01 M18 13.500h.01", INDIGO),
 "bulb": ("M9 18h6 M10 21h4 M12 3a6 6 0 0 0-4 10.500c.7.600 1 1.300 1 2h6c0-.7.300-1.400 1-2A6 6 0 0 0 12 3z", YELLOW),
 "book": ("M4 5a2 2 0 0 1 2-2h5v17H6a2 2 0 0 0-2 2z M20 5a2 2 0 0 0-2-2h-5v17h5a2 2 0 0 1 2 2z", ORANGE),
 "sparkles": ("M12 3l1.800 5.200L19 10l-5.200 1.800L12 17l-1.800-5.200L5 10l5.200-1.800z M19 16l.7 1.800L21.500 18.500l-1.800.7L19 21l-.7-1.800-1.800-.7 1.800-.7z", YELLOW),
 "cap": ("M2 9l10-5 10 5-10 5z M6 11v5c0 1.500 2.700 3 6 3s6-1.500 6-3v-5", BLUE),
 "pencil": ("M4 20l1-4L16 5l3 3L8 19z M14 7l3 3", ORANGE),
 "wave": ("M3 12h2 M7 8v8 M11 4v16 M15 8v8 M19 10v4", PINK),
 "n1": ("#1", BLUE), "n2": ("#2", PURPLE), "n3": ("#3", PINK),
}
EMO = {
 "💥": "bolt", "🧭": "compass", "🎨": "palette", "😂": "smile", "🎭": "drama", "👻": "ghost", "🚀": "rocket", "🕵️": "eye", "💕": "heart", "🔍": "search",
 "🧙": "wand", "⚔️": "shield", "🤠": "star", "🎥": "film", "🌍": "globe", "🏥": "cross", "⚖️": "scale", "📺": "tv", "🕰️": "clock", "1️⃣": "n1", "2️⃣": "n2", "3️⃣": "n3",
 "🎵": "note", "🎶": "note", "🔥": "flame", "🎬": "film", "🎼": "note", "📝": "doc", "🏀": "ball", "🧒": "smile", "🎞️": "film", "🎤": "mic", "🍿": "film", "🎙️": "mic",
 "🔁": "shuffle", "🎧": "headphones", "🤝": "users", "🏛️": "landmark", "🌿": "leaf", "🐾": "leaf", "🔬": "flask", "✈️": "plane", "💰": "coin", "🧠": "help", "⚽": "ball",
 "🎯": "target", "🏆": "trophy", "📰": "news", "🥗": "leaf", "🍲": "cook", "🍰": "cook", "⚡": "bolt", "🥐": "cook", "🥬": "leaf", "👨‍🍳": "cook", "🎮": "gamepad",
 "💡": "bulb", "📘": "book", "🏁": "bolt", "🆕": "sparkles", "🧩": "help", "🌱": "leaf", "📚": "book", "✍️": "pencil", "🎓": "cap", "🕺": "wave", "🥁": "wave", "🎸": "note",
 "💖": "heart", "🙏": "heart", "🌴": "leaf", "🎷": "note", "🎹": "note", "📖": "book", "🔤": "book", "⭐": "star",
}
def js():
    d = {k: {"d": v[0], "c": v[1]} for k, v in ICONS.items()}
    return "const RIC = " + json.dumps(d, ensure_ascii=False) + ";\n  const REMO = " + json.dumps(EMO, ensure_ascii=False) + ";\n"
def kt():
    out = ["package com.tumemanque.app", "", "import androidx.compose.ui.graphics.Color", "import androidx.compose.ui.graphics.SolidColor", "import androidx.compose.ui.graphics.StrokeCap",
           "import androidx.compose.ui.graphics.StrokeJoin", "import androidx.compose.ui.graphics.vector.ImageVector", "import androidx.compose.ui.graphics.vector.PathParser", "import androidx.compose.ui.unit.dp", "",
           "/** Icônes fines des rubriques (générées par tools/gen-rubric-icons.py). Une icône : tracé, couleur, ou un texte (numéro de saison). */",
           "class RIcon(val vector: ImageVector?, val color: Color, val text: String?)", "", "object RIcons {",
           "    private fun v(d: String): ImageVector {",
           "        val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)",
           "        runCatching { b.addPath(PathParser().parsePathString(d).toNodes(), fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }",
           "        return b.build()", "    }", "    private fun c(h: String) = Color(0xFF000000 or h.removePrefix(\"#\").toLong(16))", "    private val icons: Map<String, RIcon> = mapOf("]
    for k, (d, col) in ICONS.items():
        if d.startswith("#"): out.append(f'        "{k}" to RIcon(null, c("{col}"), "{d[1:]}"),')
        else: out.append(f'        "{k}" to RIcon(v("{d}"), c("{col}"), null),')
    out += ["    )", "    private val emo: Map<String, String> = mapOf("]
    for e, n in EMO.items(): out.append(f'        "{e}" to "{n}",')
    out += ["    )", "    fun of(emoji: String): RIcon = icons[emo[emoji] ?: \"star\"] ?: icons.getValue(\"star\")", "}", ""]
    return "\n".join(out)
if __name__ == "__main__":
    p = ROOT / "web/player.js"; s = p.read_text()
    a, b = "  // @rubric-icons:begin\n", "  // @rubric-icons:end\n"
    block = a + "  " + js() + b
    if a in s: s = re.sub(re.escape(a) + r".*?" + re.escape(b), lambda m: block, s, flags=re.S)
    else: s = s.replace("  const cl = (x) =>", block + "  const cl = (x) =>", 1)
    p.write_text(s)
    (ROOT / "app/src/casque/java/com/tumemanque/app/RubricIcons.kt").write_text(kt())
    print("icônes générées")
