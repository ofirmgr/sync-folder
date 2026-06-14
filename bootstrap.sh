#!/bin/bash
# Downloads the Gradle wrapper JAR so you can build without Android Studio.
# Requires curl. Normally Android Studio handles this automatically.
set -e
GRADLE_VERSION="8.9"
JAR_PATH="gradle/wrapper/gradle-wrapper.jar"

if [ -f "$JAR_PATH" ]; then
    echo "gradle-wrapper.jar already present."
    exit 0
fi

echo "Downloading gradle-wrapper.jar for Gradle $GRADLE_VERSION..."
mkdir -p gradle/wrapper

# Download the full distribution and extract the wrapper jar
TMP=$(mktemp -d)
curl -L "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "$TMP/gradle.zip"
unzip -q "$TMP/gradle.zip" -d "$TMP"
cp "$TMP/gradle-${GRADLE_VERSION}/lib/gradle-wrapper-${GRADLE_VERSION}.jar" "$JAR_PATH" 2>/dev/null || \
  find "$TMP" -name "gradle-wrapper*.jar" | head -1 | xargs -I{} cp {} "$JAR_PATH"
rm -rf "$TMP"

chmod +x gradlew
echo "Done. Run: ./gradlew assembleDebug"
