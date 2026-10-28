#!/bin/bash
# JVM tests for the pure logic (memory, parsing, tasks, reminders, policy, conversation). Needs kotlinc + org.json jar.
# usage: KOTLINC=/path/to/kotlinc/bin/kotlinc JSONJAR=/path/json.jar STDLIB=/path/kotlin-stdlib.jar ./run-tests.sh
set -e
SRC="$(cd "$(dirname "$0")/../app/src/main/java/com/sven/assistant" && pwd)"; T="$(cd "$(dirname "$0")" && pwd)"
P="JsonFileStore.kt TextUtil.kt TimeParser.kt CommandParser.kt AgentRouter.kt Memory.kt Tasks.kt Reminders.kt Proactive.kt Conversation.kt"
cd "$SRC"; OUT=$(mktemp -d)
for t in MemoryTest TimeParserTest CommandParserTest TasksTest ProactiveTest ConversationTest; do
  "$KOTLINC" -nowarn -cp "$JSONJAR" -d "$OUT/$t" $P "$T/TestKit.kt" "$T/$t.kt" 2>&1 | grep error: || true
  java -cp "$OUT/$t:$STDLIB:$JSONJAR" com.sven.assistant.${t}Kt
done
