#!/bin/sh
set -eu

validate_redis_password() {
  name="$1"
  password="$2"
  case "$password" in
    ''|*[!A-Za-z0-9_-]*)
      echo "$name must contain only letters, digits, underscore, or hyphen" >&2
      return 1
      ;;
  esac
  if [ "${#password}" -lt 32 ]; then
    echo "$name must be at least 32 characters" >&2
    return 1
  fi
}

write_application_acl_users() {
  validate_redis_password REDIS_FEATURE_ENRICHER_PASSWORD "${REDIS_FEATURE_ENRICHER_PASSWORD:-}"
  validate_redis_password REDIS_ALERT_PASSWORD "${REDIS_ALERT_PASSWORD:-}"
  if [ "$REDIS_FEATURE_ENRICHER_PASSWORD" = "$REDIS_ALERT_PASSWORD" ]; then
    echo "Redis application passwords must be distinct" >&2
    return 1
  fi

  printf '%s\n' \
    'user default off' \
    "user feature-enricher on >${REDIS_FEATURE_ENRICHER_PASSWORD} ~feature:* +ping +hello +select +client|setinfo +get +set +exists +type +pttl +expire +pexpire +hexists +hget +hset +sadd +sismember +zadd +zscore +zrangebyscore +zremrangebyscore +zcount +eval +evalsha" \
    "user alert-service on >${REDIS_ALERT_PASSWORD} ~model-promotion:api-rate:v1:* ~audit:coverage:rate:* +ping +hello +select +client|setinfo +incrby +expire +pexpire +time +zremrangebyscore +zscore +zcard +zadd +eval +evalsha"
}

prepare_redis_acl_file() {
  REDIS_ACL_DIRECTORY="$(mktemp -d /tmp/redis-acl.XXXXXX)"
  REDIS_ACL_FILE="$REDIS_ACL_DIRECTORY/users.acl"
  umask 077
  write_application_acl_users > "$REDIS_ACL_FILE"
}

start_redis_with_acl() {
  chown redis:redis "$REDIS_ACL_DIRECTORY" "$REDIS_ACL_FILE"
  chmod 0700 "$REDIS_ACL_DIRECTORY"
  chmod 0400 "$REDIS_ACL_FILE"
  exec /usr/local/bin/docker-entrypoint.sh redis-server --aclfile "$REDIS_ACL_FILE"
}
