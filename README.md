# RecommenderAgent

An AI-driven audiobook recommendation agent built with **Spring Boot 4.1.0 (Java 21)**.

This application processes natural language requests for audiobooks, manages user sessions and preferences, retrieves and ranks audiobook candidates using Apache Solr, and generates natural language responses using an AI model.

---

### 1. Project Overview

The `RecommenderAgent` acts as a middleware orchestration engine. Its primary responsibilities include:

* **Input Parsing:** Taking raw text input and parsing it into structured intents and constraints (`InputParser`).
* **Session Management:** Maintaining conversational state and history using Redis (`RedisSessionCache`).
* **User Profiling:** Storing and retrieving long-term user preferences using PostgreSQL (`UserProfileDB`).
* **Candidate Retrieval & Ranking:** Querying an external Apache Solr instance for audiobook candidates (`SolrAudiobookRepository`).
* **Response Generation:** Compiling recommendations and user context into prompts for an external AI/LLM service (`ResponseGenerator`).

The architecture is highly decoupled, utilizing Spring's `ApplicationEventPublisher` to handle asynchronous session updates without blocking the main request thread. It also leverages Java Virtual Threads for high-concurrency request handling.

---

### 2. Prerequisites

To run and develop this project, you will need:

* **Java 21:** Required by Spring Boot 4.1.0.
* **Docker & Docker Compose:** For local PostgreSQL and Redis instances.
* **Apache Solr 9.x:** Must be hosted or run separately (not included in the Docker Compose file).
* **Maven:** A Maven wrapper (`mvnw`) is included in the repository.

---

### 3. Environment Variables

The application relies on environment variables for configuration. You should create a `.env` file in the root directory or export these variables in your terminal before starting the application.

#### Required Variables
The application will fail to start if these are not provided, as they do not have default fallback values in `application.properties`:

| Variable | Description | Example / Format |
| :--- | :--- | :--- |
| `SOLR_URL` | The full HTTP URL to your Solr instance | `http://localhost:8983/solr` |
| `SOLR_USERNAME` | The basic auth username for Solr | `admin` |
| `SOLR_PASSWORD` | The basic auth password for Solr | `admin_secret` |
| `AI_API_KEY` | The API key for the LLM/AI service used in the ResponseGenerator | `sk-your-api-key-here` |

#### Optional Variables (Defaults Provided)

| Variable | Description | Default Value |
| :--- | :--- | :--- |
| `POSTGRES_URL` | JDBC URL for PostgreSQL | `jdbc:postgresql://localhost:5432/recommender` |
| `POSTGRES_USERNAME` | Database user | `recommender` |
| `POSTGRES_PASSWORD` | Database password | `recommender` |
| `REDIS_HOST` | Redis host | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_USERNAME` | Redis username | *(Empty)* |
| `REDIS_PASSWORD` | Redis password | *(Empty)* |

---

### 4. Docker Setup

The repository includes a `compose.yaml` file that provisions the required local databases.

#### Services Included
* **postgres:** PostgreSQL 16 (Exposed on port `5432`)
* **redis:** Redis 7 (Exposed on port `6379`)

#### Commands
Start Redis and Postgres background services:
```bash
docker compose up -d
```

Stop the services:
```bash
docker compose down
```

> [!NOTE]
> Solr is not included in the `compose.yaml`. You must provide your own Solr instance and configure the `SOLR_*` environment variables accordingly.

---

### 5. Local Development Setup

1. **Clone the repository:**
   ```bash
   git clone <repository-url>
   cd recommenderagent
   ```

2. **Start local infrastructure:**
   ```bash
   docker compose up -d
   ```

3. **Configure the environment:**
   Ensure your `SOLR_*` and `AI_API_KEY` environment variables are exported in your terminal or loaded via your IDE.

4. **Run the application:**
   Use the included Maven wrapper to start the Spring Boot application:
   ```bash
   ./mvnw spring-boot:run
   ```

5. **Access the API:**
   The application runs on port `8080`. You can interact with the main endpoint via a `POST` request:
   ```bash
   curl -X POST http://localhost:8080/api/v1/recommendations \
     -H "X-Session-Id: session-123" \
     -H "X-User-Id: user-456" \
     -H "Content-Type: text/plain" \
     -d "I am looking for a historical fiction audiobook about WWII."
   ```

---

### 6. Running Tests

The project uses JUnit 5 and Testcontainers.

#### Important Test Requirements
1. **Testcontainers:** The `UserProfileDBTest` and `RedisSessionCacheTest` use Testcontainers to automatically spin up ephemeral Postgres and Redis Docker containers. Docker must be running on your machine to execute these tests.
2. **Solr Test:** The `SolrAudiobookRepositoryTest` attempts to connect to a live Solr instance using `System.getenv("SOLR_URL")`. You must have the `SOLR_URL`, `SOLR_USERNAME`, and `SOLR_PASSWORD` environment variables set in your terminal before running the tests, or this specific test will fail.
3. **Context Load Test:** `RecommenderAgentApplicationTests` loads the full Spring context. It requires the `SOLR_URL` environment variable to be present (due to `application.properties`) and expects Postgres/Redis to be available on localhost (via `compose.yaml`).

#### Execution Command
Export the required variables first, then execute the test suite:
```bash
export SOLR_URL="http://localhost:8983/solr"
export SOLR_USERNAME="admin"
export SOLR_PASSWORD="password"

./mvnw test
```

---

### 7. Project Structure

```text
src/main/java/com/gen3/recommenderagent/
├── api/             # REST Controllers (RequestGateway)
├── config/          # Spring Configurations (Async, Redis, Solr)
├── domain/          # Domain Models (Session, UserProfile, Intent, etc.)
├── engine/          # Core orchestration logic (RecommendationEngine, Events)
├── inputparser/     # Parses raw text into structured SessionRequests
├── ranker/          # Solr integration and ML candidate ranking
├── response/        # AI/LLM integration for natural language generation
└── storage/         # Database integrations (RedisSessionCache, UserProfileDB)
```

---

### 8. Configuration and External Services

* **PostgreSQL (User Profiles):** Configured via Spring Data JPA. The schema is automatically updated on startup via `spring.jpa.hibernate.ddl-auto=update`.  
  *(Note: "update" should be changed to "validate" in production environments)*
* **Redis (Session Cache):** Configured using `RedisTemplate` with a `JacksonJsonRedisSerializer` to store `Session` objects as JSON strings.
* **Solr (Search):** Configured via `HttpJdkSolrClient`. The `SolrAudiobookRepository` queries this service to retrieve audiobook candidates.
* **AI Service:** The `ResponseGenerator` currently contains placeholder logic for an LLM client. It requires an `AI_API_KEY` to be injected for future implementation.

---

### 9. Common Issues / Troubleshooting

* **Application fails to start with `IllegalArgumentException: Could not resolve placeholder 'SOLR_URL'`:**  
  You have not provided the required `SOLR_URL` environment variable. Spring Boot cannot initialize the `SolrConfig` bean without it.
* **Tests fail with `IllegalStateException: Could not find a valid Docker environment`:**  
  Testcontainers cannot find your Docker daemon. Ensure Docker Desktop or the Docker daemon is running before executing `./mvnw test`.
* **`SolrAudiobookRepositoryTest` fails:**  
  This test requires a live Solr instance. Ensure your `SOLR_*` environment variables are pointing to a running Solr server with a `combinedbooks` collection.
* **Database connection refused on startup:**  
  Ensure you have run `docker compose up -d` and that port `5432` (Postgres) and port `6379` (Redis) are not being blocked or used by other local services.

---

### 10. Security

* **Never commit secrets:** Do not commit `.env` files, `AI_API_KEY` secrets, or database production passwords to version control systems.
* **Gitignore:** The repository's `.gitignore` file is already configured to block `.env` files. Ensure you keep your local credentials confined to these ignored files.
* **Production DDL:** In your production `application.properties` configuration, make sure the setup for `spring.jpa.hibernate.ddl-auto` is locked down.
