from prometheus_client import Counter, Gauge, Histogram


REQUEST_COUNTER = Counter(
    "fraud_ml_inference_requests_total",
    "Total ML inference HTTP requests by endpoint, method, status, and outcome.",
    ("endpoint", "method", "status", "outcome"),
)
REQUEST_LATENCY = Histogram(
    "fraud_ml_inference_request_latency_seconds",
    "Latency of ML inference HTTP requests.",
    ("endpoint", "method", "status", "outcome"),
    buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0),
)
ERROR_COUNTER = Counter(
    "fraud_ml_inference_errors_total",
    "Rejected or failed ML inference requests.",
    ("endpoint", "method", "outcome"),
)
INTERNAL_AUTH_SUCCESSES = Counter(
    "fraud_internal_auth_success_total",
    "Accepted internal service authentication attempts.",
    ("source_service", "target_service", "mode"),
)
INTERNAL_AUTH_FAILURES = Counter(
    "fraud_internal_auth_failure_total",
    "Rejected internal service authentication attempts.",
    ("target_service", "mode", "reason"),
)
INTERNAL_MTLS_CERTIFICATE_EXPIRY = Gauge(
    "fraud_internal_mtls_certificate_expiry_seconds",
    "Seconds until the accepted internal mTLS client certificate expires.",
    ("source_service", "target_service"),
)
INTERNAL_MTLS_CERT_EXPIRY = Gauge(
    "fraud_internal_mtls_cert_expiry_seconds",
    "Seconds until an internal mTLS certificate expires.",
    ("source_service", "target_service"),
)
INTERNAL_MTLS_CERT_AGE = Gauge(
    "fraud_internal_mtls_cert_age_seconds",
    "Seconds since an internal mTLS certificate became valid.",
    ("source_service", "target_service"),
)
INTERNAL_MTLS_CERT_EXPIRY_STATE = Counter(
    "fraud_internal_mtls_cert_expiry_state_total",
    "Observed internal mTLS certificate lifecycle states.",
    ("state",),
)
INTERNAL_MTLS_HANDSHAKE_FAILURES = Counter(
    "fraud_internal_mtls_handshake_failures_total",
    "Rejected internal mTLS certificate handshakes or certificate-presenting requests.",
    ("reason",),
)
INTERNAL_AUTH_REPLAY_REJECTIONS = Counter(
    "fraud_internal_auth_replay_rejected_total",
    "Rejected internal service JWT replay or freshness attempts.",
    ("reason",),
)
INTERNAL_AUTH_TOKEN_AGE = Histogram(
    "fraud_internal_auth_token_age_seconds",
    "Age of internal service JWTs rejected by replay or freshness checks.",
    ("reason",),
    buckets=(0, 1, 5, 15, 30, 60, 120, 300, 600, 900),
)
MODEL_LOAD_STATUS = Gauge(
    "fraud_ml_model_load_status",
    "Model load status for the active runtime.",
    ("outcome", "model_name", "model_version"),
)
MODEL_INFO = Gauge(
    "fraud_ml_model_info",
    "Active model metadata for the ML inference runtime.",
    ("model_name", "model_version"),
)
GOVERNANCE_DRIFT_STATUS = Gauge(
    "fraud_ml_governance_drift_status",
    "Current ML governance drift status. Exactly one status label is set to 1 after a drift check.",
    ("model_name", "model_version", "status"),
)
GOVERNANCE_FEATURE_DRIFT_DETECTED = Gauge(
    "fraud_ml_governance_feature_drift_detected",
    "Whether any feature drift signal is active for the current drift severity.",
    ("model_name", "model_version", "severity"),
)
GOVERNANCE_SCORE_DRIFT_DETECTED = Gauge(
    "fraud_ml_governance_score_drift_detected",
    "Whether any score drift signal is active for the current drift severity.",
    ("model_name", "model_version", "severity"),
)
GOVERNANCE_PROFILE_OBSERVATIONS = Counter(
    "fraud_ml_governance_profile_observations_total",
    "Successful scoring observations included in the aggregate inference profile.",
    ("model_name", "model_version"),
)
GOVERNANCE_REFERENCE_PROFILE_LOADED = Gauge(
    "fraud_ml_governance_reference_profile_loaded",
    "Reference profile load status for ML governance.",
    ("model_name", "model_version", "status"),
)
GOVERNANCE_DRIFT_CONFIDENCE = Gauge(
    "fraud_ml_governance_drift_confidence",
    "Current ML governance drift confidence. Exactly one confidence label is set to 1 after a drift check.",
    ("model_name", "model_version", "confidence"),
)
GOVERNANCE_SNAPSHOTS_PERSISTED = Counter(
    "fraud_ml_governance_snapshots_persisted_total",
    "Aggregate governance snapshots persisted successfully.",
    ("model_name", "model_version", "status"),
)
GOVERNANCE_SNAPSHOT_PERSISTENCE_FAILURES = Counter(
    "fraud_ml_governance_snapshot_persistence_failures_total",
    "Aggregate governance snapshot persistence failures.",
    ("model_name", "model_version", "status"),
)
GOVERNANCE_SNAPSHOT_HISTORY_AVAILABLE = Gauge(
    "fraud_ml_governance_snapshot_history_available",
    "Whether persisted governance snapshot history is currently available.",
    ("model_name", "model_version", "status"),
)
GOVERNANCE_DRIFT_ACTION_RECOMMENDATION = Gauge(
    "fraud_ml_governance_drift_action_recommendation",
    "Current advisory drift action recommendation.",
    ("model_name", "model_version", "severity"),
)
MODEL_LIFECYCLE_INFO = Gauge(
    "fraud_ml_model_lifecycle_info",
    "Read-only model lifecycle metadata for the active runtime.",
    ("model_name", "model_version", "lifecycle_mode"),
)
MODEL_LIFECYCLE_EVENTS = Counter(
    "fraud_ml_model_lifecycle_events_total",
    "Read-only model lifecycle events by event type and persistence status.",
    ("event_type", "model_name", "model_version", "status"),
)
MODEL_LIFECYCLE_HISTORY_AVAILABLE = Gauge(
    "fraud_ml_model_lifecycle_history_available",
    "Whether model lifecycle event history is available from persistent storage.",
    ("model_name", "model_version", "status"),
)
GOVERNANCE_ADVISORY_EVENTS_EMITTED = Counter(
    "fraud_ml_governance_advisory_events_emitted_total",
    "Governance advisory events emitted by severity and storage status.",
    ("severity", "model_name", "model_version", "status"),
)
GOVERNANCE_ADVISORY_EVENTS_PERSISTED = Counter(
    "fraud_ml_governance_advisory_events_persisted_total",
    "Governance advisory events persisted successfully.",
    ("severity", "model_name", "model_version", "status"),
)
GOVERNANCE_ADVISORY_PERSISTENCE_FAILURES = Counter(
    "fraud_ml_governance_advisory_persistence_failures_total",
    "Governance advisory event persistence failures.",
    ("severity", "model_name", "model_version", "status"),
)
