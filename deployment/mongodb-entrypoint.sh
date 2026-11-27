#!/usr/bin/env bash
set -Eeuo pipefail

# This Compose topology is a single-member development replica set. Its internal
# keyfile is ephemeral and is regenerated on container creation; do not reuse
# this entrypoint for a multi-member or production replica set.
keyfile=/tmp/mongodb-replica-set-key
umask 077
openssl rand -base64 756 > "$keyfile"
chown mongodb:mongodb "$keyfile"
chmod 0400 "$keyfile"

exec /usr/local/bin/docker-entrypoint.sh mongod \
  --replSet rs0 \
  --bind_ip_all \
  --keyFile "$keyfile"
