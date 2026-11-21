// Stream Goat for the browser (iPhone, Android, computers): a static web app on GitHub Pages that uses
// Stremio add-ons directly. No server: add-ons, My list, history and progress are saved in this browser, with
// automatic backups kept in the browser (IndexedDB) and, after signing in with Google, in Google Drive.
// CloudStream extensions can't run in a browser; they need the Linux/Android apps (or the server web app).
"use strict";

const $view = document.getElementById("view");
const $tabs = document.getElementById("tabs");
const DEFAULT_ADDONS = [
  // What's trending on Netflix, HBO Max, Disney+, Prime Video and Apple TV+ (catalogs only): first, so Home opens on it.
  "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/manifest.json",
  "https://v3-cinemeta.strem.io/manifest.json", // catalogs and details (IMDb), no streams
  "https://opensubtitles-v3.strem.io/manifest.json", // subtitles
];
const cache = { meta: {}, home: null, live: null };

// ---------- helpers ----------

const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const qs = (o) => Object.entries(o).filter(([, v]) => v != null && v !== "").map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join("&");
const icons = {
  play: '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>',
  back: '<svg viewBox="0 0 24 24"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>',
  add: '<svg viewBox="0 0 24 24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg>',
  check: '<svg viewBox="0 0 24 24"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>',
  list: '<svg viewBox="0 0 24 24"><path d="M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z"/></svg>',
  subs: '<svg viewBox="0 0 24 24"><path d="M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM4 12h4v2H4v-2zm10 6H4v-2h10v2zm6 0h-4v-2h4v2zm0-4H10v-2h10v2z"/></svg>',
  next: '<svg viewBox="0 0 24 24"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>',
  trash: '<svg viewBox="0 0 24 24"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>',
  pause: '<svg viewBox="0 0 24 24"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>',
  rew: '<svg viewBox="0 0 24 24"><path d="M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z"/><text x="12" y="16.6" font-size="7" font-weight="700" text-anchor="middle" font-family="Arial, sans-serif">10</text></svg>',
  fwd: '<svg viewBox="0 0 24 24"><path d="M12 5V1l5 5-5 5V7c-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69 6-6h2c0 4.42-3.58 8-8 8s-8-3.58-8-8 3.58-8 8-8z"/><text x="12" y="16.6" font-size="7" font-weight="700" text-anchor="middle" font-family="Arial, sans-serif">10</text></svg>',
  vol: '<svg viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/></svg>',
  mute: '<svg viewBox="0 0 24 24"><path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"/></svg>',
  audio: '<svg viewBox="0 0 24 24"><path d="M12 3v9.28c-.47-.17-.97-.28-1.5-.28C8.01 12 6 14.01 6 16.5S8.01 21 10.5 21c2.31 0 4.2-1.75 4.45-4H15V6h4V3h-7z"/></svg>',
  speed: '<svg viewBox="0 0 24 24"><path d="M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z"/></svg>',
  hd: '<svg viewBox="0 0 24 24"><path d="M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm-8 12H9.5v-2h-2v2H6V9h1.5v2.5h2V9H11v6zm2-6h4c.55 0 1 .45 1 1v4c0 .55-.45 1-1 1h-4V9zm1.5 4.5h2v-3h-2v3z"/></svg>',
  pip: '<svg viewBox="0 0 24 24"><path d="M19 11h-8v6h8v-6zm4 8V4.98C23 3.88 22.1 3 21 3H3c-1.1 0-2 .88-2 1.98V19c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2zm-2 .02H3V4.97h18v14.05z"/></svg>',
  fs: '<svg viewBox="0 0 24 24"><path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"/></svg>',
  fsExit: '<svg viewBox="0 0 24 24"><path d="M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z"/></svg>',
  info: '<svg viewBox="0 0 24 24"><path d="M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z"/></svg>',
  google: '<svg viewBox="0 0 48 48"><path fill="#FFC107" d="M43.6 20.5H42V20H24v8h11.3C33.7 32.7 29.2 36 24 36c-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34.1 6.1 29.3 4 24 4 12.9 4 4 12.9 4 24s8.9 20 20 20 20-8.9 20-20c0-1.3-.1-2.4-.4-3.5z"/><path fill="#FF3D00" d="M6.3 14.7l6.6 4.8C14.7 15.1 19 12 24 12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34.1 6.1 29.3 4 24 4 16.3 4 9.7 8.3 6.3 14.7z"/><path fill="#4CAF50" d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2C29.2 35.1 26.7 36 24 36c-5.2 0-9.6-3.3-11.3-8l-6.5 5C9.5 39.6 16.2 44 24 44z"/><path fill="#1976D2" d="M43.6 20.5H42V20H24v8h11.3c-.8 2.2-2.2 4.2-4.1 5.6l6.2 5.2C37 39.2 44 34 44 24c0-1.3-.1-2.4-.4-3.5z"/></svg>',
};
const clock = (s) => { s = Math.floor(s || 0); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60); return h ? `${h}h ${m}m` : `${m}m`; };

// ---------- Report a glitch: last steps and errors, into GitHub's new-issue page (nothing is sent by the page) ----------

const glitch = { steps: [], errors: [] };
function step(text) {
  const t = new Date().toTimeString().slice(0, 8);
  glitch.steps.push(`${t} ${String(text).replace(/\s+/g, " ").slice(0, 160)}`);
  if (glitch.steps.length > 20) glitch.steps.shift();
}
function noteError(text) {
  glitch.errors.push(String(text).slice(0, 400));
  if (glitch.errors.length > 10) glitch.errors.shift();
  step(`Error: ${String(text).split("\n")[0].slice(0, 120)}`);
}
window.addEventListener("error", (e) => noteError(`${e.message} (${(e.filename || "").split("/").pop()}:${e.lineno})`));
window.addEventListener("unhandledrejection", (e) => noteError(`Unhandled: ${(e.reason && (e.reason.stack || e.reason.message)) || e.reason}`));
const redact = (t) => String(t)
  .replace(/(access_token|refresh_token|id_token|token|api[_-]?key|password)(["'=:\s]+)[^\s"'&,]+/gi, "$1$2…")
  .replace(/[\w.+-]+@[\w-]+\.[\w.]+/g, "…@…");
function glitchReport() {
  const lines = [
    `Stream Goat web on ${navigator.userAgent}`,
    `Screen ${window.innerWidth}x${window.innerHeight} @${window.devicePixelRatio}x · ${navigator.onLine ? "online" : "offline"} · ${(() => { try { return addons().length; } catch { return "?"; } })()} add-ons`,
    `Page: ${location.hash || "#/home"}`,
  ];
  if (glitch.steps.length) lines.push("", "Last steps:", ...glitch.steps.slice(-12).map((s) => "  " + s));
  if (glitch.errors.length) lines.push("", "Errors:", ...glitch.errors.slice(-6));
  return redact(lines.join("\n"));
}
function reportGlitch() {
  sheet("Report a glitch", `
    <p class="muted small">Say what looks wrong. GitHub opens with the details below filled in; add a screenshot there if you can and press Submit. Nothing is sent by this page.</p>
    <textarea class="field" id="g-what" rows="3" placeholder="What went wrong? e.g. &quot;The play button does nothing&quot;"></textarea>
    <details style="margin-top:10px"><summary class="muted small">What the page adds</summary><pre class="glitch-pre">${esc(glitchReport())}</pre></details>
    <div class="actions-line"><button class="btn primary" id="g-send">Send on GitHub</button><button class="btn small" id="g-copy">Copy details</button></div>`, (box) => {
    const what = () => box.querySelector("#g-what").value.trim();
    box.querySelector("#g-send").addEventListener("click", () => {
      const first = what().split("\n")[0].slice(0, 70);
      const title = first ? `Glitch (web): ${first}` : `Glitch (web): ${location.hash || "#/home"}`;
      const body = `**What went wrong**\n${what() || "(describe what you saw and what you expected)"}\n\n**Details from the page**\n\`\`\`\n${glitchReport().slice(0, 5500)}\n\`\`\`\n`;
      window.open(`https://github.com/sherazi5110/stream-goat/issues/new?title=${encodeURIComponent(title)}&body=${encodeURIComponent(body)}`, "_blank", "noopener");
    });
    box.querySelector("#g-copy").addEventListener("click", async () => {
      try { await navigator.clipboard.writeText((what() ? what() + "\n\n" : "") + glitchReport()); toast("Copied"); } catch { toast("Couldn't copy here"); }
    });
  });
}

let toastTimer;
function toast(text) {
  step(`Message: ${text}`);
  const t = document.getElementById("toast");
  const host = document.fullscreenElement || document.webkitFullscreenElement || document.body;
  if (t.parentNode !== host) host.appendChild(t);
  t.textContent = text; t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => (t.hidden = true), 3500);
}

function sheet(title, html, onReady) {
  const back = document.createElement("div");
  back.className = "sheet-back";
  back.innerHTML = `<div class="sheet"><div class="grip"></div>${title ? `<h3>${esc(title)}</h3>` : ""}${html}</div>`;
  back.addEventListener("click", (e) => { if (e.target === back) back.remove(); });
  (document.fullscreenElement || document.webkitFullscreenElement || document.body).appendChild(back); // stays visible in full screen
  onReady && onReady(back.querySelector(".sheet"), () => back.remove());
}

// ---------- storage (this browser only) ----------

const store = {
  get(key, def) { try { const v = localStorage.getItem("cs." + key); return v == null ? def : JSON.parse(v); } catch { return def; } },
  set(key, value) { try { localStorage.setItem("cs." + key, JSON.stringify(value)); } catch { toast("Couldn't save: browser storage is full or blocked"); } },
};

// ---------- Stremio add-ons ----------

function manifestUrl(input) {
  let u = input.trim();
  if (u.startsWith("stremio://")) u = "https://" + u.slice("stremio://".length);
  if (!/^https?:\/\//.test(u)) u = "https://" + u;
  u = u.replace(/\/configure\/?$/, "");
  if (!/manifest\.json(\?.*)?$/.test(u)) u = u.replace(/\/?$/, "/manifest.json");
  return u;
}
const baseOf = (url) => url.replace(/\/manifest\.json(\?.*)?$/, "");

function addons() { return store.get("addons", []); }

async function fetchJson(url, ms = 20000) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), ms);
  try {
    const r = await fetch(url, { signal: ctl.signal, referrerPolicy: "no-referrer" });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return await r.json();
  } catch (e) {
    if (e.name === "AbortError") throw new Error("The add-on took too long to answer");
    if (e instanceof TypeError) throw new Error("The add-on couldn't be reached (offline, or it doesn't allow browser apps)");
    throw e;
  } finally { clearTimeout(timer); }
}

class NeedsConfig extends Error {
  constructor(m, url) { super(`${m.name} needs setting up first`); this.manifest = m; this.configure = baseOf(url) + "/configure"; }
}

async function installAddon(input, force, first) {
  const url = manifestUrl(input);
  let m;
  try { m = await fetchJson(url); } catch (e) {
    // 404: nothing at that address. Often a CloudStream repository link (those work only in the apps) or a page address.
    if (/HTTP 40[34]/.test(e.message)) throw new Error(`No Stremio add-on at ${new URL(url).host} (${e.message.replace("HTTP ", "error ")}). ` +
      "Check the link: it should end in /manifest.json. CloudStream repositories (repo.json) work only in the Stream Goat apps.");
    throw e;
  }
  if (!m || !m.id || !m.resources) throw new Error("That isn't a Stremio add-on manifest");
  // The plain link of a configurable add-on (debrid services, etc.) has no account in it: it returns nothing.
  if (!force && m.behaviorHints && m.behaviorHints.configurationRequired) throw new NeedsConfig(m, url);
  const list = addons().filter((a) => a.manifest.id !== m.id);
  if (first) list.unshift({ url, manifest: m }); else list.push({ url, manifest: m });
  store.set("addons", list);
  cache.home = null; cache.live = null;
  return m;
}

/** Each default add-on is added once (a removed one stays removed); one added later reaches existing visitors too. */
async function ensureDefaults() {
  let offered = store.get("preinstalled", null);
  // Visitors from before this list had the first defaults offered already.
  if (!offered) offered = store.get("defaultsDone", false) ? DEFAULT_ADDONS.slice(1) : [];
  for (const [i, u] of DEFAULT_ADDONS.entries()) {
    if (offered.includes(u)) continue;
    if (!addons().some((a) => a.url === u)) { try { await installAddon(u, false, i === 0); } catch { continue; /* offline: next time */ } }
    offered = [...offered, u];
    store.set("preinstalled", offered);
  }
  store.set("defaultsDone", true);
}

function supports(a, resource, type, id) {
  const m = a.manifest;
  const r = (m.resources || []).find((x) => (typeof x === "string" ? x : x.name) === resource);
  if (!r) return false;
  const types = (typeof r === "object" && r.types) || m.types;
  const prefixes = (typeof r === "object" && r.idPrefixes) || m.idPrefixes;
  return (!types || !type || types.includes(type)) && (!prefixes || !prefixes.length || !id || prefixes.some((p) => id.startsWith(p)));
}

const res = (a, resource, type, id, extra) =>
  fetchJson(`${baseOf(a.url)}/${resource}/${encodeURIComponent(type)}/${encodeURIComponent(id)}${extra ? "/" + extra : ""}.json`);

const isLiveType = (t) => t === "tv" || t === "channel" || t === "events";

/** Home catalogs: those that need no input (no required search or genre). */
function homeCatalogs(live) {
  return addons().flatMap((a) => (a.manifest.catalogs || [])
    .filter((c) => !(c.extra || []).some((e) => e.isRequired) && !(c.extraRequired || []).length && isLiveType(c.type) === live)
    .map((c) => ({ addon: a, catalog: c })));
}

function item(meta, addon) {
  return { type: meta.type, id: meta.id, name: meta.name, poster: meta.poster, shape: meta.posterShape, addon: addon ? addon.manifest.id : "" };
}

function poster(it, wide) {
  const href = isLiveType(it.type) ? `#/watch?${qs({ type: it.type, id: it.id, v: it.id, name: it.name })}` : `#/title?${qs({ type: it.type, id: it.id, a: it.addon })}`;
  const img = it.poster ? `<img loading="lazy" src="${esc(it.poster)}" alt="" referrerpolicy="no-referrer" onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'noimg',textContent:${esc(JSON.stringify(it.name || ""))}}))">`
    : `<div class="noimg">${esc(it.name)}</div>`;
  const progress = it.progress > 0 ? `<div class="bar-progress"><i style="width:${Math.round(it.progress * 100)}%"></i></div>` : "";
  return `<a class="card ${wide ? "wide" : ""}" href="${href}"><div class="pic">${img}${progress}</div><div class="name">${esc(it.name)}</div>${it.sub ? `<div class="sub">${esc(it.sub)}</div>` : ""}</a>`;
}
const shelf = (title, items, wide) => items.length ? `<section class="row"><h2>${esc(title)}</h2><div class="shelf-wrap">
  <button class="arrow left" aria-label="Back" onclick="this.parentNode.querySelector('.shelf').scrollBy({left:-this.parentNode.clientWidth*0.8,behavior:'smooth'})">‹</button>
  <div class="shelf">${items.map((i) => poster(i, wide)).join("")}</div>
  <button class="arrow right" aria-label="More" onclick="this.parentNode.querySelector('.shelf').scrollBy({left:this.parentNode.clientWidth*0.8,behavior:'smooth'})">›</button></div></section>` : "";

/** Continue Watching: the episode's still (or the backdrop), time left, a progress bar and "remove from row". */
function cwCard(e) {
  const href = `#/watch?${qs({ type: e.type, id: e.id, v: e.video })}`;
  const pic = e.background || e.poster;
  return `<div class="card cw"><a href="${href}"><div class="pic">${pic ? `<img loading="lazy" src="${esc(pic)}" alt="" referrerpolicy="no-referrer">` : `<div class="noimg">${esc(e.name)}</div>`}
    <div class="playmark"><i>${icons.play}</i></div>
    <div class="over"><b>${esc(e.name)}</b><span>${esc([e.label, `${clock(e.dur - e.pos)} left`].filter(Boolean).join(" · "))}</span></div>
    <div class="bar-progress"><i style="width:${Math.round(Math.min(e.pos / e.dur, 1) * 100)}%"></i></div></div></a>
    <button class="remove" data-hide="${esc(e.id)}" aria-label="Remove from Continue Watching" title="Remove from Continue Watching">✕</button></div>`;
}
function continueRow() {
  const list = lib.continueWatching();
  return list.length ? `<section class="row" id="cw"><h2>Continue Watching</h2><div class="shelf-wrap"><div class="shelf">${list.map(cwCard).join("")}</div></div></section>` : "";
}
function wireContinue() {
  $view.querySelectorAll("[data-hide]").forEach((b) => b.addEventListener("click", (ev) => {
    ev.preventDefault(); lib.hideFromContinue(b.dataset.hide);
    const card = b.closest(".card"); card && card.remove();
    if (!$view.querySelector("#cw .card")) { const r = $view.querySelector("#cw"); r && r.remove(); }
    toast("Removed from Continue Watching");
  }));
}

const typeName = (t) => ({ movie: "Movies", series: "Series", anime: "Anime", tv: "TV", channel: "Channels" }[t] || t);

// ---------- library (this browser) ----------

const lib = {
  history: () => store.get("history", {}),
  progress: (videoId) => store.get("progress", {})[videoId],
  list: () => store.get("list", []),
  inList: (id) => lib.list().some((x) => x.id === id),
  toggleList(meta) {
    let l = lib.list();
    const on = !l.some((x) => x.id === meta.id);
    l = on ? [{ type: meta.type, id: meta.id, name: meta.name, poster: meta.poster, ts: Date.now() }, ...l] : l.filter((x) => x.id !== meta.id);
    store.set("list", l);
    return on;
  },
  record(meta, video, label, pos, dur) {
    const p = store.get("progress", {});
    p[video] = { pos, dur, ts: Date.now() };
    store.set("progress", p);
    const h = lib.history();
    h[meta.id] = { type: meta.type, id: meta.id, name: meta.name, poster: meta.poster, background: meta.background, video, label, pos, dur, ts: Date.now() };
    store.set("history", h);
  },
  /** Started (a minute in, or 1%) and not finished (95%): newest first, like Netflix's row. */
  continueWatching: () => Object.values(lib.history())
    .filter((e) => e.dur > 0 && (e.pos > 60 || e.pos / e.dur > 0.01) && e.pos / e.dur < 0.95 && !e.hidden).sort((a, b) => b.ts - a.ts),
  /** "Remove from row": stays in History, leaves Continue Watching. */
  hideFromContinue(id) { const h = lib.history(); if (h[id]) { h[id].hidden = true; store.set("history", h); } },
};

// ---------- backups: automatic copies in this browser, and Google Drive ----------

const BACKUP_FORMAT = "cloudstream-web-backup";
function exportData() {
  return { format: BACKUP_FORMAT, version: 2, created: Date.now(), addons: addons().map((a) => a.url), list: lib.list(),
    history: lib.history(), progress: store.get("progress", {}), searches: store.get("searches", []) };
}
async function importData(data) {
  if (!data || data.format !== BACKUP_FORMAT) throw new Error("That isn't a Stream Goat web backup");
  store.set("list", data.list || []); store.set("history", data.history || {}); store.set("progress", data.progress || {});
  if (data.searches) store.set("searches", data.searches);
  store.set("addons", []); store.set("defaultsDone", true); store.set("preinstalled", DEFAULT_ADDONS);
  for (const u of data.addons || []) { try { await installAddon(u); } catch { /* unreachable add-on: skipped */ } }
  cache.home = null; cache.live = null;
}
const summary = (d) => `${(d.addons || []).length} add-ons · ${(d.list || []).length} in My list · ${Object.keys(d.history || {}).length} watched`;
async function hashOf(data) {
  const { created, ...rest } = data;
  const bytes = new TextEncoder().encode(JSON.stringify(rest));
  try { return [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))].map((b) => b.toString(16).padStart(2, "0")).join(""); }
  catch { let h = 0; for (const b of bytes) h = (h * 31 + b) | 0; return String(h); }
}
const deviceId = () => store.get("deviceId", null) || (() => { const id = (crypto.randomUUID ? crypto.randomUUID() : String(Math.random()).slice(2)); store.set("deviceId", id); return id; })();
function deviceName() {
  const ua = navigator.userAgent;
  const os = /iPhone/.test(ua) ? "iPhone" : /iPad/.test(ua) ? "iPad" : /Android/.test(ua) ? "Android" : /Windows/.test(ua) ? "Windows" : /Mac OS/.test(ua) ? "Mac" : /Linux/.test(ua) ? "Linux" : "a computer";
  const br = /Edg\//.test(ua) ? "Edge" : /SamsungBrowser/.test(ua) ? "Samsung Internet" : /Firefox\//.test(ua) ? "Firefox" : /CriOS|Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
  return `Web: ${br} on ${os}`;
}

/** Copies in this browser (IndexedDB, apart from the app's own storage): every 5 minutes when something changed. */
const localBackups = {
  KEEP: 12,
  db() {
    return this._db || (this._db = new Promise((ok, fail) => {
      const r = indexedDB.open("streamgoat-backups", 1);
      r.onupgradeneeded = () => r.result.createObjectStore("snapshots", { keyPath: "created" });
      r.onsuccess = () => ok(r.result); r.onerror = () => fail(r.error);
    }));
  },
  async tx(mode, fn) {
    const db = await this.db();
    return new Promise((ok, fail) => { const t = db.transaction("snapshots", mode); const out = fn(t.objectStore("snapshots")); t.oncomplete = () => ok(out && out.result); t.onerror = () => fail(t.error); });
  },
  async list() { return ((await this.tx("readonly", (st) => st.getAll())) || []).sort((a, b) => b.created - a.created); },
  async save(force) {
    const data = exportData();
    const hash = await hashOf(data);
    if (!force && hash === store.get("lastLocalHash", "")) return false;
    if (!data.addons.length && !data.list.length && !Object.keys(data.history).length) return false; // nothing worth keeping
    await this.tx("readwrite", (st) => st.put(data));
    const all = await this.list();
    for (const old of all.slice(this.KEEP)) await this.tx("readwrite", (st) => st.delete(old.created));
    store.set("lastLocalHash", hash);
    return true;
  },
};

/**
 * Google Drive backup, in the "Stream Goat backups" folder the Stream Goat apps use (scope drive.file: only the files
 * Stream Goat made are visible to it), with Google sign-in for websites.
 */
const cloud = {
  SCOPE: "https://www.googleapis.com/auth/drive.file",
  FOLDER: "Stream Goat backups",
  get clientId() { return (window.STREAMGOAT_CONFIG || {}).googleClientId || ""; },
  get configured() { return !!this.clientId; },
  get signedIn() { return !!store.get("google", null); },
  get email() { return (store.get("google", null) || {}).email || ""; },
  get valid() { const t = store.get("googleToken", null); return !!t && Date.now() < t.exp; },
  loadGis() {
    return this._gis || (this._gis = new Promise((ok, fail) => {
      if (window.google && google.accounts && google.accounts.oauth2) return ok();
      const s = document.createElement("script"); s.src = "https://accounts.google.com/gsi/client"; s.async = true;
      s.onload = () => ok(); s.onerror = () => { this._gis = null; fail(new Error("Couldn't reach Google")); };
      document.head.appendChild(s);
    }));
  },
  /** Opens Google's sign-in window (needs a tap). Access lasts an hour; "Connect" renews it without asking again. */
  async connect(firstTime) {
    if (!this.configured) throw new Error("Google sign-in isn't set up on this website yet");
    await this.loadGis();
    const r = await new Promise((ok, fail) => {
      const client = google.accounts.oauth2.initTokenClient({
        client_id: this.clientId, scope: this.SCOPE, prompt: firstTime ? "consent" : "",
        hint: this.email || undefined,
        callback: (res) => res.error ? fail(new Error(res.error_description || res.error)) : ok(res),
        error_callback: (e) => fail(new Error(e.type === "popup_closed" ? "Sign-in was closed" : e.message || e.type)),
      });
      client.requestAccessToken();
    });
    store.set("googleToken", { access: r.access_token, exp: Date.now() + (Number(r.expires_in || 3600) - 60) * 1000 });
    const about = await this.api("/drive/v3/about?fields=user(emailAddress,displayName,photoLink)").catch(() => ({}));
    const u = about.user || {};
    store.set("google", { email: u.emailAddress || u.displayName || "your Google account", photo: u.photoLink || "" });
    updateAccount();
  },
  async api(path, opts = {}) {
    const t = store.get("googleToken", null);
    if (!t || Date.now() >= t.exp) throw new Error("Connect Google again (access lasts an hour in a browser)");
    const r = await fetch("https://www.googleapis.com" + path, { ...opts, headers: { ...(opts.headers || {}), Authorization: "Bearer " + t.access } });
    if (r.status === 401) { store.set("googleToken", null); throw new Error("Connect Google again"); }
    if (!r.ok && r.status !== 204) throw new Error(`Google Drive answered ${r.status}`);
    const type = r.headers.get("content-type") || "";
    return r.status === 204 ? null : type.includes("json") ? r.json() : r.text();
  },
  /** Every backup in the account: this website's ("web") and the Stream Goat apps' (phone, TV, PC). */
  async backups() {
    const q = encodeURIComponent("(name contains 'stream-goat-backup' or name contains 'stream-goat-web') and trashed = false");
    const r = await this.api(`/drive/v3/files?q=${q}&pageSize=50&orderBy=modifiedTime%20desc&fields=files(id,name,modifiedTime,appProperties)`);
    return (r.files || []).map((f) => ({ id: f.id, web: f.name.startsWith("stream-goat-web"), modified: Date.parse(f.modifiedTime) || 0,
      device: (f.appProperties || {}).device || "A device", app: (f.appProperties || {}).app || "", mine: (f.appProperties || {}).deviceId === deviceId() }));
  },
  async backupNow(onlyIfChanged) {
    const data = exportData();
    const hash = await hashOf(data);
    if (onlyIfChanged && hash === store.get("lastCloudHash", "")) return false;
    const mine = (await this.backups()).find((b) => b.mine && b.web);
    const meta = { name: `stream-goat-web-${deviceId()}.json`, appProperties: { device: deviceName(), app: "web", deviceId: deviceId() } };
    if (!mine) meta.parents = [await this.folder()];
    const boundary = "sg" + Math.random().toString(36).slice(2);
    const body = `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(meta)}\r\n` +
      `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(data)}\r\n--${boundary}--`;
    await this.api(`/upload/drive/v3/files${mine ? "/" + mine.id : ""}?uploadType=multipart&fields=id`,
      { method: mine ? "PATCH" : "POST", headers: { "Content-Type": `multipart/related; boundary=${boundary}` }, body });
    store.set("lastCloudHash", hash); store.set("lastCloudBackup", Date.now());
    return true;
  },
  /** A web backup restores everything; an app backup (phone, TV, PC) brings its Stremio add-ons here. */
  async restore(b) {
    const text = await this.api(`/drive/v3/files/${b.id}?alt=media`);
    const data = typeof text === "string" ? JSON.parse(text) : text;
    if (data.format === BACKUP_FORMAT) { await importData(data); return "Restored"; }
    if (data.format === "cloudstream-linxdroid-backup") {
      const raw = (((data.stores || {}).stremio_addons || {}).addons || {}).s || "[]";
      const urls = JSON.parse(raw).map((a) => a.url).filter(Boolean);
      let n = 0;
      for (const u of urls) { try { await installAddon(u); n++; } catch { /* skipped */ } }
      return n ? `Added ${n} Stremio add-on(s) from ${b.device}` : `${b.device} has no Stremio add-ons to bring here`;
    }
    throw new Error("That backup isn't one this website can read");
  },
  /** The "Stream Goat backups" folder (made by any Stream Goat app or this website before), or a new one. */
  async folder() {
    const q = encodeURIComponent(`mimeType = 'application/vnd.google-apps.folder' and name = '${this.FOLDER}' and trashed = false`);
    const r = await this.api(`/drive/v3/files?q=${q}&pageSize=10&orderBy=createdTime&fields=files(id)`);
    if (r.files && r.files.length) return r.files[0].id;
    const made = await this.api("/drive/v3/files?fields=id", { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: this.FOLDER, mimeType: "application/vnd.google-apps.folder" }) });
    return made.id;
  },
  async remove(b) { await this.api(`/drive/v3/files/${b.id}`, { method: "DELETE" }); },
  signOut() {
    const t = store.get("googleToken", null);
    if (t && window.google && google.accounts && google.accounts.oauth2) google.accounts.oauth2.revoke(t.access, () => {});
    store.set("googleToken", null); store.set("google", null); store.set("lastCloudHash", "");
    updateAccount();
  },
};

/** Every 5 minutes (and when the page is put away): a copy in this browser, and in Google Drive when connected. */
async function autoBackup() {
  try { await localBackups.save(); } catch { /* private mode: no IndexedDB */ }
  if (cloud.signedIn && cloud.valid) { try { await cloud.backupNow(true); } catch { /* next time */ } }
}
setInterval(autoBackup, 5 * 60 * 1000);
document.addEventListener("visibilitychange", () => { if (document.visibilityState === "hidden") autoBackup(); });
// Ask the browser to keep this site's storage (it may otherwise clear it when space runs low).
if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});

function updateAccount() {
  const a = document.getElementById("account");
  if (!a) return;
  const g = store.get("google", null);
  a.innerHTML = g && g.photo ? `<img src="${esc(g.photo)}" alt="" referrerpolicy="no-referrer">` : `<span>${esc(((g && g.email) || "S").slice(0, 1).toUpperCase())}</span>`;
}
updateAccount();

// ---------- router ----------

const routes = { home, search, title, watch, live, library, more };
async function router() {
  const [path, query] = location.hash.replace(/^#\/?/, "").split("?");
  const name = routes[path] ? path : "home";
  const params = Object.fromEntries(new URLSearchParams(query || ""));
  step(`Opened ${name}${params.id ? " " + params.id : ""}${params.type ? " (" + params.type + ")" : ""}`);
  $tabs.hidden = name === "watch";
  document.body.classList.toggle("no-tabs", name === "watch");
  document.body.classList.toggle("home-page", name === "home" || name === "title");
  $tabs.querySelectorAll("a").forEach((a) => a.classList.toggle("on", a.dataset.tab === name || (name === "title" && a.dataset.tab === "home")));
  const navKey = name === "home" ? (params.type || "home") : name === "library" && params.tab === "list" ? "list" : name;
  document.querySelectorAll("#topnav [data-nav]").forEach((a) => a.classList.toggle("on", a.dataset.nav === navKey));
  const liveShown = homeCatalogs(true).length ? "" : "none";
  document.getElementById("tab-live").style.display = liveShown;
  document.getElementById("nav-live").style.display = liveShown;
  onScroll();
  window.scrollTo(0, 0);
  try { await routes[name](params); } catch (e) {
    noteError(e.stack || e.message);
    $view.innerHTML = `<div class="page"><div class="empty"><b>Something went wrong</b>${esc(e.message)}<br><button class="btn small" id="err-report" style="margin-top:12px">Report it</button></div></div>`;
    $view.querySelector("#err-report").addEventListener("click", reportGlitch);
  }
}
window.addEventListener("hashchange", router);
function onScroll() { document.getElementById("topnav").classList.toggle("solid", window.scrollY > 40 || !document.body.classList.contains("home-page")); }
window.addEventListener("scroll", onScroll, { passive: true });

// ---------- home ----------

async function home(params = {}) {
  const here = location.hash;
  $view.innerHTML = `<div class="page"><div class="spinner" style="margin-top:140px"></div></div>`;
  await ensureDefaults();
  if (location.hash !== here) return; // the visitor moved on while this loaded
  offerLocalRestore();
  const only = params.type;
  const catalogs = homeCatalogs(false).filter(({ catalog }) => !only || catalog.type === only);
  if (!catalogs.length) {
    $view.innerHTML = `<div class="page" style="padding-top:calc(var(--safe-top) + var(--nav) + 20px)">${continueRow()}
      <div class="empty"><b>${only ? `No ${esc(typeName(only).toLowerCase())} catalogs` : "No add-ons yet"}</b>Add Stremio add-ons in <a href="#/more" style="color:var(--accent)">More</a>.</div></div>`;
    wireContinue();
    return;
  }
  const key = only || "all";
  cache.home = cache.home || {};
  const rows = cache.home[key] || catalogs.map(({ addon, catalog }) => ({ title: `${catalog.name || typeName(catalog.type)} · ${typeName(catalog.type)}`, addon, catalog, items: null }));
  cache.home[key] = rows;
  let heroMeta = null;
  const render = () => {
    if (location.hash !== here) return;
    const first = rows.flatMap((r) => r.items || []).find((i) => i.poster);
    const m = heroMeta && first && heroMeta.id === first.id ? heroMeta : null;
    const hist = first && lib.history()[first.id];
    const play = first && (hist ? `#/watch?${qs({ type: first.type, id: first.id, v: hist.video, a: first.addon })}`
      : first.type === "movie" ? `#/watch?${qs({ type: first.type, id: first.id, v: first.id, a: first.addon })}` : `#/title?${qs({ type: first.type, id: first.id, a: first.addon })}`);
    $view.innerHTML = `<div class="page">
      ${first ? `<div class="hero"><img class="backdrop" src="${esc((m && m.background) || first.poster)}" alt="" referrerpolicy="no-referrer">
        <div class="info"><div class="kicker"><img src="icon-192.png" alt=""> ${first.type === "series" ? "SERIES" : "FILM"}</div>
          ${m && m.logo ? `<img class="logo" src="${esc(m.logo)}" alt="${esc(first.name)}" referrerpolicy="no-referrer" onerror="this.outerHTML='<h1>${esc(first.name).replace(/'/g, "")}</h1>'">` : `<h1>${esc(first.name)}</h1>`}
          ${m && m.description ? `<p class="desc">${esc(m.description)}</p>` : ""}
          <div class="actions"><a class="btn white" href="${play}">${icons.play} ${hist ? "Resume" : "Play"}</a>
            <a class="btn grey" href="#/title?${qs({ type: first.type, id: first.id, a: first.addon })}">${icons.info} More info</a></div></div></div>` : ""}
      ${first ? "" : `<div style="height:calc(var(--safe-top) + var(--nav) + 10px)"></div>`}
      ${continueRow()}
      ${rows.map((r) => r.items ? shelf(r.title, r.items) : "").join("")}
      ${rows.some((r) => !r.items) ? '<div class="spinner"></div>' : ""}
      ${rows.every((r) => r.items && !r.items.length) ? `<div class="empty"><img src="icon-192.png" alt="" class="empty-logo"><b>Couldn't load the catalogs</b>${navigator.onLine === false
        ? "You're offline." : "Your add-ons didn't answer. Check your connection, or try again in a moment."}<br><br>
        <button class="btn" onclick="cache.home=null;router()">Try again</button></div>` : ""}
    </div>`;
    wireContinue();
    onScroll();
    if (first && !heroMeta) {
      heroMeta = { id: first.id };
      loadMeta(first.type, first.id, first.addon).then((meta) => { heroMeta = { ...meta, id: first.id }; render(); }).catch(() => {});
    }
  };
  render();
  await Promise.all(rows.filter((r) => !r.items).map(async (r) => {
    try { r.items = ((await res(r.addon, "catalog", r.catalog.type, r.catalog.id)).metas || []).slice(0, 40).map((m) => item(m, r.addon)); }
    catch { r.items = []; }
    render();
  }));
}

/** This browser's storage was cleared but a backup copy is still here: offer it once. */
let offeredRestore = false;
async function offerLocalRestore() {
  if (offeredRestore || store.get("list", []).length || Object.keys(lib.history()).length || addons().length > DEFAULT_ADDONS.length) return;
  offeredRestore = true;
  const copies = await localBackups.list().catch(() => []);
  const last = copies[0];
  if (!last) return;
  sheet("Restore your backup?", `<p class="muted">This browser's Stream Goat data looks empty, but a backup from
    <b>${esc(new Date(last.created).toLocaleString())}</b> is still here (${esc(summary(last))}).</p>
    <button class="btn primary wide" id="yes">Restore</button><button class="btn wide" style="margin-top:10px" id="no">Not now</button>`, (el, close) => {
    el.querySelector("#yes").addEventListener("click", async () => { close(); await importData(last); toast("Restored"); router(); });
    el.querySelector("#no").addEventListener("click", close);
  });
}

// ---------- search ----------

async function search(params) {
  $view.innerHTML = `<div class="page"><div class="bar"><h1>Search</h1></div>
    <form><input class="field" type="search" name="q" placeholder="Movies, series, anime…" value="${esc(params.q || "")}" enterkeyhint="search" autocomplete="off"></form>
    <div id="results"></div></div>`;
  const form = $view.querySelector("form");
  const results = $view.querySelector("#results");
  form.addEventListener("submit", (e) => { e.preventDefault(); form.q.blur(); location.hash = `#/search?${qs({ q: form.q.value.trim() })}`; });
  // Suggestions while typing (Cinemeta titles), like the apps; a tap searches.
  const box = document.createElement("div");
  box.className = "suggest-box"; box.hidden = true;
  form.appendChild(box);
  let timer, seq = 0;
  form.q.addEventListener("input", () => {
    clearTimeout(timer);
    const q = form.q.value.trim();
    if (q.length < 2) { box.hidden = true; return; }
    timer = setTimeout(async () => {
      const mine = ++seq;
      const names = (await Promise.all(["movie", "series"].map((t) =>
        fetchJson(`https://v3-cinemeta.strem.io/catalog/${t}/top/search=${encodeURIComponent(q)}.json`, 8000).then((r) => (r.metas || []).map((m) => m.name)).catch(() => []))))
        .flat().filter(Boolean);
      if (mine !== seq) return;
      const list = [...new Set(names.sort((a, b) => (b.toLowerCase().startsWith(q.toLowerCase())) - (a.toLowerCase().startsWith(q.toLowerCase()))))].slice(0, 8);
      box.innerHTML = list.map((n) => `<button type="button" class="option">${esc(n)}</button>`).join("");
      box.hidden = !list.length;
      box.querySelectorAll("button").forEach((b) => b.addEventListener("click", () => { form.q.value = b.textContent; box.hidden = true; form.requestSubmit(); }));
    }, 250);
  });
  if (!params.q) { form.q.focus(); results.innerHTML = `<div class="empty">Searches every add-on that can search.</div>`; return; }
  const targets = addons().flatMap((a) => (a.manifest.catalogs || []).filter((c) => (c.extra || []).some((e) => e.name === "search") || (c.extraSupported || []).includes("search")).map((c) => ({ a, c })));
  if (!targets.length) { results.innerHTML = `<div class="empty"><b>No add-on can search</b>Add one in More.</div>`; return; }
  let pending = targets.length, found = 0;
  results.innerHTML = `<p class="muted small" id="status">Searching…</p><div id="rows"></div>`;
  const rows = results.querySelector("#rows");
  await Promise.all(targets.map(async ({ a, c }) => {
    try {
      const metas = (await res(a, "catalog", c.type, c.id, `search=${encodeURIComponent(params.q)}`)).metas || [];
      if (metas.length) { found += metas.length; rows.insertAdjacentHTML("beforeend", shelf(`${a.manifest.name} · ${c.name || typeName(c.type)}`, metas.slice(0, 40).map((m) => item(m, a)))); }
    } catch { /* this add-on failed; the others still show */ }
    pending--;
    const st = results.querySelector("#status");
    if (st) st.textContent = pending ? `Searching… ${pending} left` : found ? "" : `Nothing found for “${params.q}”.`;
  }));
}

// ---------- title page ----------

async function loadMeta(type, id, addonId) {
  const key = type + "\n" + id;
  if (cache.meta[key]) return cache.meta[key];
  const all = addons().filter((a) => supports(a, "meta", type, id));
  all.sort((x, y) => (y.manifest.id === addonId) - (x.manifest.id === addonId));
  for (const a of all) {
    try { const m = (await res(a, "meta", type, id)).meta; if (m) { cache.meta[key] = m; return m; } } catch { /* next */ }
  }
  throw new Error("None of your add-ons has details for this title");
}

function videosOf(meta) {
  const v = (meta.videos || []).slice();
  if (!v.length) return [{ id: (meta.behaviorHints && meta.behaviorHints.defaultVideoId) || meta.id, title: meta.name, movie: true }];
  v.sort((a, b) => ((a.season || 0) === 0) - ((b.season || 0) === 0) || (a.season || 0) - (b.season || 0) || (a.episode || 0) - (b.episode || 0));
  return v;
}
const label = (v) => v.movie ? "" : [v.season != null ? `S${v.season}` : "", v.episode != null ? `E${v.episode}` : ""].join("") + (v.title || v.name ? ` · ${v.title || v.name}` : "");

async function title(params) {
  const here = location.hash;
  $view.innerHTML = `<div class="page"><div class="spinner"></div></div>`;
  const meta = await loadMeta(params.type, params.id, params.a);
  if (location.hash !== here) return;
  const videos = videosOf(meta);
  const hist = lib.history()[meta.id];
  const target = (hist && videos.find((v) => v.id === hist.video)) || videos.find((v) => (v.season || 0) > 0) || videos[0];
  const tp = lib.progress(target.id);
  const resume = tp && tp.dur && tp.pos / tp.dur < 0.92 && tp.pos > 30;
  const playText = (resume ? "Resume" : "Play") + (target.movie ? "" : ` ${label(target).split(" · ")[0]}`) + (resume ? ` · ${clock(tp.pos)}` : "");
  const watchHref = (v) => `#/watch?${qs({ type: meta.type, id: meta.id, v: v.id, a: params.a })}`;
  const seasons = [...new Set(videos.filter((v) => !v.movie).map((v) => v.season ?? 0))];
  let season = target.season ?? seasons[0];
  const rating = parseFloat(meta.imdbRating);
  $view.innerHTML = `<div class="page">
    <div class="title-hero">${meta.background || meta.poster ? `<img src="${esc(meta.background || meta.poster)}" alt="" referrerpolicy="no-referrer">` : ""}</div>
    <button class="icon-btn back" onclick="history.length > 1 ? history.back() : (location.hash='#/home')">${icons.back}</button>
    <div class="title-info">${meta.logo ? `<img src="${esc(meta.logo)}" alt="${esc(meta.name)}" referrerpolicy="no-referrer" style="max-width:70%;max-height:90px;margin-bottom:10px" onerror="this.outerHTML='<h1>${esc(meta.name).replace(/'/g, "")}</h1>'">` : `<h1>${esc(meta.name)}</h1>`}
      <div class="meta">${rating ? `<span class="tag accent">IMDb ${rating.toFixed(1)}</span>` : ""}${meta.releaseInfo ? `<span>${esc(meta.releaseInfo)}</span>` : ""}
        ${meta.certification ? `<span class="tag">${esc(meta.certification)}</span>` : ""}${meta.runtime ? `<span>${esc(meta.runtime)}</span>` : ""}
        ${seasons.length ? `<span>${videos.filter((v) => !v.movie).length} episodes</span>` : ""}</div>
      <div class="title-actions">
        <a class="btn white" href="${watchHref(target)}">${icons.play} ${esc(playText)}</a>
        <div class="row2">
          <button class="btn ${lib.inList(meta.id) ? "on" : ""}" id="bm">${lib.inList(meta.id) ? icons.check : icons.add}<span>My list</span></button>
          <button class="btn" id="seen">${icons.check}<span>Watched</span></button>
          <a class="btn" href="#/search?${qs({ q: meta.name })}">${icons.list}<span>Search all</span></a>
        </div>
      </div>
      ${meta.description ? `<p class="plot clamped">${esc(meta.description)}</p>` : ""}
      ${(meta.genres || meta.genre || []).length ? `<div class="meta">${(meta.genres || meta.genre).slice(0, 6).map((g) => `<span class="tag">${esc(g)}</span>`).join("")}</div>` : ""}
      ${(meta.cast || []).length ? `<p class="muted small">Cast: ${esc(meta.cast.slice(0, 8).join(", "))}</p>` : ""}
      ${seasons.length ? `<h2>Episodes</h2><div id="episodes"></div>` : ""}
    </div></div>`;
  const renderEpisodes = () => {
    const box = $view.querySelector("#episodes");
    if (!box) return;
    box.innerHTML = (seasons.length > 1 ? `<div class="chips">${seasons.map((s) => `<button class="chip ${s === season ? "on" : ""}" data-s="${s}">${s === 0 ? "Specials" : `Season ${s}`}</button>`).join("")}</div>` : "") +
      videos.filter((v) => (v.season ?? 0) === season).map((v) => {
        const p = lib.progress(v.id);
        const frac = p && p.dur ? p.pos / p.dur : 0;
        return `<a class="ep" href="${watchHref(v)}"><div class="thumb">${v.thumbnail ? `<img loading="lazy" src="${esc(v.thumbnail)}" alt="" referrerpolicy="no-referrer">` : `<div class="noimg">E${esc(v.episode ?? "")}</div>`}
          ${frac > 0.02 ? `<div class="bar-progress"><i style="width:${Math.round(Math.min(frac, 1) * 100)}%"></i></div>` : ""}</div>
          <div class="t"><div class="code">${v.episode != null ? `E${v.episode}` : ""}</div><div class="n">${esc(v.title || v.name || `Episode ${v.episode ?? ""}`)}</div>
          ${frac > 0.92 ? '<div class="done">✓ Watched</div>' : v.overview || v.description ? `<div class="d">${esc(v.overview || v.description)}</div>` : ""}</div></a>`;
      }).join("");
    box.querySelectorAll("[data-s]").forEach((b) => b.addEventListener("click", () => { season = +b.dataset.s; renderEpisodes(); }));
  };
  renderEpisodes();
  const plot = $view.querySelector(".plot");
  plot && plot.addEventListener("click", () => plot.classList.toggle("clamped"));
  $view.querySelector("#bm").addEventListener("click", (e) => {
    const on = lib.toggleList(meta);
    e.currentTarget.classList.toggle("on", on);
    e.currentTarget.innerHTML = `${on ? icons.check : icons.add}<span>My list</span>`;
    toast(on ? "Added to My list" : "Removed from My list");
  });
  $view.querySelector("#seen").addEventListener("click", () => {
    const p = store.get("progress", {});
    videos.forEach((v) => { p[v.id] = { pos: 1, dur: 1, ts: Date.now() }; });
    store.set("progress", p);
    const h = lib.history(); delete h[meta.id]; store.set("history", h);
    toast("Marked as watched"); title(params);
  });
}

// ---------- streams ----------

/**
 * What the browser can play. Some streams need things a web page can't do (send a site's headers, decode MKV or
 * HEVC): they can still be tried, after the others, and are marked; the Stream Goat apps play them all.
 */
function classify(s) {
  if (s.url) {
    if (location.protocol === "https:" && s.url.startsWith("http:")) return { ok: false, why: "Not a secure (https) link: browsers block it" };
    const text = [s.name, s.title, s.description, s.url].filter(Boolean).join(" ");
    const headers = Object.keys((s.behaviorHints && s.behaviorHints.proxyHeaders && s.behaviorHints.proxyHeaders.request) || {}).filter((k) => k.toLowerCase() !== "user-agent");
    const notes = [];
    if (headers.length) notes.push("needs a website's headers: may not play in a browser (the apps play it)");
    if (/\.mkv(\?|$)|\bmkv\b/i.test(text)) notes.push("MKV: browsers often can't play it");
    if (/\b(hevc|x265|h\.?265)\b/i.test(text)) notes.push("HEVC: many browsers can't play it");
    if (!notes.length && s.behaviorHints && s.behaviorHints.notWebReady) notes.push("may not play in a browser");
    return { ok: true, risky: notes.length > 0, why: notes.join(" · ") };
  }
  if (s.infoHash) return { ok: false, why: "Torrent: not supported in a browser" };
  if (s.ytId) return { ok: false, external: `https://www.youtube.com/watch?v=${s.ytId}`, why: "Opens YouTube" };
  if (s.externalUrl) return { ok: false, external: s.externalUrl, why: "Opens a website" };
  return { ok: false, why: "Unknown stream" };
}

async function streamsFor(type, videoId) {
  const all = addons().filter((a) => supports(a, "stream", type, videoId));
  const lists = await Promise.all(all.map(async (a) => {
    try { return ((await res(a, "stream", type, videoId)).streams || []).map((s) => ({ ...s, addon: a.manifest.name, c: classify(s) })); }
    catch { return []; }
  }));
  return lists.flat().sort((x, y) => (y.c.ok - x.c.ok) || (!!x.c.risky - !!y.c.risky));
}

async function subtitlesFor(type, videoId) {
  const all = addons().filter((a) => supports(a, "subtitles", type, videoId));
  const lists = await Promise.all(all.map(async (a) => { try { return (await res(a, "subtitles", type, videoId)).subtitles || []; } catch { return []; } }));
  return lists.flat().filter((s) => s.url && s.url.startsWith("https:") || location.protocol === "http:");
}

let langNames;
function langName(code) {
  try { langNames = langNames || new Intl.DisplayNames(["en"], { type: "language" }); return langNames.of(code) || code; } catch { return code; }
}

function toVtt(text) {
  const t = text.replace(/^﻿/, "").replace(/\r\n?/g, "\n");
  return t.trimStart().startsWith("WEBVTT") ? t : "WEBVTT\n\n" + t.replace(/(\d{1,2}:\d{2}:\d{2}),(\d{3})/g, "$1.$2");
}

let hlsLoading;
function loadHls() {
  hlsLoading = hlsLoading || new Promise((ok, fail) => {
    const s = document.createElement("script");
    s.src = "https://cdn.jsdelivr.net/npm/hls.js@1.5.13/dist/hls.min.js";
    s.onload = () => ok(window.Hls); s.onerror = () => fail(new Error("Couldn't load the HLS player"));
    document.head.appendChild(s);
  });
  return hlsLoading;
}

// ---------- player ----------

const fmt = (t) => {
  if (!isFinite(t) || t < 0) t = 0;
  const h = Math.floor(t / 3600), m = Math.floor(t % 3600 / 60), sec = Math.floor(t % 60);
  return (h ? `${h}:${String(m).padStart(2, "0")}` : `${m}`) + `:${String(sec).padStart(2, "0")}`;
};

/** The browser's preferred languages first (two-letter codes), then English. */
function preferredLangs() {
  const l = [...(navigator.languages || [navigator.language || "en"])].map((x) => x.slice(0, 2).toLowerCase());
  return [...new Set([...l, "en"])];
}

async function watch(params) {
  const live = isLiveType(params.type);
  const here = location.hash;
  document.body.style.overflow = "hidden";
  $view.innerHTML = `<div class="player" id="player">
    <video playsinline autoplay preload="auto"></video>
    <div class="pl-status" id="pstatus"><div class="spinner"></div>Finding streams…</div>
    <div class="pl-ui">
      <div class="pl-top"><button class="pl-btn" id="close" aria-label="Back">${icons.back}</button>
        <div class="t"><b id="ptitle">${esc(params.name || "")}</b><span id="psource">Finding streams…</span></div></div>
      <div class="pl-center">
        ${live ? "" : `<button class="pl-btn big" id="rew" aria-label="Back 10 seconds">${icons.rew}</button>`}
        <button class="pl-btn huge" id="pp" aria-label="Play or pause">${icons.play}</button>
        ${live ? "" : `<button class="pl-btn big" id="fwd" aria-label="Forward 10 seconds">${icons.fwd}</button>`}</div>
      <div class="pl-bottom">
        ${live ? "" : `<div class="pl-seek"><div class="buf"></div><div class="done"></div><input type="range" id="seek" min="0" max="1000" value="0" step="1" aria-label="Seek"></div>`}
        <div class="pl-bar">
          <button class="pl-btn" id="pp2" aria-label="Play or pause">${icons.play}</button>
          <button class="pl-btn" id="vol" aria-label="Mute">${icons.vol}</button>
          <input type="range" id="volume" class="pl-volume" min="0" max="1" step="0.05" value="1" aria-label="Volume">
          <span class="pl-time" id="time">${live ? "LIVE" : "0:00 / 0:00"}</span>
          <span class="spacer"></span>
          <button class="pl-btn txt" id="nextBtn" hidden>${icons.next}<span>Next episode</span></button>
          <button class="pl-btn txt" id="subs">${icons.subs}<span>Subtitles</span></button>
          <button class="pl-btn txt" id="audio">${icons.audio}<span>Audio</span></button>
          ${live ? "" : `<button class="pl-btn txt" id="speed">${icons.speed}<span id="speedLabel">1×</span></button>`}
          <button class="pl-btn txt" id="quality" hidden>${icons.hd}<span id="qLabel">Auto</span></button>
          <button class="pl-btn txt" id="srcs">${icons.list}<span>Sources</span></button>
          <button class="pl-btn" id="pip" hidden aria-label="Picture in picture">${icons.pip}</button>
          <button class="pl-btn" id="fs" aria-label="Full screen">${icons.fs}</button>
        </div>
      </div>
    </div></div>`;
  const player = $view.querySelector("#player");
  const video = player.querySelector("video");
  const status = player.querySelector("#pstatus");
  const $ = (id) => player.querySelector(id);
  const setStatus = (html) => { status.innerHTML = html; status.hidden = !html; };
  let saveTimer, countdown, hls, idleTimer, stuckTimer;

  // Controls: shown on movement, hidden after 3 s while playing.
  const poke = () => {
    player.classList.remove("idle");
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() => { if (!video.paused && !document.querySelector(".sheet-back")) player.classList.add("idle"); }, 3000);
  };
  ["mousemove", "touchstart", "click"].forEach((e) => player.addEventListener(e, poke, { passive: true }));
  const togglePlay = () => (video.paused ? video.play().catch(() => {}) : video.pause());
  const seekBy = (d) => { if (isFinite(video.duration)) video.currentTime = Math.max(0, Math.min(video.duration - 1, video.currentTime + d)); poke(); };
  $("#pp").addEventListener("click", (e) => { e.stopPropagation(); togglePlay(); });
  $("#pp2").addEventListener("click", togglePlay);
  if (!live) { $("#rew").addEventListener("click", (e) => { e.stopPropagation(); seekBy(-10); }); $("#fwd").addEventListener("click", (e) => { e.stopPropagation(); seekBy(10); }); }
  video.addEventListener("click", () => { if (matchMedia("(hover: hover)").matches) togglePlay(); });
  video.addEventListener("dblclick", () => toggleFs());
  const showPlaying = () => { const i = video.paused ? icons.play : icons.pause; $("#pp").innerHTML = i; $("#pp2").innerHTML = i; player.classList.toggle("paused", video.paused); poke(); };
  video.addEventListener("play", showPlaying); video.addEventListener("pause", showPlaying);

  // Volume (remembered), mute.
  video.volume = store.get("volume", 1);
  $("#volume").value = video.volume;
  const showVol = () => { $("#vol").innerHTML = video.muted || video.volume === 0 ? icons.mute : icons.vol; };
  $("#volume").addEventListener("input", (e) => { video.volume = +e.target.value; video.muted = false; store.set("volume", video.volume); showVol(); });
  $("#vol").addEventListener("click", () => { video.muted = !video.muted; showVol(); });

  // Seek bar with what's buffered.
  if (!live) {
    const seek = $("#seek");
    let dragging = false;
    seek.addEventListener("input", () => { dragging = true; if (isFinite(video.duration)) $("#time").textContent = `${fmt(seek.value / 1000 * video.duration)} / ${fmt(video.duration)}`; });
    seek.addEventListener("change", () => { dragging = false; if (isFinite(video.duration)) video.currentTime = seek.value / 1000 * video.duration; });
    video.addEventListener("timeupdate", () => {
      if (!isFinite(video.duration) || video.duration <= 0) return;
      const f = video.currentTime / video.duration;
      if (!dragging) { seek.value = Math.round(f * 1000); $("#time").textContent = `${fmt(video.currentTime)} / ${fmt(video.duration)}`; }
      $(".pl-seek .done").style.width = `${f * 100}%`;
      const b = video.buffered;
      for (let i = 0; i < b.length; i++) if (b.start(i) <= video.currentTime && video.currentTime <= b.end(i)) $(".pl-seek .buf").style.width = `${b.end(i) / video.duration * 100}%`;
    });
  }

  // Full screen, picture in picture.
  const inFs = () => document.fullscreenElement || document.webkitFullscreenElement;
  function toggleFs() {
    if (inFs()) (document.exitFullscreen || document.webkitExitFullscreen).call(document);
    else if (player.requestFullscreen) player.requestFullscreen().catch(() => video.webkitEnterFullscreen && video.webkitEnterFullscreen());
    else if (video.webkitEnterFullscreen) video.webkitEnterFullscreen(); // iPhone
  }
  $("#fs").addEventListener("click", toggleFs);
  document.addEventListener("fullscreenchange", () => { const f = $("#fs"); if (f) f.innerHTML = inFs() ? icons.fsExit : icons.fs; });
  if (document.pictureInPictureEnabled) { $("#pip").hidden = false; $("#pip").addEventListener("click", () => (document.pictureInPictureElement ? document.exitPictureInPicture() : video.requestPictureInPicture()).catch(() => {})); }

  // Speed.
  if (!live) $("#speed").addEventListener("click", () => sheet("Playback speed", [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2].map((r) =>
    `<button class="option ${video.playbackRate === r ? "on" : ""}" data-r="${r}">${r === 1 ? "Normal (1×)" : `${r}×`}</button>`).join(""), (el, close) => {
    el.querySelectorAll("[data-r]").forEach((b) => b.addEventListener("click", () => { video.playbackRate = +b.dataset.r; $("#speedLabel").textContent = `${b.dataset.r}×`; close(); }));
  }));

  // Keyboard on computers.
  const keys = (e) => {
    if (!document.body.contains(video)) return document.removeEventListener("keydown", keys);
    if (e.target && /INPUT|TEXTAREA/.test(e.target.tagName) && e.target.type !== "range") return;
    const k = e.key;
    if (k === " " || k === "k") { togglePlay(); e.preventDefault(); }
    else if (k === "ArrowLeft" && !live) { seekBy(-10); e.preventDefault(); }
    else if (k === "ArrowRight" && !live) { seekBy(10); e.preventDefault(); }
    else if (k === "ArrowUp") { video.volume = Math.min(1, video.volume + 0.1); $("#volume").value = video.volume; showVol(); poke(); e.preventDefault(); }
    else if (k === "ArrowDown") { video.volume = Math.max(0, video.volume - 0.1); $("#volume").value = video.volume; showVol(); poke(); e.preventDefault(); }
    else if (k === "f") toggleFs();
    else if (k === "m") { video.muted = !video.muted; showVol(); }
  };
  document.addEventListener("keydown", keys);

  const leave = () => { saveProgress(); clearInterval(saveTimer); clearInterval(countdown); clearTimeout(stuckTimer); hls && hls.destroy(); document.body.style.overflow = ""; document.removeEventListener("keydown", keys); if (inFs()) document.exitFullscreen().catch(() => {}); };
  $("#close").addEventListener("click", () => { history.length > 1 ? history.back() : (location.hash = "#/home"); });
  window.addEventListener("hashchange", function off() { leave(); window.removeEventListener("hashchange", off); });
  window.addEventListener("pagehide", () => saveProgress());

  let meta = null, videos = [], v = null, next = null;
  if (!live) {
    meta = await loadMeta(params.type, params.id, params.a);
    if (location.hash !== here) return;
    videos = videosOf(meta);
    const i = videos.findIndex((x) => x.id === params.v);
    v = videos[i] || videos[0];
    next = i >= 0 && !v.movie ? videos.slice(i + 1).find((x) => (x.season ?? 0) > 0 || (v.season ?? 0) === 0) : null;
    $("#ptitle").textContent = v.movie ? meta.name : `${meta.name} · ${label(v)}`;
    if (next) { const nb = $("#nextBtn"); nb.hidden = false; nb.addEventListener("click", () => goNext()); }
  }
  const videoId = live ? params.id : v.id;
  const [streams, subs] = await Promise.all([streamsFor(params.type, videoId), live ? [] : subtitlesFor(params.type, videoId)]);
  if (location.hash !== here) return;
  const playable = streams.filter((s) => s.c.ok);
  if (!streams.length) return setStatus(`<b>No streams</b><br>None of your add-ons has a stream for this. Add a stream add-on in More.`);

  const p0 = !live && lib.progress(videoId);
  let resumeAt = p0 && p0.dur && p0.pos / p0.dur < 0.95 && p0.pos > 10 ? p0.pos : 0;
  let current = null;
  const sourceText = (s) => [s.addon, (s.name || "").replace(/\n/g, " ")].filter(Boolean).join(" · ");
  const play = async (s) => {
    current = s;
    clearTimeout(stuckTimer);
    $("#psource").textContent = sourceText(s) + (s.c.why ? ` · ${s.c.why}` : "");
    setStatus('<div class="spinner"></div>Loading…');
    if (video.currentTime > 5) resumeAt = video.currentTime;
    hls && hls.destroy(); hls = null;
    $("#quality").hidden = true;
    [...video.querySelectorAll("track")].forEach((t) => t.remove());
    const isHls = /\.m3u8(\?|$)/i.test(s.url) || /m3u8|hls/i.test((s.behaviorHints && s.behaviorHints.videoMimeType) || "");
    if (isHls && !video.canPlayType("application/vnd.apple.mpegurl")) {
      try {
        const Hls = await loadHls();
        hls = new Hls({ enableWorker: true });
        hls.on(Hls.Events.ERROR, (_, d) => { if (d.fatal) failed("it stopped loading"); });
        hls.on(Hls.Events.MANIFEST_PARSED, () => {
          if (hls.levels.length > 1) { $("#quality").hidden = false; $("#qLabel").textContent = "Auto"; }
        });
        hls.on(Hls.Events.LEVEL_SWITCHED, (_, d) => {
          const lv = hls.levels[d.level];
          if (lv && hls.autoLevelEnabled) $("#qLabel").textContent = `Auto${lv.height ? ` (${lv.height}p)` : ""}`;
        });
        hls.loadSource(s.url); hls.attachMedia(video);
      } catch (e) { return setStatus(esc(e.message)); }
    } else { video.src = s.url; video.load(); }
    video.play().catch(() => setStatus("Press ▶ to start"));
    // A stream that hasn't started in 25 s is treated as not playable here.
    stuckTimer = setTimeout(() => { if (video.readyState < 3 && current === s) failed("it didn't start"); }, 25000);
  };
  // A stream that fails moves on to the next playable one, keeping the position (never to the next episode).
  const failed = (why) => {
    clearTimeout(stuckTimer);
    if (current) current.failed = true;
    const rest = playable.slice(playable.indexOf(current) + 1);
    const reason = current && current.c.why ? current.c.why.split(" · ")[0] : why;
    if (rest.length) { toast(`That source didn't play in this browser (${reason}). Trying the next one…`); play(rest[0]); }
    else setStatus(`<b>None of the sources would play in this browser.</b><br>Many of them need the Stream Goat app (phone, TV, Windows),
      which plays every source. Or pick one in Sources.`);
  };
  video.addEventListener("loadedmetadata", () => { if (resumeAt > 5 && isFinite(video.duration) && resumeAt < video.duration - 30) video.currentTime = resumeAt; });
  video.addEventListener("playing", () => { setStatus(""); clearTimeout(stuckTimer); poke(); });
  video.addEventListener("waiting", () => setStatus('<div class="spinner"></div>'));
  video.addEventListener("error", () => { if (!hls) failed(video.error && video.error.code === 4 ? "the link is dead or the format isn't supported" : "it couldn't load"); });
  video.addEventListener("timeupdate", () => {
    if (!next || !isFinite(video.duration) || video.duration < 300 || countdown) return;
    if (video.currentTime > video.duration - 20) showNext();
  });
  video.addEventListener("ended", () => { saveProgress(true); if (next && video.duration >= 180) showNext(); });

  function showNext() {
    if (countdown || !next) return;
    let n = 10;
    const card = document.createElement("div");
    card.className = "next-card";
    card.innerHTML = `<div class="label">NEXT EPISODE</div><b>${esc(label(next))}</b><div class="muted small" id="cd">Starts in ${n} s</div>
      <div class="actions"><button class="btn primary small" id="now">${icons.play} Play now</button><button class="btn small" id="cancel">Cancel</button></div>`;
    player.appendChild(card);
    card.querySelector("#now").addEventListener("click", goNext);
    card.querySelector("#cancel").addEventListener("click", () => { clearInterval(countdown); card.remove(); countdown = "cancelled"; });
    countdown = setInterval(() => { n--; const cd = card.querySelector("#cd"); if (cd) cd.textContent = `Starts in ${n} s`; if (n <= 0) goNext(); }, 1000);
  }
  function goNext() { clearInterval(countdown); saveProgress(); location.replace(`#/watch?${qs({ type: params.type, id: params.id, v: next.id, a: params.a })}`); }
  function saveProgress(finished) {
    if (live || !meta || !isFinite(video.duration) || video.duration <= 0 || video.currentTime < 5) return;
    lib.record(meta, videoId, v.movie ? "" : label(v).split(" · ")[0], finished ? video.duration : video.currentTime, video.duration);
  }
  saveTimer = setInterval(() => { if (!video.paused) saveProgress(); }, 5000);
  video.addEventListener("pause", () => saveProgress());
  document.addEventListener("visibilitychange", function hide() {
    if (!document.body.contains(video)) return document.removeEventListener("visibilitychange", hide);
    if (document.visibilityState === "hidden") saveProgress();
  });

  // Sources: every stream, the ones a browser may not play marked; they can still be tried.
  $("#srcs").addEventListener("click", () => sheet("Sources", streams.map((s, i) =>
    `<button class="option ${s === current ? "on" : ""}" data-i="${i}" ${s.c.ok || s.c.external ? "" : "disabled"}><span>${esc(sourceText(s))}
      <span class="why">${esc([(s.title || s.description || "").replace(/\n/g, " ").slice(0, 140), s.failed ? "didn't play here" : "", s.c.why].filter(Boolean).join(" · "))}</span></span></button>`).join(""), (el, close) => {
    el.querySelectorAll("[data-i]").forEach((b) => b.addEventListener("click", () => {
      const s = streams[+b.dataset.i]; close();
      if (s.c.external) window.open(s.c.external, "_blank", "noopener"); else play(s);
    }));
  }));

  // Subtitles: add-ons' (your languages first) and those inside the stream; text size.
  const langs = preferredLangs();
  const lang2 = (code) => { try { return new Intl.Locale(code).language.slice(0, 2).toLowerCase(); } catch { return (code || "").slice(0, 2).toLowerCase(); } }; // "spa" -> "es"
  const rank = (code) => { const i = langs.indexOf(lang2(code)); return i < 0 ? 99 : i; };
  const addonSubs = subs.slice().sort((a, b) => rank(a.lang) - rank(b.lang) || langName(a.lang || "").localeCompare(langName(b.lang || ""))).slice(0, 80);
  let subShown = -1;
  const showTrack = (track) => { [...video.textTracks].forEach((t) => (t.mode = t === track ? "showing" : "disabled")); };
  $("#subs").addEventListener("click", () => {
    const inside = hls ? hls.subtitleTracks.map((t, i) => ({ i, name: t.name || langName(t.lang || "und") })) : [];
    const counts = {};
    sheet("Subtitles", `<button class="option ${subShown === -1 ? "on" : ""}" data-s="-1">Off</button>` +
      inside.map((t) => `<button class="option" data-h="${t.i}">${esc(t.name)} <span class="why">in the stream</span></button>`).join("") +
      addonSubs.map((s, i) => { const n = langName(s.lang || "und"); counts[n] = (counts[n] || 0) + 1;
        return `<button class="option ${subShown === i ? "on" : ""}" data-s="${i}">${esc(n)}${counts[n] > 1 ? ` ${counts[n]}` : ""}</button>`; }).join("") +
      (addonSubs.length || inside.length ? "" : `<p class="muted small" style="padding:6px 12px">No subtitles found for this. Add-ons like OpenSubtitles (in More) provide them.</p>`) +
      `<div class="chips" style="margin:10px 0 0;padding:0 12px">${["Small", "Normal", "Large"].map((z) => `<button class="chip ${store.get("cue", "Normal") === z ? "on" : ""}" data-z="${z}">${z} text</button>`).join("")}</div>`, (el, close) => {
      el.querySelectorAll("[data-z]").forEach((b) => b.addEventListener("click", () => { store.set("cue", b.dataset.z); player.dataset.cue = b.dataset.z; close(); }));
      el.querySelectorAll("[data-h]").forEach((b) => b.addEventListener("click", () => { close(); hls.subtitleTrack = +b.dataset.h; hls.subtitleDisplay = true; subShown = -2; }));
      el.querySelectorAll("[data-s]").forEach((b) => b.addEventListener("click", async () => {
        close();
        const i = +b.dataset.s;
        subShown = i;
        if (hls) hls.subtitleTrack = -1;
        if (i < 0) return showTrack(null);
        try {
          const r = await fetch(addonSubs[i].url, { referrerPolicy: "no-referrer" });
          const url = URL.createObjectURL(new Blob([toVtt(await r.text())], { type: "text/vtt" }));
          const track = document.createElement("track");
          Object.assign(track, { kind: "subtitles", label: langName(addonSubs[i].lang || "und"), srclang: lang2(addonSubs[i].lang || "en"), src: url });
          video.appendChild(track);
          track.addEventListener("load", () => showTrack(track.track));
          showTrack(track.track);
        } catch { toast("Couldn't load those subtitles"); }
      }));
    });
  });
  player.dataset.cue = store.get("cue", "Normal");

  // Audio: alternate audio of adaptive (HLS) streams, or the file's tracks where the browser allows (Safari).
  $("#audio").addEventListener("click", () => {
    const hlsTracks = hls ? hls.audioTracks : [];
    const native = video.audioTracks && video.audioTracks.length > 1 ? [...video.audioTracks] : [];
    if (hlsTracks.length > 1) return sheet("Audio", hlsTracks.map((t, i) => `<button class="option ${hls.audioTrack === i ? "on" : ""}" data-a="${i}">${esc(t.name || langName(t.lang || "und"))}</button>`).join(""), (el, close) => {
      el.querySelectorAll("[data-a]").forEach((b) => b.addEventListener("click", () => { hls.audioTrack = +b.dataset.a; close(); }));
    });
    if (native.length) return sheet("Audio", native.map((t, i) => `<button class="option ${t.enabled ? "on" : ""}" data-a="${i}">${esc(t.label || langName(t.language || "und"))}</button>`).join(""), (el, close) => {
      el.querySelectorAll("[data-a]").forEach((b) => b.addEventListener("click", () => { native.forEach((t, j) => (t.enabled = j === +b.dataset.a)); close(); }));
    });
    sheet("Audio", `<p class="muted">This source has one audio track here: a browser can't switch the language inside this kind of file.</p>
      <p class="muted small">For another language, pick a source whose name says it (Sources), or use the Stream Goat app (phone, TV, Windows), which can switch the audio of any file.</p>`);
  });

  // Quality of adaptive (HLS) streams.
  $("#quality").addEventListener("click", () => {
    if (!hls) return;
    const levels = hls.levels.map((l, i) => ({ i, h: l.height, b: l.bitrate })).sort((a, b) => (b.h || 0) - (a.h || 0) || b.b - a.b);
    sheet("Quality", `<button class="option ${hls.autoLevelEnabled ? "on" : ""}" data-q="-1">Auto</button>` + levels.map((l) =>
      `<button class="option ${!hls.autoLevelEnabled && hls.currentLevel === l.i ? "on" : ""}" data-q="${l.i}">${l.h ? `${l.h}p` : `${Math.round(l.b / 1000)} kbps`}</button>`).join(""), (el, close) => {
      el.querySelectorAll("[data-q]").forEach((b) => b.addEventListener("click", () => {
        hls.currentLevel = +b.dataset.q; const lv = hls.levels[+b.dataset.q];
        $("#qLabel").textContent = +b.dataset.q < 0 ? "Auto" : lv && lv.height ? `${lv.height}p` : "Fixed"; close();
      }));
    });
  });

  if (!playable.length) {
    setStatus(`<b>No source this browser can play</b><br>${esc(streams[0].c.why)}. Open Sources to see them all, or use the Stream Goat app.`);
    $("#psource").textContent = `${streams.length} source(s), none playable here`;
    return;
  }
  play(playable[0]);
}

// ---------- Live TV (Stremio TV add-ons) ----------

async function live() {
  $view.innerHTML = `<div class="page"><div class="bar"><h1>Live TV</h1></div><div class="spinner"></div></div>`;
  const cats = homeCatalogs(true);
  if (!cats.length) { $view.querySelector(".spinner").outerHTML = `<div class="empty"><b>No TV add-ons</b>Add a Stremio add-on with TV channels in More.</div>`; return; }
  const here = location.hash;
  cache.live = cache.live || await Promise.all(cats.map(async ({ addon, catalog }) => {
    try { return { title: `${catalog.name || "Channels"} · ${addon.manifest.name}`, items: ((await res(addon, "catalog", catalog.type, catalog.id)).metas || []).map((m) => item(m, addon)) }; }
    catch { return { title: catalog.name, items: [] }; }
  }));
  if (location.hash !== here) return;
  $view.querySelector(".page").innerHTML = `<div class="bar"><h1>Live TV</h1></div>${cache.live.map((r) => r.items.length ? `<h2>${esc(r.title)}</h2><div class="grid">${r.items.slice(0, 200).map((i) => poster(i)).join("")}</div>` : "").join("") || '<div class="empty">The TV add-ons returned no channels.</div>'}`;
}

// ---------- library ----------

async function library(params) {
  const tab = params.tab || "continue";
  const tabs = { continue: "Continue watching", list: "My list", history: "History" };
  let items;
  if (tab === "list") items = lib.list();
  else items = (tab === "history" ? Object.values(lib.history()).sort((a, b) => b.ts - a.ts) : lib.continueWatching())
    .map((e) => ({ ...e, progress: e.dur ? e.pos / e.dur : 0, sub: [e.label, e.dur && e.pos / e.dur > 0.92 ? "Watched" : `${clock(e.dur - e.pos)} left`].filter(Boolean).join(" · ") }));
  $view.innerHTML = `<div class="page"><div class="bar"><h1>Library</h1></div>
    <div class="chips">${Object.entries(tabs).map(([k, v]) => `<a class="chip ${k === tab ? "on" : ""}" href="#/library?tab=${k}">${esc(v)}</a>`).join("")}</div>
    <div style="margin-top:14px">${items.length ? `<div class="grid">${items.map((i) => poster(i)).join("")}</div>` : `<div class="empty"><b>Nothing yet</b>${tab === "list" ? "Use “My list” on a title to keep it here." : "What you watch shows up here."}</div>`}</div>
    <p class="faint small center" style="margin-top:24px">Saved in this browser on this device.</p></div>`;
}

// ---------- add-ons, backup, about ----------

async function more() {
  const list = addons();
  $view.innerHTML = `<div class="page"><div class="bar"><h1>More</h1></div>
    <h2>Google account</h2><div id="account-box" class="panel"></div>
    <h2>Backups in this browser</h2>
    <p class="muted small">Every 5 minutes when something changed, a copy of your add-ons, My list, history and progress is kept in this
      browser, apart from the app's own storage, so it can come back if that gets cleared. The last 12 are kept.</p>
    <div id="local-box" class="panel"><div class="spinner"></div></div>
    <div class="actions-line"><button class="btn small" id="snap">Back up now</button><button class="btn small" id="exp">Save to a file</button>
      <label class="btn small">Restore from a file<input type="file" accept="application/json,.json" id="imp" hidden></label></div>
    <h2>Add-ons</h2>
    <form id="add"><input class="field" name="url" placeholder="Add-on link (…/manifest.json or stremio://…)" autocomplete="off" autocapitalize="off" spellcheck="false">
      <button class="btn primary wide" style="margin-top:10px">${icons.add} Install</button></form>
    <div class="note">Paste the <b>manifest link</b> of a Stremio add-on (the same ones Stremio and Nuvio use; the add-on's
      website shows it after you configure it). Add-ons that give <b>direct https video links</b> play best here.
      Torrent add-ons and links that need special headers only work in the Stream Goat apps.
      <b>Debrid add-ons</b> work when set up with your account, but on iPhone MKV files often won't play: pick MP4 results.</div>
    <div id="addons">${list.map((a, i) => `<div class="addon">${a.manifest.logo ? `<img src="${esc(a.manifest.logo)}" alt="" referrerpolicy="no-referrer">` : `<div class="noimg">${esc(a.manifest.name.slice(0, 2))}</div>`}
      <div class="t"><b>${esc(a.manifest.name)}</b><span>${esc([(a.manifest.resources || []).map((r) => typeof r === "string" ? r : r.name).join(", "), a.manifest.description].filter(Boolean).join(" · "))}</span></div>
      <button class="icon-btn" data-rm="${i}" aria-label="Remove">${icons.trash}</button></div>`).join("") || '<div class="empty">No add-ons installed.</div>'}</div>
    <h2>On iPhone</h2>
    <p class="muted small">In Safari tap <b>Share</b> → <b>Add to Home Screen</b>. Stream Goat then opens full screen like an app, with its own storage.</p>
    <h2>About</h2>
    <p class="muted small"><b style="color:var(--accent)">Created by Syed Ali Hamza.</b> Stream Goat web, the free browser version of <a href="https://github.com/sherazi5110/stream-goat" style="color:var(--accent)">Stream Goat for Linux, Windows and Android</a>.
      It plays streams from Stremio add-ons; CloudStream extensions need the Linux, Windows or Android app. This page hosts no content.</p>
    <div class="actions-line"><button class="btn small" id="glitch">Report a glitch or problem</button>
      <a class="btn small" href="https://discord.gg/PUmEQzJSa" target="_blank" rel="noopener">Discord</a></div>
  </div>`;
  $view.querySelector("#glitch").addEventListener("click", reportGlitch);
  const form = $view.querySelector("#add");
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const btn = form.querySelector("button"); btn.disabled = true;
    try { const m = await installAddon(form.url.value); toast(`Installed ${m.name}`); more(); }
    catch (err) {
      btn.disabled = false;
      if (!(err instanceof NeedsConfig)) return toast(err.message);
      sheet(`Set up ${err.manifest.name}`, `<p class="muted">This add-on needs your settings (for example your debrid account key) before it can find streams.</p>
        <ol class="muted small" style="padding-left:18px;line-height:1.6"><li>Tap <b>Open setup page</b> and fill it in.</li>
        <li>At the end, copy the <b>install link</b> it gives you (often “Copy link” or “Install”; it starts with <b>stremio://</b> or ends with <b>manifest.json</b>).</li>
        <li>Come back here and paste that link into the box, then tap Install.</li></ol>
        <a class="btn primary wide" href="${esc(err.configure)}" target="_blank" rel="noopener">Open setup page</a>
        <button class="btn wide" style="margin-top:10px" id="anyway">Install without setup</button>`, (el, close) => {
        el.querySelector("#anyway").addEventListener("click", async () => {
          close();
          try { const m = await installAddon(form.url.value, true); toast(`Installed ${m.name} (not set up)`); more(); } catch (e2) { toast(e2.message); }
        });
      });
    }
  });
  $view.querySelectorAll("[data-rm]").forEach((b) => b.addEventListener("click", () => {
    const l = addons(); const [gone] = l.splice(+b.dataset.rm, 1);
    if (!confirm(`Remove ${gone.manifest.name}?`)) return;
    store.set("addons", l); store.set("defaultsDone", true); cache.home = null; cache.live = null; more();
  }));
  renderAccount();
  renderLocal();
  $view.querySelector("#snap").addEventListener("click", async () => {
    try { await localBackups.save(true); toast("Backed up in this browser"); renderLocal(); } catch (e) { toast("Couldn't back up here: " + e.message); }
  });
  $view.querySelector("#exp").addEventListener("click", () => {
    const data = exportData();
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([JSON.stringify(data)], { type: "application/json" }));
    a.download = `streamgoat-web-backup-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
  });
  $view.querySelector("#imp").addEventListener("change", async (e) => {
    try {
      await importData(JSON.parse(await e.target.files[0].text()));
      toast("Restored"); more();
    } catch (err) { toast(err.message); }
  });
}

// ---------- More: account and backups ----------

function ago(ts) {
  if (!ts) return "never";
  const m = Math.round((Date.now() - ts) / 60000);
  return m < 1 ? "just now" : m < 60 ? `${m} min ago` : m < 48 * 60 ? `${Math.round(m / 60)} h ago` : `${Math.round(m / 1440)} days ago`;
}

async function renderLocal() {
  const box = $view.querySelector("#local-box");
  if (!box) return;
  let copies;
  try { copies = await localBackups.list(); } catch { box.innerHTML = `<p class="muted small">This browser doesn't allow backups here (private mode?).</p>`; return; }
  box.innerHTML = copies.length ? copies.map((c, i) => `<div class="row-line"><div class="t"><b>${esc(new Date(c.created).toLocaleString())}</b><span>${esc(summary(c))}</span></div>
      <button class="btn small" data-local="${i}">Restore</button></div>`).join("")
    : `<p class="muted small">No copies yet: the first one is made within 5 minutes once you have add-ons, a list or history.</p>`;
  box.querySelectorAll("[data-local]").forEach((b) => b.addEventListener("click", async () => {
    const c = copies[+b.dataset.local];
    if (!confirm(`Replace this browser's add-ons, My list, history and progress with the copy from ${new Date(c.created).toLocaleString()}?`)) return;
    await importData(c); toast("Restored"); more();
  }));
}

function renderAccount() {
  const box = $view.querySelector("#account-box");
  if (!box) return;
  if (!cloud.configured) {
    box.innerHTML = `<p class="muted small">Google sign-in isn't set up on this website yet. Your data is kept in this browser and its backups (below).</p>`;
    return;
  }
  if (!cloud.signedIn) {
    box.innerHTML = `<p class="muted small">Sign in with Google to back up to your Google Drive, every 5 minutes, and to bring your Stremio add-ons over
      from the Stream Goat app on your phone or TV. They go in a "Stream Goat backups" folder in your Drive: Stream Goat only sees the files it made, never your other files.</p>
      <button class="google-btn" id="gsign">${icons.google} Sign in with Google</button>`;
    box.querySelector("#gsign").addEventListener("click", async () => {
      try { await cloud.connect(true); toast(`Signed in as ${cloud.email}`); await afterSignIn(); renderAccount(); } catch (e) { toast(e.message); }
    });
    return;
  }
  const connected = cloud.valid;
  box.innerHTML = `<div class="row-line"><div class="t"><b>${esc(cloud.email)}</b><span>Google Drive · last backup ${esc(ago(store.get("lastCloudBackup", 0)))}
      ${connected ? "" : " · connect again to keep backing up (a browser's Google access lasts an hour)"}</span></div></div>
    <div class="actions-line">
      ${connected ? `<button class="btn small primary" id="gnow">Back up now</button><button class="btn small" id="grestore">Restore…</button>`
        : `<button class="google-btn" id="gconnect">${icons.google} Connect again</button>`}
      <button class="btn small" id="gout">Sign out</button></div>`;
  const on = (id, fn) => { const el = box.querySelector(id); el && el.addEventListener("click", fn); };
  on("#gconnect", async () => { try { await cloud.connect(false); renderAccount(); autoBackup(); } catch (e) { toast(e.message); } });
  on("#gnow", async () => { try { await cloud.backupNow(false); toast("Backed up to Google Drive"); renderAccount(); } catch (e) { toast(e.message); renderAccount(); } });
  on("#grestore", () => chooseCloudBackup());
  on("#gout", () => { if (confirm("Sign out of Google? Your backups stay in your Google Drive.")) { cloud.signOut(); renderAccount(); } });
}

/** After signing in: offer the backups already in the account (another browser's, or the phone's add-ons); else back up now. */
async function afterSignIn() {
  const list = await cloud.backups().catch(() => []);
  if (list.some((b) => !b.mine)) return chooseCloudBackup(list);
  try { await cloud.backupNow(false); toast("Backed up to Google Drive"); } catch { /* shown in the account box */ }
}

async function chooseCloudBackup(list) {
  try { list = list || await cloud.backups(); } catch (e) { return toast(e.message); }
  if (!list.length) return toast("There's no backup in this Google account yet");
  sheet("Restore a backup", `<p class="muted small">A browser backup brings back everything; a backup of the Stream Goat app (phone, TV, PC) brings
      its Stremio add-ons here (CloudStream extensions only run in the apps).</p>` +
    list.map((b, i) => `<div class="row-line" style="display:flex;gap:10px;align-items:center;padding:8px 0">
      <button class="option" data-i="${i}" style="flex:1"><span>${esc(b.device)}${b.mine ? " (this browser)" : ""}
        <span class="why">${esc(ago(b.modified))} · ${b.web ? "everything" : "its Stremio add-ons"}</span></span></button>
      <button class="icon-btn" data-del="${i}" aria-label="Delete this backup">${icons.trash}</button></div>`).join(""), (el, close) => {
    el.querySelectorAll("[data-i]").forEach((btn) => btn.addEventListener("click", async () => {
      const b = list[+btn.dataset.i];
      if (b.web && !confirm(`Replace this browser's add-ons, My list, history and progress with the backup from ${b.device}?`)) return;
      close();
      try { toast(await cloud.restore(b)); router(); } catch (e) { toast(e.message); }
    }));
    el.querySelectorAll("[data-del]").forEach((btn) => btn.addEventListener("click", async () => {
      const b = list[+btn.dataset.del];
      if (!confirm(`Delete the backup from ${b.device}?`)) return;
      try { await cloud.remove(b); btn.closest(".row-line").remove(); toast("Backup deleted"); } catch (e) { toast(e.message); }
    }));
  });
}

router();
