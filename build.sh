#!/usr/bin/env bash
# Builds the app (an AccessibilityService plus a settings screen) without Gradle.
# Output: build/tv-remote-buttons.apk
# Requires: Android SDK (platforms/android-34, build-tools/34.0.0), a JDK (11+), rsvg-convert, ImageMagick.
#
# Signing: by default a local key is created in ~/.android/tv-remote-buttons.jks. To use an existing
# key, create signing.env next to this script (it is git-ignored), e.g.
#   KEYSTORE=$HOME/.android/my.jks
#   KEY_ALIAS=mykey
#   KEYSTORE_PASS=secret
# Keep using the same key: Android only installs updates signed with the key of the installed app.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
OUT=build
APK="$OUT/tv-remote-buttons.apk"
[ -f signing.env ] && . ./signing.env
KEYSTORE="${KEYSTORE:-$HOME/.android/tv-remote-buttons.jks}"
KEY_ALIAS="${KEY_ALIAS:-tv-remote-buttons}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"

rm -rf "$OUT" res/drawable-de-xhdpi; mkdir -p "$OUT/classes" res/drawable-xhdpi res/mipmap-xhdpi

# Graphics from SVG: launcher banner and icon
rsvg-convert -w 320 -h 180 art/banner.svg -o res/drawable-xhdpi/banner.png
magick res/drawable-xhdpi/banner.png -gravity west -crop 180x180+0+0 +repage -resize 96x96 res/mipmap-xhdpi/ic_launcher.png

# Resources + manifest (also generates R.java)
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o "$OUT/unsigned.apk" --java "$OUT/gen" "$OUT/res.zip"

# Code
javac --release 11 -Xlint:-options -classpath "$JAR" -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')
python3 -I -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1],\"a\").write(sys.argv[2],\"classes.dex\")" "$OUT/unsigned.apk" "$OUT/classes.dex"

# Align + sign
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tv-remote-buttons" >/dev/null 2>&1
  echo "Created signing key $KEYSTORE"
fi
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass "pass:$KEYSTORE_PASS" \
  --key-pass "pass:$KEYSTORE_PASS" --out "$APK" "$OUT/aligned.apk"
echo "OK: $APK"
