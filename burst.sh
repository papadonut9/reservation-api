#!/usr/bin/env bash
# One-command on-sale stampede: ./burst.sh <BASE_URL>. Needs JDK 21; the app needs DEV_TOKEN_ENABLED=true.
# Env: REQUESTS (20000), CONCURRENCY (= REQUESTS; cap it on Windows/local, e.g. 5000). Exit 1 on any FAIL.
# All logic lives in scripts/Burst.java (JDK only, no build step).
exec java "$(dirname "$0")/scripts/Burst.java" "$@"
