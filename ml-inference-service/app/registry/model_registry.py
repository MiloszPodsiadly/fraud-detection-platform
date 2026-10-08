from __future__ import annotations

import json
import os
import tempfile
import threading
import time
from contextlib import contextmanager
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import BinaryIO, Iterator

from app.model_identity_policy import ModelArtifactIdentity, ModelLogicalIdentity
from app.models.model_loader import (
    MAX_MODEL_ARTIFACT_BYTES,
    ModelConfigurationError,
    ValidatedModelArtifact,
    load_model_artifact,
    load_validated_model_artifact,
    validate_loaded_model_artifact,
)


DEFAULT_MAX_REGISTRY_INDEX_BYTES = 4 * 1024 * 1024
DEFAULT_MAX_REGISTRY_ENTRIES = 1_000
DEFAULT_MUTATION_LOCK_TIMEOUT_SECONDS = 5.0
LOCK_POLL_INTERVAL_SECONDS = 0.01

_PROCESS_LOCKS_GUARD = threading.Lock()
_PROCESS_LOCKS: dict[Path, threading.Lock] = {}


class ModelRegistryIntegrityError(RuntimeError):
    """Raised when persisted registry state cannot prove immutable model identity."""


class ModelRegistryConflictError(ModelRegistryIntegrityError):
    """Raised when one logical model identity is associated with different artifact state."""


class ModelRegistryMutationError(ModelRegistryIntegrityError):
    """Raised when exclusive registry mutation cannot be acquired or published."""


@dataclass(frozen=True)
class ModelRegistryEntry:
    """Immutable identity and managed location of one registered model artifact."""

    model_name: str
    model_version: str
    model_type: str
    model_family: str
    feature_contract_version: str
    artifact_sha256: str
    artifact_path: str

    @property
    def logical_identity(self) -> ModelLogicalIdentity:
        return ModelLogicalIdentity(self.model_name, self.model_version)

    @property
    def artifact_identity(self) -> ModelArtifactIdentity:
        return ModelArtifactIdentity(
            model_name=self.model_name,
            model_version=self.model_version,
            model_type=self.model_type,
            model_family=self.model_family,
            feature_contract_version=self.feature_contract_version,
            artifact_sha256=self.artifact_sha256,
        )


class ModelRegistry:
    """Bounded local registry with exact-byte integrity and serialized mutation."""

    SCHEMA_VERSION = 2
    INDEX_NAME = "registry.json"
    LOCK_NAME = ".registry.lock"
    INDEX_FIELDS = frozenset({"schemaVersion", "models"})
    ENTRY_FIELDS = frozenset(ModelRegistryEntry.__dataclass_fields__)

    def __init__(
            self,
            root: Path,
            max_artifact_bytes: int = MAX_MODEL_ARTIFACT_BYTES,
            max_index_bytes: int = DEFAULT_MAX_REGISTRY_INDEX_BYTES,
            max_entries: int = DEFAULT_MAX_REGISTRY_ENTRIES,
            mutation_lock_timeout_seconds: float = DEFAULT_MUTATION_LOCK_TIMEOUT_SECONDS,
    ) -> None:
        if min(max_artifact_bytes, max_index_bytes, max_entries) < 1:
            raise ValueError("Registry size and entry limits must be positive.")
        if mutation_lock_timeout_seconds <= 0:
            raise ValueError("Registry mutation lock timeout must be positive.")
        self.root = root.resolve()
        self.artifacts_root = self.root / "artifacts"
        self.index_path = self.root / self.INDEX_NAME
        self.lock_path = self.root / self.LOCK_NAME
        self.max_artifact_bytes = max_artifact_bytes
        self.max_index_bytes = max_index_bytes
        self.max_entries = max_entries
        self.mutation_lock_timeout_seconds = mutation_lock_timeout_seconds

    def register(
            self,
            artifact_path: Path,
    ) -> ModelRegistryEntry:
        """Publish artifact-derived immutable identity or return its existing entry."""
        loaded = load_model_artifact(artifact_path, max_bytes=self.max_artifact_bytes)
        with self._mutation_lock():
            entries = self.entries()
            existing = next(
                (entry for entry in entries if entry.logical_identity == loaded.logical_identity),
                None,
            )
            if existing is not None:
                self._resolve_entry(existing)
                if existing.artifact_identity != loaded.artifact_identity:
                    raise ModelRegistryConflictError(
                        "Model logical identity is already registered with different immutable artifact identity: "
                        f"{loaded.logical_identity.model_name}/{loaded.logical_identity.model_version}"
                    )
                validate_loaded_model_artifact(loaded)
                return existing
            if len(entries) >= self.max_entries:
                raise ModelRegistryIntegrityError(
                    f"Registry entry limit of {self.max_entries} would be exceeded."
                )
            validated = validate_loaded_model_artifact(loaded)
            target = self._artifact_target(validated.logical_identity)
            self.artifacts_root.mkdir(parents=True, exist_ok=True)
            if target.exists() or target.is_symlink():
                canonical_target = self._managed_artifact_path(target, must_exist=True)
                target_artifact = load_validated_model_artifact(
                    canonical_target,
                    max_bytes=self.max_artifact_bytes,
                )
                if target_artifact.artifact_identity != validated.artifact_identity:
                    raise ModelRegistryConflictError(
                        "Registry artifact destination already contains different immutable artifact bytes: "
                        f"{validated.logical_identity.model_name}/{validated.logical_identity.model_version}"
                    )
            else:
                self._managed_artifact_path(target, must_exist=False)
                self._atomic_write_bytes(target, validated.exact_bytes, replace_existing=False, label="model artifact")

            entry = ModelRegistryEntry(
                model_name=validated.artifact_identity.model_name,
                model_version=validated.artifact_identity.model_version,
                model_type=validated.artifact_identity.model_type,
                model_family=validated.artifact_identity.model_family,
                feature_contract_version=validated.artifact_identity.feature_contract_version,
                artifact_sha256=validated.artifact_sha256,
                artifact_path=str(target.resolve(strict=True)),
            )
            entries.append(entry)
            self._write_entries(entries)
            return entry

    def resolve(self, entry: ModelRegistryEntry) -> ValidatedModelArtifact:
        """Resolve and verify exact registered bytes before model construction."""
        authoritative = self.by_identity(entry.model_name, entry.model_version)
        if authoritative is None or authoritative != entry:
            raise ModelRegistryIntegrityError(
                f"Registry entry is not authoritative for {entry.model_name}/{entry.model_version}."
            )
        return self._resolve_entry(authoritative)

    def _resolve_entry(self, entry: ModelRegistryEntry) -> ValidatedModelArtifact:
        artifact_path = self._managed_artifact_path(entry.artifact_path, must_exist=True)
        try:
            validated = load_validated_model_artifact(artifact_path, max_bytes=self.max_artifact_bytes)
        except ModelConfigurationError as exception:
            raise ModelRegistryIntegrityError(
                f"Registered artifact is invalid for {entry.model_name}/{entry.model_version}."
            ) from exception
        if validated.artifact_sha256 != entry.artifact_sha256:
            raise ModelRegistryIntegrityError(
                f"Registered artifact digest mismatch for {entry.model_name}/{entry.model_version}."
            )
        if validated.artifact_identity != entry.artifact_identity:
            raise ModelRegistryIntegrityError(
                f"Registered artifact metadata mismatch for {entry.model_name}/{entry.model_version}."
            )
        return validated

    def by_identity(self, model_name: str, model_version: str) -> ModelRegistryEntry | None:
        """Load a registry entry by exact logical identity."""
        requested = ModelLogicalIdentity(model_name, model_version)
        return next((entry for entry in self.entries() if entry.logical_identity == requested), None)

    def entries(self) -> list[ModelRegistryEntry]:
        """Load bounded registry state and reject corruption as non-empty failure."""
        if not self.index_path.exists() and not self.index_path.is_symlink():
            return []
        index_path = self._contained_regular_file(self.index_path, self.root, "registry index")
        index_bytes = self._read_bounded(index_path, self.max_index_bytes, "Registry index")
        try:
            payload = json.loads(index_bytes)
        except (UnicodeDecodeError, json.JSONDecodeError) as exception:
            raise ModelRegistryIntegrityError("Registry index is not valid JSON.") from exception
        if not isinstance(payload, dict):
            raise ModelRegistryIntegrityError("Registry index must be a JSON object.")
        if "schemaVersion" not in payload:
            raise ModelRegistryIntegrityError("Registry index schemaVersion is required.")
        schema_version = payload["schemaVersion"]
        if type(schema_version) is not int:
            raise ModelRegistryIntegrityError("Registry index schemaVersion must be an integer.")
        if schema_version != self.SCHEMA_VERSION:
            raise ModelRegistryIntegrityError(
                f"Registry index schemaVersion {schema_version} is unsupported."
            )
        if frozenset(payload) != self.INDEX_FIELDS:
            raise ModelRegistryIntegrityError("Registry index fields do not match schema version 2.")
        if not isinstance(payload.get("models"), list):
            raise ModelRegistryIntegrityError("Registry index must contain a models array.")
        if len(payload["models"]) > self.max_entries:
            raise ModelRegistryIntegrityError(
                f"Registry entry limit of {self.max_entries} is exceeded."
            )
        entries: list[ModelRegistryEntry] = []
        logical_identities: set[ModelLogicalIdentity] = set()
        for row in payload["models"]:
            if not isinstance(row, dict):
                raise ModelRegistryIntegrityError("Registry entry must be an object.")
            if frozenset(row) != self.ENTRY_FIELDS:
                raise ModelRegistryIntegrityError(
                    "Registry entry fields do not match schema version 2."
                )
            try:
                entry = ModelRegistryEntry(**row)
                artifact_identity = entry.artifact_identity
                if not isinstance(entry.artifact_path, str) or not entry.artifact_path.strip():
                    raise ValueError("artifact_path is required")
            except (TypeError, ValueError) as exception:
                raise ModelRegistryIntegrityError(
                    "Registry entry uses an unsupported or invalid identity schema."
                ) from exception
            self._managed_artifact_path(entry.artifact_path, must_exist=True)
            if artifact_identity.logical_identity in logical_identities:
                raise ModelRegistryIntegrityError(
                    "Registry contains duplicate logical model identity: "
                    f"{entry.model_name}/{entry.model_version}"
                )
            logical_identities.add(artifact_identity.logical_identity)
            entries.append(entry)
        return entries

    def _artifact_target(self, identity: ModelLogicalIdentity) -> Path:
        return self.artifacts_root / f"{identity.model_name}--{identity.model_version}.json"

    def _managed_artifact_path(self, raw_path: str | Path, must_exist: bool) -> Path:
        path = Path(raw_path)
        if not path.is_absolute():
            path = self.root / path
        if path.is_symlink():
            raise ModelRegistryIntegrityError("Registry-managed artifact must not be a symbolic link.")
        if must_exist:
            return self._contained_regular_file(path, self.artifacts_root, "registry artifact")
        self.artifacts_root.mkdir(parents=True, exist_ok=True)
        canonical_root = self.artifacts_root.resolve(strict=True)
        canonical_parent = path.parent.resolve(strict=True)
        self._require_contained(canonical_parent, canonical_root, "registry artifact")
        return canonical_parent / path.name

    def _contained_regular_file(self, path: Path, root: Path, label: str) -> Path:
        try:
            canonical_root = root.resolve(strict=True)
            canonical_path = path.resolve(strict=True)
        except OSError as exception:
            raise ModelRegistryIntegrityError(f"{label.capitalize()} cannot be resolved.") from exception
        self._require_contained(canonical_path, canonical_root, label)
        if path.is_symlink() or not canonical_path.is_file():
            raise ModelRegistryIntegrityError(f"{label.capitalize()} must be a regular non-symlink file.")
        return canonical_path

    def _require_contained(self, path: Path, root: Path, label: str) -> None:
        try:
            path.relative_to(root)
        except ValueError as exception:
            raise ModelRegistryIntegrityError(f"{label.capitalize()} escapes the configured registry root.") from exception

    def _read_bounded(self, path: Path, max_bytes: int, label: str) -> bytes:
        try:
            with path.open("rb") as source:
                payload = source.read(max_bytes + 1)
        except OSError as exception:
            raise ModelRegistryIntegrityError(f"{label} cannot be read.") from exception
        if len(payload) > max_bytes:
            raise ModelRegistryIntegrityError(f"{label} exceeds maximum size of {max_bytes} bytes.")
        return payload

    def _write_entries(self, entries: list[ModelRegistryEntry]) -> None:
        if len(entries) > self.max_entries:
            raise ModelRegistryIntegrityError(
                f"Registry entry limit of {self.max_entries} is exceeded."
            )
        payload = {
            "schemaVersion": self.SCHEMA_VERSION,
            "models": [
                asdict(entry)
                for entry in sorted(entries, key=lambda item: (item.model_name, item.model_version))
            ]
        }
        try:
            index_bytes = (json.dumps(payload, indent=2, sort_keys=True) + "\n").encode("utf-8")
        except (TypeError, ValueError) as exception:
            raise ModelRegistryIntegrityError("Registry metadata is not JSON serializable.") from exception
        if len(index_bytes) > self.max_index_bytes:
            raise ModelRegistryIntegrityError(
                f"Registry index exceeds maximum size of {self.max_index_bytes} bytes."
            )
        self.root.mkdir(parents=True, exist_ok=True)
        self._atomic_write_bytes(self.index_path, index_bytes, replace_existing=True, label="registry index")

    def _atomic_write_bytes(self, target: Path, payload: bytes, replace_existing: bool, label: str) -> None:
        target.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(
            dir=target.parent,
            prefix=f".{target.name}.",
            suffix=".tmp",
        )
        temporary_path = Path(temporary_name)
        try:
            with os.fdopen(descriptor, "wb") as destination:
                destination.write(payload)
                destination.flush()
                os.fsync(destination.fileno())
            if not replace_existing and (target.exists() or target.is_symlink()):
                raise ModelRegistryConflictError(f"{label.capitalize()} already exists.")
            os.replace(temporary_path, target)
            self._fsync_directory(target.parent)
        except ModelRegistryIntegrityError:
            raise
        except OSError as exception:
            raise ModelRegistryMutationError(f"Failed to publish {label} atomically.") from exception
        finally:
            temporary_path.unlink(missing_ok=True)

    def _fsync_directory(self, directory: Path) -> None:
        if os.name == "nt":
            return
        descriptor = os.open(directory, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    @contextmanager
    def _mutation_lock(self) -> Iterator[None]:
        self.root.mkdir(parents=True, exist_ok=True)
        process_lock = _process_lock(self.root)
        if not process_lock.acquire(timeout=self.mutation_lock_timeout_seconds):
            raise ModelRegistryMutationError("Timed out acquiring in-process registry mutation lock.")
        lock_file: BinaryIO | None = None
        locked = False
        operation_failed = False
        try:
            lock_file = self.lock_path.open("a+b")
            _ensure_lock_byte(lock_file)
            deadline = time.monotonic() + self.mutation_lock_timeout_seconds
            while not locked:
                locked = _try_lock_file(lock_file)
                if locked:
                    break
                if time.monotonic() >= deadline:
                    raise ModelRegistryMutationError("Timed out acquiring inter-process registry mutation lock.")
                time.sleep(LOCK_POLL_INTERVAL_SECONDS)
            yield
        except OSError as exception:
            operation_failed = True
            raise ModelRegistryMutationError("Registry mutation lock failed.") from exception
        except BaseException:
            operation_failed = True
            raise
        finally:
            cleanup_error: OSError | None = None
            try:
                if locked and lock_file is not None:
                    try:
                        _unlock_file(lock_file)
                    except OSError as exception:
                        cleanup_error = exception
                if lock_file is not None:
                    try:
                        lock_file.close()
                    except OSError as exception:
                        cleanup_error = cleanup_error or exception
            finally:
                process_lock.release()
            if cleanup_error is not None and not operation_failed:
                raise ModelRegistryMutationError("Registry mutation lock cleanup failed.") from cleanup_error

def _process_lock(root: Path) -> threading.Lock:
    with _PROCESS_LOCKS_GUARD:
        return _PROCESS_LOCKS.setdefault(root, threading.Lock())


def _ensure_lock_byte(lock_file: BinaryIO) -> None:
    lock_file.seek(0, os.SEEK_END)
    if lock_file.tell() == 0:
        lock_file.write(b"\0")
        lock_file.flush()
        os.fsync(lock_file.fileno())


def _try_lock_file(lock_file: BinaryIO) -> bool:
    lock_file.seek(0)
    try:
        if os.name == "nt":
            import msvcrt

            msvcrt.locking(lock_file.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl

            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        return True
    except OSError:
        return False


def _unlock_file(lock_file: BinaryIO) -> None:
    lock_file.seek(0)
    if os.name == "nt":
        import msvcrt

        msvcrt.locking(lock_file.fileno(), msvcrt.LK_UNLCK, 1)
    else:
        import fcntl

        fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)


def default_registry_path() -> Path:
    """Default registry directory under the ML service app folder."""
    return Path(__file__).resolve().parents[1] / "model_registry"
