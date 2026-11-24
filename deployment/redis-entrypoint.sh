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

if [ "${REDIS_TEST_MODE:-false}" = "true" ]; then
  validate_password REDIS_TEST_ADMIN_PASSWORD "${REDIS_TEST_ADMIN_PASSWORD:-}"
elif [ -n "${REDIS_TEST_ADMIN_PASSWORD:-}" ]; then
  echo "REDIS_TEST_ADMIN_PASSWORD requires REDIS_TEST_MODE=true" >&2
  exit 1
fi

acl_file=/tmp/redis-users.acl
umask 077
printf '%s\n' \
  'user default off' \
  "user feature-enricher on >${REDIS_FEATURE_ENRICHER_PASSWORD} ~feature:* +ping +hello +select +client|setinfo +get +set +exists +type +pttl +expire +pexpire +hexists +hget +hset +sadd +sismember +zadd +zscore +zrangebyscore +zremrangebyscore +zcount +eval +evalsha" \
  "user alert-service on >${REDIS_ALERT_PASSWORD} ~model-promotion:api-rate:v1:* ~audit:coverage:rate:* +ping +hello +select +client|setinfo +incrby +expire +pexpire +time +zremrangebyscore +zscore +zcard +zadd +eval +evalsha" \
  > "$acl_file"
if [ "${REDIS_TEST_MODE:-false}" = "true" ]; then
  printf '%s\n' \
    "user test-admin on >${REDIS_TEST_ADMIN_PASSWORD} ~* +ping +hello +select +client|setinfo +get +set +exists +del +keys +flushdb +flushall +info" \
    >> "$acl_file"
fi
chmod 0600 "$acl_file"

exec redis-server --aclfile "$acl_file"
