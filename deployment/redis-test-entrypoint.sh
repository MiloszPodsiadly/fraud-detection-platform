#!/bin/sh
set -eu

script_directory="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$script_directory/redis-acl-users.sh"
validate_redis_password REDIS_TEST_ADMIN_PASSWORD "${REDIS_TEST_ADMIN_PASSWORD:-}"

prepare_redis_acl_file
printf '%s\n' \
  "user test-admin on >${REDIS_TEST_ADMIN_PASSWORD} ~* +ping +hello +select +client|setinfo +get +set +exists +del +keys +flushdb +flushall +info" \
  >> "$REDIS_ACL_FILE"
start_redis_with_acl
