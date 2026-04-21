#!/usr/bin/env bash
set -euo pipefail

count=10000
seed=7341
replay_output="data/generated/canonical-replay.jsonl"
labels_output="data/generated/canonical-labels.csv"
dimensions_path="data/generated/dimensions.json"
normal_percentage=80
new_device_percentage=10
high_proxy_percentage=7
country_mismatch_percentage=1
account_takeover_percentage=2

while [[ $# -gt 0 ]]; do
  case "$1" in
    --count) count="$2"; shift 2 ;;
    --seed) seed="$2"; shift 2 ;;
    --replay-output) replay_output="$2"; shift 2 ;;
    --labels-output) labels_output="$2"; shift 2 ;;
    --dimensions) dimensions_path="$2"; shift 2 ;;
    --normal-percentage) normal_percentage="$2"; shift 2 ;;
    --new-device-percentage) new_device_percentage="$2"; shift 2 ;;
    --high-proxy-percentage) high_proxy_percentage="$2"; shift 2 ;;
    --country-mismatch-percentage) country_mismatch_percentage="$2"; shift 2 ;;
    --account-takeover-percentage) account_takeover_percentage="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

"$(dirname "$0")/generate-canonical-replay.sh" \
  --count "$count" \
  --seed "$seed" \
  --dimensions "$dimensions_path" \
  --output "$replay_output" \
  --labels-output "$labels_output" \
  --normal-percentage "$normal_percentage" \
  --new-device-percentage "$new_device_percentage" \
  --high-proxy-percentage "$high_proxy_percentage" \
  --country-mismatch-percentage "$country_mismatch_percentage" \
  --account-takeover-percentage "$account_takeover_percentage"
