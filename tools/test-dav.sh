#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
: "${DAV_TEST_CLASSPATH:?Set DAV_TEST_CLASSPATH to okhttp 4.12, okio-jvm and kxml2 jars}"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/caldav-android-dav.XXXXXX")"
trap 'rm -f "$tmp/dav.jar"; rmdir "$tmp"' EXIT
"${KOTLINC:-kotlinc}" "$root"/app/src/main/java/io/github/brunofin/caldavtasks/core/*.kt \
    "$root/app/src/main/java/io/github/brunofin/caldavtasks/data/CalDavClient.kt" \
    "$root/app/src/test/java/io/github/brunofin/caldavtasks/DavChecks.kt" \
    -classpath "$DAV_TEST_CLASSPATH" -include-runtime -jvm-target 17 -d "$tmp/dav.jar"
java -cp "$tmp/dav.jar:$DAV_TEST_CLASSPATH" io.github.brunofin.caldavtasks.DavChecksKt
