# QuickDrop

**Learn how production systems break by breaking one on purpose.**

QuickDrop is a tiny quick-commerce backend (think Zepto / Blinkit). A customer orders from a
dark store, stock is reserved, a nearby rider is assigned and an SMS goes out. That's the whole
product, and it stays that small.

What grows is everything around it. Over eight phases the system goes from one instance and
one database to several instances, Redis, a sharded database and Kafka. Every phase starts
with a decision that looks reasonable, then you:

1. **break it** with a load script,
2. **see it** on Grafana dashboards (metrics first, code second),
3. **fix it** by flipping a toggle to a better strategy that already lives in the code,
4. learn **what can still go wrong**.

**Just want to run it?** [docs/CHEATSHEET.md](docs/CHEATSHEET.md): start, check, every dashboard and tool link, stop.

The full plan is in [ROADMAP.md](ROADMAP.md). Scenarios that can't be reproduced on a laptop are
written up in [docs/FAILURE_TRACKER.md](docs/FAILURE_TRACKER.md).

---

## Quick start

You need **Docker** (with Compose v2) and about **4 GB of free RAM** for the early phases
(8–10 GB for everything). No JDK, Maven or k6 install needed: they all run in containers.

```bash
make up                      # builds the images and starts Phase 0 (takes a few minutes the first time)
open http://localhost:3000   # Grafana, no login needed
make load ID=0.1             # a normal day of traffic: watch the Overview dashboard fill up
```

Then do your first scenario:

```bash
make load ID=1.1 MODE=broken   # flash sale: 200 units in stock... and ~227 get sold
make load ID=1.1 MODE=fixed    # the same flash sale with an atomic UPDATE: exactly 200
```

Read the runbook while it runs: [scenarios/phase-1/1.1-oversell.md](scenarios/phase-1/1.1-oversell.md).

---

## How a scenario works

Every scenario has a **runbook** (`scenarios/phase-N/<id>-*.md`) and a **load script**
(`loadtest/k6/scenarios/<id>-*.js`). The runbook tells the story and what to look for. The
script sets up the world and generates the load:

```
make load ID=<id> MODE=<mode>
          │         └── broken | fixed | other named variants listed at the top of each script
          └── scenario id from the roadmap, e.g. 1.4
```

- **`setup()`** flips the toggles for the chosen mode on every running instance and resets the
  fixtures (stock levels, available riders, ...).
- **The load** runs for 30 s – 2 min. Its metrics go to Prometheus, so they show up in Grafana
  next to the server-side metrics.
- **`teardown()`** asks the **invariant checker** whether the data is still correct and prints a
  verdict: `OK` / `FAIL oversold_units violations=256`.

### Toggles

Each wrong way and each right way is a strategy class, chosen at runtime by a toggle:

```bash
make toggles                                          # what every instance is running
make toggle NAME=inventory-reservation VALUE=atomic   # flip on every instance, mid-test if you like
make vendor NAME=latency-ms VALUE=1000                # make the SMS vendor slow
```

Every flip is drawn as an orange line on the dashboards, so you can see exactly when behaviour
changed. Run a script with `MODE=keep` to leave the toggles alone while you flip them by hand.

### Useful commands

```bash
make help          # everything below, with descriptions
make ps            # what's running
make logs S=core-1 # follow a service's logs
make invariants    # is the data correct right now?
make psql          # poke at the database
make down          # stop (keeps data)    make clean   # stop and wipe all data
make test          # the end-to-end integration test (Testcontainers)
```

---

## What's running

| URL | What |
|---|---|
| http://localhost:8080 | The API, through the nginx load balancer |
| http://localhost:8081 | core-1 directly (admin endpoints: `/admin/toggles`, `/admin/invariants`) |
| http://localhost:8090 | vendor-mock, the fake SMS vendor (`/v1/stats`) |
| http://localhost:3000 | Grafana: Overview, Saturation, Postgres, Redis, Kafka, Operating (Phase 8), Containers & logs |
| http://localhost:9090 | Prometheus |
| http://localhost:8474 | Toxiproxy API (inject latency, cut connections) |
| http://localhost:8091 | notification-1 (Phase 6+): `/admin/kafka-lab/*`, `/api/alerts` (Phase 8) |
| http://localhost:8085 | Kafka UI (Phase 6+): topics, consumer groups, messages |
| http://localhost:3200 | Tempo (Phase 8): traces, explored through Grafana → Explore → Tempo |
| http://localhost:9093 | Alertmanager (Phase 8): what's firing, silences |
| localhost:5432 | Postgres (`quickdrop` / `quickdrop`) |

```
   k6 ──▶ nginx ──▶ core-1 (core-2, core-3 from Phase 3) ──▶ Toxiproxy ──▶ Postgres
                         │                                       └──────▶ vendor-mock (SMS)
                         └── /actuator/prometheus ◀── Prometheus ◀── postgres-exporter, cAdvisor
                                                          │
                                         Grafana ◀────────┴──── Loki ◀── Alloy (container logs)
```

Later phases add Redis and two more core instances (Phase 3), a replica and shards (Phase 5), Kafka and
notification-service (Phase 6), and Tempo plus Alertmanager (Phase 8). `make up PHASE=n` starts exactly what a
phase needs.

Every container has **CPU and memory caps** (each core instance gets 1 CPU, Postgres 2). On an
unconstrained 16-core laptop nothing would ever saturate, and saturation is the point.

---

## Repository layout

```
services/core-service/         orders, inventory, riders, matching — all the strategies live here
services/notification-service/ SMS delivery (Phase 6), the Kafka lab (Phase 7), the alert webhook (Phase 8)
services/vendor-mock/          fake SMS vendor with latency / error / rate-limit / queueing knobs
libs/quickdrop-common/         toggles, strategy selection, chaos knobs, retries, tracing filters
infra/                         docker-compose.yml and every config file (Prometheus + alert rules, Alertmanager,
                               Grafana, Tempo, Loki, nginx, Toxiproxy, Postgres)
infra/grafana/gen_dashboards.py   dashboards as code (make dashboards)
loadtest/k6/                   load scripts, one per scenario
scripts/                       helpers: toggles, toxics, the Kafka acks lab (K6), game day (8.5)
scenarios/                     runbooks, one per scenario
docs/                          failure tracker, build log
```

Start reading the code at `OrderService.java`. Its class comment shows the whole order flow,
and every strategy class says in its first line whether it is BROKEN or a FIX, and why.

---

## Troubleshooting

- **Port already in use**: something else is on 5432/8080/3000. Postgres can move: `PG_PORT=5433 make up`.
- **Linux with SELinux (Fedora, RHEL)**: handled. Containers that bind-mount config use `label=disable`.
- **`permission denied` on the Docker socket**: add yourself to the `docker` group and log in again.
- **A scenario doesn't reproduce**: the runbook says what to expect and which knob widens the race
  window. A cold JVM (right after `make up`) races more than a warm one. That isn't a bug, it's the lesson.
- **Building outside Docker**: needs JDK 21. `./mvnw package`, or `make mvn ARGS="-DskipTests package"`
  to use a JDK container.
