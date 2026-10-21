#!/usr/bin/env python3
"""Refresh the GitHub-hosted trending mirror (data branch: trending.json).

Chains the Worker's normalized /trending.json endpoint and writes
{updatedAt, videos:[...]} for the app to read from
raw.githubusercontent.com — reachable on networks that block the
Worker's own hosts, so Home needs no VPN.

Source (since 2026-10-21, "Option A"): the Worker serves this from
InnerTube search ("Myanmar music") — ZERO YouTube Data API quota.
The old Data API search.list chain cost ~500 units per refresh
(~2,000/day) and exhausted the project once.

Publish gate (the hardening that makes a private-API source safe):
an item is valid only with a non-empty url, title and thumbnailUrl.
The run publishes ONLY when at least 40 valid videos were collected
AND at least 90% of them carry a duration (> 0) — the signature of a
healthy parse. Anything less exits 1 and the previous trending.json
stays live: users see a stale feed, never a broken one, and the
failed workflow run alerts the owner.

Usage: refresh_trending.py <output-path>
"""
import json
import random
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone

BASE = "https://api.tugyi.dpdns.org/trending.json?region=MM&maxResults=20"
UA = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) PhoenixDownloader/1.0"}
MAX_PAGES = 8
MIN_VALID = 40


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
        if page:
            time.sleep(random.uniform(1.0, 3.0))  # jitter between pages
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

    valid = [
        v for v in videos
        if v.get("url") and v.get("title") and v.get("thumbnailUrl")
    ]
    with_duration = sum(1 for v in valid if (v.get("duration") or 0) > 0)
    if len(valid) < MIN_VALID:
        print(
            f"gate FAILED: only {len(valid)} valid videos "
            f"(< {MIN_VALID}) — keeping previous file"
        )
        return 1
    if with_duration < 0.9 * len(valid):
        print(
            f"gate FAILED: only {with_duration}/{len(valid)} videos "
            "have durations — parse looks broken, keeping previous file"
        )
        return 1

    out = {
        "updatedAt": datetime.now(timezone.utc).isoformat(),
        "videos": valid,
    }
    with open(sys.argv[1], "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)
    print(f"wrote {len(valid)} videos to {sys.argv[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
