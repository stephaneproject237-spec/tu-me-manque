#!/usr/bin/env python3
"""Génère web/casque/ : la version iPhone / web de « Casque » à partir du code de « Tu me manques ».
Même principe que l'Android : le code d'origine n'est jamais modifié, on l'habille (couleurs, mots)."""
import hashlib, os, re, shutil, subprocess, sys
sys.path.insert(0, os.path.dirname(__file__))
import skin

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
W = os.path.join(ROOT, "web")
OUT = os.path.join(W, "casque")
VERSION = "web 2.22"
NOTES = "Téléchargement via un ami Android plus rapide (audio sans conversion, suivi plus réactif)."
ES = os.environ.get("ESBUILD") or shutil.which("esbuild") or "/opt/npm-tools/node_modules/.bin/esbuild"


def rd(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def wr(p, t):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        f.write(t)


def prefixes(t):
    t = re.sub(r"([\"'`])tmm_", r"\1casque_", t)
    t = t.replace('"stickers/', '"../stickers/').replace("`stickers/", "`../stickers/").replace('"questions.json', '"../questions.json')
    return t


def js(name):
    return prefixes(skin.words(skin.skin_css(rd(os.path.join(W, name)))))


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(os.path.join(OUT, "dist"))
    cfg = rd(os.path.join(W, "config.js"))
    cfg = re.sub(r'WEB_URL: "[^"]*"', 'WEB_URL: "https://stephaneproject237-spec.github.io/tu-me-manque/casque/"', cfg)
    cfg = re.sub(r'APK_URL: "[^"]*"', 'APK_URL: "https://github.com/stephaneproject237-spec/tu-me-manque/releases/download/casque-5/casque.apk"', cfg)
    cfg = re.sub(r'VERSION: "[^"]*"', 'VERSION: "%s",\n  CASQUE: true' % VERSION, cfg)
    big = [cfg]
    # Le lecteur est chargé AVANT app.js : app.js l'appelle dès son premier affichage.
    big.append(rd(os.path.join(W, "player.js")))
    for f in "lock.js emoji.js chat.js extras.js ios.js photoedit.js hotpic.js nowpic.js jeux.js support.js rappels.js appel.js app.js".split():
        big.append(js(f))
    big.append("window.__tmmReady=true;")
    tmp = os.path.join(OUT, "dist", "_all.js")
    wr(tmp, "\n;\n".join(big))
    subprocess.check_call([ES, tmp, "--minify", "--target=es2019", "--outfile=" + os.path.join(OUT, "dist", "app.min.js"), "--log-level=warning"])
    css = "\n".join(skin.words(skin.skin_css(rd(os.path.join(W, f)))) for f in ["appel.css", "chat.css", "photoedit.css"]) + "\n" + rd(os.path.join(W, "player.css"))
    wr(os.path.join(OUT, "dist", "_all.css"), css)
    subprocess.check_call([ES, os.path.join(OUT, "dist", "_all.css"), "--minify", "--outfile=" + os.path.join(OUT, "dist", "app.min.css"), "--log-level=warning"])
    os.remove(tmp)
    os.remove(os.path.join(OUT, "dist", "_all.css"))
    h = hashlib.md5(open(os.path.join(OUT, "dist", "app.min.js"), "rb").read() + open(os.path.join(OUT, "dist", "app.min.css"), "rb").read()).hexdigest()[:8]

    # Page d'accueil
    ix = skin.words(skin.skin_css(rd(os.path.join(W, "index.html"))))
    ix = ix.replace(' media="print" onload="this.media=\'all\'"', "")
    diag = ("<script>(function(){function s(m){var d=document.getElementById('cq-err');if(!d){d=document.createElement('pre');d.id='cq-err';"
            "d.style.cssText='position:fixed;z-index:99999;left:8px;right:8px;top:60px;background:#400;color:#fff;padding:10px;font:12px monospace;white-space:pre-wrap;border-radius:8px';"
            "(document.body||document.documentElement).appendChild(d)}d.textContent+=m+'\\n'}"
            "addEventListener('error',function(e){if(!e.filename&&!e.lineno&&/^Script error/.test(e.message||''))return;s((e.message||'erreur')+' @'+String(e.filename||'').split('/').pop()+':'+e.lineno)});"
            "addEventListener('unhandledrejection',function(e){s('promesse: '+(e.reason&&e.reason.message||e.reason))})})();</script>")
    ix = ix.replace('<meta charset="utf-8">', '<meta charset="utf-8">\n  ' + diag, 1)
    ix = ix.replace("rgba(110,80,220,.26)", "rgba(47,123,255,.22)")
    ix = re.sub(r"dist/app\.min\.(js|css)\?v=[A-Za-z0-9]*", lambda m: "dist/app.min.%s?v=%s" % (m.group(1), h), ix)
    ix = ix.replace('<div id="chat" hidden></div>', '<div id="chat" hidden></div>\n  <div id="player" hidden></div>')
    ix = ix.replace('<meta name="apple-mobile-web-app-title" content="Casque">', '<meta name="apple-mobile-web-app-title" content="Casque">')
    wr(os.path.join(OUT, "index.html"), ix)

    # Service worker (cache à part : le SW de « Tu me manques » efface les caches « tmm-… »)
    sw = skin.words(rd(os.path.join(W, "sw.js")))
    sw = re.sub(r'const CACHE = "[^"]*"', 'const CACHE = "casque-%s"' % h, sw).replace('k.startsWith("tmm-")', 'k.startsWith("casque-")')
    sw = sw.replace('/\\/(dist|icons|stickers)\\//', '/\\/(dist|icons)\\//')
    sw = sw.replace('const isPage =', 'if (url.pathname.indexOf("/telecharger/") >= 0) return;\n  const isPage =', 1)
    wr(os.path.join(OUT, "sw.js"), sw)

    # Page de téléchargement envoyée aux amis (lien de parrainage)
    shutil.copy(os.path.join(ROOT, "tools", "telecharger.html"), os.path.join(OUT, "telecharger.html"))
    os.makedirs(os.path.join(OUT, "telecharger"), exist_ok=True)
    shutil.move(os.path.join(OUT, "telecharger.html"), os.path.join(OUT, "telecharger", "index.html"))

    mf = rd(os.path.join(W, "manifest.webmanifest"))
    mf = skin.words(mf).replace("Un bouton pour dire « Casque » à ton ami(e).", "Lecteur audio et messagerie entre amis.").replace("#160a12", "#0A0D13")
    mf = re.sub(r'"description": "[^"]*"', '"description": "Lecteur audio et messagerie entre amis."', mf)
    wr(os.path.join(OUT, "manifest.webmanifest"), mf)
    # update.json sert aux deux : « version » = iPhone/web ; versionCode / versionName / apk = Android (voir tools/casque-android.json)
    import json
    upd = {"version": VERSION, "notes": NOTES}
    ap = os.path.join(ROOT, "tools", "casque-android.json")
    if os.path.exists(ap):
        upd.update(json.loads(rd(ap)))
    wr(os.path.join(OUT, "update.json"), json.dumps(upd, ensure_ascii=False, indent=1) + "\n")

    # Icônes : un casque blanc sur fond bleu nuit
    from PIL import Image, ImageDraw
    def icon(n):
        S = 4 * n
        im = Image.new("RGB", (S, S))
        d = ImageDraw.Draw(im)
        for y in range(S):
            t = y / S
            d.line([(0, y), (S, y)], fill=(int(24 + 8 * t), int(70 - 38 * t), int(150 - 85 * t)))
        w = S * 0.07
        bx0, by0, bx1, by1 = S * 0.22, S * 0.2, S * 0.78, S * 0.76
        d.arc([bx0, by0, bx1, by1], 180, 360, fill="white", width=int(w))
        for x0 in (S * 0.17, S * 0.62):
            d.rounded_rectangle([x0, S * 0.47, x0 + S * 0.21, S * 0.74], radius=S * 0.07, fill="white")
            d.rounded_rectangle([x0 + S * 0.035, S * 0.51, x0 + S * 0.175, S * 0.70], radius=S * 0.05, fill=(34, 98, 220))
        return im.resize((n, n), Image.LANCZOS)
    for n, name in [(512, "icon-512.png"), (192, "icon-192.png"), (180, "apple-touch-icon.png")]:
        os.makedirs(os.path.join(OUT, "icons"), exist_ok=True)
        icon(n).save(os.path.join(OUT, "icons", name))
    print("casque web ok ->", OUT, h)


if __name__ == "__main__":
    main()
