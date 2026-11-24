#!/bin/sh
set -eu

validate_password() {
  name="$1"
  password="$2"
  case "$password" in
    ''|*[!A-Za-z0-9_-]*)
      echo "$name must contain only letters, digits, underscore, or hyphen" >&2
      exit 1
      ;;
  esac
  if [ "${#password}" -lt 32 ]; then
    echo "$name must be at least 32 characters" >&2
    exit 1
  fi
}

validate_password REDIS_FEATURE_ENRICHER_PASSWORD "${REDIS_FEATURE_ENRICHER_PASSWORD:-}"
validate_password REDIS_ALERT_PASSWORD "${REDIS_ALERT_PASSWORD:-}"
if [ "$REDIS_FEATURE_ENRICHER_PASSWORD" = "$REDIS_ALERT_PASSWORD" ]; then
  echo "Redis application passwords must be distinct" >&2
  exit 1
fi

acl_file=/tmp/redis-users.acl
umask 077
printf '%s\n' \
  'user default off' \
  "user feature-enricher on >${REDIS_FEATURE_ENRICHER_PASSWORD} ~feature:* +@read +@write +@connection +@scripting" \
  "user alert-service on >${REDIS_ALERT_PASSWORD} ~model-promotion:api-rate:v1:* +@read +@write +@connection +@scripting" \
  > "$acl_file"
chmod 0600 "$acl_file"

exec redis-server --aclfile "$acl_file"
