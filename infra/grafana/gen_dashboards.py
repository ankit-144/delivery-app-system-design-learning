#!/usr/bin/env python3
"""Generates the Grafana dashboards in infra/grafana/dashboards/.

Dashboards are written as code so they stay consistent and reviewable. Grafana loads the
generated JSON files on startup (and re-reads them every 10 seconds).

    python3 infra/grafana/gen_dashboards.py

Every panel has a description ("what to look for") shown when you hover the (i) icon.
Only the Python standard library is used.
"""
import json
import pathlib

OUT = pathlib.Path(__file__).parent / "dashboards"
PROM = {"type": "prometheus", "uid": "prometheus"}
LOKI = {"type": "loki", "uid": "loki"}

TOGGLE_ANNOTATIONS = {
    "list": [
        {
            "builtIn": 1,
            "datasource": {"type": "grafana", "uid": "-- Grafana --"},
            "enable": True,
            "hide": True,
            "iconColor": "rgba(0, 211, 255, 1)",
            "name": "Annotations & Alerts",
            "type": "dashboard",
        },
        {
            "datasource": {"type": "grafana", "uid": "-- Grafana --"},
            "enable": True,
            "iconColor": "orange",
            "name": "Toggle changes",
            "target": {"type": "tags", "tags": ["toggle"], "matchAny": True, "limit": 200},
        },
    ]
}


class Layout:
    """Places panels left to right on Grafana's 24-column grid, wrapping to the next row."""

    def __init__(self):
        self.x = 0
        self.y = 0
        self.row_height = 0
        self.next_id = 1
        self.panels = []

    def _place(self, panel, w, h):
        if self.x + w > 24:
            self.x = 0
            self.y += self.row_height
            self.row_height = 0
        panel["gridPos"] = {"x": self.x, "y": self.y, "w": w, "h": h}
        panel["id"] = self.next_id
        self.next_id += 1
        self.x += w
        self.row_height = max(self.row_height, h)
        self.panels.append(panel)

    def row(self, title):
        if self.x:
            self.x = 0
            self.y += self.row_height
            self.row_height = 0
        self._place({"type": "row", "title": title, "collapsed": False, "panels": []}, 24, 1)
        self.x = 0
        self.y += 1
        self.row_height = 0

    def add(self, panel, w=12, h=8):
        self._place(panel, w, h)


def t(expr, legend="", instant=False, fmt=None, exemplar=False):
    target = {"datasource": PROM, "expr": expr, "legendFormat": legend, "refId": "A"}
    if exemplar:
        target["exemplar"] = True   # Phase 8: dots carrying the trace id of one real request; click → Tempo
    if instant:
        target["instant"] = True
        target["range"] = False
    if fmt:
        target["format"] = fmt
    return target


def _with_ref_ids(targets):
    for i, target in enumerate(targets):
        target["refId"] = chr(ord("A") + i)
    return targets


def timeseries(title, targets, unit="short", desc="", stack=False, min_=None, max_=None,
               thresholds=None, overrides=None, fill=10, legend_calcs=("lastNotNull", "max")):
    defaults = {
        "unit": unit,
        "custom": {
            "drawStyle": "line",
            "lineWidth": 2,
            "fillOpacity": 30 if stack else fill,
            "showPoints": "never",
            "spanNulls": True,
            "stacking": {"mode": "normal" if stack else "none"},
        },
    }
    if min_ is not None:
        defaults["min"] = min_
    if max_ is not None:
        defaults["max"] = max_
    if thresholds:
        defaults["thresholds"] = {"mode": "absolute", "steps": thresholds}
        defaults["custom"]["thresholdsStyle"] = {"mode": "line+area" if len(thresholds) > 1 else "off"}
    return {
        "type": "timeseries",
        "title": title,
        "description": desc,
        "datasource": PROM,
        "targets": _with_ref_ids(targets),
        "fieldConfig": {"defaults": defaults, "overrides": overrides or []},
        "options": {
            "legend": {"displayMode": "table", "placement": "bottom", "calcs": list(legend_calcs)},
            "tooltip": {"mode": "multi", "sort": "desc"},
        },
    }


def stat(title, targets, unit="short", desc="", steps=None, color_mode="background", text_mode="auto"):
    return {
        "type": "stat",
        "title": title,
        "description": desc,
        "datasource": PROM,
        "targets": _with_ref_ids(targets),
        "fieldConfig": {
            "defaults": {
                "unit": unit,
                "thresholds": {"mode": "absolute", "steps": steps or [{"color": "green", "value": None}]},
                "color": {"mode": "thresholds"},
            },
            "overrides": [],
        },
        "options": {
            "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
            "colorMode": color_mode,
            "graphMode": "none",
            "textMode": text_mode,
            "justifyMode": "auto",
        },
    }


def table(title, targets, desc="", organize=None):
    panel = {
        "type": "table",
        "title": title,
        "description": desc,
        "datasource": PROM,
        "targets": _with_ref_ids(targets),
        "fieldConfig": {"defaults": {}, "overrides": []},
        "options": {"showHeader": True, "cellHeight": "sm"},
        "transformations": [],
    }
    if organize:
        panel["transformations"].append({"id": "organize", "options": organize})
    return panel


def logs(title, expr, desc=""):
    return {
        "type": "logs",
        "title": title,
        "description": desc,
        "datasource": LOKI,
        "targets": [{"datasource": LOKI, "expr": expr, "refId": "A"}],
        "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending", "enableLogDetails": True},
    }


def text(title, content):
    return {"type": "text", "title": title, "options": {"mode": "markdown", "content": content}}


def instance_var(job):
    return {
        "name": "instance",
        "label": "instance",
        "type": "query",
        "datasource": PROM,
        "query": {"query": f'label_values(up{{job="{job}"}}, instance)', "refId": "v"},
        "definition": f'label_values(up{{job="{job}"}}, instance)',
        "refresh": 2,
        "includeAll": True,
        "multi": True,
        "current": {"selected": True, "text": ["All"], "value": ["$__all"]},
        "sort": 1,
    }


def dashboard(uid, title, layout, description="", variables=None, refresh="5s", time_from="now-15m"):
    return {
        "uid": uid,
        "title": title,
        "description": description,
        "tags": ["quickdrop"],
        "timezone": "browser",
        "editable": True,
        "graphTooltip": 1,  # shared crosshair: hover one graph, see the same moment on all of them
        "refresh": refresh,
        "time": {"from": time_from, "to": "now"},
        "schemaVersion": 39,
        "annotations": TOGGLE_ANNOTATIONS,
        "templating": {"list": variables or []},
        "links": [
            {"title": "QuickDrop dashboards", "type": "dashboards", "tags": ["quickdrop"], "asDropdown": True,
             "includeVars": False, "keepTime": True},
        ],
        "panels": layout.panels,
    }


RED = "red"
GREEN = "green"
ORANGE = "orange"

API = 'job="core", uri=~"/api/.*"'


# --------------------------------------------------------------------------- overview

def overview():
    L = Layout()
    L.add(text("How to read this", (
        "**Business first, machines second.** Top rows: is the product working (orders, correctness, latency)? "
        "Lower rows: what the load generator is doing. Drill into *Service saturation*, *Postgres* and *Containers* "
        "from the dashboard links (top right) to find **why**.\n\n"
        "Orange vertical lines are **toggle changes** — the moment someone flipped a strategy.")), 24, 3)

    L.row("Correctness — every number here must be 0")
    L.add(stat("Invariant violations", [t("max by (invariant) (quickdrop_invariant_violations)", "{{invariant}}", instant=True)],
               desc="Business rules checked every 5 s by SQL (see InvariantChecker.java). Anything above 0 means the data is wrong: "
                    "oversold stock, a rider on two orders at once, duplicate orders. Only data since the last fixtures reset is checked.",
               steps=[{"color": GREEN, "value": None}, {"color": RED, "value": 1}]), 24, 4)

    L.row("Orders")
    L.add(timeseries("Orders per second by result",
                     [t("sum by (result) (rate(quickdrop_orders_total[30s]))", "{{result}}")],
                     unit="reqps", stack=True,
                     desc="Every order attempt and how it ended. placed = got a rider; placed_pending_rider = no rider free yet; "
                          "replayed = duplicate request answered from the idempotency table; anything else is an exception class."), 12)
    L.add(timeseries("Order placement latency",
                     [t('histogram_quantile(0.50, sum by (le) (rate(quickdrop_order_placement_seconds_bucket[1m])))', "p50"),
                      t('histogram_quantile(0.95, sum by (le) (rate(quickdrop_order_placement_seconds_bucket[1m])))', "p95"),
                      t('histogram_quantile(0.99, sum by (le) (rate(quickdrop_order_placement_seconds_bucket[1m])))', "p99"),
                      t('sum(rate(quickdrop_order_placement_seconds_sum[1m])) / sum(rate(quickdrop_order_placement_seconds_count[1m]))', "average")],
                     unit="s",
                     desc="Time spent inside OrderService.placeOrder. The gap between p50 and p99 is where the pain hides: "
                          "averages look fine while 1 in 100 customers waits seconds (scenario 0.2)."), 12)
    L.add(timeseries("API errors by type",
                     [t("sum by (error) (rate(quickdrop_api_errors_total[30s]))", "{{error}}")],
                     unit="reqps", stack=True,
                     desc="Failures mapped to a stable code by ApiExceptionHandler: out_of_stock (expected), deadlock (1.4), "
                          "too_many_conflicts (1.5), db_pool_exhausted (2.2/2.3), lock_timeout, internal_error."), 12)
    L.add(timeseries("Riders",
                     [t("max by (status) (quickdrop_riders)", "{{status}}")],
                     stack=True,
                     desc="Rider pool by status. When AVAILABLE hits zero, new orders wait in PENDING_RIDER."), 6)
    L.add(timeseries("Rider assignment",
                     [t("sum by (result) (rate(quickdrop_rider_assignments_total[30s]))", "{{result}}"),
                      t("sum(rate(quickdrop_rider_assignment_lost_races_total[30s]))", "lost races (conditional)")],
                     unit="ops",
                     desc="Lost races only exist with rider-assignment=conditional: the update found the rider already taken "
                          "and moved on to the next candidate. With naive, those races silently double-book riders."), 6)

    L.row("HTTP — RED per endpoint (Rate, Errors, Duration)")
    L.add(timeseries("Requests per second",
                     [t(f"sum by (method, uri) (rate(http_server_requests_seconds_count{{{API}}}[30s]))", "{{method}} {{uri}}")],
                     unit="reqps"), 8)
    L.add(timeseries("5xx error ratio",
                     [t(f'sum by (uri) (rate(http_server_requests_seconds_count{{{API}, status=~"5.."}}[30s])) '
                        f'/ sum by (uri) (rate(http_server_requests_seconds_count{{{API}}}[30s]))', "{{uri}}")],
                     unit="percentunit", min_=0,
                     desc="Share of requests that failed on the server side. 4xx (out of stock, conflicts) are business answers, not errors."), 8)
    L.add(timeseries("p99 latency",
                     [t(f"histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket{{{API}}}[1m])))", "{{uri}}",
                        exemplar=True)],
                     unit="s",
                     desc="From Phase 8 the dots are exemplars: individual requests with their trace id. Click one to open the trace in Tempo."), 8)
    L.add(timeseries("Requests per second by instance",
                     [t(f"sum by (instance) (rate(http_server_requests_seconds_count{{{API}}}[30s]))", "{{instance}}")],
                     unit="reqps",
                     desc="How the load balancer spreads traffic (Phase 3)."), 6)
    L.add(timeseries("p99 latency by instance",
                     [t(f"histogram_quantile(0.99, sum by (le, instance) (rate(http_server_requests_seconds_bucket{{{API}}}[1m])))", "{{instance}}")],
                     unit="s",
                     desc="One line far above the others = one sick instance (3.7, game day): with round-robin it still gets a third of the traffic."), 6)
    L.add(timeseries("SMS sent by mode and result",
                     [t("sum by (mode, result) (rate(quickdrop_notifications_total[30s]))", "{{mode}} {{result}}")],
                     unit="ops",
                     desc="Order confirmations and OTPs. 'failed' = vendor error or timeout; 'dropped_queue_full' = async queue limit."), 12)

    L.row("Load generator (k6)")
    L.add(timeseries("Virtual users", [t("sum(k6_vus)", "VUs")]), 6)
    L.add(timeseries("k6 requests per second by status",
                     [t("sum by (status) (rate(k6_http_reqs_total[30s]))", "{{status}}")], unit="reqps", stack=True), 9)
    L.add(timeseries("k6 request duration (client side)",
                     [t("max(k6_http_req_duration_p95)", "p95"), t("max(k6_http_req_duration_p99)", "p99")],
                     unit="s",
                     desc="What the customer experiences, measured by k6. Includes nginx and queueing in front of Tomcat, "
                          "which the server-side timers cannot see."), 9)

    L.row("Toggles in effect")
    L.add(table("Current toggles per instance",
                [t("max by (instance, toggle, value) (quickdrop_toggle_info)", instant=True, fmt="table")],
                desc="Which strategy each instance runs right now. After a flip with scripts/toggle.sh every instance should agree.",
                organize={"excludeByName": {"Time": True, "Value": True}, "indexByName": {"instance": 0, "toggle": 1, "value": 2}}), 24, 9)

    return dashboard("quickdrop-overview", "QuickDrop — Overview", L,
                     description="Is the product working? Orders, correctness, latency, errors.")


# --------------------------------------------------------------------------- service saturation

def saturation():
    L = Layout()
    I = 'instance=~"$instance"'
    L.add(text("USE: Utilisation, Saturation, Errors", (
        "For every resource a request needs — **CPU, Tomcat threads, DB connections, the vendor** — ask: how busy is it, "
        "is work queueing for it, is it failing? The resource that saturates first is your bottleneck. "
        "Fix it and the bottleneck **moves** (scenario 2.8).")), 24, 3)

    L.row("Request threads (Tomcat)")
    L.add(timeseries("Busy threads vs max",
                     [t(f"tomcat_threads_busy_threads{{{I}}}", "busy {{instance}}"),
                      t(f"tomcat_threads_config_max_threads{{{I}}}", "max {{instance}}")],
                     desc="Each in-flight request holds one thread. When busy = max, new requests queue in the accept backlog and "
                          "latency explodes — even for /actuator/health (scenario 2.1). Virtual threads remove this limit (2.8)."), 12)
    L.add(timeseries("Open connections",
                     [t(f"tomcat_connections_current_connections{{{I}}}", "{{instance}}")],
                     desc="TCP connections Tomcat is holding. Grows past busy threads when requests are queueing."), 12)

    L.row("Database connection pool (HikariCP)")
    L.add(timeseries("Connections: active / idle / waiting",
                     [t(f"hikaricp_connections_active{{{I}}}", "active {{instance}}"),
                      t(f"hikaricp_connections_idle{{{I}}}", "idle {{instance}}"),
                      t(f"hikaricp_connections_pending{{{I}}}", "waiting threads {{instance}}"),
                      t(f"hikaricp_connections_max{{{I}}}", "max {{instance}}")],
                     desc="active = max and 'waiting threads' > 0 means requests are queueing for a connection. "
                          "After connection-timeout they fail with db_pool_exhausted (scenarios 2.2, 2.3)."), 12)
    L.add(timeseries("Time to get a connection / time holding one",
                     [t(f"hikaricp_connections_acquire_seconds_max{{{I}}}", "max wait {{instance}}"),
                      t(f"hikaricp_connections_usage_seconds_max{{{I}}}", "max held {{instance}}")],
                     unit="s",
                     desc="'max held' jumping to ~1 s means something slow happens while a connection is checked out — like calling "
                          "the SMS vendor inside a transaction (2.2)."), 6)
    L.add(timeseries("Pool timeouts",
                     [t(f"rate(hikaricp_connections_timeout_total{{{I}}}[30s])", "{{instance}}")],
                     unit="ops", desc="Requests that gave up waiting for a connection."), 6)

    L.row("CPU and memory")
    L.add(timeseries("Container CPU (cores) vs limit",
                     [t('sum by (container) (rate(container_cpu_usage_seconds_total{container=~"core-.*"}[30s]))', "{{container}}"),
                      t('max by (container) (container_spec_cpu_quota{container=~"core-.*"} / container_spec_cpu_period{container=~"core-.*"})', "limit {{container}}")],
                     desc="Each core instance is capped at 1 CPU. Flat at the limit = CPU-bound (scenario 2.6)."), 8)
    L.add(timeseries("CPU throttling",
                     [t('sum by (container) (rate(container_cpu_cfs_throttled_periods_total{container=~"core-.*"}[30s])) '
                        '/ sum by (container) (rate(container_cpu_cfs_periods_total{container=~"core-.*"}[30s]))', "{{container}}")],
                     unit="percentunit", min_=0,
                     desc="Share of 100 ms scheduling periods in which the container wanted more CPU than its quota. "
                          "Throttling adds latency in 100 ms steps even when average CPU looks fine."), 8)
    L.add(timeseries("JVM heap",
                     [t(f'sum by (instance) (jvm_memory_used_bytes{{area="heap", {I}}})', "used {{instance}}"),
                      t(f'sum by (instance) (jvm_memory_max_bytes{{area="heap", {I}}})', "max {{instance}}")],
                     unit="bytes",
                     desc="Climbing heap that never comes back down after GC = something is accumulating (e.g. the unbounded "
                          "async notification queue, scenario 6.3)."), 8)
    L.add(timeseries("GC time per second",
                     [t(f"sum by (instance) (rate(jvm_gc_pause_seconds_sum{{{I}}}[30s]))", "{{instance}}")],
                     unit="s", desc="Fraction of each second spent in GC pauses."), 8)
    L.add(timeseries("JVM threads by state",
                     [t(f'sum by (state) (jvm_threads_states_threads{{{I}}})', "{{state}}")], stack=True,
                     desc="Many TIMED_WAITING/WAITING threads = threads parked on locks, sleeps or I/O (synchronized strategy, slow vendor)."), 8)
    L.add(timeseries("JPA statements per request (p95)",
                     [t(f'histogram_quantile(0.95, sum by (le, uri) (rate(quickdrop_jpa_statements_bucket{{{I}}}[1m])))', "{{uri}}")],
                     desc="N+1 detector (scenario 2.5): the order history endpoint jumps from ~2 to dozens of statements per request."), 8)

    L.row("Downstream: SMS vendor")
    L.add(timeseries("Vendor call latency (client side)",
                     [t(f'histogram_quantile(0.99, sum by (le, instance) (rate(http_client_requests_seconds_bucket{{job="core", {I}}}[1m])))', "p99 {{instance}}"),
                      t(f'histogram_quantile(0.50, sum by (le, instance) (rate(http_client_requests_seconds_bucket{{job="core", {I}}}[1m])))', "p50 {{instance}}")],
                     unit="s", desc="How long core waits for the vendor. Compare with the vendor's own view on the right."), 8)
    L.add(timeseries("Vendor: messages by result",
                     [t("sum by (result) (rate(vendor_messages_total[30s]))", "{{result}}"),
                      t("sum(rate(vendor_duplicate_messages_total[30s]))", "duplicates")],
                     unit="ops", stack=False,
                     desc="The vendor's view. duplicates = the same messageId arrived twice: a customer got the same SMS twice."), 8)
    L.add(timeseries("Vendor in-flight / async queue",
                     [t("sum(vendor_in_flight)", "vendor in flight"),
                      t(f"quickdrop_notifications_queue_size{{{I}}}", "async queue {{instance}}")],
                     desc="In-flight at the vendor rises with its latency (Little's law: concurrency = rate × latency)."), 8)

    return dashboard("quickdrop-saturation", "QuickDrop — Service saturation", L,
                     description="Threads, connection pools, CPU, memory, downstream calls — where requests wait.",
                     variables=[instance_var("core")])


# --------------------------------------------------------------------------- postgres

def postgres():
    L = Layout()
    DB = 'datname="quickdrop"'
    L.add(text("Postgres", (
        "Every core instance shares this one database. Connections, locks and CPU here are a **shared** budget: "
        "adding app instances adds load here (Phase 3), and one slow query hurts everyone. "
        "Locks show *contention* (Phase 1); seq scans show *missing indexes* (2.4); idle-in-transaction shows "
        "connections held while the app does something else (2.2).")), 24, 3)

    L.row("Connections")
    L.add(timeseries("Connections by state",
                     [t("sum by (state) (pg_connections_count)", "{{state}}"),
                      t("max(pg_settings_max_connections)", "max_connections")],
                     stack=False,
                     desc="'idle in transaction' = a connection inside an open transaction, doing nothing on the DB — the app is "
                          "busy elsewhere (calling a vendor?) while holding locks and a pool slot."), 12)
    L.add(timeseries("Active connections by wait type",
                     [t('sum by (wait_event_type) (pg_connections_count{state="active"})', "{{wait_event_type}}")],
                     stack=True,
                     desc="What running queries are waiting on. 'Lock' = blocked by another transaction's row/table lock; "
                          "'none' = actually on CPU; 'IO' = reading from disk; 'Client' = waiting for the app."), 12)

    L.row("Throughput and locks")
    L.add(timeseries("Transactions per second",
                     [t(f"rate(pg_stat_database_xact_commit{{{DB}}}[30s])", "commits"),
                      t(f"rate(pg_stat_database_xact_rollback{{{DB}}}[30s])", "rollbacks")],
                     unit="ops",
                     desc="Rollbacks rise with out-of-stock answers, optimistic conflicts and deadlocks."), 8)
    L.add(timeseries("Sessions waiting for a lock",
                     [t("sum by (mode) (pg_lock_waits_waiting)", "{{mode}}")],
                     stack=True,
                     desc="Transactions queued behind someone else's lock right now. Row locks show up as ShareLock waits on a "
                          "transaction id or as tuple locks (scenario 1.1 pessimistic, 2.2 inline-in-tx)."), 8)
    L.add(timeseries("Deadlocks",
                     [t(f"increase(pg_stat_database_deadlocks{{{DB}}}[1m])", "deadlocks per minute")],
                     desc="Postgres found a lock cycle and killed one transaction (scenario 1.4). Detection takes deadlock_timeout = 1 s, "
                          "so each deadlock also costs a second of waiting."), 8)
    L.add(timeseries("Rows per second",
                     [t(f"rate(pg_stat_database_tup_fetched{{{DB}}}[30s])", "fetched"),
                      t(f"rate(pg_stat_database_tup_inserted{{{DB}}}[30s])", "inserted"),
                      t(f"rate(pg_stat_database_tup_updated{{{DB}}}[30s])", "updated")],
                     unit="ops"), 8)
    L.add(timeseries("Rows read by sequential scans, per table",
                     [t("topk(5, rate(pg_table_scans_seq_tup_read[1m]))", "{{relname}}")],
                     unit="ops",
                     desc="A table read end to end on every query. Millions of rows per second on 'orders' = missing index (scenario 2.4)."), 8)
    L.add(timeseries("Longest open transaction",
                     [t(f"max(pg_stat_activity_max_tx_duration{{{DB}}})", "oldest transaction age")],
                     unit="s",
                     desc="A long transaction holds its locks and blocks vacuum. Phase 4: one long transaction + an ALTER TABLE freezes everything."), 8)

    L.row("Queries (pg_stat_statements)")
    L.add(timeseries("Top queries by total time",
                     [t("topk(8, sum by (query) (rate(pg_stat_statements_seconds_total{datname=\"quickdrop\"}[1m])))", "{{query}}")],
                     unit="s", legend_calcs=("lastNotNull",),
                     desc="Seconds of database time per second spent in each statement. The top line is where the DB's effort goes."), 24, 10)
    L.add(timeseries("Top queries by calls",
                     [t("topk(8, sum by (query) (rate(pg_stat_statements_calls_total{datname=\"quickdrop\"}[1m])))", "{{query}}")],
                     unit="ops", legend_calcs=("lastNotNull",),
                     desc="N+1 shows up as one small SELECT being called many times per request."), 12, 9)
    L.add(timeseries("Mean time per call, top queries",
                     [t("topk(8, sum by (query) (rate(pg_stat_statements_seconds_total{datname=\"quickdrop\"}[1m])) "
                        "/ sum by (query) (rate(pg_stat_statements_calls_total{datname=\"quickdrop\"}[1m])))", "{{query}}")],
                     unit="s", legend_calcs=("lastNotNull",)), 12, 9)

    L.row("Storage")
    L.add(timeseries("Cache hit ratio",
                     [t(f"rate(pg_stat_database_blks_hit{{{DB}}}[1m]) / (rate(pg_stat_database_blks_hit{{{DB}}}[1m]) + rate(pg_stat_database_blks_read{{{DB}}}[1m]))", "hit ratio")],
                     unit="percentunit", max_=1,
                     desc="Share of page reads served from shared_buffers. Below ~99% the working set no longer fits in memory."), 8)
    L.add(timeseries("Dead rows per table",
                     [t("topk(5, pg_table_scans_n_dead_tup)", "{{relname}}")],
                     desc="Updated/deleted row versions waiting for VACUUM. Hot rows (inventory, riders) churn fast; Phase 4 backfills create bloat."), 8)
    L.add(timeseries("Postgres container CPU",
                     [t('sum(rate(container_cpu_usage_seconds_total{container="postgres"}[30s]))', "used"),
                      t('max(container_spec_cpu_quota{container="postgres"} / container_spec_cpu_period{container="postgres"})', "limit")],
                     desc="Postgres is capped at 2 CPUs. At the limit the database is the bottleneck, and more app instances make it worse."), 8)

    L.row("Phase 5: replica and shards")
    L.add(timeseries("Replica lag",
                     [t('max(pg_replication_lag_seconds{job="postgres-replica"})', "seconds behind the primary")],
                     unit="s",
                     desc="How far the read replica is behind. Anything read there is at least this old (scenario 5.1)."), 8)
    L.add(timeseries("History reads: primary vs replica",
                     [t("sum by (target) (rate(quickdrop_reads_routed_total[30s]))", "{{target}}")],
                     unit="ops", stack=True,
                     desc="Where read-routing sends 'My orders'. replica-ryw keeps recent writers on the primary (5.1)."), 8)
    L.add(timeseries("Sharded queries per shard",
                     [t("sum by (shard, kind) (rate(quickdrop_shard_queries_total[30s]))", "shard {{shard}} {{kind}}")],
                     unit="ops",
                     desc="Writes and reads per shard. A hot shard (5.5); scatter-gather reads hit every shard (5.2)."), 8)

    return dashboard("quickdrop-postgres", "QuickDrop — Postgres", L,
                     description="Connections, locks, deadlocks, scans and the most expensive queries.")


# --------------------------------------------------------------------------- redis and scale-out

def redis():
    L = Layout()
    L.add(text("Redis and scale-out", (
        "Phase 3: three core instances share state through **Redis** — locks, rate limits, caches, rider positions. "
        "Top: the Redis server's own view (redis-exporter). Bottom: what each Redis-backed feature is doing, from the app. "
        "Redis runs commands **one at a time** on a single thread: one slow command delays every client (scenario R1).")), 24, 3)

    L.row("Redis server")
    L.add(timeseries("Commands per second by command",
                     [t('topk(10, sum by (cmd) (rate(redis_commands_total[30s])))', "{{cmd}}")],
                     unit="ops", stack=True,
                     desc="What the instances ask Redis to do. evalsha = our Lua scripts (locks, rate limiters)."), 12)
    L.add(timeseries("Average time per command",
                     [t('topk(8, sum by (cmd) (rate(redis_commands_duration_seconds_total[30s])) / sum by (cmd) (rate(redis_commands_total[30s])))', "{{cmd}}")],
                     unit="s",
                     desc="Server-side execution time. Microseconds normally; a KEYS on a million keys takes hundreds of milliseconds "
                          "and blocks everyone else meanwhile (R1)."), 12)
    L.add(timeseries("Memory used vs maxmemory",
                     [t("max(redis_memory_used_bytes)", "used"), t("max(redis_memory_max_bytes)", "maxmemory")],
                     unit="bytes",
                     desc="At maxmemory, the eviction policy decides: refuse writes (noeviction) or delete keys — possibly your locks (R3)."), 8)
    L.add(timeseries("Keys",
                     [t("sum(redis_db_keys)", "keys"), t("sum(redis_db_keys_expiring)", "with a TTL")]), 8)
    L.add(timeseries("Evicted and expired keys per second",
                     [t("sum(rate(redis_evicted_keys_total[30s]))", "evicted (memory pressure)"),
                      t("sum(rate(redis_expired_keys_total[30s]))", "expired (TTL)")],
                     unit="ops"), 8)
    L.add(timeseries("Clients",
                     [t("sum(redis_connected_clients)", "connected"), t("sum(redis_blocked_clients)", "blocked")]), 8)
    L.add(timeseries("Keyspace hit ratio",
                     [t("sum(rate(redis_keyspace_hits_total[1m])) / (sum(rate(redis_keyspace_hits_total[1m])) + sum(rate(redis_keyspace_misses_total[1m])))", "hit ratio")],
                     unit="percentunit", max_=1), 8)
    L.add(timeseries("Rejected / failed commands",
                     [t("sum by (cmd) (rate(redis_commands_rejected_calls_total[30s]))", "rejected {{cmd}}"),
                      t("sum by (cmd) (rate(redis_commands_failed_calls_total[30s]))", "failed {{cmd}}")],
                     unit="ops",
                     desc="Rejected = refused before running, e.g. OOM under noeviction (R3)."), 8)

    L.row("Redis-backed features, from the app")
    L.add(timeseries("Distributed lock",
                     [t("sum by (result) (rate(quickdrop_redis_lock_total[30s]))", "{{result}}"),
                      t("sum(rate(quickdrop_redis_lock_fallbacks_total[30s]))", "fell back to DB lock"),
                      t("sum(rate(quickdrop_redis_lock_holder_pauses_total[30s]))", "holder froze (chaos)")],
                     unit="ops",
                     desc="expired_before_release = a holder finished after its lock had already expired: someone else may have held it at the same time (3.2)."), 8)
    L.add(timeseries("Rate limiter decisions",
                     [t("sum by (endpoint, result) (rate(quickdrop_rate_limit_total[30s]))", "{{endpoint}} {{result}}")],
                     unit="ops", desc="Per endpoint: allowed, limited, and what happened while Redis was down (3.5, 3.9)."), 8)
    L.add(timeseries("Product cache",
                     [t("sum by (layer, result) (rate(quickdrop_product_cache_total[30s]))", "{{layer}} {{result}}"),
                      t("sum(rate(quickdrop_product_cache_invalidations_received_total[30s]))", "invalidations received")],
                     unit="ops", desc="Hits and misses per layer (local JVM cache, Redis) — scenario 3.3."), 8)
    L.add(timeseries("Best sellers: requests by result",
                     [t("sum by (result) (rate(quickdrop_bestsellers_requests_total[30s]))", "{{result}}")],
                     unit="ops", stack=True,
                     desc="miss = this request ran the expensive query. stale = served the previous value while someone else recomputed (3.8)."), 8)
    L.add(timeseries("Best sellers: queries running at once",
                     [t("sum(quickdrop_bestsellers_computing)", "all instances"),
                      t("max_over_time(sum(quickdrop_bestsellers_computing)[15s:1s])", "peak (15 s)")],
                     desc="The stampede: when the cached value expires, how many requests run the same expensive query simultaneously."), 8)
    L.add(timeseries("Rider distance at assignment",
                     [t('histogram_quantile(0.5, sum by (le) (rate(quickdrop_rider_assignment_distance_km_bucket[1m])))', "p50"),
                      t('histogram_quantile(0.95, sum by (le) (rate(quickdrop_rider_assignment_distance_km_bucket[1m])))', "p95")],
                     unit="lengthkm",
                     desc="A locator that only knows some riders (in-memory with 3 instances) assigns riders who are farther away (3.4)."), 8)

    L.add(timeseries("Redis circuit breaker",
                     [t('max by (instance, state) (resilience4j_circuitbreaker_state{name="redis"}) > 0', "{{instance}} {{state}}"),
                      t('sum by (kind) (rate(resilience4j_circuitbreaker_calls_seconds_count{name="redis"}[30s]))', "calls {{kind}}")],
                     desc="state = closed (normal), open (Redis is skipped, fallbacks run instantly), half_open (testing). "
                          "not_permitted calls were answered without touching Redis (3.9)."), 24)

    L.row("Load balancer (nginx)")
    L.add(timeseries("Requests per second by core instance",
                     [t(f"sum by (instance) (rate(http_server_requests_seconds_count{{{API}}}[30s]))", "{{instance}}")],
                     unit="reqps"), 12)
    L.add(timeseries("nginx connections",
                     [t("sum(nginx_connections_active)", "active"), t("sum(nginx_connections_waiting)", "idle keep-alive"),
                      t("sum(rate(nginx_http_requests_total[30s]))", "requests/s")]), 12)
    return dashboard("quickdrop-redis", "QuickDrop — Redis & scale-out", L,
                     description="Redis server health and the shared-state features built on it.")


# --------------------------------------------------------------------------- kafka and notifications

def kafka():
    L = Layout()
    L.add(text("Kafka and notifications", (
        "Phase 6-7. Producers (core) write to topics; consumer groups (notification-service) read at their own pace. "
        "**Consumer lag** = messages written but not yet processed: Kafka is holding the backlog so nobody else has to. "
        "Lag that grows and then drains is Kafka doing its job; lag that only grows means consumers are too slow.")), 24, 3)

    L.row("Kafka")
    L.add(timeseries("Consumer lag (committed offsets)",
                     [t("max by (group, topic) (notify_consumer_lag)", "{{group}} / {{topic}}"),
                      t("sum by (topic) (kafka_consumer_fetch_manager_records_lag)", "consumer records_lag (excludes prefetched) {{topic}}"),
                      t("sum by (consumergroup, topic) (kafka_consumergroup_lag_sum)", "kafka-exporter {{consumergroup}} / {{topic}}")],
                     desc="Messages not yet processed. Three ways to measure it disagree: committed offsets (the truth), the consumers' "
                          "records_lag (ignores messages already prefetched into the client: understates), kafka-exporter (right, "
                          "but its series drop out during rebalances). The priority topic should stay near zero during a blast (6.8)."), 12)
    L.add(timeseries("Messages written per second, by topic",
                     [t("sum by (topic) (rate(kafka_topic_partition_current_offset[30s]))", "{{topic}}")], unit="ops"), 12)
    L.add(timeseries("Messages consumed per second, by group",
                     [t("sum by (consumergroup, topic) (rate(kafka_consumergroup_current_offset_sum[30s]))", "{{consumergroup}} / {{topic}}")],
                     unit="ops"), 12)
    L.add(timeseries("Lag per partition",
                     [t("sum by (topic, partition) (kafka_consumergroup_lag)", "{{topic}} p{{partition}}")],
                     desc="One partition far behind the others = a slow or stuck consumer, a poison message (K4), or a hot key (K2)."), 12)

    L.row("Notification delivery")
    L.add(timeseries("Delivery delay by type (p95)",
                     [t('histogram_quantile(0.95, sum by (le, type) (rate(notify_delivery_delay_seconds_bucket[1m])))', "{{type}}")],
                     unit="s",
                     desc="From the moment the message was created to the moment the vendor accepted it. An OTP that takes minutes is useless (6.8)."), 12)
    L.add(timeseries("Delivered by type and result",
                     [t("sum by (type, result) (rate(notify_delivered_total[30s]))", "{{type}} {{result}}")], unit="ops"), 12)
    L.add(timeseries("Where the backlog lives",
                     [t("sum(notify_memory_queue)", "notification-service memory queue (6.3)"),
                      t("max(notify_db_pending)", "Postgres queue table (6.4)"),
                      t("max(quickdrop_outbox_pending)", "outbox, not yet published (6.6)"),
                      t('max(notify_consumer_lag{topic="notifications"})', "Kafka lag (6.5)")],
                     desc="The same backlog in four different places. Only some of them survive a restart, and only one doesn't load the database."), 12)
    L.add(timeseries("core: notifications by mode and result",
                     [t("sum by (mode, result) (rate(quickdrop_notifications_total[30s]))", "{{mode}} {{result}}")], unit="ops",
                     desc="dropped = timeout / breaker / bulkhead in http mode (6.2); lost_crash = the dual-write crash (6.6)."), 12)
    L.add(timeseries("Vendor: accepted vs rejected",
                     [t("sum by (result) (rate(vendor_messages_total[30s]))", "{{result}}"),
                      t("sum(rate(vendor_duplicate_messages_total[30s]))", "duplicates")], unit="ops",
                     desc="rate_limited = we sent faster than the vendor allows (6.7). duplicates = the same message twice (K3)."), 12)
    L.add(timeseries("Outbox relay",
                     [t("sum(rate(quickdrop_outbox_published_total[30s]))", "published/s"),
                      t("max(quickdrop_outbox_oldest_age_milliseconds) / 1000", "oldest unpublished (s)")]), 12)
    return dashboard("quickdrop-kafka", "QuickDrop — Kafka & notifications", L,
                     description="Topics, consumer lag, delivery delay and where the notification backlog lives.")


# --------------------------------------------------------------------------- operating (Phase 8)

def operating():
    L = Layout()
    L.add(text("Operating it", (
        "Phase 8. **Alerts** come from `infra/prometheus/rules/alerts.yml` and go through Alertmanager (http://localhost:9093) to a "
        "webhook in notification-service (`GET http://localhost:8091/api/alerts`). **Traces** are in Tempo: Explore → Tempo, or click "
        "an exemplar dot. The checkout SLO is 99.5% of `POST /api/orders` without a 5xx.")), 24, 3)

    L.row("SLO and alerts (8.2)")
    L.add(timeseries("Alerts firing",
                     [t('sum by (alertname, severity) (ALERTS{alertstate="firing", alertname!="Watchdog"})', "{{alertname}} ({{severity}})")],
                     stack=True, min_=0,
                     desc="Pages (symptoms) and warnings (causes). Compare when each fired with when customers were actually hurt."), 12)
    L.add(timeseries("Checkout error ratio vs the SLO",
                     [t("quickdrop:checkout_errors:ratio_rate1m", "errors, 1 m window"),
                      t("quickdrop:checkout_errors:ratio_rate5m", "errors, 5 m window"),
                      t("vector(0.005)", "SLO: 0.5% allowed"),
                      t("vector(14.4 * 0.005)", "fast-burn page threshold (14.4×)")],
                     unit="percentunit", min_=0,
                     desc="The fast-burn alert pages when BOTH windows are above 14.4× the allowed error rate."), 12)
    L.add(timeseries("Error budget burn rate",
                     [t("quickdrop:checkout_errors:ratio_rate5m / 0.005", "burn rate (5 m)"),
                      t("quickdrop:checkout_errors:ratio_rate30m / 0.005", "burn rate (30 m)")],
                     min_=0,
                     desc="1 = spending the error budget exactly as fast as the SLO allows. 14.4 = a 30-day budget gone in 2 days."), 12)
    L.add(timeseries("Checkout latency (with exemplars)",
                     [t('histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{job="core", method="POST", uri="/api/orders"}[1m])))',
                        "p99", exemplar=True),
                      t('histogram_quantile(0.50, sum by (le) (rate(http_server_requests_seconds_bucket{job="core", method="POST", uri="/api/orders"}[1m])))',
                        "p50")],
                     unit="s", desc="Click a dot to open that request's trace (8.1)."), 12)

    L.row("Retries (8.3) and load shedding (8.4)")
    L.add(timeseries("Retry amplification",
                     [t('sum by (client) (rate(quickdrop_retry_attempts_total{kind="retry"}[30s])) '
                        '/ sum by (client) (rate(quickdrop_retry_attempts_total{kind="first"}[30s]))', "retries per call: {{client}}"),
                      t('sum by (client) (rate(quickdrop_retry_attempts_total{kind="denied"}[30s]))', "retries denied by budget/s: {{client}}")],
                     desc="0.1 = retries add 10% load. 3 = every call became 4 attempts: the struggling downstream gets 4× the traffic."), 12)
    L.add(timeseries("SMS vendor: work vs queue",
                     [t("sum(rate(vendor_messages_total[30s]))", "sends processed/s"),
                      t("max(vendor_queue_length)", "requests waiting"),
                      t("histogram_quantile(0.99, sum by (le) (rate(vendor_queue_wait_seconds_bucket[1m]))) * 1000", "queue wait p99 (ms)")],
                     desc="With overload-mode=queue: a request that waited longer than its caller's timeout is work for nobody."), 12)
    L.add(timeseries("Load shedding",
                     [t("sum(rate(quickdrop_shedding_rejected_total[30s]))", "rejected/s (503)"),
                      t("sum(quickdrop_shedding_sheddable_in_flight)", "sheddable requests in flight")],
                     desc="load-shedding=priority: browsing and history are refused early so checkout keeps its capacity."), 12)
    L.add(timeseries("Checkout vs browsing success",
                     [t('sum(rate(http_server_requests_seconds_count{job="core", method="POST", uri="/api/orders", status=~"2.."}[30s]))', "checkout ok/s"),
                      t('sum(rate(http_server_requests_seconds_count{job="core", method="POST", uri="/api/orders", status=~"5.."}[30s]))', "checkout 5xx/s"),
                      t('sum(rate(http_server_requests_seconds_count{job="core", method="GET", uri=~"/api/stores/.*|/api/customers/.*", status=~"2.."}[30s]))', "browse ok/s")],
                     unit="reqps"), 12)

    L.row("Traces (8.1)")
    L.add(timeseries("Service graph (from traces)",
                     [t("sum by (client, server) (rate(traces_service_graph_request_total[1m]))", "{{client}} → {{server}}")],
                     unit="reqps",
                     desc="Tempo's metrics generator derives who-calls-whom from the spans. Explore → Tempo → Service graph draws it."), 12)
    L.add(logs("Alert notifications received",
               '{container=~"notification-.*"} |= "ALERT"',
               desc="What would have reached the on-call phone. Log lines carry [traceId-spanId] when they belong to a request."), 12)
    return dashboard("quickdrop-operating", "QuickDrop — Operating (Phase 8)", L,
                     description="SLO, alerts, retries, load shedding and traces.")


# --------------------------------------------------------------------------- containers

def containers():
    L = Layout()
    L.add(text("Containers", "Per-container resources from cAdvisor. Every container runs with a CPU and memory cap so laptop-sized load can saturate it."), 24, 2)
    L.add(timeseries("CPU (cores)",
                     [t('sum by (container) (rate(container_cpu_usage_seconds_total{container!=""}[30s]))', "{{container}}")],
                     legend_calcs=("lastNotNull", "max", "mean")), 12, 10)
    L.add(timeseries("Memory working set",
                     [t('sum by (container) (container_memory_working_set_bytes{container!=""})', "{{container}}")],
                     unit="bytes", legend_calcs=("lastNotNull", "max")), 12, 10)
    L.add(timeseries("CPU throttled share",
                     [t('sum by (container) (rate(container_cpu_cfs_throttled_periods_total{container!=""}[30s])) '
                        '/ sum by (container) (rate(container_cpu_cfs_periods_total{container!=""}[30s]))', "{{container}}")],
                     unit="percentunit", min_=0), 12, 9)
    L.add(timeseries("Network received",
                     [t('sum by (container) (rate(container_network_receive_bytes_total{container!=""}[30s]))', "{{container}}")],
                     unit="Bps"), 12, 9)
    L.add(logs("Logs: warnings and errors from every container",
               '{service=~".+"} |~ "(?i)(warn|error|exception|deadlock|timeout)"',
               desc="Loki, fed by Grafana Alloy. Filter by service with {service=\"core-1\"}."), 24, 10)
    return dashboard("quickdrop-containers", "QuickDrop — Containers & logs", L,
                     description="CPU, memory, throttling and logs for every container.")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, build in [("overview", overview), ("saturation", saturation), ("postgres", postgres), ("redis", redis),
                        ("kafka", kafka), ("operating", operating), ("containers", containers)]:
        path = OUT / f"{name}.json"
        path.write_text(json.dumps(build(), indent=2) + "\n")
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
