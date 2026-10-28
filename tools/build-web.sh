#!/bin/bash
# Regroupe et compresse le code web : 1 fichier JS + 1 fichier CSS (beaucoup plus rapide sur un réseau lent).
set -e
cd "$(dirname "$0")/../web"
ES=${ESBUILD:-$(command -v esbuild || echo /opt/npm-tools/node_modules/.bin/esbuild)}
mkdir -p dist
: > dist/_all.js
for f in config.js lock.js emoji.js chat.js extras.js ios.js photoedit.js hotpic.js nowpic.js jeux.js musicbot.js support.js rappels.js appel.js app.js; do
  cat "$f" >> dist/_all.js; printf '\n;\n' >> dist/_all.js
done
printf 'window.__tmmReady=true;\n' >> dist/_all.js
"$ES" dist/_all.js --minify --target=es2019 --outfile=dist/app.min.js --log-level=warning
cat appel.css chat.css photoedit.css > dist/_all.css
"$ES" dist/_all.css --minify --outfile=dist/app.min.css --log-level=warning
rm -f dist/_all.js dist/_all.css
H=$(cat dist/app.min.js dist/app.min.css | md5sum | cut -c1-8)
sed -i -E "s#dist/app\.min\.(js|css)\?v=[A-Za-z0-9]*#dist/app.min.\1?v=$H#g" index.html
sed -i -E "s#(const CACHE = \")[^\"]*#\1tmm-$H#" sw.js
ls -la dist | awk '{print $5, $9}'
