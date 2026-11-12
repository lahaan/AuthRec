#!/bin/zsh
# Bundles presets with the app: copies the preset files the connected phone has exported
# (Looks → hold a profile → Share writes Download/AuthRec/<name>.authrec.json; the share sheet
# can be closed) into app/src/main/assets/presets. The app adds each to its profiles on first
# run, with its look (CameraActivity.installBundledPresets).
#   tools/bundle-presets.sh              every preset in Download/AuthRec
#   tools/bundle-presets.sh "B&W Dreamy" only those named
# Everything bundled is published with the app: only looks and LUTs that are yours to give away.
set -e
A=${ADB:-~/Library/Android/sdk/platform-tools/adb}
OUT=${0:a:h:h}/app/src/main/assets/presets
mkdir -p $OUT
if [ $# -gt 0 ]; then names=("${@/%/.authrec.json}")
else names=("${(@f)$($A shell 'ls /sdcard/Download/AuthRec/' | tr -d '\r' | grep '\.authrec\.json$')}"); fi
for n in $names; do
  [ -n "$n" ] || continue
  $A pull -q "/sdcard/Download/AuthRec/$n" "$OUT/$n" && echo "bundled $n ($(( $(stat -f %z "$OUT/$n") / 1024 )) KB)"
done
ls $OUT
