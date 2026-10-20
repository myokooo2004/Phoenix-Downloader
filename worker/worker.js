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
