#!/bin/sh
set -eu

[ "$#" -eq 0 ] || { echo 'unexpected arguments' >&2; exit 1; }
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS CLASSPATH

# These are synthetic fixture inputs; the gate must verify their mounts and a fresh private tmpfs.
/runtime/jre/bin/java -Djava.io.tmpdir=/runtime/probe --class-path /runtime/probe-input/h2.jar /runtime/probe-input/H2SyntheticProbe.java phase1
/runtime/jre/bin/java -Djava.io.tmpdir=/runtime/probe --class-path /runtime/probe-input/h2.jar /runtime/probe-input/H2SyntheticProbe.java phase2
