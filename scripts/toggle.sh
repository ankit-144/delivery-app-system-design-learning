#!/usr/bin/env bash
# Flip a toggle on every running core instance (or on the vendor with --vendor).
#
#   scripts/toggle.sh                                   # list toggles on every instance
#   scripts/toggle.sh inventory-reservation atomic      # set on core-1..3 (whichever are up)
#   scripts/toggle.sh --vendor latency-ms 1000          # make the SMS vendor slow
#   scripts/toggle.sh --only core-2 request-delay-ms 300
#   scripts/toggle.sh --reset                           # every toggle back to its default
set -euo pipefail

declare -A PORTS=([core-1]=8081 [core-2]=8082 [core-3]=8083 [vendor]=8090)
targets=(core-1 core-2 core-3)

reset=false
if [[ "${1:-}" == "--reset" ]]; then reset=true; targets=(core-1 core-2 core-3 vendor); shift; fi
if [[ "${1:-}" == "--vendor" ]]; then targets=(vendor); shift; fi
if [[ "${1:-}" == "--only" ]]; then targets=("$2"); shift 2; fi

name="${1:-}"
value="${2:-}"

for target in "${targets[@]}"; do
  url="http://localhost:${PORTS[$target]}"
  if ! curl -sf -o /dev/null --max-time 2 "$url/actuator/health"; then
    [[ ${#targets[@]} -eq 1 ]] && echo "$target is not running" >&2
    continue
  fi
  if $reset; then
    curl -s -o /dev/null -X POST "$url/admin/toggles/reset" && echo "$target: toggles reset"
  elif [[ -z "$name" ]]; then
    echo "== $target"
    curl -s "$url/admin/toggles" | python3 -c '
import json, sys
for t in json.load(sys.stdin)["toggles"]:
    marker = "" if t["value"] == t["defaultValue"] else "   (default " + t["defaultValue"] + ")"
    print("  " + t["name"].ljust(28) + " " + t["value"] + marker)'
  else
    printf '%-8s ' "$target"
    curl -s -X PUT "$url/admin/toggles/$name?value=$value"
    echo
  fi
done
