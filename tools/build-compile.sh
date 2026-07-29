#!/usr/bin/env bash
# Windows 上 gradle daemon 会锁住 build/tmp/widen.jar，导致 applyAccessWidener
# 的 ant.move 失败（"Unable to remove existing file"）。每次构建前停 daemon 并
# 清掉 widen.jar，再用 --no-daemon 跑。
# 用法：bash tools/build-compile.sh [gradle 任务...]
set -u
cd "$(dirname "$0")/.."

TASKS="${*:-compileJava}"

./gradlew --stop >/dev/null 2>&1
rm -f build/tmp/widen.jar build/tmp/widen-stripped.jar

./gradlew --no-daemon $TASKS > build/tmp/build.log 2>&1
EXIT=$?

echo "EXIT=$EXIT"
# javac 在中文 Windows 上输出 GBK，转成 UTF-8 才能读
if command -v iconv >/dev/null 2>&1; then
  iconv -f GBK -t UTF-8//TRANSLIT build/tmp/build.log -o build/tmp/build.utf8.log 2>/dev/null \
    || cp build/tmp/build.log build/tmp/build.utf8.log
else
  cp build/tmp/build.log build/tmp/build.utf8.log
fi

echo "--- error lines ---"
grep -oE '[A-Za-z0-9_/\\.:-]+\.java:[0-9]+' build/tmp/build.utf8.log | sort -u
echo "--- count ---"
grep -cE '\.java:[0-9]+' build/tmp/build.utf8.log
