#!/usr/bin/env python3
"""Runs every PromQL query from the generated dashboards against Prometheus and reports
queries that fail or return nothing. Run it with traffic flowing.

    python3 scripts/check_dashboards.py [http://localhost:9090]
"""
import json
import pathlib
import sys
import urllib.parse
import urllib.request

prom = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:9090"
root = pathlib.Path(__file__).resolve().parent.parent / "infra" / "grafana" / "dashboards"
bad = 0
for path in sorted(root.glob("*.json")):
    dashboard = json.loads(path.read_text())
    for panel in dashboard["panels"]:
        for target in panel.get("targets", []):
            expr = target.get("expr")
            if not expr or target.get("datasource", {}).get("type") != "prometheus":
                continue
            query = expr.replace("$instance", ".*").replace("$__rate_interval", "1m")
            url = f"{prom}/api/v1/query?" + urllib.parse.urlencode({"query": query})
            try:
                result = json.load(urllib.request.urlopen(url))
                count = len(result["data"]["result"])
                status = "ok   " if count else "EMPTY"
            except Exception as e:  # noqa: BLE001
                status, count = "ERROR", str(e)
            if status != "ok   ":
                bad += 1
            print(f"{status} {path.stem:11} {panel['title'][:45]:45} {count}  {expr[:90]}")
sys.exit(1 if bad else 0)
