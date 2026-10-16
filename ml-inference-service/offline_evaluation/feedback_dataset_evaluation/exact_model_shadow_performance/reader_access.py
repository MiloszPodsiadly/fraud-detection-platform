from __future__ import annotations

import argparse
import errno
import os
import stat
import struct
import sys
from pathlib import Path

from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.schema import (
    ExactModelShadowPerformanceValidationError,
)


DEFAULT_READER_UID = 10001
READINESS_LABEL = "com.frauddetection.artifact-root-ready=true\n"
READINESS_MARKER = ".artifact-root-ready.labels"

_ACL_XATTR = "system.posix_acl_access"
_ACL_VERSION = 0x0002
_ACL_UNDEFINED_ID = 0xFFFFFFFF
_ACL_USER_OBJ = 0x01
_ACL_USER = 0x02
_ACL_GROUP_OBJ = 0x04
_ACL_MASK = 0x10
_ACL_OTHER = 0x20
_READ = 0x04
_WRITE = 0x02
_EXECUTE = 0x01


class ExactModelReaderAccessError(ExactModelShadowPerformanceValidationError):
    """The host filesystem cannot enforce the exact-model reader access contract."""


def prepare_artifact_root(
        artifact_root: str | Path,
        authorized_parent: str | Path,
        reader_uid: int = DEFAULT_READER_UID,
) -> None:
    _require_linux_acl()
    root = Path(os.path.abspath(artifact_root))
    parent = Path(os.path.abspath(authorized_parent))
    if root.parent != parent or root.name != "model-shadow-performance":
        raise ExactModelReaderAccessError(
            "exact-model artifact root must be the model-shadow-performance child of its authorized parent"
        )

    parent_descriptor = _open_or_create_directory(parent)
    try:
        root_descriptor = _open_or_create_child_directory(parent_descriptor, root.name)
    finally:
        os.close(parent_descriptor)
    try:
        _write_readiness_marker(root_descriptor)
        _apply_and_verify_descriptor_acl(
            root_descriptor,
            reader_uid,
            owner_permissions=_READ | _WRITE | _EXECUTE,
            reader_permissions=_EXECUTE,
            label="exact-model artifact root",
        )
        os.fsync(root_descriptor)
    finally:
        os.close(root_descriptor)


def verify_prepared_artifact_root(
        artifact_root: str | Path,
        authorized_parent: str | Path,
        reader_uid: int = DEFAULT_READER_UID,
) -> None:
    _require_linux_acl()
    root = Path(os.path.abspath(artifact_root))
    parent = Path(os.path.abspath(authorized_parent))
    if root.parent != parent or root.name != "model-shadow-performance":
        raise ExactModelReaderAccessError(
            "exact-model artifact root must be the model-shadow-performance child of its authorized parent"
        )
    verify_artifact_root_access(root, reader_uid)
    root_descriptor = _open_existing(root, True, "exact-model artifact root")
    try:
        marker_descriptor = os.open(
            READINESS_MARKER,
            os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=root_descriptor,
        )
        try:
            metadata = os.fstat(marker_descriptor)
            marker = os.read(marker_descriptor, len(READINESS_LABEL.encode("utf-8")) + 1)
        finally:
            os.close(marker_descriptor)
    except OSError as exception:
        raise ExactModelReaderAccessError("exact-model artifact readiness marker is unavailable") from exception
    finally:
        os.close(root_descriptor)
    if not stat.S_ISREG(metadata.st_mode) or stat.S_IMODE(metadata.st_mode) != 0o444:
        raise ExactModelReaderAccessError("exact-model artifact readiness marker permissions are invalid")
    if marker != READINESS_LABEL.encode("utf-8"):
        raise ExactModelReaderAccessError("exact-model artifact readiness marker content is invalid")


def apply_selection_reader_access(selection: Path, reader_uid: int = DEFAULT_READER_UID) -> None:
    _apply_path_acl(
        selection,
        reader_uid,
        owner_permissions=_READ | _WRITE | _EXECUTE,
        reader_permissions=_READ | _EXECUTE,
        expect_directory=True,
        label="exact-model selection directory",
    )


def apply_artifact_reader_access(artifact: Path, reader_uid: int = DEFAULT_READER_UID) -> None:
    _apply_path_acl(
        artifact,
        reader_uid,
        owner_permissions=_READ | _WRITE,
        reader_permissions=_READ,
        expect_directory=False,
        label="exact-model artifact",
    )


def verify_artifact_root_access(root: Path, reader_uid: int = DEFAULT_READER_UID) -> None:
    _verify_path_acl(
        root,
        reader_uid,
        owner_permissions=_READ | _WRITE | _EXECUTE,
        reader_permissions=_EXECUTE,
        expect_directory=True,
        label="exact-model artifact root",
    )


def verify_selection_reader_access(selection: Path, reader_uid: int = DEFAULT_READER_UID) -> None:
    _verify_path_acl(
        selection,
        reader_uid,
        owner_permissions=_READ | _WRITE | _EXECUTE,
        reader_permissions=_READ | _EXECUTE,
        expect_directory=True,
        label="exact-model selection directory",
    )


def verify_artifact_reader_access(artifact: Path, reader_uid: int = DEFAULT_READER_UID) -> None:
    _verify_path_acl(
        artifact,
        reader_uid,
        owner_permissions=_READ | _WRITE,
        reader_permissions=_READ,
        expect_directory=False,
        label="exact-model artifact",
    )


def _apply_path_acl(
        path: Path,
        reader_uid: int,
        owner_permissions: int,
        reader_permissions: int,
        expect_directory: bool,
        label: str,
) -> None:
    descriptor = _open_existing(path, expect_directory, label)
    try:
        _apply_and_verify_descriptor_acl(
            descriptor,
            reader_uid,
            owner_permissions,
            reader_permissions,
            label,
        )
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _verify_path_acl(
        path: Path,
        reader_uid: int,
        owner_permissions: int,
        reader_permissions: int,
        expect_directory: bool,
        label: str,
) -> None:
    descriptor = _open_existing(path, expect_directory, label)
    try:
        _verify_descriptor_acl(
            descriptor,
            reader_uid,
            owner_permissions,
            reader_permissions,
            label,
        )
    finally:
        os.close(descriptor)


def _open_existing(path: Path, expect_directory: bool, label: str) -> int:
    _require_linux_acl()
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    if expect_directory:
        flags |= getattr(os, "O_DIRECTORY", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exception:
        raise ExactModelReaderAccessError(f"{label} cannot be opened without following links") from exception
    metadata = os.fstat(descriptor)
    expected_type = stat.S_ISDIR(metadata.st_mode) if expect_directory else stat.S_ISREG(metadata.st_mode)
    if not expected_type:
        os.close(descriptor)
        raise ExactModelReaderAccessError(f"{label} has an unsupported filesystem type")
    return descriptor


def _apply_and_verify_descriptor_acl(
        descriptor: int,
        reader_uid: int,
        owner_permissions: int,
        reader_permissions: int,
        label: str,
) -> None:
    if reader_uid < 0 or reader_uid > 0xFFFFFFFE:
        raise ExactModelReaderAccessError("exact-model reader UID is outside the POSIX ACL range")
    payload = _acl_payload(owner_permissions, reader_uid, reader_permissions)
    try:
        os.setxattr(descriptor, _ACL_XATTR, payload)
    except OSError as exception:
        if exception.errno in {
            errno.ENOTSUP,
            getattr(errno, "EOPNOTSUPP", errno.ENOTSUP),
            errno.EPERM,
            errno.EACCES,
        }:
            raise ExactModelReaderAccessError(
                f"{label} filesystem cannot enforce the required POSIX ACL for reader UID {reader_uid}"
            ) from exception
        raise ExactModelReaderAccessError(f"{label} POSIX ACL could not be applied") from exception
    _verify_descriptor_acl(descriptor, reader_uid, owner_permissions, reader_permissions, label)


def _verify_descriptor_acl(
        descriptor: int,
        reader_uid: int,
        owner_permissions: int,
        reader_permissions: int,
        label: str,
) -> None:
    expected = _acl_entries(owner_permissions, reader_uid, reader_permissions)
    try:
        actual = _decode_acl(os.getxattr(descriptor, _ACL_XATTR))
    except OSError as exception:
        raise ExactModelReaderAccessError(f"{label} required POSIX ACL is missing") from exception
    if actual != expected:
        raise ExactModelReaderAccessError(
            f"{label} does not have the exclusive read-only ACL for reader UID {reader_uid}"
        )


def _acl_payload(owner_permissions: int, reader_uid: int, reader_permissions: int) -> bytes:
    entries = _acl_entries(owner_permissions, reader_uid, reader_permissions)
    return struct.pack("<I", _ACL_VERSION) + b"".join(
        struct.pack("<HHI", tag, permissions, identifier)
        for tag, permissions, identifier in entries
    )


def _acl_entries(owner_permissions: int, reader_uid: int, reader_permissions: int) -> tuple[tuple[int, int, int], ...]:
    return (
        (_ACL_USER_OBJ, owner_permissions, _ACL_UNDEFINED_ID),
        (_ACL_USER, reader_permissions, reader_uid),
        (_ACL_GROUP_OBJ, 0, _ACL_UNDEFINED_ID),
        (_ACL_MASK, reader_permissions, _ACL_UNDEFINED_ID),
        (_ACL_OTHER, 0, _ACL_UNDEFINED_ID),
    )


def _decode_acl(payload: bytes) -> tuple[tuple[int, int, int], ...]:
    if len(payload) < 4 or struct.unpack_from("<I", payload)[0] != _ACL_VERSION or (len(payload) - 4) % 8:
        raise ExactModelReaderAccessError("exact-model POSIX ACL has an unsupported binary representation")
    return tuple(
        struct.unpack_from("<HHI", payload, offset)
        for offset in range(4, len(payload), 8)
    )


def _open_or_create_directory(path: Path) -> int:
    absolute = Path(os.path.abspath(path))
    descriptor = os.open("/", os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_CLOEXEC", 0))
    try:
        for component in absolute.parts[1:]:
            next_descriptor = _open_or_create_child_directory(descriptor, component)
            os.close(descriptor)
            descriptor = next_descriptor
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def _open_or_create_child_directory(parent_descriptor: int, name: str) -> int:
    flags = os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        return os.open(name, flags, dir_fd=parent_descriptor)
    except FileNotFoundError:
        try:
            os.mkdir(name, mode=0o700, dir_fd=parent_descriptor)
        except FileExistsError:
            pass
        try:
            return os.open(name, flags, dir_fd=parent_descriptor)
        except OSError as exception:
            raise ExactModelReaderAccessError(
                f"authorized exact-model artifact path component {name!r} is unsafe"
            ) from exception
    except OSError as exception:
        raise ExactModelReaderAccessError(
            f"authorized exact-model artifact path component {name!r} is unsafe"
        ) from exception


def _write_readiness_marker(root_descriptor: int) -> None:
    try:
        metadata = os.stat(READINESS_MARKER, dir_fd=root_descriptor, follow_symlinks=False)
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
            raise ExactModelReaderAccessError("exact-model artifact readiness marker must be a regular file")
        marker_descriptor = os.open(
            READINESS_MARKER,
            os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=root_descriptor,
        )
        try:
            current = os.read(marker_descriptor, len(READINESS_LABEL.encode("utf-8")) + 1)
            os.fchmod(marker_descriptor, 0o444)
            os.fsync(marker_descriptor)
        finally:
            os.close(marker_descriptor)
        if current == READINESS_LABEL.encode("utf-8"):
            return
    except FileNotFoundError:
        pass

    temporary = f"{READINESS_MARKER}.tmp.{os.getpid()}"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(temporary, flags, 0o444, dir_fd=root_descriptor)
    try:
        os.write(descriptor, READINESS_LABEL.encode("utf-8"))
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    try:
        os.replace(temporary, READINESS_MARKER, src_dir_fd=root_descriptor, dst_dir_fd=root_descriptor)
    except BaseException:
        try:
            os.unlink(temporary, dir_fd=root_descriptor)
        except FileNotFoundError:
            pass
        raise


def _require_linux_acl() -> None:
    if not sys.platform.startswith("linux"):
        raise ExactModelReaderAccessError(
            "exact-model reader ACL provisioning requires a Linux POSIX-ACL filesystem"
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Provision the exact-model artifact reader access boundary.")
    parser.add_argument("--artifact-root", required=True)
    parser.add_argument("--authorized-parent", required=True)
    parser.add_argument("--reader-uid", type=int, default=DEFAULT_READER_UID)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args(argv)
    try:
        operation = verify_prepared_artifact_root if args.verify_only else prepare_artifact_root
        operation(args.artifact_root, args.authorized_parent, args.reader_uid)
    except ExactModelReaderAccessError as exception:
        print(f"exact-model artifact root preparation failed: {exception}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
