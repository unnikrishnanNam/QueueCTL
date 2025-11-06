#!/usr/bin/env bash
set -euo pipefail
JAR=target/QueueCTL-1.0-SNAPSHOT.jar

if [ ! -f "$JAR" ]; then
  echo "Jar not found: $JAR. Run: mvn -DskipTests package" >&2
  exit 1
fi

# Configure defaults
java -jar "$JAR" config set max_retries 3
java -jar "$JAR" config set backoff_base 2

# Enqueue jobs
java -jar "$JAR" enqueue --command "echo A" --id a
java -jar "$JAR" enqueue --command "echo B" --id b
java -jar "$JAR" enqueue --command "bash -lc 'exit 1'" --id willfail --max_retries 2

# Start workers (separate terminal recommended); here we just run one to process quickly
java -jar "$JAR" worker start --count 2 &
PID=$!

# Wait a bit then show status
sleep 3
java -jar "$JAR" status
java -jar "$JAR" list --state COMPLETED || true

# Wait long enough for retries to hit DLQ
sleep 6
java -jar "$JAR" dlq list || true

# Cleanup worker
kill $PID || true
wait $PID || true
