#!/bin/bash
# 编译 LSPatch 打包引擎为可嵌入 Android App 的 dex
# 配方依据：子代理实测报告（javac + d8，无需 Gradle）
set -e

JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
P="$(cd "$(dirname "$0")" && pwd)"
L=$P/third_party/lspatch
ME=$P/third_party/manifesteditor
OUT=$P/build
LIBS=$P/libs
SDK=${ANDROID_SDK:-$HOME/Android/Sdk}
ANDROID_JAR=${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}

CP="$LIBS/guava-32.0.1-jre.jar:$LIBS/failureaccess-1.0.1.jar:$LIBS/jsr305-3.0.2.jar:$LIBS/apksig-8.0.2.jar:$LIBS/auto-value-annotations-1.10.1.jar:$LIBS/gson-2.13.1.jar:$LIBS/commons-io-2.20.0.jar"

echo "=== 0/5 检查前提 ==="
[ -f "$ANDROID_JAR" ] || { echo "缺少 android.jar: $ANDROID_JAR"; exit 1; }
[ -d "$L/patch/src/main/java" ] || { echo "缺少 LSPatch 源码"; exit 1; }
[ -d "$ME/lib/src/main/java" ] || { echo "缺少 ManifestEditor 源码"; exit 1; }

rm -rf "$OUT/gen" "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/gen/org/lsposed/lspatch/share" "$OUT/classes" "$OUT/dex"

echo "=== 1/5 生成 LSPConfig.java ==="
# 注意：CORE_VERSION_HASH 是字符串，不能留空导致语法错误
sed -e 's/${apiCode}/102/' \
    -e 's/${verCode}/487/' \
    -e 's/${verName}/1.2/' \
    -e 's/${coreVerCode}/1/' \
    -e 's/${coreVerName}/1.0/' \
    -e 's/${coreVerHash}/nidome/' \
    "$L/share/java/src/template/java/org.lsposed.lspatch.share/LSPConfig.java" \
    > "$OUT/gen/org/lsposed/lspatch/share/LSPConfig.java"
grep -c 'instance\.' "$OUT/gen/org/lsposed/lspatch/share/LSPConfig.java" | xargs echo "    生成字段数:"

echo "=== 2/5 复制引擎源码（剔除 CLI 入口，避免 System.exit 与 jcommander）==="
mkdir -p "$OUT/src"
cp -r "$L/patch/src/main/java/." "$OUT/src/" 2>/dev/null || true
cp -r "$L/apkzlib/src/main/java/." "$OUT/src/" 2>/dev/null || true
cp -r "$L/share/java/src/main/java/." "$OUT/src/" 2>/dev/null || true
cp -r "$ME/lib/src/main/java/." "$OUT/src/" 2>/dev/null || true
# CLI 入口：会 System.exit 且有 main()，App 里绝不能用
rm -f "$OUT/src/org/lsposed/patch/LSPatch.java"
echo "    java 文件数: $(find "$OUT/src" "$OUT/gen" -name '*.java' | wc -l)"

echo "=== 3/5 javac（跑 auto-value 注解处理器）==="
find "$OUT/src" "$OUT/gen" -name '*.java' > "$OUT/srcs.txt"
$JAVA_HOME/bin/javac -nowarn -encoding UTF-8 \
    -processorpath "$LIBS/auto-value-1.10.1.jar" \
    -cp "$CP" -d "$OUT/classes" @"$OUT/srcs.txt" 2>&1 | grep -viE "^注:|^Note:" | head -20 || true
echo "    class 文件数: $(find "$OUT/classes" -name '*.class' | wc -l)"
echo "    AutoValue 生成: $(find "$OUT/classes" -name 'AutoValue_*' | wc -l) 个"

echo "=== 4/5 打 jar 并转 dex ==="
(cd "$OUT/classes" && $JAVA_HOME/bin/jar cf "$OUT/engine-classes.jar" .)
# 依赖必须同时作为【输入】传给 d8（只当 --classpath 不会被打进 dex！）
# 同时也要当 --classpath 供引用解析。
D8CP=""
D8IN=""
for j in guava-32.0.1-jre failureaccess-1.0.1 jsr305-3.0.2 apksig-8.0.2 \
         auto-value-annotations-1.10.1 gson-2.13.1 commons-io-2.20.0; do
    if [ -f "$LIBS/$j.jar" ]; then
        D8CP="$D8CP --classpath $LIBS/$j.jar"
        D8IN="$D8IN $LIBS/$j.jar"
    fi
done
R8=${R8:-$LIBS/r8-8.3.37.jar}
$JAVA_HOME/bin/java -cp "$R8" com.android.tools.r8.D8 \
    --min-api 28 --lib "$ANDROID_JAR" --output "$OUT/dex" \
    $D8CP "$OUT/engine-classes.jar" $D8IN 2>&1 | tail -3 || true
ls -la "$OUT/dex/"

echo "=== 5/5 统计 dex 方法数 ==="
python3 - "$OUT/dex/classes.dex" <<'PY'
import struct, sys
f = open(sys.argv[1], 'rb')
d = f.read(112)
method_ids = struct.unpack_from('<I', d, 88)[0]
class_defs = struct.unpack_from('<I', d, 96)[0]
print(f"    method_ids = {method_ids}  (上限 65536)")
print(f"    class_defs = {class_defs}")
print("    -> 单 dex 放得下" if method_ids < 65536 else "    -> 需要 multidex")
PY
echo
echo "引擎 dex: $OUT/dex/classes.dex ($(stat -c%s "$OUT/dex/classes.dex") bytes)"
