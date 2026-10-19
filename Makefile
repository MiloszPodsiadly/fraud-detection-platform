COMPOSE_PROJECT_NAME ?= fraud-detection-platform

SECURITY_HARDENED_BASE_COMPOSE = docker compose --project-name $(COMPOSE_PROJECT_NAME) --env-file deployment/.env \
	-f deployment/docker-compose.yml \
	-f deployment/docker-compose.dev.yml \
	-f deployment/docker-compose.oidc.yml \
	-f deployment/docker-compose.service-identity-mtls.yml \
	-f deployment/docker-compose.trust-authority-jwt.yml \
	-f deployment/docker-compose.model-promotion-policy-trust.yml \
	-f deployment/docker-compose.hardened.yml

SHADOW_PERFORMANCE_DEMO_COMPOSE = $(SECURITY_HARDENED_BASE_COMPOSE) \
	-f deployment/docker-compose.shadow-performance-demo.yml

SHADOW_PERFORMANCE_GENERATED_COMPOSE = $(SECURITY_HARDENED_BASE_COMPOSE) \
	-f deployment/docker-compose.shadow-performance-generated.yml

SECURITY_HARDENED_COMPOSE = $(SHADOW_PERFORMANCE_GENERATED_COMPOSE)

# TODO: Include the exact-model reader in the default local stack after the canonical publication pipeline provisions its artifact root.
EXACT_MODEL_SHADOW_COMPOSE = $(SECURITY_HARDENED_COMPOSE) \
	-f deployment/docker-compose.model-shadow-performance-generated.yml

.PHONY: up up-exact-model-shadow down ps check-compose check-compose-exact-model-shadow \
	app-up app-up-exact-model-shadow app-down app-clean app-ps check-python local-runtime-fixtures \
	regenerate-local-runtime-fixtures \
	exact-model-shadow-preflight shadow-performance-summary promotion-readiness-report \
	app-up-shadow-performance-generated shadow-performance-local-loop check-local-startup

deployment/.env:
	cp deployment/.env.example deployment/.env

local-runtime-fixtures:
	bash scripts/bootstrap-local-fixtures.sh --ensure
	node scripts/bootstrap-local-model-promotion-keys.mjs --ensure

regenerate-local-runtime-fixtures:
	bash scripts/bootstrap-local-fixtures.sh --force

app-up: deployment/.env shadow-performance-summary
	@test -f deployment/local-generated/shadow-performance/current-summary.json || \
		(echo "Generated Shadow Performance Summary not found. Run: make shadow-performance-summary" && exit 1)
	@test -f deployment/local-generated/shadow-performance/manifest.json || \
		(echo "Generated Shadow Performance manifest not found. Run: make shadow-performance-summary" && exit 1)
	$(SECURITY_HARDENED_COMPOSE) up --build -d

app-down: deployment/.env
	$(SECURITY_HARDENED_COMPOSE) down

app-clean: deployment/.env
	$(SECURITY_HARDENED_COMPOSE) down -v

app-ps: deployment/.env
	$(SECURITY_HARDENED_COMPOSE) ps

up: app-up

down: app-down

ps: app-ps

check-compose: deployment/.env
	$(SECURITY_HARDENED_COMPOSE) config --quiet
	$(SECURITY_HARDENED_COMPOSE) config --format json | node scripts/check-compose-security-config.mjs security-hardened

exact-model-shadow-preflight: deployment/.env local-runtime-fixtures
	node scripts/check-compose-security-config.mjs model-shadow-performance-source \
		--source-file deployment/docker-compose.model-shadow-performance-generated.yml
	@resolved_config="$$(mktemp)"; resolved_source="$$(mktemp)"; \
		trap 'rm -f -- "$$resolved_config" "$$resolved_source"' EXIT; \
		$(EXACT_MODEL_SHADOW_COMPOSE) config --no-env-resolution --format json >"$$resolved_config"; \
		node scripts/check-compose-security-config.mjs security-hardened-model-shadow-performance \
			--require-existing-source --write-resolved-source "$$resolved_source" <"$$resolved_config"; \
		artifact_root="$$(cat -- "$$resolved_source")"; \
		authorized_parent="$$(dirname -- "$$artifact_root")"; \
		cd ml-inference-service && PYTHONPATH=. python -m \
			offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.reader_access \
			--artifact-root "$$artifact_root" --authorized-parent "$$authorized_parent" \
			--reader-uid 10001 --verify-only

check-compose-exact-model-shadow: exact-model-shadow-preflight
	node --test scripts/check-compose-security-config.test.mjs
	bash scripts/ci/check-compose-bind-mount-security.sh

check-local-startup:
	node --test scripts/local-startup-contract.test.mjs

app-up-exact-model-shadow: deployment/.env shadow-performance-summary exact-model-shadow-preflight
	$(EXACT_MODEL_SHADOW_COMPOSE) up --build -d

up-exact-model-shadow: app-up-exact-model-shadow

check-python:
	@python --version >/dev/null 2>&1 || \
		(echo "Python 3.12+ is required to generate the local Shadow Performance Summary. Install Python and rerun." && exit 1)

shadow-performance-summary: check-python local-runtime-fixtures
	cd ml-inference-service && PYTHONPATH=. python -m offline_evaluation.generate_current_shadow_summary \
		--evaluation-card ../deployment/local-generated/platform-recommendation-evaluation-card/platform_recommendation_evaluation_card.json \
		--evaluation-card-manifest ../deployment/local-generated/platform-recommendation-evaluation-card/manifest.json \
		--output ../deployment/local-generated/shadow-performance/current-summary.json

promotion-readiness-report: check-python
	cd ml-inference-service && PYTHONPATH=. python -m offline_evaluation.generate_promotion_review_readiness_report \
		--shadow-summary ../deployment/local-generated/shadow-performance/current-summary.json \
		--shadow-summary-manifest ../deployment/local-generated/shadow-performance/manifest.json \
		--output ../deployment/local-generated/promotion-readiness/promotion-review-readiness-report.json

app-up-shadow-performance-generated: deployment/.env shadow-performance-summary
	@test -f deployment/local-generated/shadow-performance/current-summary.json || \
		(echo "Generated Shadow Performance Summary not found. Run: make shadow-performance-summary" && exit 1)
	@test -f deployment/local-generated/shadow-performance/manifest.json || \
		(echo "Generated Shadow Performance manifest not found. Run: make shadow-performance-summary" && exit 1)
	$(SHADOW_PERFORMANCE_GENERATED_COMPOSE) up --build -d

shadow-performance-local-loop: app-up-shadow-performance-generated
