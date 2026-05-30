#!/usr/bin/env bash
# Build a macOS .dmg with an icon-view layout (app on the left, /Applications alias on the
# right) so the drag-to-install gesture is obvious. Called by the :app:jpackage Gradle task.
#
# Args: $1=mainJarName  $2=appVersion  $3=jpackageBuildDir(abs)  $4=inputDir(abs)
set -euo pipefail

MAIN_JAR="$1"
APP_VERSION="$2"
PKG_DIR="$3"
INPUT_DIR="$4"

APP_NAME="Kafka Desktop"
VOL_NAME="Kafka Desktop"
IMG_DIR="$PKG_DIR/image"
STAGE_DIR="$PKG_DIR/staging"
DIST_DIR="$PKG_DIR/dist"
RW_DMG="$PKG_DIR/rw.dmg"
FINAL_DMG="$DIST_DIR/$APP_NAME-$APP_VERSION.dmg"

rm -rf "$IMG_DIR" "$STAGE_DIR" "$DIST_DIR" "$RW_DMG"
mkdir -p "$IMG_DIR" "$STAGE_DIR" "$DIST_DIR"

echo "==> jpackage app-image"
jpackage --type app-image --name "$APP_NAME" --app-version "$APP_VERSION" \
  --input "$INPUT_DIR" --dest "$IMG_DIR" \
  --main-jar "$MAIN_JAR" --main-class "com.kdt.app.MainKt"

echo "==> stage app + Applications symlink"
cp -R "$IMG_DIR/$APP_NAME.app" "$STAGE_DIR/"
ln -s /Applications "$STAGE_DIR/Applications"

# Detach any stale mount of this volume name first.
hdiutil detach "/Volumes/$VOL_NAME" -force >/dev/null 2>&1 || true

echo "==> create read-write dmg"
hdiutil create -volname "$VOL_NAME" -srcfolder "$STAGE_DIR" -fs HFS+ -format UDRW -ov "$RW_DMG" >/dev/null
DEV=$(hdiutil attach -readwrite -noverify -noautoopen "$RW_DMG" | egrep '^/dev/' | head -1 | awk '{print $1}')

echo "==> apply Finder icon-view layout (best effort — allow the Automation prompt if asked)"
osascript <<APPLESCRIPT || echo "   (layout step skipped — dmg still works, drag the app onto Applications)"
tell application "Finder"
  tell disk "$VOL_NAME"
    open
    set current view of container window to icon view
    set toolbar visible of container window to false
    set statusbar visible of container window to false
    set the bounds of container window to {200, 120, 760, 480}
    set vo to the icon view options of container window
    set arrangement of vo to not arranged
    set icon size of vo to 96
    set position of item "$APP_NAME.app" of container window to {150, 200}
    set position of item "Applications" of container window to {410, 200}
    update without registering applications
    delay 1
    close
  end tell
end tell
APPLESCRIPT

sync
hdiutil detach "$DEV" >/dev/null 2>&1 || hdiutil detach "/Volumes/$VOL_NAME" -force >/dev/null 2>&1 || true

echo "==> convert to compressed read-only dmg"
rm -f "$FINAL_DMG"
hdiutil convert "$RW_DMG" -format UDZO -o "$FINAL_DMG" >/dev/null
rm -f "$RW_DMG"

echo "created: $FINAL_DMG"
