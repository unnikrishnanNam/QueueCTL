# QueueCTL

CLI-based background job queue with SQLite persistence, multiple workers, retries with exponential backoff, and a Dead Letter Queue (DLQ).

## Features

- Enqueue shell commands as jobs (echo, sleep, bash, etc.)
- Multiple foreground workers run in parallel (threads)
- Atomic job claiming (no duplicate processing)
- Exponential backoff retries and DLQ after max retries
- Persistent storage in SQLite (embedded)
- Configurable defaults via `queuectl config`

## Requirements

- Java 21+
- Maven 3.9+

## Build

```
mvn -DskipTests package
```

Output fat JAR: `target/QueueCTL-1.0-SNAPSHOT.jar`.

## Quick start

```
# Configure retry/backoff defaults (optional)
java -jar target/QueueCTL-1.0-SNAPSHOT.jar config set max_retries 3
java -jar target/QueueCTL-1.0-SNAPSHOT.jar config set backoff_base 2

# Enqueue a job
java -jar target/QueueCTL-1.0-SNAPSHOT.jar enqueue --command "echo hello" --id job1

# Start 2 workers (foreground, Ctrl+C to stop)
java -jar target/QueueCTL-1.0-SNAPSHOT.jar worker start --count 2

# Show status and list jobs
java -jar target/QueueCTL-1.0-SNAPSHOT.jar status
java -jar target/QueueCTL-1.0-SNAPSHOT.jar list --state COMPLETED

# Fail a job and watch retries -> DLQ
java -jar target/QueueCTL-1.0-SNAPSHOT.jar enqueue --command "bash -lc 'exit 1'" --id fail1 --max_retries 2
java -jar target/QueueCTL-1.0-SNAPSHOT.jar worker start --count 1
java -jar target/QueueCTL-1.0-SNAPSHOT.jar dlq list
java -jar target/QueueCTL-1.0-SNAPSHOT.jar dlq retry fail1
```

## CLI reference

- `enqueue` — Add a new job
  - `--id` optional (UUID auto-generated if omitted)
  - `--command` required
  - `--max_retries` optional (falls back to `config max_retries` or 3)
  - `--priority` optional (default 1)
- `worker start --count N` — Start N workers in the foreground (Ctrl+C to stop)
- `status` — Counts per job state and number of busy workers
- `list [--state STATE]` — List jobs by state (PENDING, PROCESSING, COMPLETED, FAILED, DEAD)
- `dlq list` — List DLQ jobs
- `dlq retry <jobId>` — Move a DLQ job back to PENDING and reset attempts
- `config set <key> <value>` — Set config (`max_retries`, `backoff_base`)
- `config get <key>` — Get config value

## Architecture overview

- Storage: SQLite file at `~/.queuectl/queuectl.db`
- Tables:
  - `jobs(id, command, state, attempts, max_retries, created_at, updated_at, available_at, last_error, output, priority, locked_by, locked_at)`
  - `config(key PRIMARY KEY, value)`
- Locking: workers claim one job via single `UPDATE ... WHERE id = (SELECT id FROM jobs WHERE state='PENDING' AND available_at <= now ORDER BY priority DESC, created_at LIMIT 1)` to move it to `PROCESSING` and stamp `locked_by/locked_at`.
- Retry: on non-zero exit, attempts++, if attempts > max_retries ⇒ `DEAD` else `PENDING` with `available_at = now + backoff_base^attempts`.
- Worker: executes command via `/bin/sh -c` and captures stdout/stderr; foreground threads; graceful shutdown on SIGINT.

## Assumptions & trade-offs

- Foreground worker only (no daemonization). Background mode can be added later.
- Backoff cap not implemented yet (kept simple by request).
- `FAILED` state reserved; normal flow is PENDING → PROCESSING → COMPLETED or DEAD (after retries). Some systems keep transient `FAILED`, we update directly back to PENDING for retries.
- Status uses `PROCESSING` rows to estimate active (busy) workers; idle workers aren’t counted.

## Testing

Minimal manual test flow:

```
# 1) Success
java -jar target/QueueCTL-1.0-SNAPSHOT.jar enqueue --command "echo ok" --id ok1
java -jar target/QueueCTL-1.0-SNAPSHOT.jar worker start --count 1
java -jar target/QueueCTL-1.0-SNAPSHOT.jar list --state COMPLETED

# 2) Retry -> DLQ
java -jar target/QueueCTL-1.0-SNAPSHOT.jar enqueue --command "bash -lc 'exit 1'" --id flakey --max_retries 2
java -jar target/QueueCTL-1.0-SNAPSHOT.jar worker start --count 1
java -jar target/QueueCTL-1.0-SNAPSHOT.jar dlq list

# 3) Multi-worker (no overlap)
java -jar target/QueueCTL-1.0-SNAPSHOT.jar enqueue --command "sleep 2 && echo A" --id a
java -jar target/QueueCTL-1.0-SNAPSHOT.jar enqueue --command "sleep 2 && echo B" --id b
java -jar target/QueueCTL-1.0-SNAPSHOT.jar worker start --count 2

# 4) Persistence across restart
# Jobs and config live under ~/.queuectl/queuectl.db
```

## Troubleshooting

- SQLite busy: we set a 5s busy timeout; try again if under heavy write.
- macOS shell: commands run with `/bin/sh -c`. For Bash specifics, prefix with `bash -lc '...'`.
- Java 21 toolchain warnings from Maven are safe; consider switching to `maven-compiler-plugin` `--release 21`.

## License

MIT (for assignment/demo purposes)
