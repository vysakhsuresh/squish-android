#!/bin/sh
# Compiles and runs one tools/jvm suite with the Kotlin 2.0.20 compiler from the
# Gradle cache (no kotlinc on this machine); see CLAUDE.md.
#   sh jvmcheck.sh <SuiteName> <source files...>
set -e
cd "$(dirname "$0")/../.."
JAVA="/c/Users/vysak/.jdks/jbr-21.0.11/bin/java"
C="C:/Users/vysak/.gradle/caches/modules-2/files-2.1"
COMPILER="$C/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.20/244b603e8c729f98baaf9088d90b5b9350c68af8/kotlin-compiler-embeddable-2.0.20.jar"
STD="$C/org.jetbrains.kotlin/kotlin-stdlib/2.0.20/7388d355f7cceb002cd387ccb7ab3850e4e0a07f/kotlin-stdlib-2.0.20.jar"
REFLECT="$C/org.jetbrains.kotlin/kotlin-reflect/2.0.20/580c610641d75b6448825cbe455a1ded220e148b/kotlin-reflect-2.0.20.jar"
SCRIPT="$C/org.jetbrains.kotlin/kotlin-script-runtime/2.0.20/4aea042b39014e0a924c2e1d4a21b6fff7e4d35/kotlin-script-runtime-2.0.20.jar"
DAEMON="$C/org.jetbrains.kotlin/kotlin-daemon-embeddable/2.0.20/9eb02dce62f058efe6a121cf00cf5da9779e2746/kotlin-daemon-embeddable-2.0.20.jar"
TROVE="$C/org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar"
ANNOT="$C/org.jetbrains/annotations/13.0/919f0dfe192fb4e063e7dacadee7f8bb9a2672a9/annotations-13.0.jar"
COROUTINES="$C/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1/bb0e192bd7c2b6b8217440d36e9758e377e450/kotlinx-coroutines-core-jvm-1.8.1.jar"
SUITE=$1; shift
OUTW="$(cygpath -m "${TMPDIR:-/tmp}")/squish-jvm/out-$SUITE"
rm -rf "$OUTW"; mkdir -p "$OUTW"
CP="$COMPILER;$STD;$REFLECT;$SCRIPT;$DAEMON;$TROVE;$ANNOT;$COROUTINES"
# EXTRA_CP: more jars, ;-separated, for a suite that runs app code against a
# library (VoiceEffectsChecks against media3-common).
LIB="$STD${EXTRA_CP:+;$EXTRA_CP}"
"$JAVA" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -classpath "$LIB" -d "$OUTW" "$@" 2>&1 | grep -v "^Picked up" || true
"$JAVA" -cp "$OUTW;$LIB" "${SUITE}Kt" $SUITE_ARGS | grep -v "^Picked up"
