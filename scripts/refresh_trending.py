#!/usr/bin/env python3
"""Refresh the GitHub-hosted trending mirror (data branch: trending.json).

Chains the Worker's normalized /trending.json endpoint (5 pages x 20 =
up to 100 videos) and writes {updatedAt, videos:[...]} for the app to
read from raw.githubusercontent.com — reachable on networks that block
the Worker's own hosts, so Home needs no VPN.

Quota discipline: each upstream page is a search.list call (100 units),
so the workflow runs this only every 6 hours (<= 2,000 units/day).

Failure rules: page 1 empty -> exit 1 and keep the previous file (an
upstream quota 429 must never blank the feed). A later page coming back
empty just ends the chain; whatever was collected (>= 20) is written.

Usage: refresh_trending.py <output-path>
"""
import json
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone

BASE = "https://api.phoenixdl.dpdns.org/trending.json?region=MM&maxResults=20"
UA = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) PhoenixDownloader/1.0"}
MAX_PAGES = 5


def fetch(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def main():
    if len(sys.argv) != 2:
        print("usage: refresh_trending.py <output-path>")
        return 2
    videos = []
    seen = set()
    token = None
    for page in range(MAX_PAGES):
        url = BASE + (("&pageToken=" + urllib.parse.quote(token)) if token else "")
        try:
            data = fetch(url)
        except Exception as e:
            print(f"page {page + 1}: fetch failed: {e}")
            break
        batch = data.get("videos") or []
        if not batch:
            print(f"page {page + 1}: empty")
            break
        for v in batch:
            u = v.get("url")
            if u and u not in seen:
                seen.add(u)
                videos.append(v)
        token = data.get("nextPageToken")
        if not token:
            break
    if len(videos) < 20:
        print(f"only {len(videos)} videos collected — keeping previous file")
        return 1
    out = {
        "updatedAt": datetime.now(timezone.utc).isoformat(),
        "videos": videos,
    }
    with open(sys.argv[1], "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)
    print(f"wrote {len(videos)} videos to {sys.argv[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
