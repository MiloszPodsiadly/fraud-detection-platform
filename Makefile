SECURITY_HARDENED_COMPOSE = docker compose --env-file deployment/.env \
	-f deployment/docker-compose.yml \
	-f deployment/docker-compose.dev.yml \
	-f deployment/docker-compose.oidc.yml \
	-f deployment/docker-compose.service-identity-mtls.yml \
	-f deployment/docker-compose.trust-authority-jwt.yml \
	-f deployment/docker-compose.hardened.yml

.PHONY: app-up app-down app-clean app-ps

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
