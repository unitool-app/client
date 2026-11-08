#!/usr/bin/env bash
#
# unitool-kit's common tests on an Intel Mac's iOS simulator (cr-restore D618): osx15 is x86_64, and
# Compose ships no iosX64, so only the kit — which needs no Compose and no Android SDK — runs there.
# The kit's sources go into a throwaway one-target project; the Mac needs nothing but a network:
# a JDK is unpacked into ~/cr-check on first use.
#
#   ./scripts/kit-ios-sim.sh                         # osx15 through hm
#   MAC="-J xziy@ny.m42.cx:2522 ci@10.10.0.115" ./scripts/kit-ios-sim.sh
set -euo pipefail

mac=${MAC:-"-J xziy@hm.m42.cx:2522 ci@10.10.0.115"}
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/kit-ios/src" "$work/kit-ios/gradle"
cp -r "$root/unitool-kit/src/commonMain" "$root/unitool-kit/src/iosMain" "$root/unitool-kit/src/commonTest" "$work/kit-ios/src/"
cp -r "$root/gradle/wrapper" "$work/kit-ios/gradle/"
cp "$root/gradlew" "$work/kit-ios/"
kotlin=$(sed -n 's/^kotlin = "\(.*\)"/\1/p' "$root/gradle/libs.versions.toml")
cat > "$work/kit-ios/settings.gradle.kts" <<KTS
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "kit-ios-check"
KTS
cat > "$work/kit-ios/build.gradle.kts" <<KTS
plugins { kotlin("multiplatform") version "$kotlin" }
kotlin {
    explicitApi()
    iosX64()
    sourceSets { commonTest.dependencies { implementation(kotlin("test")) } }
}
KTS
echo "org.gradle.jvmargs=-Xmx3g" > "$work/kit-ios/gradle.properties"
tar czf "$work/kit-ios.tgz" -C "$work" kit-ios

ssh_args=($mac)
target=${ssh_args[-1]}
jump=("${ssh_args[@]:0:${#ssh_args[@]}-1}")
scp -q -o BatchMode=yes "${jump[@]}" "$work/kit-ios.tgz" "$target:/tmp/kit-ios.tgz"
ssh -o BatchMode=yes -o ServerAliveInterval=30 "${jump[@]}" "$target" '
  set -e
  mkdir -p ~/cr-check && cd ~/cr-check && rm -rf kit-ios && tar xzf /tmp/kit-ios.tgz 2>/dev/null
  if [ ! -d jdk ]; then
    curl -sSL -o jdk.tgz "https://api.adoptium.net/v3/binary/latest/21/ga/mac/x64/jdk/hotspot/normal/eclipse"
    mkdir jdk && tar xzf jdk.tgz -C jdk --strip-components 1 && rm jdk.tgz
  fi
  cd kit-ios && JAVA_HOME=~/cr-check/jdk/Contents/Home ./gradlew --no-daemon --quiet iosX64Test
  cat build/test-results/iosX64Test/*.xml | grep -o "<testsuite name=\"[^\"]*\" tests=\"[0-9]*\" skipped=\"[0-9]*\" failures=\"[0-9]*\" errors=\"[0-9]*\""
'
