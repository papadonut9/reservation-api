#!/usr/bin/env bash
# Mint an HS256 token offline, signed with the same JWT_SECRET the app uses.
# Usage: JWT_SECRET=... scripts/jwt.sh <sub> [USER|ADMIN] [ttl_seconds]
#   scripts/jwt.sh u1            -> normal user, 1h
#   scripts/jwt.sh a1 ADMIN 600  -> admin, 10 min
set -euo pipefail

sub=${1:?usage: JWT_SECRET=... $0 <sub> [USER|ADMIN] [ttl_seconds]}
role=${2:-USER}
ttl=${3:-3600}
: "${JWT_SECRET:?set JWT_SECRET}"
[[ $sub =~ ^[A-Za-z0-9_-]{1,128}$ ]] || { echo "bad sub" >&2; exit 1; }
[[ $role == USER || $role == ADMIN ]] || { echo "role must be USER or ADMIN" >&2; exit 1; }

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now=$(date +%s)
header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
payload=$(printf '{"sub":"%s","role":"%s","iat":%d,"exp":%d}' "$sub" "$role" "$now" "$((now + ttl))" | b64url)
sig=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$JWT_SECRET" -binary | b64url)
echo "$header.$payload.$sig"
