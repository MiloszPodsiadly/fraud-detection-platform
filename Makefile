SECURITY_HARDENED_COMPOSE = docker compose --env-file deployment/.env \
	-f deployment/docker-compose.yml \
	-f deployment/docker-compose.dev.yml \
	-f deployment/docker-compose.oidc.yml \
	-f deployment/docker-compose.service-identity-mtls.yml \
	-f deployment/docker-compose.trust-authority-jwt.yml \
	-f deployment/docker-compose.hardened.yml \
	-f deployment/docker-compose.shadow-performance-demo.yml

.PHONY: app-up app-down app-clean app-ps shadow-performance-summary

deployment/.env:
	cp deployment/.env.example deployment/.env

app-up: deployment/.env
	bash scripts/bootstrap-local-fixtures.sh
	$(SECURITY_HARDENED_COMPOSE) up --build -d

app-down: deployment/.env
	$(SECURITY_HARDENED_COMPOSE) down

app-clean: deployment/.env
	$(SECURITY_HARDENED_COMPOSE) down -v

app-ps: deployment/.env
	$(SECURITY_HARDENED_COMPOSE) ps

shadow-performance-summary:
	cd ml-inference-service && PYTHONPATH=. python -m offline_evaluation.generate_current_shadow_summary \
		--dataset-jsonl ../deployment/local-demo-inputs/shadow-performance/fdp102-feedback-dataset.synthetic.jsonl \
		--model-metadata ../deployment/local-demo-inputs/shadow-performance/model-metadata.synthetic.json \
		--output ../deployment/local-generated/shadow-performance/current-summary.json
