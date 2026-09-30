#!/usr/bin/env bash
# Source this file before using Gradle/ADB in a terminal.
# JAVA_HOME here selects the project's build JDK; Android Studio uses its bundled runtime.
export JAVA_HOME="${GLYPH_JDK_DIR:-$HOME/.local/opt/jdk-17.0.20.1+1}"
export ANDROID_HOME="${GLYPH_ANDROID_SDK:-$HOME/Android/Sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
