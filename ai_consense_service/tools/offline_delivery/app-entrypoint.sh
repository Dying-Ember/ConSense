#!/bin/sh
set -eu

: "${APP_JAR:?}"
: "${APP_WEB_DIRECTORY:?}"
APP_ARTIFACT_ROOT=${APP_ARTIFACT_ROOT:-/artifacts}
APP_FILE_MANIFEST=${APP_FILE_MANIFEST:-/app-files}
cd "$APP_ARTIFACT_ROOT"
test -d "$APP_WEB_DIRECTORY"
test -z "$(find "$APP_WEB_DIRECTORY" ! -type f ! -type d -print -quit)"
actual=$(mktemp)
trap 'rm -f "$actual"' EXIT HUP INT TERM
LC_ALL=C find "$APP_WEB_DIRECTORY" -type f -print | LC_ALL=C sort > "$actual"
cmp -s "$APP_FILE_MANIFEST/web-files" "$actual"
sha256sum -c "$APP_FILE_MANIFEST/sha256sums"
rm -f "$actual"
trap - EXIT HUP INT TERM
exec java -jar "$APP_JAR" "--spring.web.resources.static-locations=file:$APP_ARTIFACT_ROOT/$APP_WEB_DIRECTORY/"
