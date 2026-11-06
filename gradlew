#!/bin/sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "[ERROR] Missing gradle/wrapper/gradle-wrapper.jar" >&2
  exit 2
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_EXE="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_EXE=java
else
  echo "[ERROR] Java 17+ was not found." >&2
  exit 3
fi

exec "$JAVA_EXE" -jar "$WRAPPER_JAR" "$@"
