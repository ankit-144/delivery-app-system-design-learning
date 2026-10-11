# QuickDrop — everything you need, one command each.  `make help` lists them.
#
# The phase you start with `make up PHASE=n` is remembered in .phase, so later commands
# (load, down, ...) know which pieces are running.

SHELL := /bin/bash
COMPOSE := docker compose -f infra/docker-compose.yml
PHASE ?= $(shell cat .phase 2>/dev/null || echo 0)
MODE ?= broken
ID ?=

# --- which compose profiles and settings each phase needs ------------------------------
# Phase 5's replica and shards are a lab of their own: later phases leave them out to save memory.
# Add any profile back with EXTRA, e.g.  make up PHASE=6 EXTRA="replica shards"
EXTRA ?=
PROFILES := $(foreach p,$(EXTRA),--profile $(p))
ifeq ($(shell [ $(PHASE) -ge 3 ] && echo yes),yes)
  PROFILES += --profile scale --profile redis
  export CORE_UPSTREAMS ?= round-robin
endif
ifeq ($(shell [ $(PHASE) -eq 5 ] && echo yes),yes)
  PROFILES += --profile replica --profile shards
endif
ifneq ($(filter replica,$(EXTRA))$(shell [ $(PHASE) -eq 5 ] && echo yes),)
  export REPLICA_DB_URL ?= jdbc:postgresql://toxiproxy:15433/quickdrop
endif
ifneq ($(filter shards,$(EXTRA))$(shell [ $(PHASE) -eq 5 ] && echo yes),)
  export SHARD_URLS ?= jdbc:postgresql://toxiproxy:15434/quickdrop,jdbc:postgresql://toxiproxy:15435/quickdrop
endif
ifeq ($(shell [ $(PHASE) -ge 6 ] && echo yes),yes)
  PROFILES += --profile kafka
  export KAFKA_BOOTSTRAP ?= kafka:9092
endif
ifeq ($(shell [ $(PHASE) -ge 8 ] && echo yes),yes)
  PROFILES += --profile tracing --profile alerting
  export TRACING_ENABLED ?= true
endif

export CORE_URLS := http://core-1:8080,http://core-2:8080,http://core-3:8080

# Knobs a load script may read; passed through from `make load KNOB=value`.
KNOBS := RATE VUS DURATION SCALE RESET RACE_WINDOW LINE_DELAY STOCK RIDERS RETRIES SLOW_PERCENT DB_LATENCY FLEET BLAST WORKERS

.PHONY: help up down clean rebuild lb restart build ps logs urls load toggles toggle reset-toggles vendor toxics clear-toxics psql invariants dashboards check-dashboards mvn test

help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "  Current phase: $(PHASE)"

up: ## Start the stack for a phase:  make up PHASE=3
	@echo $(PHASE) > .phase
	$(COMPOSE) $(PROFILES) up -d --build
	@$(MAKE) --no-print-directory urls

down: ## Stop every container (data is kept)
	$(COMPOSE) --profile '*' down

clean: ## Stop everything and delete all data (database, metrics, dashboards state)
	$(COMPOSE) --profile '*' down -v
	@rm -f .phase

rebuild: ## Rebuild one service's image and restart it (stops if the build fails):  make rebuild S=core-1
	$(COMPOSE) build $(S) && $(COMPOSE) up -d --no-build $(S)

lb: ## Switch the load balancer algorithm (Phase 3+):  make lb ALGO=least-conn   (or round-robin)
	@test -n "$(ALGO)" || (echo "usage: make lb ALGO=round-robin|least-conn" && exit 1)
	CORE_UPSTREAMS=$(ALGO) $(COMPOSE) $(PROFILES) up -d --no-build --force-recreate nginx

restart: ## Restart one service:  make restart S=core-1
	$(COMPOSE) restart $(S)

build: ## Rebuild the service images
	$(COMPOSE) $(PROFILES) build

ps: ## Show running containers
	$(COMPOSE) ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'

logs: ## Follow logs of one service:  make logs S=core-1
	$(COMPOSE) logs -f --tail 100 $(S)

urls: ## Print the useful URLs
	@echo ""
	@echo "  API (through nginx)   http://localhost:8080"
	@echo "  core-1 directly       http://localhost:8081   (admin: /admin/toggles, /admin/invariants)"
	@echo "  vendor-mock           http://localhost:8090   (/v1/stats)"
	@echo "  Grafana               http://localhost:3000"
	@echo "  Prometheus            http://localhost:9090"
	@echo "  Toxiproxy API         http://localhost:8474/proxies"
	@echo "  Postgres              localhost:5432  quickdrop/quickdrop   (make psql)"
	@echo ""

load: ## Run a scenario's load script:  make load ID=1.1 MODE=broken [RATE=100 DURATION=60s]
	@test -n "$(ID)" || (echo "usage: make load ID=1.1 [MODE=broken|fixed|...]" && exit 1)
	@if [ "$(ID)" = K6 ]; then exec scripts/kafka-acks-lab.sh $(MODE); fi; \
	script=$$(cd loadtest/k6/scenarios && ls $(ID)-*.js 2>/dev/null | head -1); \
	test -n "$$script" || (echo "no script loadtest/k6/scenarios/$(ID)-*.js" && exit 1); \
	echo "running $$script with MODE=$(MODE)"; \
	$(COMPOSE) run --rm --quiet-pull \
	  -e MODE=$(MODE) $(foreach knob,$(KNOBS),-e $(knob)=$($(knob))) \
	  k6 run --quiet -o experimental-prometheus-rw /scripts/scenarios/$$script

gameday: ## Scenario 8.5: inject one hidden fault (run make load ID=8.5 in another terminal first)
	@scripts/gameday.sh start

gameday-reveal: ## Scenario 8.5: what was the fault, and how long did the diagnosis take?
	@scripts/gameday.sh reveal

gameday-end: ## Scenario 8.5: undo the fault
	@scripts/gameday.sh end

kafka-cluster-down: ## Stop the 3-broker cluster that K6 (make load ID=K6) starts
	$(COMPOSE) --profile kafka-cluster rm -sf kafka-a kafka-b kafka-c kafka-ctl

toggles: ## List the toggles on every running core instance
	@scripts/toggle.sh

toggle: ## Flip a toggle on every core instance:  make toggle NAME=inventory-reservation VALUE=atomic
	@scripts/toggle.sh $(NAME) $(VALUE)

reset-toggles: ## Put every toggle (core and vendor) back to its default
	@scripts/toggle.sh --reset

vendor: ## Change a vendor-mock knob:  make vendor NAME=latency-ms VALUE=1000
	@scripts/toggle.sh --vendor $(NAME) $(VALUE)

toxics: ## Show Toxiproxy proxies and active toxics
	@python3 scripts/toxics.py

clear-toxics: ## Remove every Toxiproxy toxic (network back to normal)
	@python3 scripts/toxics.py clear

invariants: ## Ask the invariant checker right now
	@curl -s 'localhost:8081/admin/invariants?samples=3' | python3 -m json.tool

psql: ## Open psql on the database
	docker exec -it qd-postgres psql -U quickdrop quickdrop

dashboards: ## Regenerate the Grafana dashboards from infra/grafana/gen_dashboards.py
	python3 infra/grafana/gen_dashboards.py

check-dashboards: ## Run every dashboard query against Prometheus
	python3 scripts/check_dashboards.py

mvn: ## Run Maven in a JDK container:  make mvn ARGS="-DskipTests package"
	scripts/mvn.sh $(ARGS)

test: ## Run the end-to-end integration test (needs Docker)
	scripts/mvn.sh -pl services/core-service -am verify
