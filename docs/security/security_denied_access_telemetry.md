# Security Denied Access Telemetry

Status: current security documentation for FDP-65.

## Purpose

FDP-65 complements FDP-64. FDP-64 records controller-level SuspiciousTransaction query telemetry after a request reaches
the read controller. FDP-65 records security-layer denied access telemetry because 401 and 403 decisions can happen
before controllers run.

Controller telemetry must not be interpreted as complete denied-access visibility. Denied access visibility belongs to
the security layer.

## Metric

Denied access telemetry uses the metric `fraud.security.access.denied`.

Allowed labels:

- routeGroup
- outcome
- method
- authState

Allowed routeGroup values:

- suspicious_transaction_read
- fraud_alert
- fraud_case
- trust
- internal_other
- unknown

Allowed outcome values:

- unauthorized
- forbidden

Allowed method values:

- GET
- POST
- PUT
- PATCH
- DELETE
- OTHER

Allowed authState values:

- anonymous
- authenticated
- unknown

## Data Boundaries

Security telemetry classifies denied access without exposing request values. Labels and logs must not contain:

- raw paths
- full URLs
- query strings
- path variables
- cursor tokens
- decoded cursor payloads
- transaction, customer, account, source, correlation, or linked-alert identifiers
- Authorization header
- JWT or token values
- username or email
- raw IP
- user agent
- exception message
- request body
- response body

## Non-Claims

FDP-65 does not change authentication behavior. It does not change authorization behavior, add roles, add permissions,
or expose new API behavior.

FDP-65 is diagnostic telemetry only. It is not audit assurance, not a security guarantee, not fraud evidence, and not
legal evidence.
