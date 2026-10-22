/**
 * Phoenix Downloader — Worker relay (Google Apps Script).
 *
 * Why this exists (2026-10-21): on the user's home network (MPT,
 * IPv4-only), connections to BOTH Cloudflare Worker hosts are killed
 * mid-handshake (connection reset) — in the app AND in Chrome; a
 * fresh domain/IP pair survived only ~3 hours. Google IPs are NOT
 * affected (playback/downloads work all day). So the app talks to
 * script.google.com instead, and this script fetches the Worker
 * from Google's servers.
 *
 * Contract: GET <deployment>/exec?u=<url-encoded Worker path+query>
 *   u must start with "/" (e.g. "/search?q=zaw+gyi&maxResults=20").
 * Response: always HTTP 200 (Apps Script web apps cannot set their
 *   own status), body = {"s": <real Worker status>, "b": <Worker
 *   body as string>}. The app unwraps it, so its callers see the
 *   Worker's true status code.
 *
 * Deploy: script.google.com -> New project -> paste this file ->
 * Deploy -> New deployment -> Web app -> Execute as: Me ->
 * Who has access: Anyone -> Deploy -> copy the /exec URL into
 * YouTubeApiClient.RELAY_URLS.
 *
 * Quota note: consumer Apps Script allows ~20,000 UrlFetch calls
 * per day on the deploying account — the known ceiling for this
 * design.
 *
 * LIVE deployment (2026-10-22, Version 1): project "Untitled project"
 * under luluthuthulay@gmail.com, deployment ID
 * AKfycbw6REMeVfXlP72GytuRTA_B08isVqB79y5qwz1fZt8fNzGHRLWnaABr1F4HM18G3itcew
 * — /exec URL verified end-to-end from the VM (health/hot/related all
 * returned wrapped Worker responses). To change the script later:
 * edit the project, then Deploy -> Manage deployments -> edit ->
 * Version: New version (editing code alone does not update /exec).
 */

var WORKER_BASES = [
  "https://api.tugyi.dpdns.org",
  "https://api.phoenixdl.dpdns.org"
];

function doGet(e) {
  var u = (e && e.parameter && e.parameter.u) || "";
  if (u.charAt(0) !== "/") {
    return wrap(400, JSON.stringify({ error: "bad u" }));
  }
  var last = null;
  for (var i = 0; i < WORKER_BASES.length; i++) {
    try {
      var resp = UrlFetchApp.fetch(WORKER_BASES[i] + u, {
        muteHttpExceptions: true,
        followRedirects: true
      });
      last = { s: resp.getResponseCode(), b: resp.getContentText() };
      // A real answer (even a 4xx) is final; only a server-side
      // failure is worth trying the second host for.
      if (last.s < 500) return wrap(last.s, last.b);
    } catch (err) {
      last = { s: 590, b: JSON.stringify({ error: "relay fetch failed" }) };
    }
  }
  return wrap(last.s, last.b);
}

function wrap(status, body) {
  return ContentService
    .createTextOutput(JSON.stringify({ s: status, b: body }))
    .setMimeType(ContentService.MimeType.JSON);
}
