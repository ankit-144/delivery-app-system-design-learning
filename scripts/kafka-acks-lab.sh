#!/usr/bin/env bash
# Kafka deep dive K6 — what does an acknowledged write promise? (needs the stack at Phase 6+)
#
#   make load ID=K6 MODE=broken    # acks=1:   the leader acknowledges as soon as IT has the message
#   make load ID=K6 MODE=fixed     # acks=all: the leader acknowledges once every in-sync replica has it
#
# Starts a separate 3-broker cluster (profile kafka-cluster, ~1.6 GB of RAM) and runs two experiments on a topic
# with replication factor 3 and min.insync.replicas=2:
#   1. produce 20,000 messages/s; the two followers stall for 3 s (docker pause: a GC pause, a network blip), and
#      while they're behind, the leader is killed with kill -9. Then count acknowledged messages that are gone.
#   2. kill two of the three brokers and try to write
# The cluster stays up afterwards; stop it with  make kafka-cluster-down
# k6 can't kill containers, which is why this lab is a shell script.
set -euo pipefail
cd "$(dirname "$0")/.."

MODE=${1:-broken}
case "$MODE" in
  broken) ACKS=1 ;;
  fixed) ACKS=all ;;
  *) echo "MODE must be broken (acks=1) or fixed (acks=all)"; exit 1 ;;
esac
RATE=${RATE:-20000}
COMPOSE="docker compose -f infra/docker-compose.yml --profile kafka-cluster"
LAB=${NOTIFY_URL:-http://localhost:8091}/admin/kafka-lab
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"; docker unpause qd-kafka-a qd-kafka-b qd-kafka-c >/dev/null 2>&1 || true' EXIT

container() { echo "qd-kafka-$(echo abc | cut -c"$1")"; }           # broker id 1..3 → qd-kafka-a..c
live() {                                                             # a broker that's running and not paused
  for c in qd-kafka-a qd-kafka-b qd-kafka-c; do
    [[ "$(docker inspect -f '{{.State.Running}} {{.State.Paused}}' "$c" 2>/dev/null)" == "true false" ]] && { echo "$c"; return; }
  done
}
topics() { docker exec "$(live)" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 "$@"; }
partition() { topics --describe --topic acks-lab 2>/dev/null | grep -o 'Leader: .*Isr: [0-9,]*' || echo "(not available)"; }
leader() { topics --describe --topic acks-lab | grep -o 'Leader: [0-9]*' | awk '{print $2}'; }
json() { python3 -c "import json,sys; d=json.load(open('$1')); print($2)"; }

wait_healthy() {
  for c in qd-kafka-a qd-kafka-b qd-kafka-c; do
    for _ in $(seq 1 60); do
      [[ "$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null)" == healthy ]] && break
      sleep 2
    done
  done
}

wait_full_isr() {
  for _ in $(seq 1 30); do   # each check starts a JVM (~3 s)
    partition | grep -qE 'Isr: [0-9]+,[0-9]+,[0-9]+' && return 0
    sleep 2
  done
  echo "  (the ISR did not get back to 3 replicas within 2 minutes)"
}

start_all() {
  for c in qd-kafka-a qd-kafka-b qd-kafka-c; do docker start "$c" >/dev/null; done
  wait_healthy
  wait_full_isr
}

echo "==> starting the 3-broker cluster (kafka-ctl + kafka-a/b/c)"
$COMPOSE up -d kafka-ctl kafka-a kafka-b kafka-c >/dev/null 2>&1
for c in qd-kafka-a qd-kafka-b qd-kafka-c; do docker start "$c" >/dev/null; done   # brokers a previous run left killed
wait_healthy

echo "==> fresh topic acks-lab: 1 partition, replication factor 3, min.insync.replicas=2"
topics --delete --topic acks-lab >/dev/null 2>&1 || true
for _ in $(seq 1 30); do
  topics --create --topic acks-lab --partitions 1 --replication-factor 3 --config min.insync.replicas=2 >/dev/null 2>&1 && break
  sleep 2
done
wait_full_isr
echo "    $(partition)"

echo
echo "==> experiment 1: acks=$ACKS, $RATE messages/s for 20 s"
L=$(leader)
FOLLOWERS=()
for id in 1 2 3; do [[ "$id" == "$L" ]] || FOLLOWERS+=("$(container "$id")"); done
curl -s -X POST "$LAB/acks/produce?acks=$ACKS&rate=$RATE&seconds=20" > "$OUT/produce.json" &
PRODUCER=$!
sleep 5
echo "    t=5s   the followers (${FOLLOWERS[*]}) stall for 3 s: docker pause"
docker pause "${FOLLOWERS[@]}" >/dev/null
sleep 3
echo "    t=8s   kill -9 the leader, broker $L: docker kill $(container "$L"); the followers resume"
docker kill "$(container "$L")" >/dev/null
docker unpause "${FOLLOWERS[@]}" >/dev/null
sleep 9
echo "    t=17s  $(partition)"
wait $PRODUCER
echo "    producer finished; restarting broker $L and waiting for it to catch up"
start_all
echo "    $(partition)"
curl -s "$LAB/acks/verify" > "$OUT/verify.json"

echo
echo "==> experiment 2: kill two of the three brokers, then write 1,000 messages/s for 10 s with acks=$ACKS"
L=$(leader)
for id in 1 2 3; do
  [[ "$id" == "$L" ]] || docker kill "$(container "$id")" >/dev/null
done
sleep 9   # the controller fences a dead broker after broker.session.timeout.ms (6 s); it leaves the ISR
echo "    $(partition)"
curl -s -X POST "$LAB/acks/produce?acks=$ACKS&rate=1000&seconds=10&deliveryTimeoutMs=8000" > "$OUT/produce2.json"
start_all

echo
echo "================ K6 — acks=$ACKS, replication factor 3, min.insync.replicas=2 ================"
echo "  experiment 1 (followers stalled 3 s, then the leader killed):"
json "$OUT/verify.json" "f\"    sent {d['sent']:,}   acknowledged {d['acked']:,}   in the topic afterwards {d['distinctInTopic']:,} distinct ({d['duplicatesInTopic']:,} duplicates)\""
json "$OUT/verify.json" "f\"    ACKNOWLEDGED BUT LOST: {d['ackedButLost']:,}      reported failed but actually stored: {d['reportedFailedButStored']:,}\""
json "$OUT/produce.json" "f\"    producer errors: {d['errors'] or 'none'}\""
json "$OUT/produce.json" "'    acknowledged per second: ' + ', '.join(f'{int(k)}s {v.split()[0]}' for k, v in list(d['perSecond'].items())[3:20])"
echo "  experiment 2 (two of three brokers down):"
json "$OUT/produce2.json" "f\"    sent {d['sent']:,}   acknowledged {d['acked']:,}   failed {d['failed']:,}   errors: {d['errors'] or 'none'}\""
echo
echo "  The cluster is still running (~1.6 GB). Stop it with: make kafka-cluster-down"
