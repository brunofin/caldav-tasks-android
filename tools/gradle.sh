#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
# Android Studio's machine-local Gradle JDK setting is intentionally gitignored.
if [[ -z "${JAVA_HOME:-}" && -f "$root/.gradle/config.properties" ]]; then
    JAVA_HOME="$(sed -n 's/^java.home=//p' "$root/.gradle/config.properties" | head -n 1)"
    export JAVA_HOME
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
exec "$root/gradlew" -p "$root" "$@"
