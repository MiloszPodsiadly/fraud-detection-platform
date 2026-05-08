# API Error Contract

Canonical error envelope for the local REST APIs in `fraud-detection-platform`.

This contract exists to keep local reviewer expectations, frontend parsing, and service error handling aligned without leaking internal implementation details.

## Envelope

All normalized API errors use this JSON shape:

```json
{
  "timestamp": "2026-04-25T15:42:10.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Malformed JSON request.",
  "details": []
}
```

The Java services and the Python `ml-inference-service` use this same flat envelope. Existing success responses are not wrapped in `data` or `metadata` because that would break current clients; a wrapped shape would require a future versioned endpoint.

Fields:

- `timestamp`: UTC time when the error response was generated.
- `status`: HTTP status code returned by the endpoint.
- `error`: standard HTTP reason phrase or stable status label for the response.
- `message`: short client-facing summary of the failure.
- `details`: zero or more machine-readable or field-level details that help the caller understand the failure without exposing internals.

## Details Semantics

Use `details = []` when:

- the response is intentionally generic
- no field-level explanation is needed
- exposing more detail would leak implementation internals

Populate `details` when:

- validation failed for one or more fields
- a security response needs a stable machine-readable reason such as `reason:missing_credentials`
- the API can safely expose a bounded, non-internal explanation

Rules:

- `details` must always be present.
- `details` must be an array.
- field-level validation errors must be exposed through `details`, not a separate `validationErrors` response property.
- internal exception class names, stack traces, binder object names, and framework diagnostics must not be exposed.

## Examples

### 400 Validation Error

```json
{
  "timestamp": "2026-04-25T15:42:10.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed.",
  "details": [
    "maxEvents: must be greater than or equal to 1",
    "throttleMillis: must be greater than or equal to 0"
  ]
}
```

### 409 Conflict

```json
{
  "timestamp": "2026-04-25T15:42:10.123Z",
  "status": 409,
  "error": "Conflict",
  "message": "Replay is already running.",
  "details": []
}
```

### 500 Internal Server Error

```json
{
  "timestamp": "2026-04-25T15:42:10.123Z",
  "status": 500,
  "error": "Internal Server Error",
  "message": "An unexpected error occurred.",
  "details": []
}
```

## Non-Leakage Rule

The API error contract is a client contract, not a diagnostics dump.

Do not leak:

- stack traces
- exception class names
- Spring binding internals
- raw parser exceptions
- persistence or network implementation details

Detailed diagnostics belong in structured logs and metrics, not in the HTTP response body.
