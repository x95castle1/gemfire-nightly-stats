SHELL := /bin/bash
.DEFAULT_GOAL := help

.PHONY: help build run start stop status logs admin test dist clean

help: ## Show the commands
	@scripts/examples.sh help

build: ## Build and test the source
run: ## Run the example
start: ## Start the locator, servers, regions and SeaweedFS
stop: ## Stop the environment and keep its data
status: ## Show the environment and Ubuntu collector status
logs: ## Follow the collector journal or locator log
admin: ## Open the SeaweedFS Admin UI in your browser
test: ## Run the integration checks
dist: ## Build ZIP and TAR distributions
clean: ## Stop the environment and delete builds, test files and SeaweedFS volumes

build run start stop status logs admin test dist clean:
	@scripts/examples.sh $@
