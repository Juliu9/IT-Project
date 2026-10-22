# Grafana Cloud logs

[Back to README](../README.md)

The optional Alloy collector sends only structured `recommendation.completed` events to Grafana
Cloud; it excludes request text and session contents. Nonmatching log lines, malformed JSON, and
summaries with missing, mistyped, or extra top-level fields are dropped before export. The allowlist
follows the ten fields emitted by `RequestTelemetry`; update it if that event schema changes. Other
application logs remain local.

1. Create a Grafana Cloud access-policy token with `logs:write`, then create `.env` in the repo:

   ```dotenv
   GRAFANA_LOKI_URL=https://<loki-host>/loki/api/v1/push
   GRAFANA_LOKI_USER=<loki-user-id>
   GRAFANA_LOKI_TOKEN=<logs-write-token>
   ```

2. Configure the application log file. In IntelliJ, add this VM option; from a terminal, set the
   same value as `LOGGING_FILE_NAME`:

   ```text
   -Dlogging.file.name=/absolute/path/to/IT-Project/logs/recommender-agent.log
   ```

3. Start the collector and inspect it if needed:

   ```bash
   mkdir -p logs && touch logs/recommender-agent.log
   docker compose -f compose.yaml -f compose.observability.yaml up -d
   docker compose -f compose.yaml -f compose.observability.yaml logs -f alloy
   ```

4. In Grafana **Explore**, select the Loki source and run:

   ```logql
   {service="recommender-agent", environment="local"}
   ```

   Add `outcome="FAILURE"` to see only failed requests. Alloy retains its file position and sends
   events written while it was stopped once it resumes.
