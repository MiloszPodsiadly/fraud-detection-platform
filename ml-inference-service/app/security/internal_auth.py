from __future__ import annotations

import hashlib
import json
import os
import re
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

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

from app.observability.metrics import INTERNAL_AUTH_REPLAY_REJECTIONS, INTERNAL_AUTH_TOKEN_AGE


@dataclass(frozen=True)
class InternalServicePrincipal:
    service_name: str
    authorities: frozenset[str]
    authenticated_at: datetime
    auth_mode: str
    certificate_expires_at: datetime | None = None
    certificate_not_before: datetime | None = None


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
MTLS_HANDSHAKE_FAILURE_REASONS = {
    "EXPIRED_CERT",
    "UNTRUSTED_CA",
    "HOSTNAME_MISMATCH",
    "MISSING_CERT",
}
MTLS_CERT_EXPIRES_SOON_SECONDS = 7 * 24 * 60 * 60
MTLS_CERT_EXPIRES_ESCALATED_SECONDS = 3 * 24 * 60 * 60
MTLS_CERT_EXPIRES_IMMINENTLY_SECONDS = 24 * 60 * 60
MTLS_CERT_ROTATION_AGE_WARNING_SECONDS = 90 * 24 * 60 * 60
MTLS_CERT_MONITOR_INTERVAL_SECONDS = 6 * 60 * 60
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


def normalize_internal_auth_mode(mode: str) -> str:
    candidate = mode.strip().upper()
    if candidate not in SUPPORTED_INTERNAL_AUTH_MODES:
        raise RuntimeError("Unsupported internal auth mode.")
    if candidate in LOCAL_INTERNAL_AUTH_MODES:
        return "DISABLED_LOCAL_ONLY"
    if candidate in TOKEN_INTERNAL_AUTH_MODES:
        return "TOKEN_VALIDATOR"
    return candidate


def internal_auth_mode() -> str:
    return normalize_internal_auth_mode(os.getenv("INTERNAL_AUTH_MODE", "REQUIRED"))


def runtime_profile() -> str:
    return (
        os.getenv("INTERNAL_AUTH_PROFILE")
        or os.getenv("APP_PROFILE")
        or os.getenv("ENVIRONMENT")
        or os.getenv("SPRING_PROFILES_ACTIVE")
        or "localdev"
    ).strip().lower()


def prod_like_profile(profile: str | None = None) -> bool:
    value = (profile or runtime_profile()).strip().lower()
    profiles = {part.strip() for part in value.replace(";", ",").split(",") if part.strip()}
    return bool(profiles & PROD_LIKE_PROFILES)


def local_fixture_profile(profile: str | None = None) -> bool:
    value = (profile or runtime_profile()).strip().lower()
    profiles = {part.strip() for part in value.replace(";", ",").split(",") if part.strip()}
    local_fixture = bool(profiles & {"local", "dev", "docker-local", "localdev"})
    explicit_test_fixture = "test" in profiles and any(
        (os.getenv(name) or "").strip().lower() in {"true", "1", "yes", "on"}
        for name in ("LOCAL_FIXTURE_TEST_ENABLED", "APP_LOCAL_FIXTURE_TEST_ENABLED", "CI")
    )
    return local_fixture or explicit_test_fixture


def demo_local_secret_configured() -> bool:
    return any(
        "local-dev-" in os.getenv(name, "")
        for name in ("INTERNAL_AUTH_JWT_SECRET", "INTERNAL_AUTH_ALLOWED_SERVICES")
    )


def token_hash_mode() -> bool:
    return os.getenv("INTERNAL_AUTH_TOKEN_HASH_MODE", "false").strip().lower() in {"1", "true", "yes", "on"}


def allow_token_validator_in_prod() -> bool:
    return os.getenv("INTERNAL_AUTH_ALLOW_TOKEN_VALIDATOR_IN_PROD", "false").strip().lower() in {
        "1", "true", "yes", "on"
    }


def allowed_internal_services() -> dict[str, InternalServiceCredential]:
    raw = os.getenv("INTERNAL_AUTH_ALLOWED_SERVICES", "")
    services: dict[str, InternalServiceCredential] = {}
    hash_mode = token_hash_mode()
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


def jwt_issuer() -> str:
    return os.getenv("INTERNAL_AUTH_JWT_ISSUER", "").strip()


def jwt_audience() -> str:
    return os.getenv("INTERNAL_AUTH_JWT_AUDIENCE", "").strip()


def jwt_secret() -> str:
    return os.getenv("INTERNAL_AUTH_JWT_SECRET", "").strip()


def jwt_algorithm() -> str:
    return os.getenv("INTERNAL_AUTH_JWT_ALGORITHM", "HS256").strip().upper()


def jwt_jwks_json() -> str:
    return os.getenv("INTERNAL_AUTH_JWKS_JSON", "").strip()


def jwt_jwks_path() -> str:
    return os.getenv("INTERNAL_AUTH_JWKS_PATH", "").strip()


def jwt_service_claim() -> str:
    return os.getenv("INTERNAL_AUTH_JWT_SERVICE_CLAIM", "service_name").strip() or "service_name"


def jwt_authorities_claim() -> str:
    return os.getenv("INTERNAL_AUTH_JWT_AUTHORITIES_CLAIM", "authorities").strip() or "authorities"


def _env_int(name: str, default: int) -> int:
    try:
        value = int(os.getenv(name, str(default)).strip())
    except (TypeError, ValueError):
        return default
    return value if value > 0 else default


def jwt_max_token_age_seconds() -> int:
    return _env_int("INTERNAL_AUTH_JWT_MAX_TOKEN_AGE_SECONDS", DEFAULT_JWT_MAX_TOKEN_AGE_SECONDS)


def jwt_max_allowed_ttl_seconds() -> int:
    return _env_int("INTERNAL_AUTH_JWT_MAX_ALLOWED_TTL_SECONDS", DEFAULT_JWT_MAX_ALLOWED_TTL_SECONDS)


def jwt_clock_skew_seconds() -> int:
    return _env_int("INTERNAL_AUTH_JWT_CLOCK_SKEW_SECONDS", DEFAULT_JWT_CLOCK_SKEW_SECONDS)


def replay_cache_enabled() -> bool:
    return os.getenv("INTERNAL_AUTH_REPLAY_CACHE_ENABLED", "false").strip().lower() in {"1", "true", "yes", "on"}


def replay_cache_reject_mode() -> bool:
    return os.getenv("INTERNAL_AUTH_REPLAY_CACHE_MODE", "log").strip().lower() == "reject"


def replay_cache_max_entries() -> int:
    return _env_int("INTERNAL_AUTH_REPLAY_CACHE_MAX_ENTRIES", DEFAULT_REPLAY_CACHE_MAX_ENTRIES)


def allowed_jwt_service_authorities() -> dict[str, frozenset[str]]:
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
        if authority_set:
            services[service_name] = authority_set
    return services


def allowed_internal_service_authorities() -> dict[str, frozenset[str]]:
    return allowed_jwt_service_authorities()


def allowed_jwt_service_keys() -> dict[str, frozenset[str]]:
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
        if key_id_set:
            services[service_name] = key_id_set
    return services


def jwt_jwks_configured() -> bool:
    return bool(jwt_jwks_json() or jwt_jwks_path())


def jwt_configured() -> bool:
    algorithm = jwt_algorithm()
    if algorithm == "RS256":
        return bool(
            jwt_issuer()
            and jwt_audience()
            and jwt_jwks_configured()
            and allowed_jwt_service_authorities()
            and allowed_jwt_service_keys()
        )
    if algorithm == "HS256":
        return bool(
            jwt_issuer()
            and jwt_audience()
            and len(jwt_secret().encode("utf-8")) >= 32
            and allowed_jwt_service_authorities()
        )
    return False


def mtls_server_certfile() -> str:
    return os.getenv("INTERNAL_AUTH_MTLS_SERVER_CERTFILE", "").strip()


def mtls_server_keyfile() -> str:
    return os.getenv("INTERNAL_AUTH_MTLS_SERVER_KEYFILE", "").strip()


def mtls_ca_files() -> list[str]:
    raw = os.getenv("INTERNAL_AUTH_MTLS_CA_FILES") or os.getenv("INTERNAL_AUTH_MTLS_CA_FILE") or ""
    return [part.strip() for part in re.split(r"[,;]", raw) if part.strip()]


def mtls_spiffe_trust_domain() -> str:
    return os.getenv("INTERNAL_AUTH_MTLS_SPIFFE_TRUST_DOMAIN", "fraud-platform").strip() or "fraud-platform"


def mtls_configured() -> bool:
    return bool(
        mtls_server_certfile()
        and mtls_server_keyfile()
        and mtls_ca_files()
        and allowed_internal_service_authorities()
    )


def spiffe_uri_for_service(service_name: str) -> str:
    return f"spiffe://{mtls_spiffe_trust_domain()}/{service_name}"


def _load_jwks() -> dict[str, Any]:
    raw = jwt_jwks_json()
    if not raw:
        path = jwt_jwks_path()
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


def validate_jwt_service_token(
        token: str,
        required_authority: str,
) -> tuple[InternalServicePrincipal | None, int, str]:
    algorithm = jwt_algorithm()
    if algorithm not in {"RS256", "HS256"}:
        return None, 403, "invalid_internal_token"
    try:
        header = jwt.get_unverified_header(token)
    except InvalidTokenError:
        return None, 403, "invalid_internal_token"
    if not isinstance(header, dict) or header.get("alg") != algorithm:
        return None, 403, "invalid_internal_token"
    kid = header.get("kid")
    if algorithm == "RS256":
        if not isinstance(kid, str) or not kid.strip():
            return None, 403, "invalid_internal_token"
        kid = kid.strip()
        key: Any = _rs256_public_key_for_kid(kid)
        if key is None:
            return None, 403, "invalid_internal_token"
    else:
        key = jwt_secret()
    try:
        claims = jwt.decode(
            token,
            key,
            algorithms=[algorithm],
            issuer=jwt_issuer(),
            audience=jwt_audience(),
            options={
                "require": ["iss", "aud", "iat", "exp", jwt_service_claim(), jwt_authorities_claim()],
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
    skew_seconds = jwt_clock_skew_seconds()
    max_token_age_seconds = jwt_max_token_age_seconds()
    max_allowed_ttl_seconds = jwt_max_allowed_ttl_seconds()
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
    if exp - iat > max_allowed_ttl_seconds or token_age_seconds > max_token_age_seconds:
        _record_replay_metric(REPLAY_REASON_TOO_OLD, token_age_seconds)
        return None, 403, "invalid_internal_token"
    if now > exp + skew_seconds:
        _record_replay_metric(REPLAY_REASON_EXPIRED, token_age_seconds)
        return None, 401, "expired_internal_token"
    service_name = claims.get(jwt_service_claim())
    if not isinstance(service_name, str) or not service_name.strip():
        return None, 403, "unknown_internal_service"
    service_name = service_name.strip()
    allowed_authorities = allowed_jwt_service_authorities().get(service_name)
    if allowed_authorities is None:
        return None, 403, "unknown_internal_service"
    if algorithm == "RS256":
        allowed_key_ids = allowed_jwt_service_keys().get(service_name)
        if allowed_key_ids is None or kid not in allowed_key_ids:
            return None, 403, "invalid_internal_token"
    token_authorities = _jwt_authorities(claims.get(jwt_authorities_claim()))
    if required_authority not in allowed_authorities or required_authority not in token_authorities:
        return None, 403, "missing_internal_authority"
    if replay_cache_enabled():
        replay_expires_at = min(exp + skew_seconds, now + max(exp - iat, 1))
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        if SOFT_REPLAY_CACHE.seen(token_hash, replay_expires_at, now, replay_cache_max_entries()):
            _record_replay_metric(REPLAY_REASON_REPLAY_DETECTED, token_age_seconds)
            _log_internal_auth_replay_detected()
            if replay_cache_reject_mode():
                return None, 403, "invalid_internal_token"
    return InternalServicePrincipal(
        service_name=service_name,
        authorities=token_authorities & allowed_authorities,
        authenticated_at=datetime.now(timezone.utc),
        auth_mode="JWT_SERVICE_IDENTITY",
    ), 200, "allowed"
