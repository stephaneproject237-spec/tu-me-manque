#!/usr/bin/env bash
# Resigne les APK avec la clé privée de Free tune (secrets GitHub) en gardant la continuité
# avec l'ancienne clé (lignée de signature Android), pour que les mises à jour s'installent.
# Usage : tools/resign.sh fichier.apk [autre.apk ...]
set -uo pipefail
LOG=$(mktemp)
fail() { echo "::error::Signature : échec à l'étape « $1 »"; head -c 1500 "$LOG" | head -12 | while IFS= read -r l; do echo "::error::$l"; done; exit 1; }
if [ -z "${SIGN_KEYSTORE_B64:-}" ] || [ -z "${SIGN_PASSWORD:-}" ]; then
  if [ "${REQUIRE_SIGN:-0}" = 1 ]; then
    echo "::error::Clé de signature absente (secrets SIGN_KEYSTORE_B64 et SIGN_PASSWORD) : publication refusée."
    exit 1
  fi
  echo "::warning::Clé de signature absente : APK laissés signés avec la clé de test (pas de publication)."
  exit 0
fi
BT=$(ls -d "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/build-tools/* 2>"$LOG" | sort -V | tail -1); [ -x "$BT/apksigner" ] || { echo "apksigner introuvable ($BT)" >> "$LOG"; fail "outil apksigner"; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
printf '%s' "$SIGN_KEYSTORE_B64" | tr -d ' \r\n' | base64 -d > "$T/new.p12" 2>"$LOG" || fail "lecture de la clé (base64)"
[ -s "$T/new.p12" ] || { echo "clé vide" > "$LOG"; fail "clé vide"; }
"$BT/apksigner" rotate --out "$T/lineage" \
  --old-signer --ks app/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
  --new-signer --ks "$T/new.p12" --ks-pass env:SIGN_PASSWORD --ks-key-alias freetune --key-pass env:SIGN_PASSWORD >"$LOG" 2>&1 || fail "création de la lignée"
for f in "$@"; do
  # Ancienne clé en premier (nécessaire à la signature v1 des vieux Android), nouvelle clé ensuite, avec la lignée.
  "$BT/apksigner" sign --ks app/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
    --next-signer --ks "$T/new.p12" --ks-pass env:SIGN_PASSWORD --ks-key-alias freetune --key-pass env:SIGN_PASSWORD \
    --lineage "$T/lineage" "$f" >"$LOG" 2>&1 || fail "signature de $f"
  echo "== $f"
  "$BT/apksigner" verify --verbose --print-certs "$f" >"$LOG" 2>&1 || fail "vérification de $f"
  grep -E "^Verifies|Verified using|Number of signers|certificate SHA-256" "$LOG"
done
