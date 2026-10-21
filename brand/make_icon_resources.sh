#!/usr/bin/env bash
#
# D-216: every app icon resource both shells ship, rebuilt from the committed
# masters in brand/skipti-icon-assets/ alone (the drawing sources stay outside
# the repository; the BUILD-LOG D-216 row names them). Idempotent: run it
# again and the tree does not change. Only macOS's own `sips` resizes.
#
#   iOS:     Assets.xcassets/AppIcon{,-Norse,-Vardr}.appiconset (Any, Dark,
#            Tinted 1024, copied) and IconPreview-<Theme>.imageset (144 px).
#   Android: mipmap-<density>/ic_launcher*_{background,foreground}.png and
#            ic_launcher_monochrome.png (108 dp: 108, 162, 216, 324, 432 px),
#            drawable-nodpi/icon_preview_<theme>.png (the iOS preview, byte
#            for byte) and drawable/ic_stat_skipti.xml (the status-bar glyph).
#
# Usage: brand/make_icon_resources.sh   (from anywhere; refuses if a master is missing)

set -euo pipefail
cd "$(dirname "$0")/.."

SRC=brand/skipti-icon-assets
XC=ios/Skipti/App/Assets.xcassets
RES=android/app/src/main/res

MASTERS="ios-appicon-1024.png ios-appicon-dark-1024.png ios-appicon-tinted-1024.png
ios-appicon-norse-1024.png ios-appicon-norse-dark-1024.png
ios-appicon-vardr-1024.png ios-appicon-vardr-dark-1024.png
android-background-432.png android-foreground-432.png android-monochrome-432.png
android-norse-background-432.png android-norse-foreground-432.png
android-vardr-background-432.png android-vardr-foreground-432.png
ic_stat_skipti.xml"

missing=0
for f in $MASTERS; do
  if [ ! -f "$SRC/$f" ]; then
    echo "make_icon_resources: missing master $SRC/$f" >&2
    missing=1
  fi
done
[ "$missing" -eq 0 ] || { echo "make_icon_resources: refused, nothing written" >&2; exit 1; }
command -v sips >/dev/null || { echo "make_icon_resources: sips not found (macOS only)" >&2; exit 1; }

# One iOS icon set: Any, Dark and Tinted, all 1024 (Xcode makes every size).
appiconset() { # <set name> <any> <dark> <tinted>
  local dir="$XC/$1.appiconset"
  mkdir -p "$dir"
  cp "$SRC/$2" "$SRC/$3" "$SRC/$4" "$dir/"
  cat > "$dir/Contents.json" <<JSON
{
  "images" : [
    {
      "filename" : "$2",
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    },
    {
      "appearances" : [
        {
          "appearance" : "luminosity",
          "value" : "dark"
        }
      ],
      "filename" : "$3",
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    },
    {
      "appearances" : [
        {
          "appearance" : "luminosity",
          "value" : "tinted"
        }
      ],
      "filename" : "$4",
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
JSON
}

appiconset AppIcon       ios-appicon-1024.png       ios-appicon-dark-1024.png       ios-appicon-tinted-1024.png
appiconset AppIcon-Norse ios-appicon-norse-1024.png ios-appicon-norse-dark-1024.png ios-appicon-tinted-1024.png
appiconset AppIcon-Vardr ios-appicon-vardr-1024.png ios-appicon-vardr-dark-1024.png ios-appicon-tinted-1024.png

# The picker's previews: each icon's Any image at 144 px (36 pt at 3x and more
# than 3 x 36 px on Android), written once and copied to both shells.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$RES/drawable-nodpi"
preview() { # <theme> <Theme> <any>
  sips -z 144 144 "$SRC/$3" --out "$tmp/icon-preview-$1.png" >/dev/null
  local dir="$XC/IconPreview-$2.imageset"
  mkdir -p "$dir"
  cp "$tmp/icon-preview-$1.png" "$dir/icon-preview-$1.png"
  cp "$tmp/icon-preview-$1.png" "$RES/drawable-nodpi/icon_preview_$1.png"
  cat > "$dir/Contents.json" <<JSON
{
  "images" : [
    {
      "filename" : "icon-preview-$1.png",
      "idiom" : "universal"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
JSON
}
preview aurora Aurora ios-appicon-1024.png
preview norse  Norse  ios-appicon-norse-1024.png
preview vardr  Vardr  ios-appicon-vardr-1024.png

# Android's adaptive layers at every density (108 dp each); xxxhdpi is the
# 432 px master itself, copied.
layer() { # <resource name> <master>
  local d px
  for d in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324; do
    px="${d#*:}"
    mkdir -p "$RES/mipmap-${d%%:*}"
    sips -z "$px" "$px" "$SRC/$2" --out "$RES/mipmap-${d%%:*}/$1.png" >/dev/null
  done
  mkdir -p "$RES/mipmap-xxxhdpi"
  cp "$SRC/$2" "$RES/mipmap-xxxhdpi/$1.png"
}
layer ic_launcher_background       android-background-432.png
layer ic_launcher_foreground       android-foreground-432.png
layer ic_launcher_monochrome       android-monochrome-432.png
layer ic_launcher_norse_background android-norse-background-432.png
layer ic_launcher_norse_foreground android-norse-foreground-432.png
layer ic_launcher_vardr_background android-vardr-background-432.png
layer ic_launcher_vardr_foreground android-vardr-foreground-432.png

mkdir -p "$RES/drawable"
cp "$SRC/ic_stat_skipti.xml" "$RES/drawable/ic_stat_skipti.xml"

echo "make_icon_resources: iOS 3 icon sets + 3 previews, Android 7 layers x 5 densities + 3 previews + the status-bar glyph"
