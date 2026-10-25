# Contributing

This explains how our team works on RecommenderAgent.

## Setup

You need Java 21 and Docker. The README explains the `.env` file and how to run the project.

Never commit `.env`, API keys or passwords.

## Branches

- `main` is our stable branch.
- `staging` is where we put everything together and test it.
- Make your branch from `staging` and name it `feature/<what-it-does>`, for example `feature/session-lock`.
- Open a pull request into `staging`. When staging has been tested, we merge it into `main` with another pull request.

## Commit messages

Write one short line that starts with a verb, for example `Add REPEAT intent for the latest recommendation page` or `Migrate stored Solr catalogue data without generating embeddings`. If it needs more explanation, add it underneath. Keep each commit to one change.

## Code style

- We format code with google-java-format through Spotless. Run `./mvnw spotless:apply` before you commit.
- Checkstyle runs on every build. It checks for things like no `*` imports, no unused imports, braces on every `if` and `for`, no tabs, no catching plain `Exception`, and a `default` case in every `switch`.
- Use constructor injection.
- Each class should have one job. Keep business logic out of `api` and `storage`.
- Comments should explain why the code does something, not what it does.

## Tests

Add or update tests whenever you change something. We use JUnit 5, Mockito and Testcontainers.

Before you open a pull request, run this:

```
OPENAI_API_KEY=ci-placeholder ./mvnw clean verify -DexcludedGroups=external
```

## Pull requests

- CI must pass (Spotless, Checkstyle and the tests).
- Pull requests into `main` should be approved by a teammate before they are merged. `main` is protected.
- For `staging`, ask a teammate to look at anything big before you merge it.
- Explain what changed and why, and link the Jira ticket (MAPA-xx).
- Do not push straight to `main`. Use a feature branch for anything bigger than a small fix.
