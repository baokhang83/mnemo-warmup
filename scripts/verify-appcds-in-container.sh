#!/usr/bin/env bash
# Runs inside a JDK container (see verify-appcds.sh). Trains a dynamic AppCDS archive for a tiny
# app, then runs it unchanged and with deliberate mismatches under -Xshare:auto and -Xshare:on.
# Prints one tab-separated row per scenario and mode:
#   <jdk>  <scenario>  <mode>  <exit code>  <classes loaded from the dynamic archive>  <first JVM message>
set -u
WORK=/work/app
FOREIGN=${FOREIGN_ARCHIVE:-}
mkdir -p "$WORK/src" && cd "$WORK"
JAR=$WORK/app.jar   # absolute, like the real tool: the path is recorded in the archive
JDK=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')

cat > src/Main.java <<'JAVA'
public class Main {
    public static void main(String[] a) {
        System.out.println(new Helper().greet() + " " + java.util.List.of(1, 2, 3).stream().mapToInt(i -> i).sum());
    }
}
JAVA
cat > src/Helper.java <<'JAVA'
public class Helper { String greet() { return "hello"; } }
JAVA
javac -d classes src/*.java
jar --create --file app.jar --main-class Main -C classes .
cp -p app.jar app.jar.orig

row() { # scenario mode extra-java-args...
  local scenario=$1 mode=$2; shift 2
  local log=$WORK/class-load.log out code top msg
  rm -f "$log"
  out=$(timeout 60 java -Xshare:"$mode" -XX:SharedArchiveFile="$ARCHIVE" -Xlog:class+load:file="$log" "$@" -jar "$JAR" 2>&1)
  code=$?
  top=$(grep -c 'shared objects file (top)' "$log" 2>/dev/null || true)
  msg=$(printf '%s\n' "$out" | grep -v '^hello' | head -1 | cut -c1-110)
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$JDK" "$scenario" "-Xshare:$mode" "$code" "${top:-0}" "${msg:--}"
}
both() { row "$1" auto "${@:2}"; row "$1" on "${@:2}"; }
restore() { cp -p app.jar.orig app.jar; }

# Training run: one normal run that writes the archive at exit.
ARCHIVE=$WORK/app.jsa
java -XX:ArchiveClassesAtExit="$ARCHIVE" -jar "$JAR" >/dev/null 2>"$WORK/train.err"
echo "# train exit=$? archive_bytes=$(stat -c %s "$ARCHIVE" 2>/dev/null || echo missing)" >&2

both "baseline (same jar, same flags)"
touch -d '2001-01-01 00:00:00' app.jar;                    both "jar mtime changed";              restore
sleep 2   # the JVM compares modification times at one-second resolution
cp app.jar.orig app.jar;                                   both "same bytes, new mtime (plain copy)"; restore
sleep 1; mkdir -p extra && echo x > extra/extra.txt && jar --update --file app.jar -C extra extra.txt
                                                           both "jar content changed (size and mtime)"; restore
mkdir -p "$WORK/moved" && cp -p app.jar.orig "$WORK/moved/app.jar"
JAR=$WORK/moved/app.jar;                                   both "jar moved to another directory"; JAR=$WORK/app.jar
both "GC changed (train default, run +UseSerialGC)" -XX:+UseSerialGC
both "GC changed (run +UseParallelGC)"              -XX:+UseParallelGC
both "heap changed (-Xmx64m)"                       -Xmx64m
both "heap changed (-Xmx40g, no compressed oops)"   -Xmx40g
both "compressed class pointers off"                -XX:-UseCompressedClassPointers
head -c 4096 "$ARCHIVE" > truncated.jsa
(ARCHIVE=$WORK/truncated.jsa; both "archive truncated")
(ARCHIVE=$WORK/missing.jsa;   both "archive file missing")
if [ -n "$FOREIGN" ] && [ -f "$FOREIGN" ]; then
  (ARCHIVE=$FOREIGN; both "archive from another JDK ($(basename "$FOREIGN" .jsa))")
fi
cp "$ARCHIVE" /work/out/jdk-"$JDK".jsa
