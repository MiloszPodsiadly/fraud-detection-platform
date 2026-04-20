#!/bin/bash

set -euo pipefail

BOOTSTRAP_SERVER="${BOOTSTRAP_SERVER:-kafka:9092}"

create_topic() {
  local topic_name="$1"
  local partitions="$2"
  local replication_factor="$3"

  /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "${BOOTSTRAP_SERVER}" \
    --create \
    --if-not-exists \
    --topic "${topic_name}" \
    --partitions "${partitions}" \
    --replication-factor "${replication_factor}"
}

create_topic "transactions.raw" 3 1
create_topic "transactions.enriched" 3 1
create_topic "transactions.scored" 3 1
create_topic "fraud.alerts" 3 1
create_topic "fraud.decisions" 3 1
create_topic "transactions.dead-letter" 3 1
