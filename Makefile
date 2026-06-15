SECURITY_HARDENED_BASE_COMPOSE = docker compose --env-file deployment/.env \
	-f deployment/docker-compose.yml \
	-f deployment/docker-compose.dev.yml \
	-f deployment/docker-compose.oidc.yml \
	-f deployment/docker-compose.service-identity-mtls.yml \
	-f deployment/docker-compose.trust-authority-jwt.yml \
	-f deployment/docker-compose.hardened.yml

SHADOW_PERFORMANCE_DEMO_COMPOSE = $(SECURITY_HARDENED_BASE_COMPOSE) \
	-f deployment/docker-compose.shadow-performance-demo.yml

SHADOW_PERFORMANCE_GENERATED_COMPOSE = $(SECURITY_HARDENED_BASE_COMPOSE) \
	-f deployment/docker-compose.shadow-performance-generated.yml

SECURITY_HARDENED_COMPOSE = $(SHADOW_PERFORMANCE_GENERATED_COMPOSE)

.PHONY: app-up app-down app-clean app-ps check-python shadow-performance-summary promotion-readiness-report app-up-shadow-performance-generated shadow-performance-local-loop

deployment/.env:
	cp deployment/.env.example deployment/.env

app-up: deployment/.env shadow-performance-summary
	bash scripts/bootstrap-local-fixtures.sh
	@test -f deployment/local-generated/shadow-performance/current-summary.json || \
		(echo "Generated Shadow Performance Summary not found. Run: make shadow-performance-summary" && exit 1)
	$(SECURITY_HARDENED_COMPOSE) up --build -d

app-down: deployment/.env
	$(SECURITY_HARDENED_COMPOSE) down

app-clean: deployment/.env
	$(SECURITY_HARDENED_COMPOSE) down -v

app-ps: deployment/.env
	$(SECURITY_HARDENED_COMPOSE) ps

check-python:
	@python --version >/dev/null 2>&1 || \
		(echo "Python 3.12+ is required to generate the local Shadow Performance Summary. Install Python and rerun." && exit 1)

shadow-performance-summary: check-python
	cd ml-inference-service && PYTHONPATH=. python -m offline_evaluation.generate_current_shadow_summary \
		--dataset-jsonl ../deployment/local-demo-inputs/shadow-performance/fdp102-feedback-dataset.synthetic.jsonl \
		--model-metadata ../deployment/local-demo-inputs/shadow-performance/model-metadata.synthetic.json \
		--output ../deployment/local-generated/shadow-performance/current-summary.json

promotion-readiness-report: check-python
	cd ml-inference-service && PYTHONPATH=. python -m offline_evaluation.generate_promotion_review_readiness_report \
		--shadow-summary ../deployment/local-generated/shadow-performance/current-summary.json \
		--output ../deployment/local-generated/promotion-readiness/promotion-review-readiness-report.json

app-up-shadow-performance-generated: deployment/.env shadow-performance-summary
	@test -f deployment/local-generated/shadow-performance/current-summary.json || \
		(echo "Generated Shadow Performance Summary not found. Run: make shadow-performance-summary" && exit 1)
	$(SHADOW_PERFORMANCE_GENERATED_COMPOSE) up --build -d

shadow-performance-local-loop: app-up-shadow-performance-generated
