from __future__ import annotations

import ctypes
import errno
import os
import sys
from pathlib import Path


_AT_FDCWD = -100
_RENAME_NOREPLACE = 1
_DESTINATION_EXISTS_ERRNOS = frozenset((errno.EEXIST, errno.ENOTEMPTY))
_UNSUPPORTED_ERRNOS = frozenset(
    value
    for value in (
        errno.ENOSYS,
        errno.EINVAL,
        getattr(errno, "ENOTSUP", None),
        getattr(errno, "EOPNOTSUPP", None),
    )
    if value is not None
)


class AtomicDirectoryPublicationUnsupported(OSError):
    """The runtime cannot prove atomic directory publication without replacement."""


def publish_directory_without_replacement(source: Path, destination: Path) -> None:
    if os.name == "nt":
        _publish_on_windows(source, destination)
        return
    if sys.platform.startswith("linux"):
        _publish_on_linux(source, destination)
        return
    raise AtomicDirectoryPublicationUnsupported(
        f"atomic no-replace directory publication is unsupported on {sys.platform}"
    )


def _publish_on_windows(source: Path, destination: Path) -> None:
    # Windows rename fails atomically when the destination already exists.
    os.rename(source, destination)


def _publish_on_linux(source: Path, destination: Path) -> None:
    try:
        renameat2 = ctypes.CDLL(None, use_errno=True).renameat2
    except (AttributeError, OSError) as exc:
        raise AtomicDirectoryPublicationUnsupported(
            "Linux runtime does not expose renameat2 for atomic no-replace publication"
        ) from exc

    renameat2.argtypes = (
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    )
    renameat2.restype = ctypes.c_int
    ctypes.set_errno(0)
    result = renameat2(
        _AT_FDCWD,
        os.fsencode(source),
        _AT_FDCWD,
        os.fsencode(destination),
        _RENAME_NOREPLACE,
    )
    if result == 0:
        return

    error_number = ctypes.get_errno()
    if error_number in _DESTINATION_EXISTS_ERRNOS:
        raise FileExistsError(error_number, os.strerror(error_number), destination)
    if error_number in _UNSUPPORTED_ERRNOS:
        raise AtomicDirectoryPublicationUnsupported(
            error_number,
            "filesystem does not support atomic no-replace directory publication",
            destination,
        )
    raise OSError(error_number, os.strerror(error_number), destination)
