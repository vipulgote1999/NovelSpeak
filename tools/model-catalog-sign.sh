#!/usr/bin/env bash
# Sign / verify the model catalog for OTA updates (RSA SHA-256).
#
#   tools/model-catalog-sign.sh keygen                 # create release key pair (keep private key offline)
#   tools/model-catalog-sign.sh sign [catalog.json]    # writes model_catalog.json.sig (hex) next to it
#   tools/model-catalog-sign.sh verify [catalog.json]  # verify with the bundled public key
#
# The app trusts only catalogs whose signature verifies with the embedded
# app/assets/model_catalog_pub.pem (ModelCatalog.verifySignature).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/assets"
KEY="${KEY:-$ROOT/build/model-catalog.pem}"
PUB="$ASSETS/model_catalog_pub.pem"
CAT="${2:-$ASSETS/model_catalog.json}"
SIG="$CAT.sig"

case "${1:-}" in
  keygen)
    mkdir -p "$(dirname "$KEY")"
    [ -f "$KEY" ] && { echo "refusing to overwrite existing key: $KEY" >&2; exit 1; }
    openssl genrsa -out "$KEY" 3072 >/dev/null
    openssl rsa -in "$KEY" -pubout -out "$PUB" >/dev/null
    echo "private key: $KEY (keep offline; never commit)"
    echo "public key:  $PUB (commit this)"
    ;;
  sign)
    [ -f "$KEY" ] || { echo "missing private key: $KEY" >&2; exit 1; }
    openssl dgst -sha256 -sign "$KEY" -out "$CAT.sig.bin" "$CAT"
    xxd -p -c 4096 "$CAT.sig.bin" | tr -d '\n' > "$SIG"
    rm -f "$CAT.sig.bin"
    echo "signed: $CAT"
    echo "signature: $SIG"
    ;;
  verify)
    openssl dgst -sha256 -verify "$PUB" -signature <(xxd -r -p "$SIG") "$CAT" 2>/dev/null \
      || { openssl dgst -sha256 -verify "$PUB" -signature "$CAT.sig.bin" "$CAT"; }
    ;;
  *)
    sed -n '2,10p' "$0"; exit 1 ;;
esac
