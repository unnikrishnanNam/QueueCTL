<div align="center">

# QueueCTL

Small, sharp, and reliable. A pragmatic job queue you can run anywhere.

— ship shell commands as background jobs; keep the logs; sleep well.

</div>

---

## What is this?

QueueCTL is a CLI-first background job queue backed by SQLite with:

- Atomic job claiming (no double processing)
- Multiple workers on virtual threads for high concurrency with low footprint
- Exponential backoff retries and a clear Dead Letter Queue (DLQ)
- Scheduling (run_at) and per-job timeouts
- Always-on persistence under `~/.queuectl/`
- Optional web dashboard (embedded HTTP server) for live metrics, jobs, workers, and logs

Use it to offload long-running or failure-prone tasks, batch jobs, or small automation pipelines without dragging in a whole message broker.

## Why use QueueCTL?

- It’s portable: a single shaded JAR, zero external services.
- It’s disciplined: SQL-first, simple schema, deterministic behavior.
- It’s efficient: workers run on Java 21 virtual threads; idle costs are near-zero.
- It’s observable: per-worker log files, CLI status, and an optional web UI.
- It’s pragmatic: the happy path “just works,” failure paths are first-class.

## What we optimized for

- Virtual threads: each worker runs on `Thread.ofVirtual()`, letting you scale worker counts without burning OS threads.
- SQLite with sane defaults: durable writes, a 5s busy timeout, and compact schema. Atomic UPDATE-based claiming ensures correctness without heavyweight locks.
- Exponential backoff that won’t wake the world: delay = base^attempts with configurable base.
- Scheduling built in: `--run_at` accepts ISO-8601, epoch seconds, or relative `+5s`/`+2m`/`+1h`.
- Timeouts enforced in the worker: no job runs forever.
- Practical web UI: self-hosted, no Node build step. Start/stop from the CLI.

## Install

### Option A: JAR

Requirements: Java 21+, Maven 3.9+

```bash
# One-liner (build + run status)
mvn -DskipTests package && java -jar target/QueueCTL-1.0-SNAPSHOT.jar status

# Or use the wrapper script (auto-builds if needed)
./bin/queuectl status
```

### Option B: Docker

 # Configure defaults
./bin/queuectl config set max_retries 3
./bin/queuectl config set backoff_base 2
./bin/queuectl config set priority_default 5

# Quick status
docker run --rm -v $(pwd)/queuectl-data:/data queuectl:latest status

# Enqueue & process (separate terminal/container for workers)
docker run --rm -v $(pwd)/queuectl-data:/data queuectl:latest enqueue --command "echo from docker" --id d1
docker run --rm -v $(pwd)/queuectl-data:/data queuectl:latest worker start --count 2
```

Tip: the container uses `-Duser.home=/data` and exposes 8080; you can optionally run the web UI inside the container and publish that port.

## Usage (CLI)

```
# Configure defaults
java -jar target/QueueCTL-1.0-SNAPSHOT-shaded.jar config set max_retries 3
java -jar target/QueueCTL-1.0-SNAPSHOT-shaded.jar config set backoff_base 2
java -jar target/QueueCTL-1.0-SNAPSHOT-shaded.jar config set priority_default 5

# Enqueue (success)
./bin/queuectl enqueue --command "echo hello" --id job1

# Enqueue a failing job (will retry then DLQ)
./bin/queuectl enqueue --command "bash -lc 'exit 1'" --id fail-demo --max_retries 1

# Start workers (foreground)
./bin/queuectl worker start --count 2

# Status / Lists
./bin/queuectl status
./bin/queuectl list --state COMPLETED

# DLQ roundtrip
./bin/queuectl dlq list
./bin/queuectl dlq retry fail-demo

# Graceful stop for detached/daemonized workers
./bin/queuectl worker stop
```

Full CLI reference:

- enqueue — `--id`, `--command`, `--max_retries`, `--priority`, `--timeout`, `--run_at`
- worker — `start --count N [--detached]`, `stop`, `daemon [--start|--stop|--status|--install-units]`, `logs [-f] [--worker-id ID]`
- status — state counts and worker summary
- list — `--state PENDING|PROCESSING|COMPLETED|DEAD`
- dlq — `list`, `retry <jobId>`
- config — `set <key> <value>`, `get <key>` (keys: `max_retries`, `backoff_base`, `timeout_default`, `priority_default`)

## Documentation

- Data directory: `~/.queuectl/` contains `queuectl.db` and per-worker logs under `logs/`.
- Schema: `jobs`, `config`, `workers` (with heartbeats for status/visibility).
- Claiming order: priority DESC, available_at ASC, created_at ASC.
- States: PENDING → PROCESSING → COMPLETED or DEAD.
- Scheduling: set both `run_at` and `available_at` to the future time.
- Timeouts: worker enforces a hard wall clock timeout per job.
- Web Server: start/stop via CLI (not covered in demo script by design).
- Web Server: start with `./bin/queuectl webserver start --port 8080 --foreground` or detach without `--foreground`. Stop via `./bin/queuectl webserver stop`. Status via `./bin/queuectl webserver status`.

## Videos

- Installation walkthrough: https://example.com/queuectl-install-video
- CLI usage tour: https://example.com/queuectl-usage-video

## Demo script

There’s a curated demo that runs end-to-end in an isolated home directory (so it won’t touch your real data):

```bash
scripts/demo.sh
```

It builds if needed, configures defaults, enqueues a set of jobs, starts workers detached, demonstrates DLQ retry, and shuts down gracefully.

## Notes for operators

- Backup: the SQLite DB sits under `~/.queuectl/queuectl.db`. As always, stop writers before snapshots.
- Concurrency: virtual-thread workers scale well; the bottleneck will be the work you run, not the queue.
- Portability: runs on any Java 21+ runtime; Docker image provided.

## License

MIT
