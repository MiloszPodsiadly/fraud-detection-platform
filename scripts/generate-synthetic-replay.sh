#!/usr/bin/env bash
set -euo pipefail

count=50000
seed=7341
output_path="data/generated/synthetic-replay.jsonl"
normal_percentage=93
rapid_transfer_seed_percentage=2
rapid_transfer_percentage=1
new_device_percentage=1
high_proxy_percentage=1
country_mismatch_percentage=1
account_takeover_percentage=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --count) count="$2"; shift 2 ;;
    --seed) seed="$2"; shift 2 ;;
    --output) output_path="$2"; shift 2 ;;
    --normal-percentage) normal_percentage="$2"; shift 2 ;;
    --rapid-transfer-seed-percentage) rapid_transfer_seed_percentage="$2"; shift 2 ;;
    --rapid-transfer-percentage) rapid_transfer_percentage="$2"; shift 2 ;;
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
  --output "$output_path" \
  --normal-percentage "$normal_percentage" \
  --rapid-transfer-seed-percentage "$rapid_transfer_seed_percentage" \
  --rapid-transfer-percentage "$rapid_transfer_percentage" \
  --new-device-percentage "$new_device_percentage" \
  --high-proxy-percentage "$high_proxy_percentage" \
  --country-mismatch-percentage "$country_mismatch_percentage" \
  --account-takeover-percentage "$account_takeover_percentage"
