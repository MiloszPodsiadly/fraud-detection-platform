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

## Metric Contract Change

FDP-65 replaces the previous access-denied metric tag schema. The metric name remains
`fraud.security.access.denied`, but the allowed tag keys are now only:

- routeGroup
- outcome
- method
- authState

The previous schema is replaced to avoid endpoint, reason, and auth-type drift and to keep denied-access telemetry
bounded and security-layer oriented. Dashboards and alerts using `auth_type`, `endpoint`, `reason`, or `actor_type`
must migrate to `routeGroup`, `outcome`, `method`, and `authState`.

The service does not dual-emit legacy tags.

## Route Group Maintenance

Route groups are manually maintained. This is intentional: `unknown` and `internal_other` are safer fallbacks than raw
path labels. Future route families must add an explicit bucket and tests before they appear in telemetry. Raw path
values must never be emitted as a fallback.

## Out of Scope

FDP-65 only records security-layer denied access decisions:

- unauthorized
- forbidden

It does not add telemetry for:

- 404 not found responses
- 405 method not allowed responses
- application routing anomalies
- controller exceptions
- request anomaly detection

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

`authState` is intentionally coarse. It must not include username, email, subject, session ID, client ID, token
metadata, IP, user agent, or role list.

FDP-65 uses metrics as the primary signal. It does not log every denied request. Logs are limited to bounded telemetry
failure diagnostics.

## Non-Claims

FDP-65 does not change authentication behavior. It does not change authorization behavior, add roles, add permissions,
or expose new API behavior.

FDP-65 is diagnostic telemetry only. It is not audit assurance, not a security guarantee, not fraud evidence, and not
legal evidence.
