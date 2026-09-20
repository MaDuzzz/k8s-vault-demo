.DEFAULT_GOAL := help

VSO_CHART_VERSION ?= 1.5.1
BACKEND_IMAGE ?= todo-backend:demo
FRONTEND_IMAGE ?= todo-frontend:demo

.PHONY: help images postgres-up postgres-status postgres-logs postgres-stop \
	lab-secrets app-deploy app-status \
	vault-db vault-smoke vso-install vault-k8s-auth vso-apply \
	disable-bootstrap port-forward

help:
	@echo "Phase 1 - deploy and prove the application:"
	@echo "  make postgres-up        Start PostgreSQL with Docker Compose"
	@echo "  make postgres-status    Show PostgreSQL container health"
	@echo "  make postgres-logs      Follow PostgreSQL logs"
	@echo "  make images             Build backend/frontend images"
	@echo "  make lab-secrets        Create the app bootstrap Secret from .env"
	@echo "  make app-deploy         Deploy backend and frontend to Kubernetes"
	@echo "  make app-status         Show application resources"
	@echo "  Frontend NodePort       http://<k8s-node-ip>:30080"
	@echo "  make port-forward       Fallback at http://127.0.0.1:8081"
	@echo ""
	@echo "Phase 2 - integrate the existing Vault cluster through VSO:"
	@echo "  make vault-db           Configure Vault database secrets engine"
	@echo "  make vault-smoke        Test issue/login/revoke before VSO"
	@echo "  make vso-install        Install Vault Secrets Operator and CRDs"
	@echo "  make vault-k8s-auth     Configure Vault Kubernetes auth for VSO"
	@echo "  make vso-apply          Create VaultConnection/Auth/DynamicSecret"
	@echo "  make disable-bootstrap  Disable the initial static PostgreSQL login"

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

vault-db:
	./scripts/configure-vault-database.sh

vault-smoke:
	./scripts/smoke-test-vault-db.sh

vso-install:
	helm repo add hashicorp https://helm.releases.hashicorp.com --force-update
	helm repo update hashicorp
	helm upgrade --install vault-secrets-operator hashicorp/vault-secrets-operator \
		--version $(VSO_CHART_VERSION) \
		--namespace vault-secrets-operator-system \
		--create-namespace \
		--wait

vault-k8s-auth:
	./scripts/configure-kubernetes-auth.sh

vso-apply:
	./scripts/apply-vso-integration.sh

disable-bootstrap:
	./scripts/disable-bootstrap-db-user.sh
