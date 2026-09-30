# Source this before running Gradle by hand:  source scripts/env.sh
#
# JAVA_TOOL_OPTIONS rather than org.gradle.jvmargs on purpose: this network's
# IPv6 TLS handshakes fail from the JVM (curl is fine, Java is not), so Gradle
# cannot resolve dependencies without preferIPv4Stack. Gradle does NOT forward
# arbitrary -D flags from org.gradle.jvmargs into the daemon's real JVM args —
# it sets them as system properties after startup, which is too late for the
# network stack. JAVA_TOOL_OPTIONS is read at JVM startup by every forked
# process, daemon included, so it is the one place that actually works.
#
# Android Studio: set this in Preferences → Build → Build Tools → Gradle, or
# export it from your shell profile so Studio inherits it.

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Djava.net.preferIPv4Stack=true}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
