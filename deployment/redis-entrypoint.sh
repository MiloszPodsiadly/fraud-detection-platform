#!/bin/sh
set -eu

script_directory="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$script_directory/redis-acl-users.sh"

prepare_redis_acl_file
start_redis_with_acl
