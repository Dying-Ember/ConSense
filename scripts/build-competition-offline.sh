#!/bin/sh
set -eu

if [ "$#" -ne 6 ]; then
    echo "Usage: $0 SOURCE_COPY JAVA_HOME MAVEN_HOME NODE_HOME NPM_CACHE MAVEN_REPOSITORY" >&2
    exit 2
fi

source_copy=$1
JAVA_HOME=$2
maven_home=$3
node_home=$4
npm_cache=$5
maven_repository=$6
for path in "$source_copy" "$JAVA_HOME" "$maven_home" "$node_home" "$npm_cache" "$maven_repository"; do
    case "$path" in /*) ;; *) echo "All paths must be absolute" >&2; exit 2 ;; esac
    [ -d "$path" ] || { echo "Missing directory: $path" >&2; exit 2; }
done

source_copy=$(cd "$source_copy" && pwd -P)
[ -f "$source_copy/ai_consense_web/package-lock.json" ]
[ -f "$source_copy/ai_consense_service/pom.xml" ]
[ -x "$JAVA_HOME/bin/java" ] && [ -x "$JAVA_HOME/bin/javac" ] && [ -x "$maven_home/bin/mvn" ] && [ -x "$node_home/bin/node" ] && [ -x "$node_home/bin/npm" ]
export JAVA_HOME
PATH="$node_home/bin:$JAVA_HOME/bin:$PATH"
export PATH

cd "$source_copy/ai_consense_web"
"$node_home/bin/npm" --offline ci --no-audit --no-fund --cache "$npm_cache"
"$node_home/bin/npm" --offline run build
cd "$source_copy/ai_consense_service"
"$maven_home/bin/mvn" --offline -B -ntp "-Dmaven.repo.local=$maven_repository" -DskipTests package
