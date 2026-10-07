# Build, run and test the nightly stats collector. Run `make` to list the targets.
# Needs GEMFIRE_HOME set to a GemFire install, e.g. export GEMFIRE_HOME=~/dev/vmware-gemfire-10.3.2

SHELL := /bin/bash
.DEFAULT_GOAL := help
export GEMFIRE_HOME

# Test cluster settings. Override on the command line, e.g. make test SSL=false
SSL ?= true
SERVER_COUNT ?= 2
LOCATOR_PORT ?= 20334
JMX_PORT ?= 21099
SERVER_PORT_BASE ?= 40504
TEST_ENV ?= local
TEST_CLUSTER_NAME ?= test-cluster
TEST_DIR ?= $(CURDIR)/.test-cluster
# S3=true also uploads the test cluster's report to the local SeaweedFS (docker-compose.yml) and checks it
S3 ?= false
S3_ENDPOINT ?= http://localhost:8333
S3_BUCKET ?= gemfire-stats
S3_ACCESS_KEY ?= nightly-stats-dev
S3_SECRET_KEY ?= nightly-stats-dev-secret
# Properties file for `make run`
PROPERTIES ?= locator.properties

GFSH = $(GEMFIRE_HOME)/bin/gfsh
LOCATOR_DIR = $(TEST_DIR)/locator
REPORT_DIR = $(LOCATOR_DIR)/nightly-stats
CERTS_DIR = $(TEST_DIR)/certs
SECURITY_FILE = $(TEST_DIR)/gfsecurity.properties
CERT_PASSWORD = changeit
ssl_on = $(filter true,$(SSL))
s3_on = $(filter true,$(S3))
CONNECT = connect --locator=localhost[$(LOCATOR_PORT)] $(if $(ssl_on),--use-ssl --security-properties-file=$(SECURITY_FILE))
# Succeeds if something is listening on the port
port_open = (exec 3<>/dev/tcp/127.0.0.1/$(1)) 2>/dev/null

# SSL settings shared by every test cluster member (and gfsh)
define SSL_PROPERTIES
ssl-enabled-components=all
ssl-protocols=TLSv1.2,TLSv1.3
ssl-keystore=$(CERTS_DIR)/keystore.p12
ssl-keystore-password=$(CERT_PASSWORD)
ssl-keystore-type=PKCS12
ssl-truststore=$(CERTS_DIR)/truststore.p12
ssl-truststore-password=$(CERT_PASSWORD)
ssl-truststore-type=PKCS12
endef
export SSL_PROPERTIES

.PHONY: help
help: ## List the targets
	@grep -E '^[a-zA-Z0-9_-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "}; {printf "  %-15s %s\n", $$1, $$2}'
	@echo ""
	@echo "Test cluster settings (override with make <target> NAME=value):"
	@echo "  SSL=$(SSL) S3=$(S3) SERVER_COUNT=$(SERVER_COUNT) LOCATOR_PORT=$(LOCATOR_PORT) JMX_PORT=$(JMX_PORT) SERVER_PORT_BASE=$(SERVER_PORT_BASE)"

.PHONY: check-env
check-env:
	@test -n "$(GEMFIRE_HOME)" || { echo "✘ GEMFIRE_HOME is not set. Point it at a GemFire install, e.g. export GEMFIRE_HOME=~/dev/vmware-gemfire-10.3.2"; exit 1; }
	@test -x "$(GFSH)" || { echo "✘ No gfsh at $(GFSH). Check GEMFIRE_HOME."; exit 1; }

.PHONY: build
build: check-env ## Build the collector jar
	@./gradlew build --quiet
	@echo "✓ Built build/libs/gemfire-nightly-stats.jar"

.PHONY: run
run: build ## Run a locator with the collector in the foreground, from PROPERTIES (default locator.properties)
	@test -f "$(PROPERTIES)" || { echo "✘ $(PROPERTIES) not found. Copy locator.properties.example and edit it."; exit 1; }
	scripts/start-locator.sh $(PROPERTIES)

.PHONY: test
test: ## End-to-end test: start the test cluster, wait for the collector's startup run, check the report, stop
	@$(MAKE) --no-print-directory stop >/dev/null
	@rm -rf $(REPORT_DIR)
	@$(if $(s3_on),$(MAKE) --no-print-directory s3-up)
	@$(MAKE) --no-print-directory start
	@echo "Waiting for the collector's startup run (about 60 seconds after the locator started)..."
	@for i in $$(seq 1 120); do ls $(REPORT_DIR)/*.json >/dev/null 2>&1 && break; sleep 1; done
	@status=0; $(MAKE) --no-print-directory verify $(if $(s3_on),verify-upload) || status=$$?; $(MAKE) --no-print-directory stop; exit $$status

.PHONY: start
start: start-locator start-servers create-regions ## Start the test cluster: the collector's locator, servers and 3 regions

.PHONY: start-locator
start-locator: build test-config ## Start the test cluster's locator, with the collector, in the background
	@if $(call port_open,$(LOCATOR_PORT)); then echo "✘ Port $(LOCATOR_PORT) is in use. Run make stop, or set LOCATOR_PORT."; exit 1; fi
	@mkdir -p $(LOCATOR_DIR)
	@JAVA_OPTS=-Xmx512m nohup scripts/start-locator.sh $(TEST_DIR)/locator.properties > $(TEST_DIR)/locator.out 2>&1 &
	@for i in $$(seq 1 60); do \
	  if $(call port_open,$(JMX_PORT)); then echo "✓ Locator started (SSL=$(SSL)). Log: $(LOCATOR_DIR)/test-locator.log"; exit 0; fi; \
	  sleep 1; \
	done; \
	echo "✘ The locator didn't start. See $(TEST_DIR)/locator.out"; exit 1

.PHONY: start-servers
start-servers: check-env test-config ## Start the test cluster's servers (SERVER_COUNT of them)
	@for i in $$(seq 0 $$(($(SERVER_COUNT) - 1))); do \
	  mkdir -p $(TEST_DIR)/server-$$i; \
	  $(GFSH) -e "start server --name=test-server-$$i --dir=$(TEST_DIR)/server-$$i \
	    --locators=localhost[$(LOCATOR_PORT)] --server-port=$$(($(SERVER_PORT_BASE) + i)) \
	    $(if $(ssl_on),--security-properties-file=$(SECURITY_FILE)) --J=-Xmx512m --J=-Dgemfire.http-service-port=0" \
	    | grep "is currently online" >/dev/null || { echo "✘ test-server-$$i didn't start. See $(TEST_DIR)/server-$$i"; exit 1; }; \
	  echo "✓ Started test-server-$$i"; \
	done

.PHONY: create-regions
create-regions: check-env ## Create a partition, a replicate and a local region in the test cluster
	@$(GFSH) -e "$(CONNECT)" \
	  -e "create region --name=TestPartition --type=PARTITION --if-not-exists" \
	  -e "create region --name=TestReplicate --type=REPLICATE --if-not-exists" \
	  -e "create region --name=TestLocal --type=LOCAL --if-not-exists" \
	  | grep "TestLocal" >/dev/null || { echo "✘ Couldn't create the regions"; exit 1; }
	@echo "✓ Created /TestPartition, /TestReplicate and /TestLocal"

.PHONY: stop
stop: check-env ## Stop the test cluster
	@for dir in $(TEST_DIR)/server-*; do \
	  [ -d "$$dir" ] && $(GFSH) -e "stop server --dir=$$dir" >/dev/null 2>&1; \
	done; true
	@[ -d $(LOCATOR_DIR) ] && $(GFSH) -e "stop locator --dir=$(LOCATOR_DIR)" >/dev/null 2>&1; true
	@for i in $$(seq 1 30); do $(call port_open,$(LOCATOR_PORT)) || break; sleep 1; done
	@echo "✓ Test cluster stopped"

.PHONY: status
status: check-env ## List the test cluster's members
	@$(GFSH) -e "$(CONNECT)" -e "list members"

.PHONY: report
report: ## Show the newest report the test cluster wrote
	@file=$$(ls -t $(REPORT_DIR)/*.json 2>/dev/null | head -1); \
	[ -n "$$file" ] || { echo "✘ No report yet in $(REPORT_DIR)"; exit 1; }; \
	echo "$$file"; jq . "$$file"

.PHONY: verify
verify: ## Check the newest report against sample-output.json and the test cluster
	@scripts/verify-report.sh "$$(ls -t $(REPORT_DIR)/*.json 2>/dev/null | head -1)" $(SERVER_COUNT) $(SSL)

.PHONY: verify-upload
verify-upload: ## Check the newest report was uploaded to the local SeaweedFS unchanged
	@scripts/verify-upload.sh "$$(ls -t $(REPORT_DIR)/*.json 2>/dev/null | head -1)" \
	  "$(S3_ENDPOINT)/$(S3_BUCKET)/nightly-stats/$(TEST_ENV)/$(TEST_CLUSTER_NAME)" "$(S3_ACCESS_KEY):$(S3_SECRET_KEY)"

.PHONY: s3-up
s3-up: ## Start the local SeaweedFS S3 store and create its bucket (docker-compose.yml)
	@S3_BUCKET=$(S3_BUCKET) docker compose up -d --wait seaweedfs >/dev/null
	@S3_BUCKET=$(S3_BUCKET) docker compose run --rm create-bucket | tail -1
	@echo "✓ SeaweedFS is up. S3 API: $(S3_ENDPOINT), file browser: http://localhost:8888/buckets/$(S3_BUCKET)/"

.PHONY: s3-down
s3-down: ## Stop the local SeaweedFS (keeps its data; docker compose down -v deletes it)
	@docker compose down
	@echo "✓ SeaweedFS stopped"

.PHONY: s3-ls
s3-ls: ## List what's in the local SeaweedFS bucket
	@set -o pipefail; curl -sf --aws-sigv4 "aws:amz:us-east-1:s3" --user "$(S3_ACCESS_KEY):$(S3_SECRET_KEY)" \
	  "$(S3_ENDPOINT)/$(S3_BUCKET)/?list-type=2" \
	  | awk 'BEGIN { RS = "<Contents>" } NR > 1 { \
	      key = $$0; sub(/.*<Key>/, "", key); sub(/<.*/, "", key); \
	      size = $$0; sub(/.*<Size>/, "", size); sub(/<.*/, "", size); \
	      modified = $$0; sub(/.*<LastModified>/, "", modified); sub(/<.*/, "", modified); \
	      printf "%s  %8s  %s\n", modified, size, key }' \
	  || echo "✘ Couldn't list $(S3_BUCKET) at $(S3_ENDPOINT). Is SeaweedFS up (make s3-up)?"

.PHONY: logs
logs: ## Follow the test cluster's locator log
	@tail -f $(LOCATOR_DIR)/test-locator.log

.PHONY: clean
clean: stop ## Stop the test cluster, then delete it and the build output
	@./gradlew clean --quiet
	@rm -rf $(TEST_DIR)
	@echo "✓ Cleaned"

# Writes the test locator's properties, and the SSL settings when SSL=true
.PHONY: test-config
test-config: $(if $(ssl_on),certs)
	@mkdir -p $(TEST_DIR)
	@{ \
	  echo "ENV=$(TEST_ENV)"; \
	  echo "CLUSTER_NAME=$(TEST_CLUSTER_NAME)"; \
	  echo "MEMBER_NAME=test-locator"; \
	  echo "LOCATOR_PORT=$(LOCATOR_PORT)"; \
	  echo "JMX_MANAGER_PORT=$(JMX_PORT)"; \
	  echo "LOG_DIRECTORY=$(LOCATOR_DIR)"; \
	  echo "NIGHTLY_STATS_RUN_ON_STARTUP=true"; \
	  echo "gemfire.http-service-port=0"; \
	  if [ -n "$(s3_on)" ]; then \
	    echo "S3_ENDPOINT=$(S3_ENDPOINT)"; echo "S3_BUCKET=$(S3_BUCKET)"; \
	    echo "S3_ACCESS_KEY=$(S3_ACCESS_KEY)"; echo "S3_SECRET_KEY=$(S3_SECRET_KEY)"; \
	  fi; \
	  if [ -n "$(ssl_on)" ]; then echo "$$SSL_PROPERTIES" | sed 's/^/gemfire./'; fi; \
	} > $(TEST_DIR)/locator.properties
	@if [ -n "$(ssl_on)" ]; then echo "$$SSL_PROPERTIES" > $(SECURITY_FILE); fi

.PHONY: certs
certs: $(CERTS_DIR)/truststore.p12 ## Create a self-signed keystore and truststore for the test cluster

$(CERTS_DIR)/truststore.p12:
	@mkdir -p $(CERTS_DIR)
	@keytool -genkeypair -alias test-cluster -keyalg RSA -keysize 2048 -validity 365 \
	  -dname "CN=test-cluster" -ext "SAN=dns:localhost,ip:127.0.0.1" \
	  -storetype PKCS12 -keystore $(CERTS_DIR)/keystore.p12 -storepass $(CERT_PASSWORD) -keypass $(CERT_PASSWORD)
	@keytool -exportcert -alias test-cluster -keystore $(CERTS_DIR)/keystore.p12 -storepass $(CERT_PASSWORD) \
	  -file $(CERTS_DIR)/test-cluster.crt 2>/dev/null
	@keytool -importcert -noprompt -alias test-cluster -file $(CERTS_DIR)/test-cluster.crt \
	  -storetype PKCS12 -keystore $@ -storepass $(CERT_PASSWORD) 2>/dev/null
	@echo "✓ Created test certificates in $(CERTS_DIR)"
