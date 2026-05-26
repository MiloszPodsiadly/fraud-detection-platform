#!/usr/bin/env bash
set -euo pipefail

violations=()
while IFS= read -r path; do
  [[ -e "$path" ]] || continue
  case "$path" in
    *-key.pem|*private*.pem|*local-dev-ca-key.pem)
      violations+=("$path")
      ;;
  esac
done < <(git ls-files)

pem_pattern='^[[:space:]]*-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[[:space:]]*$'
if pem_blocks="$(git grep -n -I -E -- "$pem_pattern" -- . 2>/dev/null)"; then
  violations+=("$pem_blocks")
fi

if ((${#violations[@]} > 0)); then
  echo "Tracked private key material is forbidden:" >&2
  printf '%s\n' "${violations[@]}" >&2
  exit 1
fi

echo "No committed private key material detected."
