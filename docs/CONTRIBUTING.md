# Contributing

Development workflow for RecommenderAgent.

## Setup

Use Java 21 and Docker; follow the [README](../README.md) for setup.

**Never commit `.env`, API keys, passwords, generated datasets, snapshots, or logs.**

## Branches

- `main` is our stable branch.
- `staging` is where we put everything together and test it.
- Make your branch from `staging` and name it `feature/<what-it-does>`, for example `feature/session-lock`.
- Open a pull request into `staging`. When staging has been tested, we merge it into `main` with another pull request.

## Commit messages

Start with a verb, keep the subject short, and keep each commit to one change. Add a body only when
the reason is not obvious.

## Code style

- We format code with google-java-format through Spotless. Run `./mvnw spotless:apply` before you commit.
- Checkstyle runs on every build; follow its reported rules instead of duplicating them here.
- Use constructor injection.
- Each class should have one job. Keep business logic out of `api` and `storage`.
- Comments should explain why the code does something, not what it does.

## Tests

Add or update tests with behavior changes. We use JUnit 5, Mockito, and Testcontainers.

Test the stable application contract separately from each technology adapter:

- Domain, workflow and service tests depend on ports or Spring AI interfaces, not OpenAI, PostgreSQL,
  Redis, Solr or Qdrant classes.
- Every adapter has its own unit or Testcontainers integration tests for mapping, queries, failures and
  configuration. A replacement adapter must pass the same port behaviour and add tests for its own
  technology-specific details; keep the old adapter tests while that adapter remains supported.
- Live AI and end-to-end tests assert outcomes, not provider wording, token counts or implementation
  details. They run only through explicit `RUN_LIVE_*` gates and use whichever providers and adapters
  the environment configures.
- Shared fixtures contain domain records. Provider request/response payloads belong only in that
  provider adapter's tests.

Changing a technology should therefore add or replace adapter tests, not rewrite domain and workflow
tests. Do not rename an adapter-specific test to make it look generic.

Before you open a pull request, run this:

```bash
OPENAI_API_KEY=ci-placeholder ./mvnw clean verify -DexcludedGroups=external
```

`OPENAI_API_KEY=ci-placeholder` is required only by the currently installed OpenAI Spring AI starter
during context creation; it does not make the local suite call OpenAI. Replace this bootstrap variable
when the installed starter changes.

## Pull requests

When changing behaviour, update the corresponding documentation in the same change:

- [README](../README.md): project entry point and setup.
- [Architecture](architecture.md): request/session behaviour and adapter contracts.
- [Configuration](configuration.md): checked-in properties, environment variables and defaults.
- [Job runbooks](jobs/README.md): per-job commands, prerequisites, writes, completion and recovery.
- [Maintenance operations](maintenance.md): shared job execution and progress rules.
- [Observability](observability.md): telemetry fields and log export.
- [Deployment and future work](deployment-and-considerations.md): production safeguards, scaling guidance and roadmap items.
- [Diagrams](diagrams/README.md): update sources and regenerate affected SVGs.

Keep each operational instruction in one owning guide and link to it elsewhere. Examples must say
whether they write records, require live credentials or can incur charges. Generated datasets,
snapshots and logs under `data/` and `logs/` are local artifacts, not committed fixtures.

- **CI must pass** (Spotless, Checkstyle, and tests).
- Pull requests into `main` should be approved by a teammate before they are merged. `main` is protected.
- For `staging`, ask a teammate to look at anything big before you merge it.
- Explain what changed and why, and link the Jira ticket (MAPA-xx).
- **Do not push directly to `main`.**
