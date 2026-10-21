// Phoenix Downloader API proxy worker
// Proxies YouTube Data API v3 with server-side key + edge caching.
// Endpoints:
//   GET /trending?region=MM&maxResults=20&pageToken=...
//   GET /related?videoId=...&maxResults=20
//   GET /comments?videoId=...&maxResults=20

const YT_BASE = "https://www.googleapis.com/youtube/v3";

async function ytFetch(url, cacheTtlSeconds) {
  const cache = caches.default;
  const cacheKey = new Request(url, { method: "GET" });
  let cached = await cache.match(cacheKey);
  if (cached) return cached;

  const resp = await fetch(url, {
    headers: { "User-Agent": "PhoenixDownloader/1.0" },
  });
  const body = await resp.text();
  const out = new Response(body, {
    status: resp.status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": `public, max-age=${cacheTtlSeconds}`,
      "Access-Control-Allow-Origin": "*",
    },
  });
  if (resp.ok) {
    // Cache successful responses at the edge
    const toCache = out.clone();
    const cacheResp = new Response(toCache.body, toCache);
    cacheResp.headers.set("Cache-Control", `public, max-age=${cacheTtlSeconds}`);
    await cache.put(cacheKey, cacheResp);
  }
  return out;
}

// Strip title noise ([Official MV], (Lyrics), | channel suffix, #tags) so
// the search query keeps just the artist + title in its own language.
function cleanTitleQuery(t) {
  let s = (t || "").replace(/\[[^\]]*\]/g, " ").replace(/\([^)]*\)/g, " ");
  s = s.split("|")[0];
  s = s.replace(/#\S+/g, " ");
  s = s.replace(/\s+/g, " ").trim();
  return s || (t || "");
}

// One search.list attempt for the related ladder. Returns the raw
// response plus how many items it carried, so the caller can widen the
// query when a narrow one (long specific tags) matches almost nothing.
async function searchOnce(key, query, categoryId, pageToken, maxResults) {
  let api = `${YT_BASE}/search?part=snippet&q=${encodeURIComponent(query)}&type=video&order=viewCount&maxResults=${maxResults}&key=${key}`;
  if (categoryId) api += `&videoCategoryId=${encodeURIComponent(categoryId)}`;
  if (pageToken) api += `&pageToken=${encodeURIComponent(pageToken)}`;
  const resp = await ytFetch(api, 7200);
  if (!resp.ok) return { resp, count: -1, status: resp.status };
  const j = await resp.clone().json().catch(() => null);
  return { resp, count: j && Array.isArray(j.items) ? j.items.length : 0, status: resp.status };
}

function jsonError(status, message) {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
  });
}

// ---------------------------------------------------------------------------
// InnerTube "next" — YouTube's own Up next flow (user spec 2026-10-21:
// Related must be exactly what real YouTube shows, and infinite). The
// public Data API has no related endpoint anymore (relatedToVideoId was
// removed in 2023); the search ladder below only approximates it and
// reads like search results. The youtubei next endpoint returns the
// genuine recommendation list plus a continuation token for the next
// page, and costs ZERO Data API quota. Uses the public WEB client key
// (the same key embedded in every youtube.com page). If anything about
// the response shape is off, callers fall back to the search ladder.
// ---------------------------------------------------------------------------
const INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
const INNERTUBE_CTX = {
  context: { client: { clientName: "WEB", clientVersion: "2.20240111.09.00", hl: "en", gl: "US" } },
};

function badgeToSeconds(text) {
  const parts = String(text || "").split(":").map((s) => parseInt(s, 10));
  if (parts.some((n) => !isFinite(n))) return 0;
  return parts.reduce((acc, n) => acc * 60 + n, 0);
}

// One lockupViewModel (the current Up next item shape) -> the Data API
// search-result item shape the app already parses. Null when unusable.
function lockupToItem(lk) {
  try {
    const videoId = lk.contentId;
    if (!videoId || lk.contentType !== "LOCKUP_CONTENT_TYPE_VIDEO") return null;
    const md = lk.metadata && lk.metadata.lockupMetadataViewModel;
    const title = md && md.title && md.title.content;
    if (!title) return null;
    let channel = "";
    const rows = (md.metadata && md.metadata.contentMetadataViewModel &&
      md.metadata.contentMetadataViewModel.metadataRows) || [];
    if (rows.length) {
      channel = (rows[0].metadataParts || [])
        .map((p) => (p.text && p.text.content) || "").join("").trim();
    }
    let durationSec = 0;
    const overlays = (lk.contentImage && lk.contentImage.thumbnailViewModel &&
      lk.contentImage.thumbnailViewModel.overlays) || [];
    for (const ov of overlays) {
      const badges = (ov.thumbnailBottomOverlayViewModel &&
        ov.thumbnailBottomOverlayViewModel.badges) || [];
      for (const b of badges) {
        const t = b.thumbnailBadgeViewModel && b.thumbnailBadgeViewModel.text;
        if (t) durationSec = badgeToSeconds(t);
      }
    }
    return {
      id: { videoId },
      snippet: {
        title,
        channelTitle: channel,
        thumbnails: {
          default: { url: `https://i.ytimg.com/vi/${videoId}/default.jpg` },
          medium: { url: `https://i.ytimg.com/vi/${videoId}/mqdefault.jpg` },
          high: { url: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg` },
        },
      },
      durationSec,
    };
  } catch (e) {
    return null;
  }
}

// Walk one results array (first page or a continuation page): collect
// video items in order plus the list's own continuation token.
function collectNextItems(arr) {
  const items = [];
  let token = null;
  for (const entry of arr || []) {
    if (entry.lockupViewModel) {
      const it = lockupToItem(entry.lockupViewModel);
      if (it) items.push(it);
    } else if (entry.continuationItemRenderer) {
      const t = entry.continuationItemRenderer.continuationEndpoint &&
        entry.continuationItemRenderer.continuationEndpoint.continuationCommand &&
        entry.continuationItemRenderer.continuationEndpoint.continuationCommand.token;
      if (t) token = t;
    }
  }
  return { items, token };
}

// Returns { items, token } from YouTube's Up next for [videoId], or the
// next page when [continuation] is given. Null on any failure/shape drift.
async function innertubeRelated(videoId, continuation) {
  try {
    const payload = continuation
      ? { ...INNERTUBE_CTX, continuation }
      : { ...INNERTUBE_CTX, videoId };
    const resp = await fetch(
      `https://www.youtube.com/youtubei/v1/next?key=${INNERTUBE_KEY}`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        },
        body: JSON.stringify(payload),
      }
    );
    if (!resp.ok) return null;
    const root = await resp.json();
    if (!continuation) {
      const sec = root.contents && root.contents.twoColumnWatchNextResults &&
        root.contents.twoColumnWatchNextResults.secondaryResults &&
        root.contents.twoColumnWatchNextResults.secondaryResults.secondaryResults;
      if (!sec || !Array.isArray(sec.results)) return null;
      return collectNextItems(sec.results);
    }
    const eps = root.onResponseReceivedEndpoints || [];
    for (const ep of eps) {
      const act = ep.appendContinuationItemsAction;
      if (act && Array.isArray(act.continuationItems)) {
        return collectNextItems(act.continuationItems);
      }
    }
    return null;
  } catch (e) {
    return null;
  }
}

async function handleRequest(request) {
  const key = YT_API_KEY;
  if (!key) return jsonError(500, "YT_API_KEY not configured");

  const url = new URL(request.url);
  const path = url.pathname;

  if (request.method === "OPTIONS") {
    return new Response(null, {
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET, OPTIONS",
        "Access-Control-Allow-Headers": "*",
      },
    });
  }

  if (path === "/trending.json") {
    // Normalized trending for the GitHub mirror: scripts/refresh_
    // trending.py republishes this to the repo's data branch hourly and
    // the app reads it from raw.githubusercontent.com, which stays
    // reachable on networks that block this Worker's hosts (the app then
    // needs no VPN for Home). Same upstream data as /trending, flattened
    // to {videos:[{url,title,thumbnailUrl,duration}],nextPageToken} with
    // durations resolved via one videos.list contentDetails call.
    const cache = caches.default;
    const cacheKey = new Request(url.toString(), { method: "GET" });
    const hit = await cache.match(cacheKey);
    if (hit) return hit;
    const region = url.searchParams.get("region") || "MM";
    const maxResults = Math.min(parseInt(url.searchParams.get("maxResults") || "20", 10), 50);
    const pageToken = url.searchParams.get("pageToken");
    let api;
    if (region === "MM") {
      api = `${YT_BASE}/search?part=snippet&q=${encodeURIComponent("Myanmar music")}&type=video&maxResults=${maxResults}&key=${key}`;
    } else {
      api = `${YT_BASE}/videos?part=snippet,contentDetails,statistics&chart=mostPopular&regionCode=${encodeURIComponent(region)}&maxResults=${maxResults}&key=${key}`;
    }
    if (pageToken) api += `&pageToken=${encodeURIComponent(pageToken)}`;
    const upstream = await (await ytFetch(api, 3600)).clone().json().catch(() => null);
    const items = (upstream && upstream.items) || [];
    // Durations: the chart response carries contentDetails inline; the
    // search response does not, so resolve those ids in one batch call.
    const durations = {};
    const missing = [];
    for (const it of items) {
      const id = typeof it.id === "string" ? it.id : (it.id && it.id.videoId) || null;
      if (!id) continue;
      const iso = it.contentDetails && it.contentDetails.duration;
      if (iso) durations[id] = iso; else missing.push(id);
    }
    if (missing.length) {
      try {
        const dApi = `${YT_BASE}/videos?part=contentDetails&id=${missing.join(",")}&key=${key}`;
        const dJson = await (await ytFetch(dApi, 3600)).clone().json().catch(() => null);
        for (const d of (dJson && dJson.items) || []) {
          if (d.contentDetails && d.contentDetails.duration) durations[d.id] = d.contentDetails.duration;
        }
      } catch (e) { /* durations stay 0 for those items */ }
    }
    const isoToSec = (iso) => {
      const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(iso || "");
      if (!m) return 0;
      return (parseInt(m[1] || "0", 10) * 3600) + (parseInt(m[2] || "0", 10) * 60) + parseInt(m[3] || "0", 10);
    };
    const videos = [];
    for (const it of items) {
      const id = typeof it.id === "string" ? it.id : (it.id && it.id.videoId) || null;
      const sn = it.snippet || {};
      if (!id || !sn.title) continue;
      const th = sn.thumbnails || {};
      videos.push({
        url: `https://www.youtube.com/watch?v=${id}`,
        title: sn.title,
        thumbnailUrl: (th.high && th.high.url) || (th.medium && th.medium.url) || (th.default && th.default.url) || "",
        duration: isoToSec(durations[id]),
      });
    }
    const resp = new Response(
      JSON.stringify({ videos, nextPageToken: (upstream && upstream.nextPageToken) || null }),
      {
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": "public, max-age=3600",
          "Access-Control-Allow-Origin": "*",
        },
      }
    );
    // Never pin an empty page in the edge cache: an upstream quota 429
    // yields zero items, and caching that would keep the mirror chain
    // truncated for an hour after the quota resets.
    if (videos.length) await cache.put(cacheKey, resp.clone());
    return resp;
  }

  if (path === "/trending") {
    const region = url.searchParams.get("region") || "MM";
    const maxResults = Math.min(parseInt(url.searchParams.get("maxResults") || "20", 10), 50);
    const pageToken = url.searchParams.get("pageToken");
    let api;
    if (region === "MM") {
      // YouTube rejects regionCode=MM for mostPopular chart.
      // Use search for Myanmar music videos instead (real, live data).
      api = `${YT_BASE}/search?part=snippet&q=${encodeURIComponent("Myanmar music")}&type=video&maxResults=${maxResults}&key=${key}`;
      if (pageToken) api += `&pageToken=${encodeURIComponent(pageToken)}`;
      // Cache 1 hour to save quota (search is expensive)
      return ytFetch(api, 3600);
    } else {
      // For other regions, use the trending chart
      api = `${YT_BASE}/videos?part=snippet,contentDetails,statistics&chart=mostPopular&maxResults=${maxResults}&key=${key}`;
      api += `&regionCode=${encodeURIComponent(region)}`;
      if (pageToken) api += `&pageToken=${encodeURIComponent(pageToken)}`;
      // Trending changes slowly; cache 1 hour
      return ytFetch(api, 3600);
    }
  }

  if (path === "/related") {
    const videoId = url.searchParams.get("videoId");
    const q = url.searchParams.get("q");
    if (!videoId && !q) return jsonError(400, "videoId or q required");
    const maxResults = Math.min(parseInt(url.searchParams.get("maxResults") || "20", 10), 50);
    const pageToken = url.searchParams.get("pageToken");
    // Primary source: YouTube's own Up next (InnerTube). Normalized to
    // the exact JSON shape the app parses for the ladder below, with
    // the continuation token riding as nextPageToken — so the app's
    // existing pagination scrolls YouTube's genuine flow, infinitely.
    // Any doubt (fetch failed, shape drift, too few items) falls
    // through to the search ladder, which stays as the fallback.
    if (videoId) {
      const cache = caches.default;
      const cacheKey = new Request(url.toString(), { method: "GET" });
      const hit = await cache.match(cacheKey);
      if (hit) return hit;
      const it = await innertubeRelated(videoId, pageToken);
      if (it && it.items.length >= 5) {
        const seen = new Set();
        const items = [];
        for (const item of it.items) {
          const id = item.id.videoId;
          if (id === videoId || seen.has(id)) continue;
          seen.add(id);
          items.push(item);
          if (items.length >= maxResults) break;
        }
        if (items.length >= 5) {
          const ttl = pageToken ? 300 : 600;
          const resp = new Response(
            JSON.stringify({ items, nextPageToken: it.token || null, relatedSource: "innertube-next" }),
            {
              headers: {
                "Content-Type": "application/json",
                "Cache-Control": `public, max-age=${ttl}`,
                "Access-Control-Allow-Origin": "*",
              },
            }
          );
          await cache.put(cacheKey, resp.clone());
          return resp;
        }
      }
    }
    // The YouTube API flow for "related" (relatedToVideoId was removed by
    // YouTube): 1) videos.list -> the watched video's own metadata
    // (categoryId, tags, title). 2) search.list ordered by viewCount
    // (popular first) inside that category, using the video's own
    // tags/title as the terms — so the terms carry the video's own
    // language/topic: English video -> English popular, Myanmar video ->
    // Myanmar popular, Myanmar melody -> Myanmar melody popular.
    // Spec: user 2026-10-20.
    let titleQuery = cleanTitleQuery(q || "");
    let tagsQuery = "";
    let categoryId = "";
    if (videoId) {
      try {
        const metaResp = await ytFetch(
          `${YT_BASE}/videos?part=snippet&id=${encodeURIComponent(videoId)}&key=${key}`,
          86400
        );
        if (metaResp.ok) {
          const meta = await metaResp.json();
          const snip = meta.items && meta.items[0] && meta.items[0].snippet;
          if (snip) {
            categoryId = snip.categoryId || "";
            const tags = Array.isArray(snip.tags) ? snip.tags.slice(0, 2).join(" ") : "";
            tagsQuery = cleanTitleQuery(tags);
            titleQuery = cleanTitleQuery(snip.title || "") || titleQuery;
          }
        }
      } catch (e) { /* metadata failed: title query only */ }
    }
    if (!tagsQuery && !titleQuery) {
      const only = await searchOnce(key, videoId, "", pageToken, maxResults);
      return only.resp;
    }
    const debug = url.searchParams.get("debug") === "1";
    const dbg = { tagsQuery, titleQuery, categoryId, attempts: [] };
    // Title segments: a long full title ("Song A / Song B - Artist")
    // matches only the video itself, so the ladder also tries the first
    // segment (usually the main song) and the artist segment.
    const segs = titleQuery.split(/\s*[/|–—]\s*|\s+-\s+/).map(s => s.trim()).filter(s => s.length >= 2);
    const firstSeg = segs.length > 1 ? segs[0] : "";
    const artistSeg = segs.length > 1 ? segs[segs.length - 1] : "";
    // Widening ladder, most specific first. An attempt wins with >= 10
    // items; else the fullest result is used. Errors don't stop the
    // ladder (a 429 on one query shape may not affect another). Every
    // successful attempt is edge-cached, so page 2 re-runs the same
    // ladder from cache and lands on the same attempt, which keeps its
    // pageToken valid.
    const attempts = [];
    if (tagsQuery) attempts.push([tagsQuery, categoryId]);
    if (titleQuery && titleQuery !== tagsQuery) attempts.push([titleQuery, categoryId]);
    if (firstSeg && firstSeg !== titleQuery && firstSeg !== tagsQuery) attempts.push([firstSeg, categoryId]);
    if (firstSeg) attempts.push([firstSeg, ""]);
    if (artistSeg && artistSeg !== firstSeg && artistSeg !== titleQuery) attempts.push([artistSeg, ""]);
    if (titleQuery && categoryId) attempts.push([titleQuery, ""]);
    let best = null;
    let firstError = null;
    for (const [query, cat] of attempts) {
      const r = await searchOnce(key, query, cat, pageToken, maxResults);
      dbg.attempts.push({ q: query, cat, status: r.status, count: r.count });
      if (r.count >= 10) { if (debug) return Response.json(dbg); return r.resp; }
      if (r.count < 0) { if (!firstError) firstError = r; continue; }
      if (!best || r.count > best.count) best = r;
    }
    if (debug) return Response.json(dbg);
    return (best || firstError).resp;
  }

  if (path === "/hot") {
    // Hot Searches, the genuine-YouTube way (user spec 2026-10-20):
    // YouTube shows no hot list for an empty search box (suggest returns
    // [] for an empty query); its real popularity signal is the
    // suggestion ranking. So compose the list from YouTube's own top
    // suggestions for each Myanmar initial letter — every term is
    // YouTube-ranked, nothing is hand-picked. Refreshed daily via the
    // edge cache below.
    const cache = caches.default;
    const cacheKey = new Request(url.toString(), { method: "GET" });
    const hit = await cache.match(cacheKey);
    if (hit) return hit;
    const seeds = ["အ", "က", "ခ", "ဂ", "စ", "ဆ", "ဇ", "တ", "ထ", "န", "ပ", "ဖ", "ဗ", "မ", "ယ", "ရ", "လ", "ဝ", "သ", "ဟ"];
    const perSeed = await Promise.all(seeds.map(async (seed) => {
      try {
        const r = await fetch(
          "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=" + encodeURIComponent(seed),
          { headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 14)" } }
        );
        if (!r.ok) return [];
        const data = await r.json();
        return Array.isArray(data[1]) ? data[1] : [];
      } catch (e) {
        return [];
      }
    }));
    const scores = new Map();
    for (const list of perSeed) {
      list.slice(0, 5).forEach((term, rank) => {
        const t = String(term).trim();
        if (!t || t.length > 30) return;
        if (/[0-9၀-၉]/.test(t)) return; // episode/part numbers = noise
        scores.set(t, (scores.get(t) || 0) + (5 - rank));
      });
    }
    const terms = [...scores.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, 14)
      .map((e) => e[0]);
    const ttl = terms.length ? 86400 : 300;
    const resp = new Response(JSON.stringify({ terms }), {
      headers: {
        "Content-Type": "application/json",
        "Cache-Control": `public, max-age=${ttl}`,
        "Access-Control-Allow-Origin": "*",
      },
    });
    await cache.put(cacheKey, resp.clone());
    return resp;
  }

  if (path === "/search") {
    const q = url.searchParams.get("q");
    if (!q) return jsonError(400, "q required");
    const maxResults = Math.min(parseInt(url.searchParams.get("maxResults") || "20", 10), 50);
    const pageToken = url.searchParams.get("pageToken");
    let api = `${YT_BASE}/search?part=snippet&q=${encodeURIComponent(q)}&type=video&maxResults=${maxResults}&key=${key}`;
    if (pageToken) api += `&pageToken=${encodeURIComponent(pageToken)}`;
    // Search results shift; short cache to save quota (10 min)
    return ytFetch(api, 600);
  }

  if (path === "/comments") {
    const videoId = url.searchParams.get("videoId");
    if (!videoId) return jsonError(400, "videoId required");
    const maxResults = Math.min(parseInt(url.searchParams.get("maxResults") || "20", 10), 100);
    const api = `${YT_BASE}/commentThreads?part=snippet&videoId=${encodeURIComponent(videoId)}&order=relevance&maxResults=${maxResults}&textFormat=plainText&key=${key}`;
    // Comments change; cache 30 minutes
    return ytFetch(api, 1800);
  }

  if (path === "/" || path === "/health") {
    return new Response(JSON.stringify({ ok: true, service: "phoenix-downloader-api" }), {
      headers: { "Content-Type": "application/json" },
    });
  }

  return jsonError(404, "not found");
}

addEventListener("fetch", (event) => {
  event.respondWith(handleRequest(event.request));
});
