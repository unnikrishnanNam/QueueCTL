#!/usr/bin/env zsh
# QueueCTL comprehensive CLI demo (no web UI)
#
# This script showcases the major capabilities of QueueCTL:
#  1. Build (if needed) and isolate demo state
#  2. Configure runtime defaults (retries, backoff, timeout, default priority)
#  3. Enqueue diverse jobs: success, failure->DLQ, scheduled, timeout, priority
#  4. Start workers (detached) and monitor status
#  5. Demonstrate DLQ retry
#  6. Graceful stop
#
# It uses a temporary HOME so your real ~/.queuectl remains untouched.
# Re-runnable: it nukes the demo home each invocation.
#
# Requirements: Java 21+, Maven, zsh
# Optional: jq (for pretty-print examples) if you add those commands.

set -euo pipefail
SCRIPT_DIR=${0:A:h}
ROOT_DIR=${SCRIPT_DIR:h}
JAR_SHADED="$ROOT_DIR/target/QueueCTL-1.0-SNAPSHOT.jar"
DEMO_HOME="$ROOT_DIR/.demo-home"

hr() { echo "\n============================================================\n$1\n============================================================"; }

queuectl() {
  java -Duser.home="$DEMO_HOME" -jar "$JAR_SHADED" "$@"
}

hr "(1) Build if needed"
if [[ ! -f "$JAR_SHADED" ]]; then
  (cd "$ROOT_DIR" && mvn -q -DskipTests package)
fi

hr "(2) Reset isolated demo home"
rm -rf "$DEMO_HOME"
mkdir -p "$DEMO_HOME"

hr "(3) Configure runtime defaults"
queuectl config set max_retries 2
queuectl config set backoff_base 2
queuectl config set timeout_default 0
queuectl config set priority_default 5

hr "(4) Enqueue jobs"
echo "• success job"
queuectl enqueue --id ok-1 --command "echo 'hello world'"
echo "• failing job (will hit DLQ after retries)"
queuectl enqueue --id fail-1 --command "bash -lc 'exit 1'" --max_retries 1
echo "• scheduled job (+5s)"
queuectl enqueue --id sched-1 --command "echo 'scheduled run'" --run_at +5s
echo "• timeout job (will exceed 1s timeout)"
queuectl enqueue --id timeout-1 --command "bash -lc 'sleep 3; echo done'" --timeout 1
echo "• high priority job (priority 10)"
queuectl enqueue --id prio-1 --command "echo 'high priority'" --priority 10

hr "(5) Start workers detached"
queuectl worker start --count 2 --detached
echo "Workers started in detached mode. Logs in $DEMO_HOME/.queuectl/logs"
sleep 2

hr "(6) Status (initial)"
queuectl status || true

wait_for_quiet() {
  local tries=0
  while (( tries < 30 )); do
    local processing pending
    processing=$(queuectl list --state PROCESSING | wc -l | tr -d ' ')
    pending=$(queuectl list --state PENDING | wc -l | tr -d ' ')
    # ignore the scheduled job until its time comes
    if (( processing == 0 )) && (( pending <= 1 )); then
      break
    fi
    sleep 1
    ((tries++))
  done
}

hr "(7) Waiting for first pass of processing"
wait_for_quiet || true

hr "(8) Show DLQ (failed jobs)"
queuectl dlq list || true

hr "(9) Retry DLQ job"
queuectl dlq retry fail-1 || true
sleep 2
queuectl status || true

hr "(10) Wait for retry cycle"
wait_for_quiet || true

hr "(11) Lists per state"
echo "Completed:"; queuectl list --state COMPLETED || true
echo "Pending (maybe scheduled waiting):"; queuectl list --state PENDING || true
echo "Dead:"; queuectl list --state DEAD || true

hr "(12) Graceful worker stop request"
queuectl worker stop
sleep 2
queuectl status || true

hr "Demo complete"
echo "Demo HOME: $DEMO_HOME"
echo "SQLite DB: $DEMO_HOME/.queuectl/queuectl.db"
echo "Logs:      $DEMO_HOME/.queuectl/logs"
echo "Re-run script to restart a fresh demo."
