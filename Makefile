.DEFAULT_GOAL := help

VSO_CHART_VERSION ?= 1.5.1
BACKEND_IMAGE ?= todo-backend:demo
FRONTEND_IMAGE ?= todo-frontend:demo

.PHONY: help images lab-secrets app-deploy app-status postgres-expose \
	vault-db vault-smoke vso-install vault-k8s-auth vso-apply \
	disable-bootstrap port-forward

help:
	@echo "Phase 1 - deploy and prove the application:"
	@echo "  make images             Build backend/frontend images"
	@echo "  make lab-secrets        Create PostgreSQL/bootstrap Secrets from .env"
	@echo "  make app-deploy         Deploy PostgreSQL, backend and frontend"
	@echo "  make app-status         Show application resources"
	@echo "  make port-forward       Expose frontend at http://127.0.0.1:8081"
	@echo ""
	@echo "Phase 2 - integrate the existing Vault cluster through VSO:"
	@echo "  make postgres-expose    Apply optional lab-only PostgreSQL NodePort"
	@echo "  make vault-db           Configure Vault database secrets engine"
	@echo "  make vault-smoke        Test issue/login/revoke before VSO"
	@echo "  make vso-install        Install Vault Secrets Operator and CRDs"
	@echo "  make vault-k8s-auth     Configure Vault Kubernetes auth for VSO"
	@echo "  make vso-apply          Create VaultConnection/Auth/DynamicSecret"
	@echo "  make disable-bootstrap  Disable the initial static PostgreSQL login"

images:
	docker build -t $(BACKEND_IMAGE) backend
	docker build -t $(FRONTEND_IMAGE) frontend

lab-secrets:
	./scripts/prepare-k8s-secrets.sh

app-deploy:
	kubectl apply -k k8s/base
	kubectl -n vault-demo set image deployment/backend backend=$(BACKEND_IMAGE)
	kubectl -n vault-demo set image deployment/frontend frontend=$(FRONTEND_IMAGE)
	kubectl -n vault-demo rollout status statefulset/postgres --timeout=180s
	kubectl -n vault-demo rollout status deployment/backend --timeout=180s
	kubectl -n vault-demo rollout status deployment/frontend --timeout=180s

app-status:
	kubectl -n vault-demo get pod,svc,pvc,deploy,statefulset

port-forward:
	kubectl -n vault-demo port-forward service/frontend 8081:80

postgres-expose:
	kubectl apply -f k8s/addons/postgres-nodeport.yaml

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
