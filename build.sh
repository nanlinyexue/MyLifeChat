#!/bin/bash
# 构建 fdchat：编译 -> 合并依赖 -> 打 jar
set -e
cd "$(dirname "$0")"
JC=/usr/lib/jvm/zulu-25/bin/javac
JAR=/usr/lib/jvm/zulu-25/bin/jar
CP=$(ls libs/*.jar | tr '\n' ':')

rm -rf build/classes build/jar
mkdir -p build/classes build/jar

find src -name "*.java" > build/srcs.txt
$JC -proc:none -encoding UTF-8 -cp "$CP" -d build/classes @build/srcs.txt

cp -r build/classes/* build/jar/
cp resources/*.yml build/jar/

cd build/jar
for j in ../../libs/mysql-connector.jar ../../libs/hikari.jar ../../libs/snakeyaml.jar; do
  unzip -o -q "$j" -x "META-INF/*.SF" "META-INF/*.RSA" "META-INF/*.DSA" "module-info.class" 2>/dev/null || true
done
rm -rf META-INF/versions 2>/dev/null || true
cat > velocity-plugin.json <<'JSON'
{
  "id": "mylifechat",
  "name": "MyLifeChat",
  "version": "1.0.0",
  "description": "MyLifeChat —— 自研聊天系统：频道 / 中文名 / 提及 / 反广告 / 反刷屏",
  "authors": ["FloatDream"],
  "dependencies": [
    { "id": "miniplaceholders", "optional": true },
    { "id": "luckperms", "optional": true },
    { "id": "fdtags", "optional": true }
  ],
  "main": "fd.chat.MyLifeChatPlugin"
}
JSON
cd ../..
$JAR --create --file build/MyLifeChat-1.0.0.jar -C build/jar .
echo "构建完成: $(ls -la build/MyLifeChat-1.0.0.jar | awk '{print $5}') bytes"
