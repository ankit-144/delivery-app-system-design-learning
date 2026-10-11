#!/usr/bin/env python3
"""Show or clear Toxiproxy toxics.

    scripts/toxics.py          list proxies and their toxics
    scripts/toxics.py clear    remove every toxic
"""
import json
import sys
import urllib.request

API = "http://localhost:8474"


def call(method, path):
    req = urllib.request.Request(API + path, method=method)
    with urllib.request.urlopen(req) as res:
        body = res.read()
        return json.loads(body) if body else None


proxies = call("GET", "/proxies")
for name, proxy in sorted(proxies.items()):
    toxics = proxy["toxics"]
    if len(sys.argv) > 1 and sys.argv[1] == "clear":
        for toxic in toxics:
            call("DELETE", f"/proxies/{name}/toxics/{toxic['name']}")
            print(f"removed {name}/{toxic['name']}")
    else:
        described = ", ".join(f"{t['name']} {t['attributes']}" for t in toxics) or "none"
        print(f"  {name:9} {proxy['listen']:15} -> {proxy['upstream']:18} toxics: {described}")
