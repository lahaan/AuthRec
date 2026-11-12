#!/bin/zsh
# Renders the bundled presets' looks on this Mac with the app's own look code: a colour chart for
# each, and any S-Log3 frames given (rgb48le raw, 800x600, with the WB shift their clip had).
#   tools/lookpreview/preview.sh <out dir> [frame.raw:wbWarm ...]
# e.g. ffmpeg -ss 1 -i clip.mp4 -frames:v 1 -vf scale=800:600 -pix_fmt rgb48le -f rawvideo room.raw
# The dials (Texture / Clarity / Dehaze) aren't in it: those are judged on the phone.
HERE=${0:a:h}; R=${HERE:h:h}; OUT=$1; shift; mkdir -p $OUT
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
(cd $R && ./gradlew -q compileDebugKotlin 2>&1 | grep -E "^e:|error:" | head)
CP="$R/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes:$(find ~/.gradle/caches/modules-2 -name 'kotlin-stdlib-2*.jar' | sort | tail -1)"
J() { "$JAVA_HOME/bin/java" -cp "$CP" $HERE/LookPreview.java "$@"; }
print "exposure=0" > $OUT/_plain.txt
for f in $OUT/_plain.txt $R/app/src/main/assets/presets/*.authrec.json; do
  n=${${f:t}%.authrec.json}; n=${${n%.txt}// /_}
  if [[ $f == *.json ]]; then
    python3 -c "import json,sys; p=json.load(open(sys.argv[1]))['look']['params']; print('\n'.join(k+'='+(','.join(map(str,v)) if isinstance(v,list) else str(v).lower()) for k,v in p.items()))" "$f" > $OUT/$n.txt
  fi
  size=($(J $OUT/$n.txt chart $OUT/$n.chart.raw))
  ffmpeg -v error -y -f rawvideo -pix_fmt rgb24 -s ${size[1]}x${size[2]} -i $OUT/$n.chart.raw $OUT/$n.chart.png
  for spec in "$@"; do
    raw=${spec%%:*}; warm=${spec##*:}; s=${${raw:t}%.raw}
    J $OUT/$n.txt log $raw 800 600 $warm $OUT/$n.$s.raw
    ffmpeg -v error -y -f rawvideo -pix_fmt rgb24 -s 800x600 -i $OUT/$n.$s.raw $OUT/$n.$s.png
  done
  rm -f $OUT/$n.*.raw(N)
done
ls $OUT/*.png
