# Architecture Diagrams

Status: current portfolio diagrams.

## Scope

These diagrams are simplified reviewer aids. They summarize implemented service boundaries and known regulated
mutation concepts, but they are not a complete architecture proof and do not replace code-level contracts.

## System Architecture

![Fraud Detection Platform architecture](../assets/readme_architecture.svg)

The platform is event-driven through Kafka topics, with REST APIs at service boundaries. Alert service owns regulated
mutation state, local evidence, response snapshots, and analyst-facing alert workflows.

## Runtime Flow

![Fraud detection runtime flow](../assets/readme_runtime_flow.svg)

The runtime path keeps each service responsible for one bounded transition: ingestion, enrichment, scoring, alert
projection, analyst review, and audit-backed state changes.

## Regulated Mutation Lifecycle

![Regulated mutation lifecycle](../assets/architecture_regulated_mutation_lifecycle.svg)

The lifecycle separates command intake, local audit evidence, business mutation, outbox publication, replay snapshots,
and recovery. It does not claim external finality, distributed ACID, or exactly-once Kafka delivery.

## Claim, Replay, Fencing, Renewal, And Checkpoint

![Claim replay fencing renewal and checkpoint flow](../assets/architecture_claim_replay_checkpoint.svg)

Replay is idempotent for the same canonical intent. Lease fencing and checkpoint renewal preserve bounded ownership;
they are not progress signals and they do not allow a worker to continue after a rejected renewal.

## Release Governance Flow

![Release governance flow](../assets/architecture_release_governance.svg)

Release-control documents are readiness evidence. They do not approve production enablement by themselves; enablement
requires separate owner review, environment controls, and configuration change approval.

## Alert To Case Roadmap

![Alert to fraud case roadmap](../assets/architecture_alert_case_roadmap.svg)

Current alert decisions and fraud-case workflows are implemented locally. Future case-management hardening remains
roadmap until code and tests establish the behavior.
