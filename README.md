# Fraud Detection Platform

Multi-module Maven monorepo for a production-grade fraud detection platform built as a microservice-based system.

## Overview

The platform is designed as an event-driven fraud detection system built around clearly separated backend services. Each service owns a specific responsibility in the transaction processing pipeline, while shared modules keep contracts and test support centralized.

The target architecture favors:

- microservice boundaries with explicit ownership
- asynchronous communication via Kafka
- thin controllers and thin Kafka listeners
- separation between REST DTOs, Kafka contracts, and persistence models
- maintainable, production-grade structure instead of demo-style code

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

Not every module is a standalone application. Two modules exist to prevent duplication and keep service boundaries clean.

### `common-events`

Shared contracts for asynchronous communication.

This module is intended to contain:

- Kafka event records
- shared value objects used by events
- enums and metadata shared across services

Why it exists:

- all services use the same event contracts
- event schemas stay versioned in one place
- REST DTOs and persistence models can stay separate from Kafka contracts
- changes in message structure become explicit and reviewable

### `common-test-support`

Reusable testing foundation for the backend services.

This module is intended to contain:

- Testcontainers setup for Kafka, MongoDB, and Redis
- shared fixture builders and factories
- reusable integration test utilities

Why it exists:

- avoids copy-pasting the same test infrastructure into each service
- keeps integration tests consistent across modules
- reduces setup cost when adding new services or end-to-end tests

## Modules

- `common-events` - shared Kafka event contracts and value objects
- `common-test-support` - reusable test infrastructure and fixtures
- `transaction-ingest-service` - REST ingress for incoming transactions
- `transaction-simulator-service` - replay and simulation service
- `feature-enricher-service` - feature computation and enrichment pipeline
- `fraud-scoring-service` - fraud scoring orchestration and strategies
- `alert-service` - alert lifecycle and analyst decision handling

## Technology Stack

- Java 21
- Spring Boot 3.x
- Maven
- Apache Kafka
- MongoDB
- Redis
- Docker Compose
- React

## Docker

The repository contains:

- `deployment/Dockerfile` used to build any backend service module
- `deployment/docker-compose.yml` for local container startup

Example startup:

```bash
docker compose -f deployment/docker-compose.yml up --build
```

Example shutdown:

```bash
docker compose -f deployment/docker-compose.yml down
```

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

## Module Responsibilities

### `transaction-ingest-service`

Entry point for externally submitted transactions. This service will expose REST APIs and convert validated requests into raw transaction events.

### `transaction-simulator-service`

Replay and simulation component used for controlled traffic generation, test scenarios, and later historical data playback.

### `feature-enricher-service`

Processing stage responsible for deriving fraud-related features such as velocity, device novelty, or contextual indicators before scoring.

### `fraud-scoring-service`

Risk evaluation stage where scoring strategies are applied. The first strategy is intended to be rule-based, with space for future ML integration.

### `alert-service`

Analyst-facing workflow service responsible for high-risk alert handling, storage, and later decision capture.

### `common-events`

Shared event contracts and value objects for Kafka-based communication across services.

### `common-test-support`

Reusable testing utilities and infrastructure setup intended for integration and end-to-end testing.

## Project Status

This repository currently contains the initial monorepo structure, service entrypoints, shared modules, and baseline documentation. Business logic is added incrementally in subsequent steps.
