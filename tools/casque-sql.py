#!/usr/bin/env python3
"""Génère le SQL de « Casque » : une copie des tables et fonctions de « Tu me manques » préfixées « c_ »
(données totalement séparées), plus la gestion de plusieurs amis (supabase/casque-amis.sql)."""
import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SQ = os.path.join(ROOT, "supabase")
FILES = ["schema", "compte", "chat", "contact", "fond", "typing", "video", "supprimer", "modifier",
         "jeux", "jeux2", "jeux3", "historique", "ephemere", "appels", "nowpic", "rappels"]
SKIP_FUNCS = {"admin_link"}          # l'administration reste propre à « Tu me manques »


def read(n):
    with open(os.path.join(SQ, n + ".sql"), encoding="utf-8") as f:
        return f.read()


def main():
    texts = {n: read(n) for n in FILES}
    allsrc = "\n".join(texts.values())
    tables = set(re.findall(r"create table (?:if not exists )?public\.(\w+)", allsrc))
    funcs = set(re.findall(r"create (?:or replace )?function public\.(\w+)", allsrc)) - SKIP_FUNCS
    # tables mentionnées mais définies ailleurs (admin.sql) dans les fichiers retenus
    tables |= {"profiles", "requests", "notifications", "messages", "typing", "friendship_log", "support_messages", "reminders"}
    idx = set(re.findall(r"create (?:unique )?index (?:if not exists )?(\w+)", allsrc))
    trig = set(re.findall(r"create trigger (\w+)", allsrc))
    out = ["-- Casque : base de données (copie séparée de « Tu me manques » + plusieurs amis).",
           "-- Colle tout dans Supabase → SQL Editor → Run. Peut être relancé sans danger.",
           "create extension if not exists pg_net;", ""]
    for n in FILES:
        t = texts[n]
        if n == "historique":      # l'administration reste propre à « Tu me manques »
            t = re.sub(r"-- ===== BLOC 4.*?(?=-- ===== BLOC 5)", "", t, flags=re.S)
        for name in sorted(tables, key=len, reverse=True):
            t = re.sub(r"\bpublic\.%s\b" % name, "public.c_" + name, t)
        for name in sorted(funcs, key=len, reverse=True):
            t = re.sub(r"\bpublic\.%s\b" % name, "public.c_" + name, t)
        for name in sorted(idx | trig, key=len, reverse=True):
            t = re.sub(r"(?<![\w.])%s\b" % name, "c_" + name, t)
        t = t.replace("'tmm-programmes'", "'casque-programmes'")
        t = t.replace("Tu me manques", "Casque").replace("💗", "🎧")
        out.append("-- ============================== %s ==============================" % n)
        out.append(t)
    out.append("-- ============================== plusieurs amis ==============================")
    with open(os.path.join(SQ, "casque-amis.sql"), encoding="utf-8") as f:
        out.append(f.read())
    sql = "\n".join(out)
    sql = sql.replace("PROJECT_REF", "ahwxssgcwqzkxitpfvxy")
    dst = os.path.join(SQ, "casque.sql")
    with open(dst, "w", encoding="utf-8") as f:
        f.write(sql)
    import html
    page = ('<!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">\n'
            '<title>SQL Casque</title>\n<style>body{font-family:system-ui,sans-serif;margin:0;padding:16px;background:#0E1013;color:#E8ECF1}'
            'button{font:inherit;font-weight:700;border:0;border-radius:22px;padding:14px 22px;background:#2F7BFF;color:#fff;width:100%;position:sticky;top:8px}'
            'pre{white-space:pre-wrap;word-break:break-word;font-size:11px;background:#171A1F;border-radius:12px;padding:12px;border:1px solid #2A2F37}</style></head><body>\n'
            '<button id="b">Copier le SQL</button><p id="m" style="text-align:center;color:#6FE3A0;min-height:1.2em"></p>\n'
            '<pre id="t">' + html.escape(sql, quote=False) + '</pre>\n'
            '<script>document.getElementById("b").onclick=async()=>{const t=document.getElementById("t").textContent;try{await navigator.clipboard.writeText(t);document.getElementById("m").textContent="Copié ! Colle-le dans Supabase (SQL Editor).";}catch(e){const r=document.createRange();r.selectNodeContents(document.getElementById("t"));const x=getSelection();x.removeAllRanges();x.addRange(r);document.getElementById("m").textContent="Texte sélectionné : appuie sur Copier dans le menu.";}};</script>\n</body></html>')
    with open(os.path.join(ROOT, "web", "sql", "casque.html"), "w", encoding="utf-8") as f:
        f.write(page)
    print("ok", len(sql), "octets;", len(tables), "tables;", len(funcs), "fonctions")


if __name__ == "__main__":
    main()
