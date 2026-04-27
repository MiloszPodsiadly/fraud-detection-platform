from __future__ import annotations

import json
import hashlib
import hmac
import os
import re
import ssl
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass
from http import HTTPStatus
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlparse

import jwt
from jwt import (
    ExpiredSignatureError,
    InvalidAlgorithmError,
    InvalidAudienceError,
    InvalidIssuerError,
    InvalidKeyError,
    InvalidSignatureError,
    InvalidTokenError,
    MissingRequiredClaimError,
)
from jwt.algorithms import RSAAlgorithm
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Gauge, Histogram, generate_latest

from app.governance.advisory import (
    AdvisoryEventService,
    AdvisoryPersistenceConfig,
    ADVISORY_SEVERITIES,
    MAX_ADVISORY_LIMIT,
    create_advisory_repository,
)
from app.governance.actions import (
    ACTION_SEVERITIES,
    MAX_HISTORY_FOR_ACTIONS,
    recommend_drift_actions,
    with_model_lifecycle_context,
)
from app.governance.drift import evaluate_drift
from app.governance.lifecycle import (
    LifecyclePersistenceConfig,
    ModelLifecycleService,
    create_lifecycle_repository,
    current_model_lifecycle_metadata,
    lifecycle_metadata_summary,
)
from app.governance.profile import (
    InferenceProfile,
    governance_model_metadata,
    governance_response,
    load_reference_profile,
    reference_feature_names,
)
from app.governance.persistence import (
    GovernancePersistenceConfig,
    GovernanceSnapshotService,
    MAX_HISTORY_LIMIT,
    create_snapshot_repository,
    current_snapshot_document,
)
from app.model import DEFAULT_ARTIFACT_PATH, MODEL_NAME, MODEL_VERSION, FraudModel


HOST = "0.0.0.0"
PORT = 8090
MODEL = FraudModel()
REFERENCE_PROFILE = load_reference_profile()
MODEL_GOVERNANCE = governance_model_metadata(MODEL, DEFAULT_ARTIFACT_PATH)
MODEL_LOADED_AT = datetime.now(timezone.utc)
MODEL_LIFECYCLE = current_model_lifecycle_metadata(
    MODEL_GOVERNANCE,
    DEFAULT_ARTIFACT_PATH,
    REFERENCE_PROFILE,
    MODEL_LOADED_AT,
)
INFERENCE_PROFILE = InferenceProfile(
    MODEL_NAME,
    MODEL_VERSION,
    reference_feature_names(REFERENCE_PROFILE) or MODEL_GOVERNANCE["feature_set"],
)
PERSISTENCE_CONFIG = GovernancePersistenceConfig.from_env()
SNAPSHOT_SERVICE = GovernanceSnapshotService(
    create_snapshot_repository(PERSISTENCE_CONFIG),
    PERSISTENCE_CONFIG,
)
LIFECYCLE_CONFIG = LifecyclePersistenceConfig.from_env()
LIFECYCLE_SERVICE = ModelLifecycleService(
    create_lifecycle_repository(LIFECYCLE_CONFIG),
    LIFECYCLE_CONFIG,
)
ADVISORY_CONFIG = AdvisoryPersistenceConfig.from_env()
ADVISORY_SERVICE = AdvisoryEventService(
    create_advisory_repository(ADVISORY_CONFIG),
    ADVISORY_CONFIG,
)
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

MODEL_LOAD_STATUS.labels("success", MODEL_NAME, MODEL_VERSION).set(1)
MODEL_LOAD_STATUS.labels("failure", MODEL_NAME, MODEL_VERSION).set(0)
MODEL_INFO.labels(MODEL_NAME, MODEL_VERSION).set(1)
MODEL_LIFECYCLE_INFO.labels(MODEL_NAME, MODEL_VERSION, "READ_ONLY").set(1)
GOVERNANCE_REFERENCE_PROFILE_LOADED.labels(MODEL_NAME, MODEL_VERSION, "loaded").set(
    1 if REFERENCE_PROFILE.get("available") else 0
)
GOVERNANCE_REFERENCE_PROFILE_LOADED.labels(MODEL_NAME, MODEL_VERSION, str(REFERENCE_PROFILE.get("status"))).set(1)
for _status in ("OK", "WATCH", "DRIFT", "UNKNOWN"):
    GOVERNANCE_DRIFT_STATUS.labels(MODEL_NAME, MODEL_VERSION, _status).set(1 if _status == "UNKNOWN" else 0)
for _confidence in ("LOW", "MEDIUM", "HIGH"):
    GOVERNANCE_DRIFT_CONFIDENCE.labels(MODEL_NAME, MODEL_VERSION, _confidence).set(1 if _confidence == "LOW" else 0)
for _severity in ("WATCH", "DRIFT"):
    GOVERNANCE_FEATURE_DRIFT_DETECTED.labels(MODEL_NAME, MODEL_VERSION, _severity).set(0)
    GOVERNANCE_SCORE_DRIFT_DETECTED.labels(MODEL_NAME, MODEL_VERSION, _severity).set(0)
GOVERNANCE_SNAPSHOT_HISTORY_AVAILABLE.labels(MODEL_NAME, MODEL_VERSION, "available").set(0)
GOVERNANCE_SNAPSHOT_HISTORY_AVAILABLE.labels(MODEL_NAME, MODEL_VERSION, "unavailable").set(1)
for _severity in ACTION_SEVERITIES:
    GOVERNANCE_DRIFT_ACTION_RECOMMENDATION.labels(MODEL_NAME, MODEL_VERSION, _severity).set(0)
for _status in ("available", "partial", "unavailable"):
    MODEL_LIFECYCLE_HISTORY_AVAILABLE.labels(MODEL_NAME, MODEL_VERSION, _status).set(0)


@dataclass(frozen=True)
class InternalServicePrincipal:
    service_name: str
    authorities: frozenset[str]
    authenticated_at: datetime
    auth_mode: str
    certificate_expires_at: datetime | None = None


@dataclass(frozen=True)
class InternalServiceCredential:
    token: str
    authorities: frozenset[str]


INTERNAL_AUTH_TARGET_SERVICE = "ml-inference-service"
LOCAL_INTERNAL_AUTH_MODES = {"LOCALDEV", "DISABLED_LOCAL_ONLY"}
TOKEN_INTERNAL_AUTH_MODES = {"REQUIRED", "TOKEN_VALIDATOR"}
JWT_INTERNAL_AUTH_MODES = {"JWT_SERVICE_IDENTITY"}
MTLS_INTERNAL_AUTH_MODES = {"MTLS_SERVICE_IDENTITY"}
SUPPORTED_INTERNAL_AUTH_MODES = (
    LOCAL_INTERNAL_AUTH_MODES
    | TOKEN_INTERNAL_AUTH_MODES
    | JWT_INTERNAL_AUTH_MODES
    | MTLS_INTERNAL_AUTH_MODES
    | {"MTLS_READY"}
)
PROD_LIKE_PROFILES = {"prod", "production", "staging"}
INTERNAL_AUTH_FAILURE_REASONS = {
    "missing_internal_credentials",
    "invalid_internal_credentials",
    "expired_internal_token",
    "invalid_internal_token",
    "invalid_internal_issuer",
    "invalid_internal_audience",
    "unknown_internal_service",
    "missing_internal_authority",
    "mtls_not_configured",
    "missing_client_certificate",
    "invalid_client_certificate",
}
REPLAY_REASON_EXPIRED = "EXPIRED"
REPLAY_REASON_TOO_OLD = "TOO_OLD"
REPLAY_REASON_FUTURE_IAT = "FUTURE_IAT"
REPLAY_REASON_REPLAY_DETECTED = "REPLAY_DETECTED"
DEFAULT_JWT_MAX_TOKEN_AGE_SECONDS = 300
DEFAULT_JWT_MAX_ALLOWED_TTL_SECONDS = 300
DEFAULT_JWT_CLOCK_SKEW_SECONDS = 30
DEFAULT_REPLAY_CACHE_MAX_ENTRIES = 10_000


class SoftReplayCache:
    def __init__(self) -> None:
        self._entries: OrderedDict[str, float] = OrderedDict()
        self._lock = threading.Lock()

    def seen(self, token_hash: str, expires_at: float, now: float, max_entries: int) -> bool:
        with self._lock:
            self._evict(now, max_entries)
            cached_expires_at = self._entries.get(token_hash)
            if cached_expires_at is not None and cached_expires_at > now:
                self._entries.move_to_end(token_hash)
                return True
            self._entries[token_hash] = expires_at
            self._entries.move_to_end(token_hash)
            self._evict(now, max_entries)
            return False

    def clear(self) -> None:
        with self._lock:
            self._entries.clear()

    def _evict(self, now: float, max_entries: int) -> None:
        expired = [key for key, expires_at in self._entries.items() if expires_at <= now]
        for key in expired:
            self._entries.pop(key, None)
        while len(self._entries) > max(max_entries, 1):
            self._entries.popitem(last=False)


SOFT_REPLAY_CACHE = SoftReplayCache()


def _normalize_internal_auth_mode(mode: str) -> str:
    candidate = mode.strip().upper()
    if candidate not in SUPPORTED_INTERNAL_AUTH_MODES:
        raise RuntimeError("Unsupported internal auth mode.")
    if candidate in LOCAL_INTERNAL_AUTH_MODES:
        return "DISABLED_LOCAL_ONLY"
    if candidate in TOKEN_INTERNAL_AUTH_MODES:
        return "TOKEN_VALIDATOR"
    return candidate


def _internal_auth_mode() -> str:
    return _normalize_internal_auth_mode(os.getenv("INTERNAL_AUTH_MODE", "REQUIRED"))


def _runtime_profile() -> str:
    return (
        os.getenv("INTERNAL_AUTH_PROFILE")
        or os.getenv("APP_PROFILE")
        or os.getenv("ENVIRONMENT")
        or os.getenv("SPRING_PROFILES_ACTIVE")
        or "localdev"
    ).strip().lower()


def _prod_like_profile(profile: str | None = None) -> bool:
    value = (profile or _runtime_profile()).strip().lower()
    profiles = {part.strip() for part in value.replace(";", ",").split(",") if part.strip()}
    return bool(profiles & PROD_LIKE_PROFILES)


def _token_hash_mode() -> bool:
    return os.getenv("INTERNAL_AUTH_TOKEN_HASH_MODE", "false").strip().lower() in {"1", "true", "yes", "on"}


def _allow_token_validator_in_prod() -> bool:
    return os.getenv("INTERNAL_AUTH_ALLOW_TOKEN_VALIDATOR_IN_PROD", "false").strip().lower() in {"1", "true", "yes", "on"}


def _allowed_internal_services() -> dict[str, InternalServiceCredential]:
    raw = os.getenv("INTERNAL_AUTH_ALLOWED_SERVICES", "")
    services: dict[str, InternalServiceCredential] = {}
    hash_mode = _token_hash_mode()
    for entry in raw.split(","):
        parts = entry.strip().split(":", 2)
        if len(parts) != 3:
            continue
        service_name, token, authorities = (part.strip() for part in parts)
        if not service_name or not token:
            continue
        authority_set = frozenset(authority.strip() for authority in authorities.split("|") if authority.strip())
        if not authority_set:
            continue
        if hash_mode and not re.fullmatch(r"[A-Fa-f0-9]{64}", token):
            continue
        services[service_name] = InternalServiceCredential(token=token, authorities=authority_set)
    return services


INTERNAL_SERVICE_CREDENTIALS = _allowed_internal_services()


def _jwt_issuer() -> str:
    return os.getenv("INTERNAL_AUTH_JWT_ISSUER", "").strip()


def _jwt_audience() -> str:
    return os.getenv("INTERNAL_AUTH_JWT_AUDIENCE", "").strip()


def _jwt_secret() -> str:
    return os.getenv("INTERNAL_AUTH_JWT_SECRET", "").strip()


def _jwt_algorithm() -> str:
    return os.getenv("INTERNAL_AUTH_JWT_ALGORITHM", "HS256").strip().upper()


def _jwt_jwks_json() -> str:
    return os.getenv("INTERNAL_AUTH_JWKS_JSON", "").strip()


def _jwt_jwks_path() -> str:
    return os.getenv("INTERNAL_AUTH_JWKS_PATH", "").strip()


def _jwt_service_claim() -> str:
    return os.getenv("INTERNAL_AUTH_JWT_SERVICE_CLAIM", "service_name").strip() or "service_name"


def _jwt_authorities_claim() -> str:
    return os.getenv("INTERNAL_AUTH_JWT_AUTHORITIES_CLAIM", "authorities").strip() or "authorities"


def _env_int(name: str, default: int) -> int:
    try:
        value = int(os.getenv(name, str(default)).strip())
    except (TypeError, ValueError):
        return default
    return value if value > 0 else default


def _jwt_max_token_age_seconds() -> int:
    return _env_int("INTERNAL_AUTH_JWT_MAX_TOKEN_AGE_SECONDS", DEFAULT_JWT_MAX_TOKEN_AGE_SECONDS)


def _jwt_max_allowed_ttl_seconds() -> int:
    return _env_int("INTERNAL_AUTH_JWT_MAX_ALLOWED_TTL_SECONDS", DEFAULT_JWT_MAX_ALLOWED_TTL_SECONDS)


def _jwt_clock_skew_seconds() -> int:
    return _env_int("INTERNAL_AUTH_JWT_CLOCK_SKEW_SECONDS", DEFAULT_JWT_CLOCK_SKEW_SECONDS)


def _replay_cache_enabled() -> bool:
    return os.getenv("INTERNAL_AUTH_REPLAY_CACHE_ENABLED", "false").strip().lower() in {"1", "true", "yes", "on"}


def _replay_cache_reject_mode() -> bool:
    return os.getenv("INTERNAL_AUTH_REPLAY_CACHE_MODE", "log").strip().lower() == "reject"


def _replay_cache_max_entries() -> int:
    return _env_int("INTERNAL_AUTH_REPLAY_CACHE_MAX_ENTRIES", DEFAULT_REPLAY_CACHE_MAX_ENTRIES)


def _allowed_jwt_service_authorities() -> dict[str, frozenset[str]]:
    raw = os.getenv("INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES", "")
    services: dict[str, frozenset[str]] = {}
    for entry in raw.split(","):
        parts = entry.strip().split(":", 1)
        if len(parts) != 2:
            continue
        service_name, authorities = (part.strip() for part in parts)
        if not service_name:
            continue
        authority_set = frozenset(authority.strip() for authority in authorities.split("|") if authority.strip())
        if not authority_set:
            continue
        services[service_name] = authority_set
    return services


def _allowed_internal_service_authorities() -> dict[str, frozenset[str]]:
    return _allowed_jwt_service_authorities()


def _allowed_jwt_service_keys() -> dict[str, frozenset[str]]:
    raw = os.getenv("INTERNAL_AUTH_ALLOWED_SERVICE_KEYS", "")
    services: dict[str, frozenset[str]] = {}
    for entry in raw.split(","):
        parts = entry.strip().split(":", 1)
        if len(parts) != 2:
            continue
        service_name, key_ids = (part.strip() for part in parts)
        if not service_name:
            continue
        key_id_set = frozenset(key_id.strip() for key_id in key_ids.split("|") if key_id.strip())
        if not key_id_set:
            continue
        services[service_name] = key_id_set
    return services


def _jwt_jwks_configured() -> bool:
    return bool(_jwt_jwks_json() or _jwt_jwks_path())


def _jwt_configured() -> bool:
    algorithm = _jwt_algorithm()
    if algorithm == "RS256":
        return bool(
            _jwt_issuer()
            and _jwt_audience()
            and _jwt_jwks_configured()
            and _allowed_jwt_service_authorities()
            and _allowed_jwt_service_keys()
        )
    if algorithm == "HS256":
        return bool(
            _jwt_issuer()
            and _jwt_audience()
            and len(_jwt_secret().encode("utf-8")) >= 32
            and _allowed_jwt_service_authorities()
        )
    return False


def _mtls_server_certfile() -> str:
    return os.getenv("INTERNAL_AUTH_MTLS_SERVER_CERTFILE", "").strip()


def _mtls_server_keyfile() -> str:
    return os.getenv("INTERNAL_AUTH_MTLS_SERVER_KEYFILE", "").strip()


def _mtls_ca_files() -> list[str]:
    raw = (
        os.getenv("INTERNAL_AUTH_MTLS_CA_FILES")
        or os.getenv("INTERNAL_AUTH_MTLS_CA_FILE")
        or ""
    )
    return [part.strip() for part in re.split(r"[,;]", raw) if part.strip()]


def _mtls_spiffe_trust_domain() -> str:
    return os.getenv("INTERNAL_AUTH_MTLS_SPIFFE_TRUST_DOMAIN", "fraud-platform").strip() or "fraud-platform"


def _mtls_configured() -> bool:
    return bool(
        _mtls_server_certfile()
        and _mtls_server_keyfile()
        and _mtls_ca_files()
        and _allowed_internal_service_authorities()
    )


def _spiffe_uri_for_service(service_name: str) -> str:
    return f"spiffe://{_mtls_spiffe_trust_domain()}/{service_name}"


def _load_jwks() -> dict[str, Any]:
    raw = _jwt_jwks_json()
    if not raw:
        path = _jwt_jwks_path()
        if not path:
            return {}
        try:
            with open(path, encoding="utf-8") as handle:
                raw = handle.read()
        except OSError:
            return {}
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _jwk_for_kid(kid: str) -> dict[str, Any] | None:
    keys = _load_jwks().get("keys")
    if not isinstance(keys, list):
        return None
    for jwk in keys:
        if not isinstance(jwk, dict) or jwk.get("kid") != kid:
            continue
        if jwk.get("kty") != "RSA" or jwk.get("alg") not in (None, "RS256"):
            return None
        if "d" in jwk or "p" in jwk or "q" in jwk:
            return None
        if not isinstance(jwk.get("n"), str) or not isinstance(jwk.get("e"), str):
            return None
        return jwk
    return None


def _rs256_public_key_for_kid(kid: str) -> Any | None:
    jwk = _jwk_for_kid(kid)
    if jwk is None:
        return None
    try:
        return RSAAlgorithm.from_jwk(json.dumps(jwk))
    except (InvalidKeyError, ValueError, TypeError, KeyError):
        return None


def _jwt_authorities(value: Any) -> frozenset[str]:
    if isinstance(value, list):
        return frozenset(item.strip() for item in value if isinstance(item, str) and item.strip())
    if isinstance(value, str):
        return frozenset(part.strip() for part in re.split(r"[\s,]+", value) if part.strip())
    return frozenset()


def _numeric_timestamp(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return int(value)


def _record_replay_metric(reason: str, token_age_seconds: float) -> None:
    INTERNAL_AUTH_REPLAY_REJECTIONS.labels(reason).inc()
    INTERNAL_AUTH_TOKEN_AGE.labels(reason).observe(max(token_age_seconds, 0.0))


def _log_internal_auth_replay_detected() -> None:
    print(json.dumps({
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
        "service": "ml-inference-service",
        "event": "internal_auth_replay_detected",
        "reason": REPLAY_REASON_REPLAY_DETECTED,
    }, separators=(",", ":"), sort_keys=True), flush=True)


def _token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _validate_jwt_service_token(token: str, required_authority: str) -> tuple[InternalServicePrincipal | None, int, str]:
    algorithm = _jwt_algorithm()
    if algorithm not in {"RS256", "HS256"}:
        return None, 403, "invalid_internal_token"
    try:
        header = jwt.get_unverified_header(token)
    except InvalidTokenError:
        return None, 403, "invalid_internal_token"
    if not isinstance(header, dict) or header.get("alg") != algorithm:
        return None, 403, "invalid_internal_token"
    key: Any
    kid = header.get("kid")
    if algorithm == "RS256":
        if not isinstance(kid, str) or not kid.strip():
            return None, 403, "invalid_internal_token"
        kid = kid.strip()
        key = _rs256_public_key_for_kid(kid)
        if key is None:
            return None, 403, "invalid_internal_token"
    else:
        key = _jwt_secret()
    try:
        claims = jwt.decode(
            token,
            key,
            algorithms=[algorithm],
            issuer=_jwt_issuer(),
            audience=_jwt_audience(),
            options={
                "require": ["iss", "aud", "iat", "exp", _jwt_service_claim(), _jwt_authorities_claim()],
                "verify_exp": False,
                "verify_iat": False,
            },
        )
    except InvalidIssuerError:
        return None, 403, "invalid_internal_issuer"
    except InvalidAudienceError:
        return None, 403, "invalid_internal_audience"
    except (ExpiredSignatureError, InvalidAlgorithmError, InvalidSignatureError, MissingRequiredClaimError, InvalidTokenError):
        return None, 403, "invalid_internal_token"
    now = int(time.time())
    skew_seconds = _jwt_clock_skew_seconds()
    max_token_age_seconds = _jwt_max_token_age_seconds()
    max_allowed_ttl_seconds = _jwt_max_allowed_ttl_seconds()
    iat = _numeric_timestamp(claims.get("iat"))
    exp = _numeric_timestamp(claims.get("exp"))
    if iat is None or exp is None:
        return None, 403, "invalid_internal_token"
    token_age_seconds = now - iat
    if iat > now + skew_seconds:
        _record_replay_metric(REPLAY_REASON_FUTURE_IAT, token_age_seconds)
        return None, 403, "invalid_internal_token"
    if exp <= iat:
        return None, 403, "invalid_internal_token"
    if exp - iat > max_allowed_ttl_seconds:
        _record_replay_metric(REPLAY_REASON_TOO_OLD, token_age_seconds)
        return None, 403, "invalid_internal_token"
    if token_age_seconds > max_token_age_seconds:
        _record_replay_metric(REPLAY_REASON_TOO_OLD, token_age_seconds)
        return None, 403, "invalid_internal_token"
    if now > exp + skew_seconds:
        _record_replay_metric(REPLAY_REASON_EXPIRED, token_age_seconds)
        return None, 401, "expired_internal_token"
    service_name = claims.get(_jwt_service_claim())
    if not isinstance(service_name, str) or not service_name.strip():
        return None, 403, "unknown_internal_service"
    service_name = service_name.strip()
    allowed_authorities = _allowed_jwt_service_authorities().get(service_name)
    if allowed_authorities is None:
        return None, 403, "unknown_internal_service"
    if algorithm == "RS256":
        allowed_key_ids = _allowed_jwt_service_keys().get(service_name)
        if allowed_key_ids is None or kid not in allowed_key_ids:
            return None, 403, "invalid_internal_token"
    token_authorities = _jwt_authorities(claims.get(_jwt_authorities_claim()))
    if required_authority not in allowed_authorities or required_authority not in token_authorities:
        return None, 403, "missing_internal_authority"
    if _replay_cache_enabled():
        replay_expires_at = min(exp + skew_seconds, now + max(exp - iat, 1))
        if SOFT_REPLAY_CACHE.seen(_token_hash(token), replay_expires_at, now, _replay_cache_max_entries()):
            _record_replay_metric(REPLAY_REASON_REPLAY_DETECTED, token_age_seconds)
            _log_internal_auth_replay_detected()
            if _replay_cache_reject_mode():
                return None, 403, "invalid_internal_token"
    return InternalServicePrincipal(
        service_name=service_name,
        authorities=token_authorities & allowed_authorities,
        authenticated_at=datetime.now(timezone.utc),
        auth_mode="JWT_SERVICE_IDENTITY",
    ), 200, "allowed"


def _mtls_service_principal_from_certificate(
        peer_certificate: dict[str, Any] | None,
        required_authority: str,
) -> tuple[InternalServicePrincipal | None, int, str]:
    if not peer_certificate:
        return None, 401, "missing_client_certificate"
    allowed_authorities = _allowed_internal_service_authorities()
    san_entries = peer_certificate.get("subjectAltName", ())
    san_uris = {
        value.strip()
        for kind, value in san_entries
        if kind == "URI" and isinstance(value, str) and value.strip()
    }
    matched_service_name: str | None = None
    for service_name in sorted(allowed_authorities):
        if _spiffe_uri_for_service(service_name) in san_uris:
            matched_service_name = service_name
            break
    if matched_service_name is None:
        return None, 403, "unknown_internal_service"
    authorities = allowed_authorities[matched_service_name]
    if required_authority not in authorities:
        return None, 403, "missing_internal_authority"
    certificate_expires_at: datetime | None = None
    not_after = peer_certificate.get("notAfter")
    if isinstance(not_after, str) and not_after.strip():
        try:
            certificate_expires_at = datetime.fromtimestamp(ssl.cert_time_to_seconds(not_after), timezone.utc)
        except (OSError, ValueError):
            return None, 403, "invalid_client_certificate"
    return InternalServicePrincipal(
        service_name=matched_service_name,
        authorities=authorities,
        authenticated_at=datetime.now(timezone.utc),
        auth_mode="MTLS_SERVICE_IDENTITY",
        certificate_expires_at=certificate_expires_at,
    ), 200, "allowed"


def _validate_internal_auth_startup(
        mode: str | None = None,
        profile: str | None = None,
        credentials: dict[str, InternalServiceCredential] | None = None,
) -> None:
    normalized_mode = _internal_auth_mode() if mode is None else _normalize_internal_auth_mode(mode)
    configured_credentials = INTERNAL_SERVICE_CREDENTIALS if credentials is None else credentials
    if normalized_mode == "DISABLED_LOCAL_ONLY" and _prod_like_profile(profile):
        raise RuntimeError("DISABLED_LOCAL_ONLY internal auth mode is forbidden in prod-like profiles.")
    if normalized_mode == "TOKEN_VALIDATOR" and _prod_like_profile(profile):
        if not _allow_token_validator_in_prod():
            raise RuntimeError("TOKEN_VALIDATOR internal auth mode requires explicit prod compatibility opt-in.")
        if not _token_hash_mode():
            raise RuntimeError("TOKEN_VALIDATOR internal auth mode requires token hash mode in prod-like profiles.")
        if not configured_credentials:
            raise RuntimeError("TOKEN_VALIDATOR internal auth mode requires an allowed service list in prod-like profiles.")
    if normalized_mode == "JWT_SERVICE_IDENTITY" and not _jwt_configured():
        raise RuntimeError("JWT_SERVICE_IDENTITY internal auth mode requires complete JWT issuer, audience, algorithm, key material, service authorities, and service key bindings.")
    if normalized_mode == "JWT_SERVICE_IDENTITY" and _jwt_algorithm() == "HS256" and _prod_like_profile(profile):
        raise RuntimeError("HS256 JWT service identity is local compatibility only and is forbidden in prod-like profiles.")
    if normalized_mode == "MTLS_READY" and _prod_like_profile(profile):
        raise RuntimeError("MTLS_READY internal auth mode is a fail-closed placeholder until mTLS is configured.")
    if normalized_mode == "MTLS_SERVICE_IDENTITY" and not _mtls_configured():
        raise RuntimeError("MTLS_SERVICE_IDENTITY internal auth mode requires server certificate, server private key, CA trust material, and service authorities.")


_validate_internal_auth_startup()


def _initialize_lifecycle_tracking() -> None:
    summary = lifecycle_metadata_summary(MODEL_LIFECYCLE)
    summary["feature_count"] = len(MODEL_GOVERNANCE.get("feature_set") or [])
    for event_type, source, reason, metadata in (
            ("MODEL_LOADED", "model_runtime", "active model loaded by inference runtime", summary),
            ("MODEL_METADATA_DETECTED", "model_artifact", "safe model metadata detected from artifact", summary),
    ):
        event, status = LIFECYCLE_SERVICE.record_event(event_type, MODEL_LIFECYCLE, source, reason, metadata)
        _record_lifecycle_event(event, status)
    if REFERENCE_PROFILE.get("available"):
        event, status = LIFECYCLE_SERVICE.record_event(
            "REFERENCE_PROFILE_LOADED",
            MODEL_LIFECYCLE,
            "reference_profile",
            "reference profile loaded for drift context",
            {"reference_profile_id": MODEL_LIFECYCLE.get("reference_profile_id")},
        )
        _record_lifecycle_event(event, status)
    event, status = LIFECYCLE_SERVICE.record_history_status(MODEL_LIFECYCLE)
    _record_lifecycle_event(event, status)
    _record_lifecycle_history_available("AVAILABLE" if event["event_type"] == "GOVERNANCE_HISTORY_AVAILABLE" else "PARTIAL")


def _record_lifecycle_event(event: dict[str, Any], status: str) -> None:
    MODEL_LIFECYCLE_EVENTS.labels(
        str(event.get("event_type", "UNKNOWN")),
        str(event.get("model_name", "unknown")),
        str(event.get("model_version", "unknown")),
        status,
    ).inc()


def _record_lifecycle_history_available(status: str) -> None:
    normalized = str(status).lower()
    if normalized not in {"available", "partial", "unavailable"}:
        normalized = "unavailable"
    for candidate in ("available", "partial", "unavailable"):
        MODEL_LIFECYCLE_HISTORY_AVAILABLE.labels(MODEL_NAME, MODEL_VERSION, candidate).set(
            1 if candidate == normalized else 0
        )


_initialize_lifecycle_tracking()


class FraudInferenceHandler(BaseHTTPRequestHandler):
    server_version = "FraudMLInference/1.0"

    def do_GET(self) -> None:
        parsed_url = urlparse(self.path)
        path = parsed_url.path
        if path == "/health":
            started_at = time.perf_counter()
            self._send_json(200, {"status": "UP", "modelName": MODEL_NAME, "modelVersion": MODEL_VERSION})
            self._record_request(path, "GET", 200, "success", started_at)
            self._log_event("health_check", statusCode=200)
            return
        if path == "/metrics":
            self._send_metrics()
            return
        if path == "/governance/model":
            if not self._require_internal_auth(path, "governance-read"):
                return
            started_at = time.perf_counter()
            inference = INFERENCE_PROFILE.snapshot()
            self._send_json(200, governance_response(MODEL_GOVERNANCE, REFERENCE_PROFILE, inference))
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/model/current":
            if not self._require_internal_auth(path, "governance-read"):
                return
            started_at = time.perf_counter()
            self._send_json(200, MODEL_LIFECYCLE)
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/model/lifecycle":
            if not self._require_internal_auth(path, "governance-read"):
                return
            started_at = time.perf_counter()
            history = LIFECYCLE_SERVICE.history_response(MODEL_LIFECYCLE)
            _record_lifecycle_history_available(history["status"])
            self._send_json(200, history)
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/profile/reference":
            if not self._require_internal_auth(path, "governance-read"):
                return
            started_at = time.perf_counter()
            inference = INFERENCE_PROFILE.snapshot()
            self._send_json(
                200,
                governance_response(
                    MODEL_GOVERNANCE,
                    REFERENCE_PROFILE,
                    inference,
                    include_reference_details=True,
                ),
            )
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/profile/inference":
            if not self._require_internal_auth(path, "governance-read"):
                return
            started_at = time.perf_counter()
            inference = INFERENCE_PROFILE.snapshot()
            self._send_json(
                200,
                governance_response(
                    MODEL_GOVERNANCE,
                    REFERENCE_PROFILE,
                    inference,
                    include_inference_details=True,
                ),
            )
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/drift":
            if not self._require_internal_auth(path, "governance-read"):
                return
            started_at = time.perf_counter()
            inference = INFERENCE_PROFILE.snapshot()
            drift = evaluate_drift(REFERENCE_PROFILE, inference)
            self._record_governance_drift(drift)
            self._send_json(200, governance_response(MODEL_GOVERNANCE, REFERENCE_PROFILE, inference, drift))
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/drift/actions":
            if not self._require_internal_auth(path, "governance-read"):
                return
            started_at = time.perf_counter()
            inference = INFERENCE_PROFILE.snapshot()
            drift = evaluate_drift(REFERENCE_PROFILE, inference)
            history = self._snapshot_history_for_actions()
            actions = recommend_drift_actions(MODEL_GOVERNANCE, drift, history)
            actions = with_model_lifecycle_context(actions, LIFECYCLE_SERVICE.lifecycle_context(MODEL_LIFECYCLE))
            self._maybe_emit_advisory_event(actions, drift)
            self._record_governance_drift(drift)
            self._record_drift_action(actions)
            self._send_json(200, actions)
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/advisories":
            if not self._require_internal_auth(path, "governance-read"):
                return
            started_at = time.perf_counter()
            limit = self._advisory_limit(parsed_url.query)
            filters = self._advisory_filters(parsed_url.query)
            response = ADVISORY_SERVICE.history_response(limit, **filters)
            self._send_json(200, response)
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/history":
            if not self._require_internal_auth(path, "governance-read"):
                return
            started_at = time.perf_counter()
            limit = self._history_limit(parsed_url.query)
            inference = INFERENCE_PROFILE.snapshot()
            fallback_snapshot = current_snapshot_document(
                SNAPSHOT_SERVICE,
                MODEL_GOVERNANCE,
                REFERENCE_PROFILE,
                inference,
            )
            history = SNAPSHOT_SERVICE.history_response(limit, fallback_snapshot)
            self._record_snapshot_history_available(history["status"] == "AVAILABLE")
            self._send_json(200, history)
            self._record_request(path, "GET", 200, "success", started_at)
            return
        self._send_error(404, "Not Found", "Not found.")
        self._record_request(path, "GET", 404, "not_found")
        self._log_event("not_found", method="GET", path=path, statusCode=404)

    def do_POST(self) -> None:
        started_at = time.perf_counter()
        path = urlparse(self.path).path
        if path != "/v1/fraud/score":
            self._send_error(404, "Not Found", "Not found.")
            self._record_request(path, "POST", 404, "not_found", started_at)
            self._log_event("not_found", method="POST", path=path, statusCode=404)
            return

        if not self._require_internal_auth(path, "ml-score"):
            return

        payload = self._read_json()
        if payload is None:
            self._send_error(400, "Bad Request", "Malformed JSON request.")
            self._record_error(path, "POST", "rejected")
            self._record_request(path, "POST", 400, "rejected", started_at)
            self._log_event("score_rejected", statusCode=400, reason="malformed_json")
            return

        features = payload.get("features")
        if not isinstance(features, dict):
            self._send_error(
                422,
                "Unprocessable Entity",
                "Field 'features' must be an object.",
                ["features: must be an object"],
            )
            self._record_error(path, "POST", "rejected")
            self._record_request(path, "POST", 422, "rejected", started_at)
            self._log_event(
                "score_rejected",
                transactionId=payload.get("transactionId"),
                correlationId=payload.get("correlationId"),
                statusCode=422,
                reason="features_not_object",
            )
            return

        try:
            response = MODEL.score(features)
        except Exception:
            self._send_error(500, "Internal Server Error", "Model inference failed.")
            self._record_error(path, "POST", "inference_error")
            self._record_request(path, "POST", 500, "inference_error", started_at)
            self._log_event("score_failed", statusCode=500)
            return

        self._send_json(200, response)
        self._update_inference_profile(response)
        self._maybe_persist_governance_snapshot()
        self._record_request(path, "POST", 200, "success", started_at)
        self._log_score(payload, features, response, started_at)

    def log_message(self, format: str, *args: Any) -> None:
        return

    def _read_json(self) -> dict[str, Any] | None:
        try:
            raw_body = self._read_body(max_bytes=128_000)
            body = json.loads(raw_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
            return None
        return body if isinstance(body, dict) else None

    def _read_body(self, max_bytes: int) -> bytes:
        transfer_encoding = self.headers.get("Transfer-Encoding", "").lower()
        if transfer_encoding == "chunked":
            return self._read_chunked_body(max_bytes)
        return self._read_fixed_body(max_bytes)

    def _read_fixed_body(self, max_bytes: int) -> bytes:
        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError as exc:
            raise ValueError("Invalid Content-Length header.") from exc
        if content_length <= 0 or content_length > max_bytes:
            raise ValueError("Request body length is outside allowed bounds.")
        return self.rfile.read(content_length)

    def _read_chunked_body(self, max_bytes: int) -> bytes:
        chunks: list[bytes] = []
        total_size = 0

        while True:
            size_line = self.rfile.readline(64).strip()
            if not size_line:
                raise ValueError("Missing chunk size.")
            try:
                chunk_size = int(size_line.split(b";", 1)[0], 16)
            except ValueError as exc:
                raise ValueError("Invalid chunk size.") from exc

            if chunk_size == 0:
                self._consume_trailing_chunk_headers()
                break

            total_size += chunk_size
            if total_size > max_bytes:
                raise ValueError("Chunked request body is too large.")

            chunk = self.rfile.read(chunk_size)
            if len(chunk) != chunk_size:
                raise ValueError("Incomplete chunked request body.")
            chunks.append(chunk)

            if self.rfile.read(2) != b"\r\n":
                raise ValueError("Invalid chunk terminator.")

        if total_size <= 0:
            raise ValueError("Empty chunked request body.")
        return b"".join(chunks)

    def _consume_trailing_chunk_headers(self) -> None:
        while True:
            line = self.rfile.readline(8192)
            if line in (b"\r\n", b"\n", b""):
                return

    def _send_json(self, status_code: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_error(
            self,
            status_code: int,
            error: str | None = None,
            message: str | None = None,
            details: list[str] | None = None,
    ) -> None:
        status = HTTPStatus(status_code)
        self._send_json(
            status_code,
            {
                "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
                "status": status_code,
                "error": error or status.phrase,
                "message": message or status.phrase,
                "details": list(details or []),
            },
        )

    def _send_metrics(self) -> None:
        started_at = time.perf_counter()
        body = generate_latest()
        self.send_response(200)
        self.send_header("Content-Type", CONTENT_TYPE_LATEST)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        self._record_request("/metrics", "GET", 200, "success", started_at)

    def _require_internal_auth(self, endpoint: str, required_authority: str) -> bool:
        principal, status_code, reason = self._internal_service_principal(required_authority)
        if principal is not None:
            self._record_internal_auth_success(principal)
            self._log_event(
                "internal_auth_allowed",
                serviceName=principal.service_name,
                authority=required_authority,
                authMode=principal.auth_mode,
            )
            return True
        self._record_internal_auth_failure(endpoint, reason)
        if status_code == 401:
            self._send_error(401, "Unauthorized", "Internal service authentication is required.")
        else:
            self._send_error(403, "Forbidden", "Internal service is not authorized for this endpoint.")
        return False

    def _internal_service_principal(self, required_authority: str) -> tuple[InternalServicePrincipal | None, int, str]:
        mode = _internal_auth_mode()
        service_name = self.headers.get("X-Internal-Service-Name", "").strip()
        token = self.headers.get("X-Internal-Service-Token", "").strip()
        if mode == "DISABLED_LOCAL_ONLY" and not service_name and not token:
            return InternalServicePrincipal(
                service_name="localdev-anonymous",
                authorities=frozenset({required_authority}),
                authenticated_at=datetime.now(timezone.utc),
                auth_mode=mode,
            ), 200, "allowed_localdev"
        if mode == "MTLS_READY":
            return None, 401, "mtls_not_configured"
        if mode == "MTLS_SERVICE_IDENTITY":
            try:
                peer_certificate = self.connection.getpeercert()
            except AttributeError:
                return None, 401, "missing_client_certificate"
            return _mtls_service_principal_from_certificate(peer_certificate, required_authority)
        if mode == "JWT_SERVICE_IDENTITY":
            authorization = self.headers.get("Authorization", "").strip()
            if not authorization:
                return None, 401, "missing_internal_credentials"
            if not authorization.startswith("Bearer ") or not authorization[7:].strip():
                return None, 403, "invalid_internal_token"
            return _validate_jwt_service_token(authorization[7:].strip(), required_authority)
        if not service_name or not token:
            return None, 401, "missing_internal_credentials"
        credential = INTERNAL_SERVICE_CREDENTIALS.get(service_name)
        if credential is None or not self._internal_token_matches(token, credential.token):
            return None, 403, "invalid_internal_credentials"
        if required_authority not in credential.authorities:
            return None, 403, "missing_internal_authority"
        return InternalServicePrincipal(
            service_name=service_name,
            authorities=credential.authorities,
            authenticated_at=datetime.now(timezone.utc),
            auth_mode=mode,
        ), 200, "allowed"

    def _internal_token_matches(self, presented_token: str, configured_token: str) -> bool:
        if _token_hash_mode():
            presented_hash = hashlib.sha256(presented_token.encode("utf-8")).hexdigest()
            return hmac.compare_digest(presented_hash, configured_token.lower())
        return hmac.compare_digest(configured_token, presented_token)

    def _record_internal_auth_success(self, principal: InternalServicePrincipal) -> None:
        if principal.auth_mode in {"JWT_SERVICE_IDENTITY", "MTLS_SERVICE_IDENTITY"}:
            allowed_services = _allowed_internal_service_authorities()
            source_service = principal.service_name if principal.service_name in allowed_services else "unknown"
        else:
            source_service = principal.service_name if principal.service_name in INTERNAL_SERVICE_CREDENTIALS else "localdev"
        INTERNAL_AUTH_SUCCESSES.labels(source_service, INTERNAL_AUTH_TARGET_SERVICE, principal.auth_mode).inc()
        if principal.auth_mode == "MTLS_SERVICE_IDENTITY" and principal.certificate_expires_at is not None:
            expires_in = max((principal.certificate_expires_at - datetime.now(timezone.utc)).total_seconds(), 0.0)
            INTERNAL_MTLS_CERTIFICATE_EXPIRY.labels(source_service, INTERNAL_AUTH_TARGET_SERVICE).set(expires_in)

    def _record_internal_auth_failure(self, endpoint: str, reason: str) -> None:
        normalized_reason = reason if reason in INTERNAL_AUTH_FAILURE_REASONS else "invalid_internal_token"
        INTERNAL_AUTH_FAILURES.labels(INTERNAL_AUTH_TARGET_SERVICE, _internal_auth_mode(), normalized_reason).inc()
        self._log_event(
            "internal_auth_rejected",
            targetService=INTERNAL_AUTH_TARGET_SERVICE,
            authMode=_internal_auth_mode(),
            reason=normalized_reason,
        )

    def _log_score(
            self,
            payload: dict[str, Any],
            features: dict[str, Any],
            response: dict[str, Any],
            started_at: float,
    ) -> None:
        elapsed_ms = round((time.perf_counter() - started_at) * 1000, 2)
        self._log_event(
            "score_completed",
            transactionId=payload.get("transactionId"),
            correlationId=payload.get("correlationId"),
            statusCode=200,
            modelName=response.get("modelName"),
            modelVersion=response.get("modelVersion"),
            fraudScore=response.get("fraudScore"),
            riskLevel=response.get("riskLevel"),
            reasonCodes=response.get("reasonCodes", []),
            featureFlags=features.get("featureFlags", []),
            latencyMs=elapsed_ms,
        )

    def _record_request(
            self,
            endpoint: str,
            method: str,
            status_code: int,
            outcome: str,
            started_at: float | None = None,
    ) -> None:
        normalized_endpoint = self._normalized_endpoint(endpoint)
        status = str(status_code)
        REQUEST_COUNTER.labels(normalized_endpoint, method, status, outcome).inc()
        if started_at is not None:
            REQUEST_LATENCY.labels(normalized_endpoint, method, status, outcome).observe(
                max(time.perf_counter() - started_at, 0.0)
            )

    def _record_error(self, endpoint: str, method: str, outcome: str) -> None:
        ERROR_COUNTER.labels(self._normalized_endpoint(endpoint), method, outcome).inc()

    def _history_limit(self, query: str) -> int:
        values = parse_qs(query).get("limit", [])
        if not values:
            return MAX_HISTORY_LIMIT
        try:
            requested = int(values[0])
        except (TypeError, ValueError):
            return MAX_HISTORY_LIMIT
        return max(min(requested, MAX_HISTORY_LIMIT), 1)

    def _advisory_limit(self, query: str) -> int:
        values = parse_qs(query).get("limit", [])
        if not values:
            return MAX_ADVISORY_LIMIT
        try:
            requested = int(values[0])
        except (TypeError, ValueError):
            return MAX_ADVISORY_LIMIT
        return max(min(requested, MAX_ADVISORY_LIMIT), 1)

    def _advisory_filters(self, query: str) -> dict[str, str | None]:
        values = parse_qs(query)
        severity = values.get("severity", [None])[0]
        if severity not in ADVISORY_SEVERITIES:
            severity = None
        model_version = values.get("model_version", [None])[0]
        if not self._valid_advisory_model_version_filter(model_version):
            model_version = None
        return {"severity": severity, "model_version": model_version}

    def _valid_advisory_model_version_filter(self, value: str | None) -> bool:
        if not isinstance(value, str) or not value or len(value) > 80:
            return False
        return all(character.isalnum() or character in {".", "_", "-"} for character in value)

    def _update_inference_profile(self, response: dict[str, Any]) -> None:
        try:
            score_details = response.get("scoreDetails")
            normalized_features = score_details.get("normalizedFeatures") if isinstance(score_details, dict) else {}
            if not isinstance(normalized_features, dict):
                normalized_features = {}
            INFERENCE_PROFILE.update(
                normalized_features,
                response.get("fraudScore"),
                response.get("riskLevel"),
            )
            GOVERNANCE_PROFILE_OBSERVATIONS.labels(MODEL_NAME, MODEL_VERSION).inc()
        except Exception as exc:
            self._log_event("governance_profile_update_failed", errorType=exc.__class__.__name__)

    def _maybe_persist_governance_snapshot(self) -> None:
        if not SNAPSHOT_SERVICE.should_persist_after_success():
            return
        try:
            inference = INFERENCE_PROFILE.snapshot()
            drift = evaluate_drift(REFERENCE_PROFILE, inference)
            SNAPSHOT_SERVICE.persist_snapshot(MODEL_GOVERNANCE, REFERENCE_PROFILE, inference, drift)
            GOVERNANCE_SNAPSHOTS_PERSISTED.labels(MODEL_NAME, MODEL_VERSION, "success").inc()
            self._record_snapshot_history_available(True)
        except Exception as exc:
            GOVERNANCE_SNAPSHOT_PERSISTENCE_FAILURES.labels(MODEL_NAME, MODEL_VERSION, "failure").inc()
            self._record_snapshot_history_available(False)
            self._log_event(
                "governance_snapshot_persistence_failed",
                level="warning",
                errorType=exc.__class__.__name__,
            )

    def _record_governance_drift(self, drift: dict[str, Any]) -> None:
        status = str(drift.get("status", "UNKNOWN"))
        for candidate in ("OK", "WATCH", "DRIFT", "UNKNOWN"):
            GOVERNANCE_DRIFT_STATUS.labels(MODEL_NAME, MODEL_VERSION, candidate).set(1 if candidate == status else 0)
        confidence = str(drift.get("confidence", "LOW"))
        for candidate in ("LOW", "MEDIUM", "HIGH"):
            GOVERNANCE_DRIFT_CONFIDENCE.labels(MODEL_NAME, MODEL_VERSION, candidate).set(
                1 if candidate == confidence else 0
            )

        feature_drift = {"WATCH": 0, "DRIFT": 0}
        score_drift = {"WATCH": 0, "DRIFT": 0}
        for signal in drift.get("signals", []):
            if not isinstance(signal, dict):
                continue
            severity = str(signal.get("severity"))
            drift_type = str(signal.get("drift_type", ""))
            if severity not in {"WATCH", "DRIFT"}:
                continue
            if drift_type.startswith("feature_") or drift_type == "missing_feature_rate":
                feature_drift[severity] = 1
            if drift_type.startswith("score_") or drift_type == "high_risk_rate_shift":
                score_drift[severity] = 1
        for severity in ("WATCH", "DRIFT"):
            GOVERNANCE_FEATURE_DRIFT_DETECTED.labels(MODEL_NAME, MODEL_VERSION, severity).set(feature_drift[severity])
            GOVERNANCE_SCORE_DRIFT_DETECTED.labels(MODEL_NAME, MODEL_VERSION, severity).set(score_drift[severity])

    def _record_snapshot_history_available(self, available: bool) -> None:
        GOVERNANCE_SNAPSHOT_HISTORY_AVAILABLE.labels(MODEL_NAME, MODEL_VERSION, "available").set(1 if available else 0)
        GOVERNANCE_SNAPSHOT_HISTORY_AVAILABLE.labels(MODEL_NAME, MODEL_VERSION, "unavailable").set(
            0 if available else 1
        )

    def _snapshot_history_for_actions(self) -> list[dict[str, Any]]:
        try:
            history = SNAPSHOT_SERVICE.repository.history(MAX_HISTORY_FOR_ACTIONS)
            self._record_snapshot_history_available(True)
            return history
        except Exception:
            self._record_snapshot_history_available(False)
            return []

    def _record_drift_action(self, actions: dict[str, Any]) -> None:
        severity = str(actions.get("severity", "INFO"))
        if severity not in ACTION_SEVERITIES:
            severity = "INFO"
        for candidate_severity in ACTION_SEVERITIES:
            GOVERNANCE_DRIFT_ACTION_RECOMMENDATION.labels(MODEL_NAME, MODEL_VERSION, candidate_severity).set(0)
        GOVERNANCE_DRIFT_ACTION_RECOMMENDATION.labels(MODEL_NAME, MODEL_VERSION, severity).set(1)

    def _maybe_emit_advisory_event(self, actions: dict[str, Any], drift: dict[str, Any]) -> None:
        lifecycle_context = actions.get("model_lifecycle")
        if not isinstance(lifecycle_context, dict):
            lifecycle_context = {}
        event, status = ADVISORY_SERVICE.emit_if_needed(actions, MODEL_GOVERNANCE, lifecycle_context, drift)
        if event is None:
            return
        severity = str(event.get("severity", "LOW"))
        model_name = str(event.get("model_name", MODEL_NAME))
        model_version = str(event.get("model_version", MODEL_VERSION))
        GOVERNANCE_ADVISORY_EVENTS_EMITTED.labels(severity, model_name, model_version, status).inc()
        if status == "persisted":
            GOVERNANCE_ADVISORY_EVENTS_PERSISTED.labels(severity, model_name, model_version, "success").inc()
        else:
            GOVERNANCE_ADVISORY_PERSISTENCE_FAILURES.labels(severity, model_name, model_version, "failure").inc()

    def _normalized_endpoint(self, path: str) -> str:
        known_paths = {
            "/health",
            "/metrics",
            "/v1/fraud/score",
            "/governance/model",
            "/governance/model/current",
            "/governance/model/lifecycle",
            "/governance/profile/reference",
            "/governance/profile/inference",
            "/governance/drift",
            "/governance/drift/actions",
            "/governance/advisories",
            "/governance/history",
        }
        return path if path in known_paths else "/unknown"

    def _log_event(self, event: str, **fields: Any) -> None:
        payload = {
            "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            "service": "ml-inference-service",
            "event": event,
            **fields,
        }
        print(json.dumps(payload, separators=(",", ":"), sort_keys=True), flush=True)


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), FraudInferenceHandler)
    if _internal_auth_mode() == "MTLS_SERVICE_IDENTITY":
        context = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(certfile=_mtls_server_certfile(), keyfile=_mtls_server_keyfile())
        for ca_file in _mtls_ca_files():
            context.load_verify_locations(cafile=ca_file)
        context.verify_mode = ssl.CERT_OPTIONAL
        server.socket = context.wrap_socket(server.socket, server_side=True)
    print(json.dumps({
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
        "service": "ml-inference-service",
        "event": "service_started",
        "host": HOST,
        "port": PORT,
        "internalAuthMode": _internal_auth_mode(),
        "modelName": MODEL_NAME,
        "modelVersion": MODEL_VERSION,
    }, separators=(",", ":"), sort_keys=True), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
