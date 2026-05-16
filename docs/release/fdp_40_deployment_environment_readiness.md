# FDP-40 Deployment Environment Readiness

Staging and production environment protection are required before production enablement. FDP-40 does not verify those protections through GitHub or deployment-platform APIs.

Required external controls:

- required reviewers for staging and production
- single release owner model
- named release owner
- secrets scoped to protected environments
- deployment references the immutable release image digest

Enablement is NO-GO until environment protection is configured outside repository code.
