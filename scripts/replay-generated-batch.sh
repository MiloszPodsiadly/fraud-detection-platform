#!/usr/bin/env bash
set -euo pipefail

replay_file="data/generated/canonical-replay.jsonl"
simulator_jsonl_path="data/generated/synthetic-replay.jsonl"
max_events=2000
throttle_millis=10
simulator_base_url="http://localhost:8082"
generate_if_missing=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --replay-file) replay_file="$2"; shift 2 ;;
    --simulator-jsonl-path) simulator_jsonl_path="$2"; shift 2 ;;
    --max-events) max_events="$2"; shift 2 ;;
    --throttle-millis) throttle_millis="$2"; shift 2 ;;
    --simulator-base-url) simulator_base_url="$2"; shift 2 ;;
    --generate-if-missing) generate_if_missing=true; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ "$generate_if_missing" == true && ! -f "$replay_file" ]]; then
  "$(dirname "$0")/generate-canonical-replay.sh" --count "$max_events" --output "$replay_file"
fi

if [[ ! -f "$replay_file" ]]; then
  echo "Replay file not found: $replay_file" >&2
  exit 1
fi

mkdir -p "$(dirname "$simulator_jsonl_path")"
cp "$replay_file" "$simulator_jsonl_path"

"$(dirname "$0")/start-synthetic-replay.sh" \
  --source-type JSONL \
  --max-events "$max_events" \
  --throttle-millis "$throttle_millis" \
  --simulator-base-url "$simulator_base_url"
