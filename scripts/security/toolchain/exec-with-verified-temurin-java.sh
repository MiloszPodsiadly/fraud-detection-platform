#!/bin/sh
set -eu
/usr/local/bin/verify-temurin-java-runtime
exec "$JAVA_HOME/bin/java" "$@"