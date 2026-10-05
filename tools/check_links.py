"""Checks every source URL in assets/regulations.json (HTTP status). Run after gen_regulations.py."""
import json, os, sys, urllib.request, ssl
p = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "regulations.json")
d = json.load(open(p, encoding="utf-8"))
urls = sorted({s["url"] for e in d["states"] + d["cities"] for s in e["sources"]})
bad = 0
for u in urls:
    try:
        req = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0 (RideRange link check)"})
        code = urllib.request.urlopen(req, timeout=25, context=ssl.create_default_context()).status
    except urllib.error.HTTPError as e:
        code = e.code
    except Exception as e:
        # Some servers fail Python's TLS checks but work in browsers/curl: ask curl.
        import subprocess
        r = subprocess.run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-L", "-A", "Mozilla/5.0", "--max-time", "25", u], capture_output=True, text=True)
        code = int(r.stdout) if r.stdout.isdigit() else type(e).__name__
    ok = code == 200 or code == 403  # 403 = bot protection (Cloudflare), the page exists for browsers
    if not ok: bad += 1
    print(("OK  " if ok else "BAD ") + str(code), u)
print(len(urls), "urls,", bad, "bad")
