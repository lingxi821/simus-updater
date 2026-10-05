#!/bin/bash
# 构建 Xposed 模块 APK（simspoof：SIM 美区伪装 + 视频页解锁横屏）
set -e
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
M=$(cd "$(dirname "$0")" && pwd)
UP=${UPDATER:-$(cd "$(dirname "$0")/../updater" && pwd)}
SDK=${ANDROID_SDK:-$HOME/Android/Sdk}
ANDROID_JAR=${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}
BT=${BT:-$SDK/build-tools-34}
OUT=$M/build

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/apk"
# 桩类只用于「编译期」，绝对不能打进模块 dex ——
# LSPatch 的 VectorLegacyBridge 会检测「模块里打包了 Xposed API 类」并拒绝加载该模块
# （报错：The Xposed API classes are compiled into the module's APK）
mkdir -p "$OUT/stub-classes"
find "$M/stub" -name '*.java' > "$OUT/stub-srcs.txt"
$JAVA_HOME/bin/javac -nowarn -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT/stub-classes" @"$OUT/stub-srcs.txt"

find "$M/src" -name '*.java' > "$OUT/srcs.txt"
$JAVA_HOME/bin/javac -nowarn -encoding UTF-8 -cp "$ANDROID_JAR:$OUT/stub-classes" \
    -d "$OUT/classes" @"$OUT/srcs.txt"

# 只把模块自己的 class 交给 D8
find "$OUT/classes" -name '*.class' > "$OUT/cls.txt"
if grep -q "de/robv" "$OUT/cls.txt"; then echo "!!! 模块 dex 里混进了 Xposed API 桩类，终止"; exit 1; fi
$JAVA_HOME/bin/java -cp "$UP/libs/r8-8.3.37.jar" com.android.tools.r8.D8 \
    --min-api 28 --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/cls.txt"

$BT/aapt2 link -o "$OUT/apk/base.apk" -I "$ANDROID_JAR" \
    --manifest "$M/AndroidManifest.xml" -A "$M/assets"
cd "$OUT/dex" && for d in classes*.dex; do $BT/aapt add "$OUT/apk/base.apk" "$d" >/dev/null; done
$BT/zipalign -f 4 "$OUT/apk/base.apk" "$OUT/apk/aligned.apk"
KS=${KS:-$UP/app.p12}
[ -n "$KS_PASS" ] || { echo "请设置 KS_PASS 环境变量"; exit 1; }
$BT/apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out "$M/simspoof-US模块.apk" "$OUT/apk/aligned.apk"

# 同步进更新器 App 的 assets（打包 TikTok 时嵌的就是它）
cp "$M/simspoof-US模块.apk" "$UP/assets/simspoof.apk"
echo "模块构建完成：$M/simspoof-US模块.apk"
ls -la "$M/simspoof-US模块.apk"
