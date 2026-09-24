#!/usr/bin/env bash
# Builds Glyph Stories without the Android SDK or Gradle, using tools from Maven Central only.
# (Use this where Google's SDK servers are unreachable. With the SDK installed, `gradle assembleRelease` works too.)
#
#   ./build-apk.sh            -> build/glyph-stories.apk
#
# Tools (downloaded to .tools/ on first run):
#   aapt2      - Google's resource compiler, from apktool-lib (org.apktool:apktool-lib)
#   android-all- Android 16 framework classes + resources, from Robolectric (org.robolectric:android-all)
#   dx         - Google's dexer, repackaged (com.jakewharton.android.repackaged:dalvik-dx)
#   apksig     - Google's APK signing library (com.android.tools.build:apksig)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
OUT="$ROOT/build"
TOOLS="$ROOT/.tools"
KEYSTORE="${KEYSTORE:-$ROOT/.tools/glyph-stories.p12}"
PKG=com.hunaid.glyphstories
MIN_SDK=33
TARGET_SDK=35
VERSION_CODE="${VERSION_CODE:-4}"
VERSION_NAME="${VERSION_NAME:-2.1}"
MAVEN=https://repo1.maven.org/maven2

fetch() { # url dest
  [ -s "$2" ] && return
  for i in 1 2 3 4 5; do
    code=$(curl -s -L -m 900 -o "$2.part" -w '%{http_code}' "$1") || code=000
    if [ "$code" = 200 ]; then mv "$2.part" "$2"; return; fi
    echo "  $1 -> HTTP $code, retrying" >&2; sleep $((i * 15))
  done
  echo "failed to download $1" >&2; exit 1
}

mkdir -p "$TOOLS"
echo "== tools"
fetch "$MAVEN/org/apktool/apktool-lib/3.0.3/apktool-lib-3.0.3.jar" "$TOOLS/apktool-lib.jar"
[ -x "$TOOLS/aapt2" ] || { unzip -o -q -j "$TOOLS/apktool-lib.jar" prebuilt/linux/aapt2 -d "$TOOLS"; chmod +x "$TOOLS/aapt2"; }
fetch "$MAVEN/org/robolectric/android-all/16-robolectric-13921718/android-all-16-robolectric-13921718.jar" "$TOOLS/android-all.jar"
fetch "$MAVEN/com/jakewharton/android/repackaged/dalvik-dx/16.0.1/dalvik-dx-16.0.1.jar" "$TOOLS/dx.jar"
fetch "$MAVEN/com/android/tools/build/apksig/2.3.0/apksig-2.3.0.jar" "$TOOLS/apksig.jar"
ANDROID_JAR="$TOOLS/android-all.jar"

rm -rf "$OUT"
mkdir -p "$OUT"/{aar,gen,classes,dexin,signer}

echo "== glyph sdk"
# Nothing's licence doesn't allow redistributing the SDK, so it isn't in this repo: fetch it from Nothing's GitHub.
if [ ! -f "$APP/libs/glyph-matrix-sdk-2.0.aar" ]; then
  rm -rf "$TOOLS/gdk"
  git clone -q --depth 1 https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit.git "$TOOLS/gdk"
  mkdir -p "$APP/libs" && cp "$TOOLS/gdk/glyph-matrix-sdk-2.0.aar" "$APP/libs/"
fi
unzip -o -q "$APP/libs/glyph-matrix-sdk-2.0.aar" -d "$OUT/aar"

echo "== manifest"
# AGP normally injects the package and SDK levels; do it here.
sed -e "s#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PKG\" android:versionCode=\"$VERSION_CODE\" android:versionName=\"$VERSION_NAME\">\n    <uses-sdk android:minSdkVersion=\"$MIN_SDK\" android:targetSdkVersion=\"$TARGET_SDK\" />#" \
  "$APP/src/main/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

echo "== resources (aapt2)"
"$TOOLS/aapt2" compile --dir "$APP/src/main/res" -o "$OUT/res.zip"
"$TOOLS/aapt2" compile --dir "$OUT/aar/res" -o "$OUT/aar-res.zip"
"$TOOLS/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest "$OUT/AndroidManifest.xml" \
  -R "$OUT/aar-res.zip" -R "$OUT/res.zip" --auto-add-overlay \
  --java "$OUT/gen" --extra-packages com.nothing.thirdparty \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK

echo "== java"
# Java 8 bytecode, no lambdas: dx doesn't desugar them (D8 would).
find "$APP/src/main/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -nowarn -encoding UTF-8 \
  -cp "$ANDROID_JAR:$OUT/aar/classes.jar" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v "^Picked up\|^warning: \[options\]\|^Note:\|warnings\?$" || true
[ -f "$OUT/classes/com/hunaid/glyphstories/MainActivity.class" ] || { echo "javac failed" >&2; exit 1; }

echo "== dex"
# The SDK's marquee helper uses a lambda and isn't used by the app, so leave it out.
(cd "$OUT/dexin" && unzip -o -q "$OUT/aar/classes.jar" && rm -f com/nothing/ketchum/GlyphMatrixFrameWithMarquee*.class)
cp -r "$OUT/classes/." "$OUT/dexin/"
java -cp "$TOOLS/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=26 \
  --output="$OUT/classes.dex" "$OUT/dexin" 2>&1 | grep -v "^Picked up" || true

echo "== package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)
python3 "$ROOT/tools/zipalign.py" "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== sign"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -storepass android -keypass android \
    -alias glyphstories -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Glyph Stories, O=Workshop" 2>&1 | grep -v "^Picked up" || true
fi
javac -nowarn -cp "$TOOLS/apksig.jar" -d "$OUT/signer" "$ROOT/tools/Sign.java" 2>&1 | grep -v "^Picked up" || true
java --add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED \
  -cp "$TOOLS/apksig.jar:$OUT/signer" Sign "$KEYSTORE" android glyphstories "$OUT/aligned.apk" "$OUT/glyph-stories.apk" $MIN_SDK 2>&1 | grep -v "^Picked up"

echo "== done: $OUT/glyph-stories.apk ($(stat -c %s "$OUT/glyph-stories.apk") bytes)"
