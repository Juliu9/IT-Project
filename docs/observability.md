# Grafana Cloud logs

[README](../README.md) · [Setup](#setup) · [Queries](#queries) · [Interpreting results](#interpreting-results)

The optional Alloy collector exports only structured `recommendation.completed` summaries.
Request text, session contents, and other application log lines remain local.
Maintenance heartbeats and import timing logs are not shipped by this collector. See
[job progress](maintenance.md#automatic-progress-reporting) and
[import logs/timings](jobs/user-preferences.md#read-only-dry-run-solr-or-qdrant).

## Setup

### 1. Set Grafana credentials

Create a Grafana Cloud access-policy token with `logs:write`. Put these values in the repository's
`.env` file, which Docker Compose reads. Never commit this file.

```dotenv
GRAFANA_LOKI_URL=https://<loki-host>/loki/api/v1/push
GRAFANA_LOKI_USER=<loki-user-id>
GRAFANA_LOKI_TOKEN=<logs-write-token>
```

### 2. Enable the application log file

Set `LOGGING_FILE_NAME` to an absolute path ending in `logs/recommender-agent.log`.
In IntelliJ, use the equivalent VM option:

```text
-Dlogging.file.name=/absolute/path/to/IT-Project/logs/recommender-agent.log
```

### 3. Start and inspect the collector

```bash
mkdir -p logs && touch logs/recommender-agent.log
docker compose -f compose.yaml -f compose.observability.yaml up -d
docker compose -f compose.yaml -f compose.observability.yaml logs -f alloy
```

Alloy retains its file position and resumes shipping events written while it was stopped.

## Queries

In Grafana **Explore**, select the Loki source:

```logql
{service="recommender-agent", environment="local"}
```

Show failed requests:

```logql
{service="recommender-agent", environment="local", outcome="FAILURE"}
```

## Interpreting results

| Field | Meaning |
| --- | --- |
| `durationMs` | Total request time |
| `stages` | Stage timings; enclosing stages can overlap, so do not sum every value |
| `retrieval` | Backend, retrieval mode, and candidate count |
| `ai.calls` / `ai.attempts` | Observed AI operations and attempts, including failures and retries |
| `ai.usageAvailable` | False if an observed operation has unavailable token usage |
| `ai.inputTokens` / `ai.outputTokens` | Reported token usage; incomplete when usage is unavailable |
| `outcome` / `responseMode` | Request success/failure and how the response was produced |

A successful built-in response after model failure can have outcome `SUCCESS`. Token totals are
reported usage, not a complete billing total when an operation's usage is unavailable.

## Local parser timing

Every parser invocation emits `parser.completed` with `durationMs`, `outcome=SUCCESS|FAILURE`,
and `mode=FAST_PATH|AI`. Inputs rejected before either path use `mode=VALIDATION`.
The request summary retains `stages.parseMs` even when the parser skips AI or completes in zero
milliseconds. AI operation logs and token totals include actual calls only; a fast-path parse does
not emit an AI operation. These individual parser logs stay local under the collector's export rules.

## Export rules

Alloy drops nonmatching lines, malformed JSON, and summaries with missing, mistyped, or extra
top-level fields. Its allowlist follows the ten fields emitted by `RequestTelemetry`; update
[the collector configuration](../observability/alloy/config.alloy) when that schema changes.
