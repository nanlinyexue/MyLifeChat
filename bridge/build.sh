#!/bin/bash
# 构建 MyLifeChatBridge
#
# ★ API 选择说明（2026-10-08）：
#   后端已升级到 Paper 26.2，其 API 移除了 AsyncChatDecorateEvent#isPreview()。
#   bridge 现在按 **26.2 API** 编译，源码中不再直接引用该方法
#   （改走反射，见 MyLifeChatBridge#isPreview）。
#
#   注意：libs/ 下同时存在 paper-api.jar(1.19.2) 与 paper-api-26.2.jar，
#   若用 libs/*.jar 通配，classpath 顺序会让 1.19.2 的 API 先出现并遮蔽 26.2，
#   于是又编译出引用 isPreview() 的字节码 —— 必须显式只用 26.2。
set -e
cd "$(dirname "$0")"
JC=/usr/lib/jvm/zulu-25/bin/javac
JAR=/usr/lib/jvm/zulu-25/bin/jar

API=libs/paper-api-26.2.jar
[ -f "$API" ] || { echo "!! 缺少 $API"; exit 1; }

# Paper 26.2 运行时用 adventure 5.2.0；必须用同版本 API 编译，
# 否则会报 "cannot access ObjectContentsLike" 之类的缺失。
# 注意 libs/ 里还留着 4.25.0 的旧 adventure，不能用通配。
CP="$API"
for j in libs/adventure-api-5.2.0.jar libs/adventure-key-5.2.0.jar \
         libs/adventure-text-minimessage-5.2.0.jar libs/adventure-text-serializer-plain-5.2.0.jar \
         libs/examination-api-1.3.0.jar libs/jb-annotations.jar; do
  [ -f "$j" ] || { echo "!! 缺少编译依赖 $j"; exit 1; }
  CP="$CP:$j"
done

rm -rf build && mkdir -p build/classes
find src -name "*.java" > build/srcs.txt
$JC --release 17 -proc:none -encoding UTF-8 -cp "$CP" -d build/classes @build/srcs.txt

# ★ 编译后自检：产物里绝不能再出现 isPreview 引用，否则 26.2 上会 NoSuchMethodError
if unzip -p build/classes/cn/floatdream/chatbridge/MyLifeChatBridge.class 2>/dev/null | strings | grep -q "isPreview"; then
  echo "!! 产物仍引用 isPreview —— 检查是否误用了 1.19.2 的 API jar"
  exit 1
fi

cp resources/*.yml build/classes/
(cd build/classes && $JAR --create --file ../MyLifeChatBridge-1.0.0.jar .)
echo "构建完成 (api=26.2): $(ls -la build/MyLifeChatBridge-1.0.0.jar | awk '{print $5}') bytes"
