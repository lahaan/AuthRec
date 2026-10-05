#!/bin/zsh
# Records a test clip on the connected phone and checks it.
#
# Usage: tools/rectest.sh <seconds> <tag> [extra am-start extras...]
#   e.g. tools/rectest.sh 15 hevc10 --es codec HEVC_10 --ei bitrate 150 --ei clean 3
#
# Starts recording via the app's adb hooks (see CameraActivity.handleCommands), screenshots
# mid-way and after stopping, pulls the newest clip to $OUT (default /tmp/authrec-test) and
# prints frame count / timestamp gaps (dropped frames). Needs ffprobe (brew install ffmpeg).
# The phone must be unlocked with the screen on; RETURN_TO=youtube switches back to YouTube
# afterwards (a handy way to keep an unattended phone awake).
set -e
A=${ADB:-~/Library/Android/sdk/platform-tools/adb}
OUT=${OUT:-/tmp/authrec-test}
SECS=$1; TAG=$2; shift 2
mkdir -p $OUT

$A logcat -c
$A shell am start -n com.authrec/.CameraActivity --es cmd rec "$@" >/dev/null
sleep $((SECS / 2 + 2)); $A exec-out screencap -p > $OUT/$TAG-rec.png
sleep $((SECS - SECS / 2))
$A shell am start -n com.authrec/.CameraActivity --es cmd stop >/dev/null
sleep 4; $A exec-out screencap -p > $OUT/$TAG-done.png
$A logcat -d -s 'AuthRec:*' -v brief | grep -E "Recording|Saved|error" | tail -4
$A logcat -d -b crash | grep -A10 com.authrec | head -14 || true

F=$($A shell ls -t /sdcard/Movies/AuthRec/ | head -1 | tr -d '\r')
if [ -n "$F" ]; then
  $A pull "/sdcard/Movies/AuthRec/$F" $OUT/$TAG.mp4 >/dev/null && echo "pulled $F -> $OUT/$TAG.mp4"
  if command -v ffprobe >/dev/null; then
    ffprobe -v error -show_entries stream=codec_type,codec_name,profile,width,height,pix_fmt,bit_rate -of compact=p=0 $OUT/$TAG.mp4
    ffprobe -v error -select_streams v:0 -show_entries packet=pts_time -of csv=p=0 $OUT/$TAG.mp4 | sort -n |
      awk 'NR>1{d=$1-p; if(d>0.05) g++} {p=$1} END{print NR" frames, gaps>50ms (drops): "g+0}'
  fi
fi

if [ "$RETURN_TO" = youtube ]; then
  $A shell "am start -n 'com.google.android.youtube/.app.honeycomb.Shell\$HomeActivity'" >/dev/null
  sleep 1; $A shell input keyevent KEYCODE_MEDIA_PLAY
fi
