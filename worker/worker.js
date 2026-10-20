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
    // Note: YouTube deprecated relatedToVideoId; use text search on title instead
    const query = q || videoId;
    let api = `${YT_BASE}/search?part=snippet&q=${encodeURIComponent(query)}&type=video&maxResults=${maxResults}&key=${key}`;
    if (pageToken) api += `&pageToken=${encodeURIComponent(pageToken)}`;
    // Related is per-video; cache 2 hours to save quota
    return ytFetch(api, 7200);
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
