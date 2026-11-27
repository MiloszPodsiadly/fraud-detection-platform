#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -eq 0 ]]; then
  echo "Usage: run-maven-with-bounded-retries.sh <command> [arguments...]" >&2
  exit 2
fi

readonly max_attempts=3
readonly max_total_seconds=900
readonly max_retry_wait_seconds=300
readonly max_backoff_seconds=15
started_at=$SECONDS
total_wait_seconds=0
temporary_log="$(mktemp)"
trap 'rm -f -- "$temporary_log"' EXIT

redact_and_print_log() {
  sed -E \
    -e 's#(https?://)[^/@[:space:]]+:[^/@[:space:]]+@#\1[REDACTED]@#gI' \
    -e 's#((password|passwd|token|secret|authorization|api[_-]?key)[[:space:]]*[=:][[:space:]]*)[^[:space:],;]+#\1[REDACTED]#gI' \
    "$temporary_log" | tail -n 120 >&2
}

is_retryable_failure() {
  grep -Eiq '(^|[^0-9])(429|500|502|503|504)([^0-9]|$)|Connection reset|Connection refused|timed out|Temporary failure|Unknown host|Name or service not known|No route to host|Network is unreachable|remote host terminated handshake' "$temporary_log"
}

retry_after_seconds() {
  local value timestamp now
  value="$(sed -nE 's/^[[:space:]]*Retry-After:[[:space:]]*([^[:space:]].*)[[:space:]]*$/\1/Ip' "$temporary_log" | tail -n 1 | tr -d '\r')"
  if [[ "$value" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "$value"
    return 0
  fi
  if [[ "$value" =~ ^(Mon|Tue|Wed|Thu|Fri|Sat|Sun),\ [0-9]{2}\ (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\ [0-9]{4}\ [0-9]{2}:[0-9]{2}:[0-9]{2}\ GMT$ ]]; then
    timestamp="$(date -u -d "$value" +%s 2>/dev/null || true)"
    now="$(date -u +%s)"
    if [[ "$timestamp" =~ ^[0-9]+$ ]]; then
      if (( timestamp > now )); then
        printf '%s\n' "$((timestamp - now))"
      else
        printf '0\n'
      fi
      return 0
    fi
  fi
  return 1
}

for ((attempt = 1; attempt <= max_attempts; attempt += 1)); do
  elapsed=$((SECONDS - started_at))
  remaining=$((max_total_seconds - elapsed))
  if (( remaining <= 0 )); then
    echo "Maven command exceeded its 900-second total deadline." >&2
    exit 124
  fi

  attempt_timeout=$remaining
  if (( attempt_timeout > 600 )); then attempt_timeout=600; fi
  set +e
  timeout --signal=TERM --kill-after=10 "${attempt_timeout}s" "$@" >"$temporary_log" 2>&1
  status=$?
  set -e
  if (( status == 0 )); then
    redact_and_print_log
    exit 0
  fi

  if (( status == 124 || attempt == max_attempts )) || ! is_retryable_failure; then
    echo "Maven command stopped after attempt ${attempt} with exit status ${status}; Maven output follows with credential-like values redacted." >&2
    redact_and_print_log
    exit "$status"
  fi

  delay=""
  if delay="$(retry_after_seconds)"; then
    :
  else
    delay=$((2 ** attempt))
    if (( delay > max_backoff_seconds )); then delay=$max_backoff_seconds; fi
  fi
  elapsed=$((SECONDS - started_at))
  remaining=$((max_total_seconds - elapsed))
  if (( total_wait_seconds + delay > max_retry_wait_seconds || delay >= remaining )); then
    echo "Maven requested a ${delay}-second retry delay beyond the remaining bounded budget; stopping without an early retry." >&2
    redact_and_print_log
    exit "$status"
  fi

  echo "Maven attempt ${attempt} failed transiently; waiting ${delay} second(s) before retry." >&2
  sleep "$delay"
  total_wait_seconds=$((total_wait_seconds + delay))
done

echo "Maven command exhausted its bounded retry budget." >&2
exit 1
