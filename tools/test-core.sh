#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
compiler="${KOTLINC:-kotlinc}"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/caldav-android-core.XXXXXX")"
trap 'rm -f "$tmp/core.jar"; rmdir "$tmp"' EXIT
"$compiler" "$root"/app/src/main/java/io/github/brunofin/caldavtasks/core/*.kt \
    "$root/app/src/test/java/io/github/brunofin/caldavtasks/CoreChecks.kt" \
    -include-runtime -jvm-target 17 -d "$tmp/core.jar"
java -jar "$tmp/core.jar"
