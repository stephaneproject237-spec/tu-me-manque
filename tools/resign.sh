#!/usr/bin/env bash
# Resigne les APK avec la clé privée de Free tune (secrets GitHub) en gardant la continuité
# avec l'ancienne clé (lignée de signature Android), pour que les mises à jour s'installent.
# Usage : tools/resign.sh fichier.apk [autre.apk ...]
set -euo pipefail
if [ -z "${SIGN_KEYSTORE_B64:-}" ] || [ -z "${SIGN_PASSWORD:-}" ]; then
  if [ "${REQUIRE_SIGN:-0}" = 1 ]; then
    echo "::error::Clé de signature absente (secrets SIGN_KEYSTORE_B64 et SIGN_PASSWORD) : publication refusée."
    exit 1
  fi
  echo "::warning::Clé de signature absente : APK laissés signés avec la clé de test (pas de publication)."
  exit 0
fi
BT=$(ls -d "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/build-tools/* | sort -V | tail -1)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
echo "$SIGN_KEYSTORE_B64" | base64 -d > "$T/new.p12"
printf '%s' "$SIGN_PASSWORD" > "$T/pw"
"$BT/apksigner" rotate --out "$T/lineage" \
  --old-signer --ks app/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
  --new-signer --ks "$T/new.p12" --ks-pass "file:$T/pw" --ks-key-alias freetune --key-pass "file:$T/pw"
for f in "$@"; do
  "$BT/apksigner" sign --ks "$T/new.p12" --ks-pass "file:$T/pw" --ks-key-alias freetune --key-pass "file:$T/pw" \
    --lineage "$T/lineage" "$f"
  echo "== $f"
  "$BT/apksigner" verify --verbose --print-certs "$f" | grep -E "^Verifies|Verified using|Number of signers|certificate SHA-256" || { echo "::error::Signature invalide : $f"; exit 1; }
done
