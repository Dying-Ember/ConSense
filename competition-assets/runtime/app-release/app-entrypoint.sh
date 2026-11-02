#!/bin/sh
set -eu

: "${APP_JAR:?}"
: "${APP_WEB_DIRECTORY:?}"
: "${APP_RELEASE_DIRECTORY:?}"
: "${APP_RELEASE_FILES_SHA256:?}"
: "${APP_CONTENT_MANIFEST_SHA256:?}"
APP_ARTIFACT_ROOT=${APP_ARTIFACT_ROOT:-/artifacts}
APP_FILE_MANIFEST=${APP_FILE_MANIFEST:-/app-files}
case "$APP_ARTIFACT_ROOT" in /*) ;; *) echo 'APP_ARTIFACT_ROOT must be absolute' >&2; exit 1 ;; esac
check_root() (
    case "$1/" in */./*|*/../*) exit 1 ;; esac
    set -f
    IFS=/
    set -- $1
    path=
    for part do
        test -n "$part" || continue
        path=$path/$part
        test ! -L "$path" || exit 1
    done
)
check_root "$APP_ARTIFACT_ROOT"
cd "$APP_ARTIFACT_ROOT"
check_release_path() (
    case "$1" in ''|[!A-Za-z0-9_]*|*[!A-Za-z0-9_./-]*|*/|*//*) exit 1 ;; esac
    case "/$1/" in */./*|*/../*) exit 1 ;; esac
    set -f
    IFS=/
    set -- $1
    path=.
    for part do
        path=$path/$part
        test ! -L "$path" || exit 1
    done
)
check_release_path "$APP_JAR"
check_release_path "$APP_WEB_DIRECTORY"
if [ "$APP_RELEASE_DIRECTORY" != . ]; then
    check_release_path "$APP_RELEASE_DIRECTORY"
    case "$APP_JAR" in "$APP_RELEASE_DIRECTORY"/*) ;; *) exit 1 ;; esac
    case "$APP_WEB_DIRECTORY" in "$APP_RELEASE_DIRECTORY"/*) ;; *) exit 1 ;; esac
fi
test -d "$APP_RELEASE_DIRECTORY"
case "$APP_RELEASE_FILES_SHA256" in *[!a-f0-9]*|'') exit 1 ;; esac
test "${#APP_RELEASE_FILES_SHA256}" -eq 64
case "$APP_CONTENT_MANIFEST_SHA256" in *[!a-f0-9]*|'') exit 1 ;; esac
test "${#APP_CONTENT_MANIFEST_SHA256}" -eq 64
test -d "$APP_WEB_DIRECTORY"
test -z "$(find "$APP_WEB_DIRECTORY" ! -type f ! -type d -print -quit)"
actual=$(mktemp)
list=$(mktemp)
sums=$(mktemp)
web_list=$(mktemp)
trap 'rm -f "$actual" "$list" "$list.sorted" "$sums" "$web_list"' EXIT HUP INT TERM
cat "$APP_FILE_MANIFEST/release-files" > "$list"
cat "$APP_FILE_MANIFEST/sha256sums" > "$sums"
cat "$APP_FILE_MANIFEST/web-files" > "$web_list"
list_checksum=$(sha256sum "$list")
case "$list_checksum" in "$APP_RELEASE_FILES_SHA256  "*) ;; *) exit 1 ;; esac
sum_checksum=$(sha256sum "$sums")
case "$sum_checksum" in "$APP_CONTENT_MANIFEST_SHA256  "*) ;; *) exit 1 ;; esac
test -s "$list"
while IFS= read -r name; do check_release_path "$name"; done < "$list"
test -z "$(find "$APP_RELEASE_DIRECTORY" ! -type f ! -type d -print -quit)"
LC_ALL=C find "$APP_RELEASE_DIRECTORY" -type f -printf '%P\0' > "$actual"
LC_ALL=C sort -z -o "$actual" "$actual"
tr '\n' '\0' < "$list" | LC_ALL=C sort -z > "$list.sorted"
cmp -s "$actual" "$list.sorted"
rm -f "$list.sorted"
LC_ALL=C find "$APP_WEB_DIRECTORY" -type f -print | LC_ALL=C sort > "$actual"
cmp -s "$web_list" "$actual"
test -f "$APP_JAR" && test ! -L "$APP_JAR"
case "$APP_JAR" in "$APP_WEB_DIRECTORY"/*) exit 1 ;; esac
{ printf '%s\n' "$APP_JAR"; cat "$web_list"; } | LC_ALL=C sort > "$actual"
cut -c 67- "$sums" | LC_ALL=C sort | cmp -s "$actual" -
sha256sum --strict -c "$sums"
jar_checksum=$(sha256sum -- "$APP_JAR")
grep -F -x -- "$jar_checksum" "$sums" >/dev/null
rm -f "$actual" "$list" "$sums" "$web_list"
trap - EXIT HUP INT TERM
exec java -jar "$APP_JAR" "--spring.web.resources.static-locations=file:$APP_ARTIFACT_ROOT/$APP_WEB_DIRECTORY/"
