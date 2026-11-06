#!/bin/bash
# Downloads the Gradle wrapper JAR so you can build without Android Studio.
# Requires curl. Normally Android Studio handles this automatically.
set -e
JAR_PATH="gradle/wrapper/gradle-wrapper.jar"
GRADLE_VERSION=$(sed -n 's|^distributionUrl=.*gradle-\([0-9.]*\)-bin\.zip$|\1|p' gradle/wrapper/gradle-wrapper.properties)

if [ -f "$JAR_PATH" ]; then
    echo "gradle-wrapper.jar already present."
    exit 0
fi

echo "Downloading gradle-wrapper.jar for Gradle $GRADLE_VERSION..."
mkdir -p gradle/wrapper

# Download the full distribution and extract the wrapper jar
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -fL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "$TMP/gradle.zip"
unzip -p "$TMP/gradle.zip" "gradle-${GRADLE_VERSION}/lib/plugins/gradle-wrapper-main-${GRADLE_VERSION}.jar" > "$TMP/wrapper-main.jar"
unzip -p "$TMP/wrapper-main.jar" gradle-wrapper.jar > "$TMP/gradle-wrapper.jar"
mv "$TMP/gradle-wrapper.jar" "$JAR_PATH"

chmod +x gradlew
echo "Done. Run: ./gradlew assembleStandaloneDebug"
