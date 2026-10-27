#!/usr/bin/env sh
# Which composables take a parameter the Compose compiler cannot compare by
# equals - under Kotlin 2's strong skipping it compares those by identity, so a
# new-but-equal object each pass re-runs the composable every time (a LongRange
# on every strip clip, 26 Oct; toTimeline()'s caption clips, 27 Oct).
#
#   sh tools/desktop/compose_unstable.sh [name-regex]
#
# Builds the reports (-PcomposeReports, app/build.gradle.kts) and lists each
# composable with its unstable parameters; app/compose_stability.conf is where a
# class that is immutable in practice is declared so.
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT" || exit 2
JAVA_HOME=${JAVA_HOME:-$HOME/.jdks/jbr-21.0.11} ./gradlew :app:compileDebugKotlin --rerun -PcomposeReports -q \
  -Dorg.gradle.jvmargs=-Xmx2200m -Pkotlin.compiler.execution.strategy=in-process 2>&1 | grep -v '^w:' | tail -5
JAVA_HOME=${JAVA_HOME:-$HOME/.jdks/jbr-21.0.11} ./gradlew --stop -q
R=app/build/compose_reports/app_debug-composables.txt
[ -f "$R" ] || { echo "no report at $R"; exit 1; }
awk '/fun [A-Za-z0-9_]+\(/ {name=$0; sub(/.*fun /,"",name); sub(/\(.*/,"",name); buf=""}
     /^  unstable / {buf=buf" "$2" "$3}
     /^\)/ {if (buf!="") print name ":" buf; buf=""}' "$R" | grep -E "${1:-.}" | sort -u
