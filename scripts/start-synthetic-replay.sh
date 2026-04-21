#!/usr/bin/env bash
set -euo pipefail

source_type="SYNTHETIC"
max_events=2000
throttle_millis=10
simulator_base_url="http://localhost:8082"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-type) source_type="$2"; shift 2 ;;
    --max-events) max_events="$2"; shift 2 ;;
    --throttle-millis) throttle_millis="$2"; shift 2 ;;
    --simulator-base-url) simulator_base_url="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

curl -fsS \
  -X POST \
  -H "Content-Type: application/json" \
  -d "{\"sourceType\":\"${source_type}\",\"maxEvents\":${max_events},\"throttleMillis\":${throttle_millis}}" \
  "${simulator_base_url}/api/v1/replay/start"
