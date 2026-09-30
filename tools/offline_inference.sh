#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 1 ]; then
    printf 'Usage: tools/offline_inference.sh /absolute/path/to/fixtures\n' >&2
    exit 2
fi
cd "$(dirname "$0")/.."
if [ ! -f core/build/host-test-classpath.txt ]; then
    printf 'First run ./gradlew :core:exportHostTestClasspath while online.\n' >&2
    exit 2
fi
voice_test_cp=$(< core/build/host-test-classpath.txt)
voice_java=java
if [ -n "${JAVA_HOME:-}" ]; then voice_java="$JAVA_HOME/bin/java"; fi
exec "$voice_java" -DvoiceFixtures="$1" -DvoiceOffline=true -cp "$voice_test_cp" org.junit.runner.JUnitCore com.pcbcupid.voice.speech.LocalInferenceTest
