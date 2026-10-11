#!/usr/bin/env bash
# Starts the checkout app: the REST API and the Temporal worker in one process.
# Builds the jar first if the sources changed. Ctrl+C (or kill -9) is the
# "pod killed" moment from the video; run this again to bring the worker back.
set -euo pipefail
cd "$(dirname "$0")"

if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17 ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

JAR=target/temporal-checkout-1.0.0-SNAPSHOT.jar
if [[ ! -f $JAR || -n "$(find src pom.xml -newer "$JAR" -type f | head -1)" ]]; then
  echo "building $JAR ..."
  mvn -q -B package -DskipTests
fi

exec "$JAVA" -jar "$JAR" "$@"
