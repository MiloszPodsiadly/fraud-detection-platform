from __future__ import annotations

import hashlib
import os
import stat
from pathlib import Path


SHA256_HEX_LENGTH = 64


class BoundedArtifactReadError(ValueError):
    """Raised when a bounded local artifact cannot be read safely."""


def read_bounded_regular_file(path: str | Path, label: str, max_bytes: int) -> bytes:
    source = Path(path)
    reject_symlink_path(source, label)
    if not source.exists():
        raise BoundedArtifactReadError(f"{label} is missing")
    if not source.is_file():
        raise BoundedArtifactReadError(f"{label} must be a regular file")
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(source, flags)
    except OSError as exception:
        raise BoundedArtifactReadError(f"{label} cannot be read") from exception
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise BoundedArtifactReadError(f"{label} must be a regular file")
        with os.fdopen(descriptor, "rb") as handle:
            descriptor = -1
            payload = handle.read(max_bytes + 1)
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    if len(payload) > max_bytes:
        raise BoundedArtifactReadError(f"{label} exceeds maximum byte size")
    return payload


def sha256_hex(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def is_lowercase_sha256(value: object) -> bool:
    return (
        isinstance(value, str)
        and len(value) == SHA256_HEX_LENGTH
        and all(character in "0123456789abcdef" for character in value)
    )


def reject_symlink_path(path: Path, label: str) -> None:
    if path.is_symlink():
        raise BoundedArtifactReadError(f"{label} must not be a symlink")
    parent = path.parent
    while parent != parent.parent:
        if parent.is_symlink():
            raise BoundedArtifactReadError(f"{label} parent must not be a symlink")
        parent = parent.parent
