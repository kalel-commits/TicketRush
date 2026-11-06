#!/usr/bin/env bash
# Generates the RSA 2048 dev key pair for user tokens:
#   private/jwt-private.pem (PKCS#8)  - mounted ONLY into user-service
#   public/jwt-public.pem   (X.509)   - mounted into the gateway and services
# Keys live in folders (not single-file mounts) so a missing key can never make Docker create a
# directory in its place. Files are git-ignored.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p private public
for f in private/jwt-private.pem public/jwt-public.pem; do
  if [ -e "$f" ] && [ ! -f "$f" ]; then
    echo "ERROR: $(pwd)/$f exists but is not a regular file. Delete it (rm -r \"$(pwd)/$f\") and run this script again."
    exit 1
  fi
done
if [ -f private/jwt-private.pem ] && [ -f public/jwt-public.pem ]; then
  echo "Keys already exist in $(pwd) - skipping"; exit 0
fi
command -v openssl >/dev/null || { echo "openssl not found (install Git for Windows or OpenSSL)"; exit 1; }
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private/jwt-private.pem
openssl pkey -in private/jwt-private.pem -pubout -out public/jwt-public.pem
echo "Generated $(pwd)/private/jwt-private.pem and $(pwd)/public/jwt-public.pem"
