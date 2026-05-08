# FDP-40 Registry Immutability Readiness

Registry immutability is required before production enablement. FDP-40 does not verify registry immutability through registry provider APIs.

Required external controls:

- mutable tag overwrite protection
- promotion by digest
- release repository retention
- fixture repository must not be promotable
- registry evidence export retained with the release

FDP-40 validates readiness shape only. It does not enforce registry immutability.
