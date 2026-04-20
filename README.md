# Fraud Detection Platform

Multi-module Maven monorepo for a production-grade fraud detection platform built as a microservice-based system.

## Overview

The platform is designed as an event-driven fraud detection system built around clearly separated backend services. Shared modules centralize integration contracts and reusable testing support, while backend services own distinct stages of the fraud processing pipeline.

## Planned Processing Flow

1. A transaction enters through `transaction-ingest-service` or is replayed by `transaction-simulator-service`.
2. A raw transaction event is published to Kafka.
3. `feature-enricher-service` derives fraud-related features from the raw event.
4. `fraud-scoring-service` evaluates fraud risk and produces a scoring result.
5. `alert-service` handles high-risk cases and exposes them for analyst workflows.
6. Analyst decisions are intended to flow back into the platform as downstream events.

## Why Multiple Spring Boot Applications

The platform is split into multiple Spring Boot applications because each service owns a different stage of the fraud detection flow.

- `transaction-ingest-service` accepts transactions from external clients
- `transaction-simulator-service` replays historical traffic for testing and simulation
- `feature-enricher-service` computes derived fraud features
- `fraud-scoring-service` evaluates fraud risk
- `alert-service` manages analyst-facing alerts and decisions

This split gives a few concrete benefits:

- clearer ownership and simpler responsibilities per service
- independent scaling, for example enrichment and scoring can scale separately from REST ingress
- isolation of failures, so one overloaded area does not automatically take down the whole platform
- easier future evolution, especially when rule-based scoring, ML scoring, and analyst workflows start diverging
- architecture aligned with Kafka event flow instead of one large synchronous application

The tradeoff is higher operational complexity. For the target architecture of this project, that tradeoff is intentional.

## Common Modules

Two shared modules prevent duplication and keep service boundaries clean:

- `common-events` centralizes Kafka event contracts, shared value objects, and enums
- `common-test-support` provides reusable Testcontainers support, integration test helpers, and fixtures

## Technology Stack

- Java 21
- Spring Boot 3.x
- Maven
- Apache Kafka
- MongoDB
- Redis
- Docker Compose
- React

## Local Development

### Prerequisites

- Docker Desktop or a compatible Docker Engine with Compose support
- Java 21 and Maven only if you want to run services outside Docker

### Start The Full Local Stack

Run:

```bash
docker compose -f deployment/docker-compose.yml up --build
```

This starts:
- Kafka in KRaft mode
- MongoDB
- Redis
- a one-off Kafka topic initialization container
- all backend Spring Boot services

### Start In Detached Mode

Run:

```bash
docker compose -f deployment/docker-compose.yml up --build -d
```

### Stop The Stack

Run:

```bash
docker compose -f deployment/docker-compose.yml down
```

### Stop And Remove Volumes

Run:

```bash
docker compose -f deployment/docker-compose.yml down -v
```

### Service Ports

- `transaction-ingest-service` -> `8081`
- `transaction-simulator-service` -> `8082`
- `feature-enricher-service` -> `8083`
- `fraud-scoring-service` -> `8084`
- `alert-service` -> `8085`
- Kafka -> `9092`
- MongoDB -> `27017`
- Redis -> `6379`

### Health Checks

After startup, backend services expose actuator health endpoints:

- `http://localhost:8081/actuator/health`
- `http://localhost:8082/actuator/health`
- `http://localhost:8083/actuator/health`
- `http://localhost:8084/actuator/health`
- `http://localhost:8085/actuator/health`

## Kafka Topics

The stack initializes these topics automatically on startup:

- `transactions.raw`
- `transactions.enriched`
- `transactions.scored`
- `fraud.alerts`
- `fraud.decisions`
- `transactions.dead-letter`

Topic bootstrap is implemented through:

- `deployment/init-kafka-topics.sh`
- `kafka-topics-init` in `deployment/docker-compose.yml`

### `transactions.raw`

Carries `TransactionRawEvent`.
- `transaction-ingest-service`
- `transaction-simulator-service`
- `feature-enricher-service`

### `transactions.enriched`

Carries `TransactionEnrichedEvent`.
- `feature-enricher-service`
- `fraud-scoring-service`

### `transactions.scored`

Carries `TransactionScoredEvent`.
- `fraud-scoring-service`
- `alert-service`

### `fraud.alerts`

Carries `FraudAlertEvent`.
- `alert-service`

### `fraud.decisions`

Carries `FraudDecisionEvent`.
- `alert-service`

### `transactions.dead-letter`

Reserved for failed event processing that cannot be recovered through retries.

## Design Rules

- Apply SOLID principles pragmatically.
- Keep controllers free of business logic.
- Keep Kafka listeners focused on orchestration only.
- Isolate infrastructure concerns in configuration, messaging, and persistence layers.
- Prefer constructor injection and cohesive class responsibilities.
- Do not reuse Kafka events as REST DTOs.
- Do not reuse persistence documents as domain models.
- Validate external input with Jakarta Validation when API contracts are introduced.
- Keep code production-grade and reviewable at every implementation step.

## Service Responsibilities

### `transaction-ingest-service`

Entry point for externally submitted transactions. This service exposes REST APIs and converts validated requests into raw transaction events.

### `transaction-simulator-service`

Replay and simulation component used for controlled traffic generation, test scenarios, and later historical data playback.

### `feature-enricher-service`

Processing stage responsible for deriving fraud-related features such as velocity, device novelty, or contextual indicators before scoring.

### `fraud-scoring-service`

Risk evaluation stage where scoring strategies are applied. The first strategy is intended to be rule-based, with space for future ML integration.

### `alert-service`

Analyst-facing workflow service responsible for high-risk alert handling, storage, and later decision capture.

### `common-events`

Shared Kafka event contracts, value objects, and enums.

### `common-test-support`

Reusable Testcontainers support, integration test helpers, and fixtures.

## Project Status

This repository currently contains the initial monorepo structure, service entrypoints, shared modules, and baseline documentation. Business logic is added incrementally in subsequent steps.
