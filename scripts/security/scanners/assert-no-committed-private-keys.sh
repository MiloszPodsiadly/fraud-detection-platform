#!/usr/bin/env bash
set -euo pipefail

violations=()
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "Cannot verify tracked private keys without repository access." >&2
  exit 1
fi
if ! tracked_files="$(git ls-files)"; then
  echo "Cannot enumerate tracked files for private-key guard." >&2
  exit 1
fi
while IFS= read -r path; do
  [[ -e "$path" ]] || continue
  case "$path" in
    *-key.pem|*private*.pem|*local-dev-ca-key.pem|*private-key.pkcs8.base64)
      violations+=("$path")
      ;;
  esac
done <<< "$tracked_files"

pem_pattern='^[[:space:]]*-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[[:space:]]*$'
set +e
pem_blocks="$(git grep -n -I -E -- "$pem_pattern" -- . 2>/dev/null)"
grep_status=$?
set -e
if [[ "$grep_status" -eq 0 ]]; then
  violations+=("$pem_blocks")
elif [[ "$grep_status" -ne 1 ]]; then
  echo "Cannot inspect tracked content for private-key blocks." >&2
  exit 1
fi

if ((${#violations[@]} > 0)); then
  echo "Tracked private key material is forbidden:" >&2
  printf '%s\n' "${violations[@]}" >&2
  exit 1
fi

echo "No committed private key material detected."
