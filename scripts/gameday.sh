#!/usr/bin/env bash
# Scenario 8.5 — game day: one hidden fault, found with dashboards, traces, logs and alerts only.
#
#   make load ID=8.5          # terminal 1: 15 minutes of normal traffic with a healthy configuration
#   make gameday              # terminal 2: injects ONE random fault and prints the incident ticket
#   make gameday-reveal       # when you have a diagnosis: what was it, and how long did you take?
#   make gameday-end          # undo the fault
#
# GAMEDAY_FAULT=3 make gameday picks a specific one (for practising). Needs Phase 8 for alerts and traces.
set -euo pipefail
cd "$(dirname "$0")/.."
STATE=.gameday
CORES=(8081 8082 8083)
NOTIFY=(8091 8092)

toggle() { curl -sf -X PUT "localhost:$1/admin/toggles/$2?value=$3" >/dev/null; }
all_cores() { for p in "${CORES[@]}"; do toggle "$p" "$1" "$2"; done; }
toxiproxy() {
  if [[ $# -ge 3 ]]; then
    curl -sf -X "$1" "localhost:8474$2" -H 'Content-Type: application/json' -d "$3" >/dev/null
  else
    curl -sf -X "$1" "localhost:8474$2" >/dev/null
  fi
}

FAULTS=(
  "slow-database|Postgres answers 150 ms slower (network latency between the services and the database)"
  "slow-instance|core-2 takes 1.5 s longer for every request (a noisy neighbour on its host)"
  "bad-deploy|core-3 fails 40% of checkouts with HTTP 500 (a bad deploy on one instance)"
  "redis-gone|Redis is unreachable (the network path to it was cut)"
  "vendor-brownout|the SMS vendor takes 4 s per message"
  "small-pool|every core instance runs with only 2 database connections (a config change)"
  "consumers-frozen|both notification-service instances are frozen (docker pause): nothing consumes Kafka"
  "missing-index|the index on orders(customer_id, created_at) was dropped (a migration 'cleanup')"
  "poison-pill|a malformed message is stuck at the head of a notifications partition, retried forever"
  "thread-starvation|core-1 runs with 3 Tomcat threads (someone 'tuned' it)"
)

inject() {
  case "$1" in
    slow-database) toxiproxy POST /proxies/postgres/toxics '{"name":"gameday","type":"latency","stream":"downstream","attributes":{"latency":150}}' ;;
    slow-instance) toggle 8082 request-delay-ms 1500 ;;
    bad-deploy) toggle 8083 error-scope checkout; toggle 8083 error-percent 40 ;;
    redis-gone) toxiproxy POST /proxies/redis '{"enabled":false}' ;;
    vendor-brownout) toggle 8090 latency-ms 4000 ;;
    small-pool) all_cores db-pool-size 2 ;;
    consumers-frozen) docker pause qd-notification-1 qd-notification-2 >/dev/null ;;
    missing-index) curl -sf -X POST "localhost:8081/admin/schema/orders-customer-index?action=drop" >/dev/null ;;
    poison-pill) for p in "${NOTIFY[@]}"; do toggle "$p" poison-handling retry-forever; done
                 curl -sf -X POST "localhost:8091/admin/kafka-lab/poison?topic=notifications&key=gameday" >/dev/null ;;
    thread-starvation) toggle 8081 tomcat-max-threads 3 ;;
  esac
}

revert() {
  case "$1" in
    slow-database) toxiproxy DELETE /proxies/postgres/toxics/gameday || true ;;
    slow-instance) toggle 8082 request-delay-ms 0 ;;
    bad-deploy) toggle 8083 error-percent 0 ;;
    redis-gone) toxiproxy POST /proxies/redis '{"enabled":true}' ;;
    vendor-brownout) toggle 8090 latency-ms 100 ;;
    small-pool) all_cores db-pool-size 10 ;;
    consumers-frozen) docker unpause qd-notification-1 qd-notification-2 >/dev/null 2>&1 || true ;;
    missing-index) curl -sf -X POST "localhost:8081/admin/schema/orders-customer-index?action=create" >/dev/null ;;
    poison-pill) for p in "${NOTIFY[@]}"; do toggle "$p" poison-handling dlq; done ;;   # unblocks: the poison goes to the DLT
    thread-starvation) toggle 8081 tomcat-max-threads 50 ;;
  esac
}

case "${1:-start}" in
  start)
    if [[ -f $STATE ]]; then echo "A game day is already running. make gameday-reveal / make gameday-end first."; exit 1; fi
    n=${GAMEDAY_FAULT:-$(( RANDOM % ${#FAULTS[@]} + 1 ))}
    id=${FAULTS[$((n - 1))]%%|*}
    inject "$id"
    printf '%s %s\n' "$(date +%s)" "$(echo -n "$id" | base64)" > $STATE
    cat <<'TICKET'

  ┌──────────────────────────────────────────────────────────────────────────────┐
  │ INCIDENT  sev-2                                                              │
  │ Something changed in production a moment ago. Customers may be affected.     │
  │                                                                              │
  │ Find out what is wrong using only what an on-call engineer has:              │
  │   Grafana http://localhost:3000   (start at Overview, then Operating)         │
  │   Alerts  http://localhost:9093   and  curl localhost:8091/api/alerts         │
  │   Traces  Grafana → Explore → Tempo        Logs  Grafana → Explore → Loki     │
  │ No reading toggles, no docker ps. Write down: impact, cause, evidence.        │
  │                                                                              │
  │   make gameday-reveal    when you have a diagnosis                            │
  │   make gameday-end       to undo the fault                                   │
  └──────────────────────────────────────────────────────────────────────────────┘
TICKET
    ;;
  reveal)
    [[ -f $STATE ]] || { echo "No game day running (make gameday)."; exit 1; }
    read -r started encoded < $STATE
    id=$(echo "$encoded" | base64 -d)
    for f in "${FAULTS[@]}"; do [[ ${f%%|*} == "$id" ]] && desc=${f#*|}; done
    echo
    echo "  The fault: $id — $desc"
    echo "  Time since it was injected: $(( ($(date +%s) - started) / 60 )) min $(( ($(date +%s) - started) % 60 )) s"
    echo "  Postmortem questions in scenarios/phase-8/8.5-game-day.md. Undo it with: make gameday-end"
    ;;
  end)
    [[ -f $STATE ]] || { echo "No game day running."; exit 0; }
    read -r _ encoded < $STATE
    id=$(echo "$encoded" | base64 -d)
    revert "$id"
    rm -f $STATE
    echo "  Reverted: $id"
    ;;
  *) echo "usage: scripts/gameday.sh start|reveal|end"; exit 1 ;;
esac
