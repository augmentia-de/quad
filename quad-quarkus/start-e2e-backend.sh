#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR" || exit 1
source "$SCRIPT_DIR/../set_keys.sh" >/dev/null 2>&1
exec mvn quarkus:dev \
  -Dquarkus.http.port=8086 \
  -DQUARKUS_DATASOURCE_JDBC_URL="jdbc:sqlite:${SCRIPT_DIR}/../data/quad.db" \
  -DskipTests \
  -Dquarkus.kafka.devservices.enabled=false \
  -Dquarkus.amqp.devservices.enabled=false