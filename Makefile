.DEFAULT_GOAL := help

BACKEND_IMAGE ?= todo-backend:demo
FRONTEND_IMAGE ?= todo-frontend:demo

.PHONY: help images postgres-up postgres-status postgres-logs postgres-stop \
	lab-secrets app-deploy app-status port-forward

help:
	@echo "Phase 1 - deploy and prove the application:"
	@echo "  make postgres-up        Start PostgreSQL with Docker Compose"
	@echo "  make postgres-status    Show PostgreSQL container health"
	@echo "  make postgres-logs      Follow PostgreSQL logs"
	@echo "  make images             Build backend/frontend images"
	@echo "  make lab-secrets        Create the app bootstrap Secret from .env"
	@echo "  make app-deploy         Deploy backend and frontend to Kubernetes"
	@echo "  make app-status         Show application resources"
	@echo "  Frontend NodePort       http://<k8s-node-ip>:30085"
	@echo "  make port-forward       Fallback at http://127.0.0.1:8081"
	@echo ""
	@echo "Phase 2 - run each Vault/VSO command from:"
	@echo "  docs/phase-2-vso-imperative.md"

images:
	docker build -t $(BACKEND_IMAGE) backend
	docker build -t $(FRONTEND_IMAGE) frontend

postgres-up:
	docker compose up -d --wait postgres

postgres-status:
	docker compose ps postgres

postgres-logs:
	docker compose logs -f postgres

postgres-stop:
	docker compose stop postgres

lab-secrets:
	./scripts/prepare-k8s-secrets.sh

app-deploy:
	BACKEND_IMAGE=$(BACKEND_IMAGE) FRONTEND_IMAGE=$(FRONTEND_IMAGE) ./scripts/deploy-k8s-app.sh

app-status:
	kubectl -n vault-demo get pod,svc,deploy

port-forward:
	kubectl -n vault-demo port-forward service/frontend 8081:80
