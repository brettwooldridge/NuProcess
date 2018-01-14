#!/bin/bash

JAVA_OPTIONS="-server -XX:-RestrictContended -XX:+AggressiveOpts -XX:+UseFastAccessorMethods -Xms1096m -Xmx1096m"
JMH_OPTIONS="-prof gc -prof hs_thr"

if [[ "clean" == "$1" || ! -f "./target/microbenchmarks.jar" ]]; then
   mvn clean package
   shift
fi

JMH_THREADS="-t 4"
if [[ "$2" == "-t" ]]; then
   JMH_THREADS="-t $3"
   set -- "$1" "${@:4}"
fi

if [[ ! -f "/tmp/random.dat" ]]; then
   echo "" ; echo "Generating 250MB random data file into /tmp/random.dat ... this might take 15-30s."
   dd bs=1048576 count=250 </dev/urandom >/tmp/random.dat
fi

if [[ "quick" == "$1" ]]; then
   java -jar ./target/microbenchmarks.jar -jvmArgs "$JAVA_OPTIONS" $JMH_OPTIONS -wi 3 -i 8 $JMH_THREADS -f 2 $2 $3 $4 $5 $6 $7 $8 $9
elif [[ "medium" == "$1" ]]; then
   java -jar ./target/microbenchmarks.jar -jvmArgs "$JAVA_OPTIONS" $JMH_OPTIONS -wi 3 -f 8 -i 6 $JMH_THREADS $2 $3 $4 $5 $6 $7 $8 $9
elif [[ "profile" == "$1" ]]; then
   java -server $JAVA_OPTIONS -agentpath:/Applications/JProfiler.app/Contents/Resources/app/bin/macos/libjprofilerti.jnilib=port=8849 -jar ./target/microbenchmarks.jar -r 5 -wi 3 -i 8 $JMH_THREADS -f 0 $2 $3 $4 $5 $6 $7 $8 $9
elif [[ "debug" == "$1" ]]; then
   java -server $JAVA_OPTIONS -Xdebug -Xrunjdwp:transport=dt_socket,address=8787,server=y,suspend=y -jar ./target/microbenchmarks.jar -r 5 -wi 3 -i 8 -t 8 -f 0 $2 $3 $4 $5 $6 $7 $8 $9
else # "long"
   java -jar ./target/microbenchmarks.jar -jvmArgs "$JAVA_OPTIONS" $JMH_OPTIONS -wi 3 -i 15 $JMH_THREADS $1 $2 $3 $4 $5 $6 $7 $8 $9
fi
