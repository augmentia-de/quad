#!/bin/bash
cd /home/torsten/dev/my-projects/nooa/quad/quad-quarkus || exit 1
source /home/torsten/dev/my-projects/nooa/quad/set_keys.sh >/dev/null 2>&1
exec mvn quarkus:dev \
  -Dquarkus.http.port=8086 \
  -DskipTests \
  -Dquarkus.kafka.devservices.enabled=false \
  -Dquarkus.amqp.devservices.enabled=false