#!/bin/bash
# 构建 SIM US Updater APK（无需 Gradle）
set -e

JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
P="$(cd "$(dirname "$0")" && pwd)"
LIBS=$P/libs
OUT=$P/build
SDK=${ANDROID_SDK:-$HOME/Android/Sdk}
ANDROID_JAR=${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}
# —— SDK 路径自适应：兼容「$SDK/build-tools-34」与标准「$SDK/build-tools/34.0.0」两种布局 ——
if [ -z "$BT" ]; then
    if [ -d "$SDK/build-tools-34" ]; then
        BT=$SDK/build-tools-34
    else
        BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
        BT=${BT%/}
    fi
fi
if [ ! -d "$BT" ]; then echo "找不到 build-tools：请设 ANDROID_SDK 或 BT 环境变量（当前 SDK=$SDK）"; exit 1; fi
if [ ! -f "$ANDROID_JAR" ]; then
    ANDROID_JAR=$(ls -d "$SDK"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)
fi
if [ ! -f "$ANDROID_JAR" ]; then echo "找不到 android.jar：请装 platforms;android-34 或设 ANDROID_JAR"; exit 1; fi

echo "=== 0/6 环境检查 ==="
[ -f "$ANDROID_JAR" ] || { echo "缺 android.jar"; exit 1; }
[ -x "$BT/d8" ] || { echo "缺 d8"; exit 1; }
[ -f "$OUT/dex/classes.dex" ] || { echo "请先运行 build-engine.sh 生成引擎 dex"; exit 1; }
[ -f "$P/assets/keystore.bks" ] || { echo "缺 assets/keystore.bks"; exit 1; }

R8=${R8:-$LIBS/r8-8.3.37.jar}   # 可从 $SDK/build-tools/*/lib/r8.jar 复制
CP="$ANDROID_JAR:$LIBS/guava-32.0.1-jre.jar:$LIBS/failureaccess-1.0.1.jar:$LIBS/jsr305-3.0.2.jar:$LIBS/apksig-8.0.2.jar:$LIBS/auto-value-annotations-1.10.1.jar:$LIBS/gson-2.13.1.jar:$LIBS/commons-io-2.20.0.jar:$OUT/engine-classes.jar:$LIBS/shizuku-api.jar:$LIBS/shizuku-provider.jar:$LIBS/shizuku-shared.jar:$LIBS/shizuku-aidl.jar"

rm -rf "$OUT/app"; mkdir -p "$OUT/app/classes" "$OUT/app/dex" "$OUT/app/apk"

echo "=== 1/6 编译 App 代码 ==="
find "$P/src" -name '*.java' > "$OUT/app/srcs.txt"
if ! $JAVA_HOME/bin/javac -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT/app/classes" @"$OUT/app/srcs.txt" 2>&1 | tee "$OUT/app/javac.log" | head -20; then
    :
fi
if grep -q "错误\|error:" "$OUT/app/javac.log" 2>/dev/null; then
    echo "!!! javac 失败，终止构建（不产出坏包）"; exit 1
fi
N=$(find "$OUT/app/classes" -name '*.class' | wc -l)
[ "$N" -lt 5 ] && { echo "!!! 只编译出 $N 个 class，异常"; exit 1; }
echo "    class: $N"

echo "=== 2/6 合并 App + 引擎 → dex ==="
find "$OUT/app/classes" -name '*.class' > "$OUT/app/cls.txt"
# 引擎的 class 也一起打进 App dex（保证能被 App 直接调用）
find "$OUT/classes" -name '*.class' >> "$OUT/app/cls.txt" 2>/dev/null || true
echo "    输入 class 总数: $(wc -l < "$OUT/app/cls.txt")"
D8CP=""
D8IN=""
for j in guava-32.0.1-jre failureaccess-1.0.1 jsr305-3.0.2 apksig-8.0.2 \
         auto-value-annotations-1.10.1 gson-2.13.1 commons-io-2.20.0 \
         shizuku-api shizuku-provider shizuku-shared shizuku-aidl; do
    [ -f "$LIBS/$j.jar" ] && { D8CP="$D8CP --classpath $LIBS/$j.jar"; D8IN="$D8IN $LIBS/$j.jar"; }
done
$JAVA_HOME/bin/java -cp "$R8" com.android.tools.r8.D8 \
    --min-api 28 --lib "$ANDROID_JAR" --output "$OUT/app/dex" \
    $D8CP @"$OUT/app/cls.txt" $D8IN 2>&1 | tail -3 || true
ls -la "$OUT/app/dex/"

echo "=== 3/6 统计方法数 ==="
python3 - "$OUT/app/dex" <<'PY'
import struct, sys, os, glob
total = 0
for f in sorted(glob.glob(os.path.join(sys.argv[1], 'classes*.dex'))):
    d = open(f, 'rb').read(112)
    m = struct.unpack_from('<I', d, 88)[0]
    total += m
    print(f"    {os.path.basename(f)}: {m} methods ({os.path.getsize(f)} bytes)")
print(f"    合计 {total} 方法" + ("（单 dex 够）" if total < 65536 else "（已自动 multidex）"))
PY

echo "=== 4/6 aapt2 link（manifest + assets + res）==="
RESARG=""
if [ -d "$P/res" ]; then
    $BT/aapt2 compile --dir "$P/res" -o "$OUT/app/res.zip"
    RESARG="-R $OUT/app/res.zip"
fi
$BT/aapt2 link -o "$OUT/app/apk/base.apk" -I "$ANDROID_JAR" \
    --manifest "$P/AndroidManifest.xml" -A "$P/assets" --auto-add-overlay $RESARG
# -A 把 assets/ 打进去；-R 是编译后的 res（主题/图标）

echo "=== 5/6 加入 dex 并对齐 ==="
cd "$OUT/app/dex" && for d in classes*.dex; do $BT/aapt add "$OUT/app/apk/base.apk" "$d" >/dev/null; done
$BT/zipalign -f 4 "$OUT/app/apk/base.apk" "$OUT/app/apk/aligned.apk"

echo "=== 6/6 签名 ==="
# 用自己的 keystore 签名（不要把私钥/口令写进仓库）：
#   keytool -genkeypair -keystore app.p12 -storetype PKCS12 -alias simus \
#           -keyalg RSA -keysize 2048 -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
#           -dname "CN=simus-updater"
# 用法：KS_PASS=你的口令 ./build-app.sh
KS=${KS:-$P/app.p12}
[ -f "$KS" ] || { echo "缺 keystore: $KS（生成命令见本脚本注释）"; exit 1; }
[ -n "$KS_PASS" ] || { echo "请设置 KS_PASS 环境变量（keystore 口令）"; exit 1; }
$BT/apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out "$P/simusupdater.apk" "$OUT/app/apk/aligned.apk"
$BT/apksigner verify "$P/simusupdater.apk" && echo "    签名校验通过"

echo
echo "================ 构建完成 ================"
ls -la "$P/simusupdater.apk"
