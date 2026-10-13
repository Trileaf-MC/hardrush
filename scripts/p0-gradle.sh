#!/usr/bin/env bash
set -euo pipefail
HARDRUSH_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -n "${HARDRUSH_JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$HARDRUSH_JAVA_HOME"
elif [[ -z "${JAVA_HOME:-}" && -f "$HARDRUSH_ROOT/.local/toolchains/java-home.txt" ]]; then
    export JAVA_HOME="$(cat "$HARDRUSH_ROOT/.local/toolchains/java-home.txt")"
fi
cd "$HARDRUSH_ROOT"
exec ./gradlew "$@"

