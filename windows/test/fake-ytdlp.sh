#!/bin/bash
# Faux yt-dlp pour les tests : crée un petit fichier à l'endroit demandé par -o.
out=""; search=0; dump=0
for ((i=1;i<=$#;i++)); do
  a="${!i}"
  if [ "$a" = "-o" ]; then j=$((i+1)); out="${!j}"; fi
  if [[ "$a" == ytsearch* ]]; then search=1; fi
  if [ "$a" = "--dump-json" ]; then dump=1; fi
done
if [ $search = 1 ]; then echo '{"entries":[{"id":"abcdefghijk","title":"Titre test","channel":"Artiste - Topic","duration":200,"view_count":5}]}'; exit 0; fi
if [ $dump = 1 ]; then echo "{\"url\":\"$FAKE_STREAM\",\"ext\":\"m4a\",\"protocol\":\"https\",\"format_id\":\"140\",\"filesize\":$FAKE_SIZE,\"http_headers\":{}}"; exit 0; fi
if [ -n "$out" ] && [ -n "$FAKE_SLOW" ] && [ ! -f "$FAKE_SLOW.go" ]; then echo "[download] 30%"; touch "$FAKE_SLOW.go"; exec sleep 20; fi
if [ -n "$out" ]; then f="${out/\%(ext)s/m4a}"; mkdir -p "$(dirname "$f")"; head -c 5000 /dev/zero > "$f"; echo "[download] 100% of 5000"; exit 0; fi
exit 1
