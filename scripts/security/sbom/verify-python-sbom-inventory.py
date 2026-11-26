#!/usr/bin/env python3
import hashlib
import importlib.metadata
import json
import re
import sys
from pathlib import Path

from pip._vendor.packaging.markers import default_environment
from pip._vendor.packaging.requirements import Requirement
from pip._vendor.packaging.utils import canonicalize_name


HASH_PATTERN = re.compile(r'^--hash=sha256:([a-f0-9]{64})$')


def read_hashed_requirements(path, marker_environment):
    raw = Path(path).read_bytes()
    entries = []
    current = None

    def finish_entry():
        nonlocal current
        if current is None:
            return
        if not current['hashes']:
            raise ValueError(f"{path}: {current['requirement']} is missing SHA-256 hashes")
        requirement = Requirement(current['requirement'])
        specifiers = list(requirement.specifier)
        if len(specifiers) != 1 or specifiers[0].operator != '==' or '*' in specifiers[0].version:
            raise ValueError(f"{path}: {current['requirement']} is not an exact version pin")
        marker_applies = requirement.marker is None or requirement.marker.evaluate(marker_environment)
        entries.append({
            'name': canonicalize_name(requirement.name),
            'version': specifiers[0].version,
            'marker': str(requirement.marker) if requirement.marker else None,
            'markerApplies': marker_applies,
            'hashes': sorted(set(current['hashes'])),
            'source': path.as_posix(),
        })
        current = None

    for original_line in raw.decode('utf-8').splitlines():
        line = original_line.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('--hash='):
            if current is None:
                raise ValueError(f'{path}: hash is not attached to a requirement')
            hash_line = line[:-1].strip() if line.endswith('\\') else line
            match = HASH_PATTERN.fullmatch(hash_line)
            if not match:
                raise ValueError(f'{path}: malformed SHA-256 requirement hash')
            current['hashes'].append(match.group(1))
            continue
        if line.startswith('--'):
            if current is not None:
                raise ValueError(f'{path}: unexpected pip option inside a requirement entry')
            continue

        finish_entry()
        continued = line.endswith('\\')
        requirement_line = line[:-1].rstrip() if continued else line
        if requirement_line.startswith('-'):
            raise ValueError(f'{path}: unsupported requirement directive {requirement_line}')
        current = {'requirement': requirement_line, 'hashes': []}

    finish_entry()
    if not entries:
        raise ValueError(f'{path}: no hash-locked package entries were found')
    return {
        'path': path.as_posix(),
        'sha256': hashlib.sha256(raw).hexdigest(),
        'packages': entries,
    }


def collect_installed_packages():
    packages = {}
    for distribution in importlib.metadata.distributions():
        name = distribution.metadata.get('Name')
        version = distribution.version
        if not name or not version:
            raise ValueError('Installed Python environment contains a distribution without a name or version')
        normalized = canonicalize_name(name)
        if normalized in packages:
            raise ValueError(f'Installed Python environment contains duplicate distribution {normalized}')
        packages[normalized] = version
    return [{'name': name, 'version': packages[name]} for name in sorted(packages)]


def build_inventory(application_lock, build_tools_lock):
    marker_environment = default_environment()
    requirement_files = [
        read_hashed_requirements(Path(application_lock), marker_environment),
        read_hashed_requirements(Path(build_tools_lock), marker_environment),
    ]
    locked = [package for file in requirement_files for package in file['packages']]
    expected_map = {}
    for package in locked:
        if not package['markerApplies']:
            continue
        name = package['name']
        previous = expected_map.get(name)
        if previous is not None and previous != package['version']:
            raise ValueError(f"Multiple active locked versions exist for Python distribution {name}")
        expected_map[name] = package['version']

    expected_packages = [{'name': name, 'version': expected_map[name]} for name in sorted(expected_map)]
    installed_packages = collect_installed_packages()
    installed_map = {package['name']: package['version'] for package in installed_packages}
    if installed_map != expected_map:
        missing = sorted(set(expected_map) - set(installed_map))
        unexpected = sorted(set(installed_map) - set(expected_map))
        wrong_versions = sorted(name for name in set(expected_map) & set(installed_map)
                                if expected_map[name] != installed_map[name])
        raise ValueError(
            'Installed Python distributions do not match the active hash-locked requirement graph: '
            f'{len(missing)} missing, {len(wrong_versions)} wrong-version, {len(unexpected)} unexpected.'
        )

    build_tools = set()
    for package in requirement_files[1]['packages']:
        if package['markerApplies']:
            build_tools.add(f"{package['name']}@{package['version']}")

    return {
        'schemaVersion': 1,
        'pythonVersion': sys.version.split()[0],
        'markerEnvironment': marker_environment,
        'requirementsFiles': [
            {'path': file['path'], 'sha256': file['sha256'],
             'lockedPackages': file['packages']}
            for file in requirement_files
        ],
        'expectedPackages': expected_packages,
        'installedPackages': installed_packages,
        'buildToolScope': {
            'includedPackages': sorted(build_tools),
            'sbomGenerator': 'cyclonedx-bom==9.0.0 installed in runner Python outside the target venv',
        },
    }


def main():
    if len(sys.argv) != 3:
        raise ValueError('Usage: verify-python-sbom-inventory.py <application-requirements> <build-tools-requirements>')
    inventory = build_inventory(Path(sys.argv[1]), Path(sys.argv[2]))
    sys.stdout.write(json.dumps(inventory, sort_keys=True, separators=(',', ':')) + '\n')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        sys.stderr.write(f'Python SBOM inventory verification failed: {error}\n')
        raise SystemExit(1)
