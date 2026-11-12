#!/bin/zsh
# Runs the app's own EVO class (com.authrec.gl.Evo) on made-up scenes on this Mac: what it settles
# at for each, how fast it moves, and (with "trace") a pan between scenes over time.
#   tools/evosim/run.sh [trace]            the scenes in EvoSim.java
#   tools/evosim/run.sh white <file.cube>  how a LUT renders a grey ramp, and where it goes white
# Needs Android Studio's JDK; builds the Kotlin first (the classes Gradle leaves in app/build).
R=${0:a:h:h:h}
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
(cd $R && ./gradlew -q compileDebugKotlin 2>&1 | grep -E "^e:|error:" | head)
STDLIB=$(find ~/.gradle/caches/modules-2 -name "kotlin-stdlib-2*.jar" | sort | tail -1)
CP="$R/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes:$STDLIB"
if [ "$1" = white ]; then "$JAVA_HOME/bin/java" -cp "$CP" ${0:a:h}/WhiteSim.java "$2"
else "$JAVA_HOME/bin/java" -cp "$CP" ${0:a:h}/EvoSim.java "$@"; fi
