#!/usr/bin/env bash
# Boots the real jar (dev login) against the fake miner and a Twitch validate mock.
set -euo pipefail
cd "$(dirname "$0")"
DATA=$(mktemp -d /tmp/tl-e2e.XXXX)
cat > "$DATA/twitch-token.json" <<JSON
{"accessToken":"e2e","login":"tomlurkt","userId":"4711","scopes":["chat:read","user_read"],"obtainedAt":"2026-09-28T10:00:00Z"}
JSON
python3 twitch_mock.py &
MOCK=$!
trap 'kill $MOCK 2>/dev/null' EXIT
PORT=18080 COOKIE_SECURE=false LURKER_DATA_DIR="$DATA" LURKER_PYTHON=python3 LURKER_RUNNER="$PWD/fake_miner.py" \
  LURKER_DEV_PASSWORD=e2e-pass java -jar ../target/twitchlurker-*.jar --spring.profiles.active=dev --lurker.twitch-id-base=http://127.0.0.1:18099
