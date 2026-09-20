const http = require('http');
const dgram = require('dgram');
const fs = require('fs');
const os = require('os');
const path = require('path');

const PORT = Number(process.env.PORT || 8787);
const DISCOVERY_PORT = Number(process.env.DISCOVERY_PORT || 8788);
const HOST = '0.0.0.0';
const DISCOVERY_REQUEST = 'ERGATTA_ROW_DIAG_DISCOVER_V1';
const DISCOVERY_RESPONSE = 'ERGATTA_ROW_DIAG_V1 ';
const ROOT = __dirname;
const PUBLIC_DIR = path.join(ROOT, 'public');
const DATA_DIR = path.join(ROOT, 'data');
const EVENTS_FILE = path.join(DATA_DIR, 'events.jsonl');
/**
 * Cesium Ion token for the flight view. Kept out of source: set CESIUM_ION_TOKEN in the
 * environment, or drop {"ionToken":"..."} into server/cesium.local.json (gitignored). It is
 * injected into fly.html at request time, so no build step and nothing committed.
 */
const CESIUM_CONFIG_FILE = path.join(ROOT, 'cesium.local.json');

function cesiumToken() {
  if (process.env.CESIUM_ION_TOKEN) {
    return process.env.CESIUM_ION_TOKEN.trim();
  }
  try {
    const raw = fs.readFileSync(CESIUM_CONFIG_FILE, 'utf8');
    const parsed = JSON.parse(raw);
    return typeof parsed.ionToken === 'string' ? parsed.ionToken.trim() : '';
  } catch {
    return '';   // no token: the page falls back to OpenStreetMap
  }
}

const clients = new Set();
const events = [];
const browserCheckins = new Map();

fs.mkdirSync(DATA_DIR, { recursive: true });

function nowIso() {
  return new Date().toISOString();
}

function localUrls() {
  const urls = [`http://localhost:${PORT}`];
  for (const records of Object.values(os.networkInterfaces())) {
    for (const record of records || []) {
      if (record.family === 'IPv4' && !record.internal) {
        urls.push(`http://${record.address}:${PORT}`);
      }
    }
  }
  return urls;
}

function ipv4ToInt(address) {
  const parts = address.split('.').map(Number);
  if (parts.length !== 4 || parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) {
    return null;
  }
  return (((parts[0] << 24) >>> 0) + (parts[1] << 16) + (parts[2] << 8) + parts[3]) >>> 0;
}

function bestLocalUrlFor(clientAddress) {
  const client = ipv4ToInt(clientAddress.replace(/^::ffff:/, ''));
  const candidates = [];
  for (const records of Object.values(os.networkInterfaces())) {
    for (const record of records || []) {
      if (record.family !== 'IPv4' || record.internal) continue;
      candidates.push(record);
      const local = ipv4ToInt(record.address);
      const mask = ipv4ToInt(record.netmask);
      if (client !== null && local !== null && mask !== null && (client & mask) === (local & mask)) {
        return `http://${record.address}:${PORT}`;
      }
    }
  }
  return candidates.length ? `http://${candidates[0].address}:${PORT}` : `http://localhost:${PORT}`;
}

/**
 * What the Install button is currently handing out. Written by tools/publish-apk.sh, so the
 * dashboard can say which build is on offer and whether the tablet is behind it.
 */
function publishedApk() {
  try {
    const raw = fs.readFileSync(path.join(PUBLIC_DIR, 'downloads', 'version.json'), 'utf8');
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

function sendJson(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Access-Control-Allow-Origin': '*',
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function readJson(req, res, callback) {
  let body = '';
  req.setEncoding('utf8');
  req.on('data', (chunk) => {
    body += chunk;
    if (body.length > 2_000_000) {
      res.writeHead(413);
      res.end('too large');
      req.destroy();
    }
  });
  req.on('end', () => {
    try {
      callback(body ? JSON.parse(body) : {});
    } catch (error) {
      sendJson(res, 400, { ok: false, error: 'invalid json' });
    }
  });
}

/* ---------------- backups and session history ---------------- */

/**
 * The tablet is the source of truth for records, profiles and progress, and a reinstall wipes
 * its SharedPreferences. These routes give it somewhere on the local network to push a copy and
 * to pull it back from - so a software update, or a second tablet, does not cost the rower their
 * history. Nothing leaves the laptop.
 *
 * Every path below is built from a profile id matched against PROFILE_RE and nothing else, so a
 * request cannot name a file outside data/backups or data/history. insideData() is the belt to
 * that braces: it re-resolves the finished path and refuses anything that escaped DATA_DIR.
 *
 * ALL disk work on these routes is async. This process is also framing the rower's live data at
 * ~8Hz and fanning it out over SSE; a synchronous read of an 8 MB history file (or a listing that
 * parsed 200 backup blobs) stalls the event loop and the gauges go dead on the dashboard. If you
 * add a route here, keep it off fs.*Sync.
 */
const fsp = fs.promises;
const PROFILE_RE = /^[a-z0-9-]{1,40}$/;
const BACKUP_DIR = path.join(DATA_DIR, 'backups');
const HISTORY_DIR = path.join(DATA_DIR, 'history');
const BACKUPS_PER_PROFILE = 30;          // this laptop has run low on disk; oldest are dropped
const BACKUP_MAX_BYTES = 4 * 1024 * 1024;
const HISTORY_POST_MAX_BYTES = 256 * 1024;
const HISTORY_FILE_MAX_BYTES = 8 * 1024 * 1024;   // rotate rather than grow without bound
const HISTORY_ARCHIVES_PER_PROFILE = 8;           // ~64 MB of rotated history per profile, capped
const HISTORY_MAX_RECORDS = 50000;                // bound what one file can pull into memory
const BACKUP_PARSE_MAX_BYTES = 512 * 1024;        // above this the listing skips the session count
const BACKUP_LIST_MAX = 60;                       // how many files the listing describes at all
const BACKUP_LIST_PARSE_MAX = 24;                 // and how many of those it opens for a count
const HISTORY_CACHE_MAX = 8;                      // parsed files held in memory, LRU

function profileId(value) {
  const id = String(value == null ? '' : value).trim().toLowerCase();
  return PROFILE_RE.test(id) ? id : null;
}

/** Resolves a path and returns it only if it really sits inside server/data. */
function insideData(filePath) {
  const resolved = path.resolve(filePath);
  return resolved.startsWith(DATA_DIR + path.sep) ? resolved : null;
}

function stampForFile(date) {
  return (date || new Date()).toISOString().replace(/[:.]/g, '-');
}

const TIMESTAMP_PART = '\\d{4}-\\d{2}-\\d{2}T[0-9-]+Z';

/**
 * Files this profile owns, newest first. `-latest.json` is deliberately not among them, and the
 * pattern is anchored on the whole name, so profile "zz" never matches "zz-selftest-...json".
 */
async function timestampedBackups(profile) {
  const pattern = new RegExp('^' + profile + '-(' + TIMESTAMP_PART + ')\\.json$');
  let names = [];
  try {
    names = await fsp.readdir(BACKUP_DIR);
  } catch {
    return [];
  }
  return names.filter((name) => pattern.test(name)).sort().reverse();
}

/**
 * Reads a request body as JSON with a hard byte cap. Rejects a content type that is not JSON and
 * anything that does not parse to an object, so a stray POST cannot land a junk file on disk.
 */
function readJsonBody(req, res, limitBytes, callback) {
  const type = String(req.headers['content-type'] || '');
  if (type && !/^(application\/json|text\/plain)/i.test(type)) {
    sendJson(res, 415, { ok: false, error: 'expected application/json' });
    req.resume();
    return;
  }
  const chunks = [];
  let bytes = 0;
  let aborted = false;
  req.on('data', (chunk) => {
    if (aborted) return;
    bytes += chunk.length;
    if (bytes > limitBytes) {
      aborted = true;
      chunks.length = 0;
      // Answer first, then drop the socket: destroying it in the same tick can lose the 413.
      res.once('finish', () => req.destroy());
      sendJson(res, 413, { ok: false, error: `body over ${limitBytes} bytes` });
      return;
    }
    chunks.push(chunk);
  });
  req.on('error', () => { aborted = true; chunks.length = 0; });
  req.on('aborted', () => { aborted = true; chunks.length = 0; });
  req.on('end', () => {
    if (aborted) return;
    let parsed;
    try {
      parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch {
      sendJson(res, 400, { ok: false, error: 'not JSON' });
      return;
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      sendJson(res, 400, { ok: false, error: 'expected a JSON object' });
      return;
    }
    callback(parsed, bytes);
  });
}

function backupSessionCount(blob) {
  if (!blob || typeof blob !== 'object') return null;
  if (Array.isArray(blob.sessions)) return blob.sessions.length;
  if (Array.isArray(blob.history)) return blob.history.length;
  if (Number.isFinite(Number(blob.sessionCount))) return Number(blob.sessionCount);
  return null;
}

function cleanText(value, fallback, max) {
  const text = String(value == null ? '' : value).replace(/[^A-Za-z0-9 _.:/-]/g, '').trim();
  return (text || fallback).slice(0, max);
}

/**
 * An instant as a real UTC ISO string. Accepts an ISO string or epoch milliseconds, because the
 * tablet's SessionLog stores `t` as a long. Returns null for anything else rather than inventing
 * a date.
 *
 * It normalises rather than passing the text through: every date filter and every sort in this
 * file is a plain string compare, and "2026-09-19T21:00:00+05:30" sorts wrong against a Z string.
 * One canonical spelling on disk is what makes those compares sound.
 */
function isoAt(value) {
  if (typeof value === 'number' && Number.isFinite(value) && value > 1e12 && value < 4e12) {
    return new Date(value).toISOString();
  }
  const text = String(value == null ? '' : value).trim();
  if (/^\d{12,13}$/.test(text)) return isoAt(Number(text));
  if (!/^\d{4}-\d{2}-\d{2}([T ][0-9:.]+)?(Z|[+-]\d{2}:?\d{2})?$/.test(text)) return null;
  const parsed = new Date(text.replace(' ', 'T'));
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}

/**
 * One completed row, normalised. Anything the tablet did not send is derived or zeroed.
 *
 * Both spellings are accepted: the long names a hand-written POST would use, and the short keys
 * SessionLog.exportJson writes (t, d, m, w, sp, st, pw, j, g), so the tablet can post a session
 * straight out of its own store with no translation step.
 */
function normaliseSession(raw, profile) {
  const s = (raw && typeof raw.session === 'object' && raw.session && !Array.isArray(raw.session))
    ? raw.session : raw;
  const first = (...values) => {
    for (const value of values) {
      if (value === undefined || value === null || value === '' || typeof value === 'object') continue;
      const parsed = Number(value);
      if (Number.isFinite(parsed)) return parsed;
    }
    return 0;
  };
  const meters = Math.max(0, Math.round(first(s.meters, s.distance, s.metres, s.m)));
  const seconds = Math.max(0, Math.round(first(s.seconds, s.time, s.durationSeconds, s.d)));
  if (meters <= 0 && seconds <= 0) return null;
  const split = first(s.splitSeconds, s.avgSplit, s.sp)
    || (meters > 0 && seconds > 0 ? (seconds / meters) * 500 : 0);
  const strokes = Math.max(0, Math.round(first(s.strokes, s.st)));
  const spmSent = first(s.spm, s.strokeRate);
  const record = {
    profile,
    at: isoAt(s.at) || isoAt(s.startedAt) || isoAt(s.finishedAt) || isoAt(s.t) || nowIso(),
    game: cleanText(s.game || s.screen || s.g, 'ROW', 40),
    meters,
    seconds,
    splitSeconds: Math.round(Math.max(0, Math.min(3599, split)) * 10) / 10,
    avgWatts: Math.max(0, Math.round(first(s.avgWatts, s.watts, s.w))),
    strokes,
    // Not sent by SessionLog, so derive it from strokes over the rowing clock when it is missing.
    spm: Math.round(Math.max(0, Math.min(80, spmSent || (seconds > 0 ? (strokes * 60) / seconds : 0))) * 10) / 10,
    calories: Math.max(0, Math.round(first(s.calories, s.kcal))),
    receivedAt: nowIso(),
  };
  const peak = first(s.peakWatts, s.pw);
  const joules = first(s.joules, s.j);
  if (peak > 0) record.peakWatts = Math.round(peak);
  if (joules > 0) record.joules = Math.round(joules);
  return record;
}

/**
 * Parsed history files, keyed by profile and invalidated on the file's size and mtime. The
 * dashboard re-reads every profile whenever a row lands over SSE; without this, each finished
 * row would re-parse every megabyte on disk while the rower is still on the machine.
 */
const historyCache = new Map();

function historyFile(profile) {
  return insideData(path.join(HISTORY_DIR, profile + '.jsonl'));
}

function cacheHistory(profile, key, records) {
  historyCache.delete(profile);
  historyCache.set(profile, { key, records });
  while (historyCache.size > HISTORY_CACHE_MAX) {
    historyCache.delete(historyCache.keys().next().value);
  }
}

/** Reads one profile's history into records, newest last. Bad lines are skipped, not fatal. */
async function readHistory(profile) {
  const file = historyFile(profile);
  if (!file) return [];
  let stat;
  try {
    stat = await fsp.stat(file);
  } catch {
    historyCache.delete(profile);
    return [];
  }
  const key = `${stat.size}:${stat.mtimeMs}`;
  const cached = historyCache.get(profile);
  if (cached && cached.key === key) {
    cacheHistory(profile, key, cached.records);   // touch, so the LRU keeps the live profile
    return cached.records;
  }
  let text = '';
  try {
    text = await fsp.readFile(file, 'utf8');
  } catch {
    return [];
  }
  const out = [];
  for (const line of text.split('\n')) {
    if (!line.trim()) continue;
    try {
      const record = JSON.parse(line);
      if (record && typeof record === 'object' && !Array.isArray(record)) out.push(record);
    } catch {
      // a half-written line from a crash: ignore it
    }
  }
  // A file this long has already been rotated once; keep the recent end rather than all of it.
  const records = out.length > HISTORY_MAX_RECORDS ? out.slice(-HISTORY_MAX_RECORDS) : out;
  cacheHistory(profile, key, records);
  return records;
}

/**
 * Appends one row. Serialised per profile through a promise chain, because two tablets (or a
 * tablet and a retry) posting at once could otherwise interleave the rotation with an append and
 * lose a row. Rotation archives; the oldest archives past the cap are dropped, since nothing
 * reads them and this laptop has filled its disk before.
 */
const historyWrites = new Map();

function appendHistory(profile, record) {
  const previous = historyWrites.get(profile) || Promise.resolve();
  const next = previous.then(() => appendHistoryNow(profile, record), () => appendHistoryNow(profile, record));
  historyWrites.set(profile, next.catch(() => {}));
  return next;
}

async function appendHistoryNow(profile, record) {
  const file = historyFile(profile);
  if (!file) throw new Error('bad path');
  await fsp.mkdir(HISTORY_DIR, { recursive: true });
  let size = 0;
  try {
    size = (await fsp.stat(file)).size;
  } catch {
    size = 0;
  }
  if (size > HISTORY_FILE_MAX_BYTES) {
    const archive = insideData(path.join(HISTORY_DIR, `${profile}-${stampForFile()}.jsonl`));
    if (archive) {
      await fsp.rename(file, archive);
      historyCache.delete(profile);
      await pruneHistoryArchives(profile);
    }
  }
  await fsp.appendFile(file, JSON.stringify(record) + '\n');
  // Keep the cache warm instead of throwing it away: this is the hot path while rowing.
  const cached = historyCache.get(profile);
  if (cached) {
    const records = cached.records.concat([record]);
    try {
      const stat = await fsp.stat(file);
      cacheHistory(profile, `${stat.size}:${stat.mtimeMs}`, records);
    } catch {
      historyCache.delete(profile);
    }
  }
}

async function pruneHistoryArchives(profile) {
  const pattern = new RegExp('^' + profile + '-(' + TIMESTAMP_PART + ')\\.jsonl$');
  let names = [];
  try {
    names = await fsp.readdir(HISTORY_DIR);
  } catch {
    return;
  }
  const archives = names.filter((name) => pattern.test(name)).sort().reverse();
  for (const name of archives.slice(HISTORY_ARCHIVES_PER_PROFILE)) {
    const victim = insideData(path.join(HISTORY_DIR, name));
    if (!victim) continue;
    try {
      await fsp.unlink(victim);
    } catch {
      // already gone
    }
  }
}

function rememberEvent(event) {
  const stored = {
    ...event,
    id: Date.now().toString(36) + Math.random().toString(36).slice(2, 8),
    receivedAt: nowIso(),
  };
  events.push(stored);
  while (events.length > 400) events.shift();
  fs.appendFile(EVENTS_FILE, JSON.stringify(stored) + '\n', () => {});
  broadcast('event', stored);
  return stored;
}

function broadcast(name, data) {
  const payload = `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const client of clients) {
    if (client.writableEnded || client.destroyed) {
      clients.delete(client);
      continue;
    }
    try {
      client.write(payload);
    } catch {
      clients.delete(client);
    }
  }
}

// Idle SSE connections get reaped by the OS and by some Wi-Fi gear; a comment frame keeps them up.
setInterval(() => {
  for (const client of clients) {
    if (client.writableEnded || client.destroyed) {
      clients.delete(client);
      continue;
    }
    try {
      client.write(': keepalive\n\n');
    } catch {
      clients.delete(client);
    }
  }
}, 20000).unref();

function clientIp(req) {
  return (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').toString();
}

function handleOptions(res) {
  res.writeHead(204, {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
  });
  res.end();
}

function handleApi(req, res, pathname, query) {
  if (req.method === 'OPTIONS') {
    handleOptions(res);
    return true;
  }

  if (pathname === '/api/state' && req.method === 'GET') {
    sendJson(res, 200, {
      ok: true,
      serverTime: nowIso(),
      serverUrls: localUrls(),
      events: events.slice(-100),
      browserCheckins: [...browserCheckins.values()],
      apkReady: fs.existsSync(path.join(PUBLIC_DIR, 'downloads', 'ergatta-row-diagnostic-debug.apk')),
      apk: publishedApk(),
    });
    return true;
  }

  // The tablet app fetches this once and caches it, so Coast Flight's maps keep working with
  // the laptop switched off. Same credential the /fly page is already served with, on the same
  // local network - no wider exposure, and still nothing stored in source.
  // The tablet posts a PNG of its own screen here. Getting images off a kiosk-locked tablet is
  // otherwise painful: no file manager, no app switcher, and the built-in screenshot lands in a
  // gallery you cannot reach. Body is the raw PNG; X-Shot-Name says which screen it was.
  if (pathname === '/api/screenshot' && req.method === 'POST') {
    const chunks = [];
    let bytes = 0;
    req.on('data', (chunk) => {
      bytes += chunk.length;
      if (bytes > 12 * 1024 * 1024) {
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      const body = Buffer.concat(chunks);
      // A PNG always starts with these 8 bytes. Refuse anything else, so a stray or empty POST
      // cannot leave a broken image among the real screenshots.
      const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
      if (body.length < PNG.length || !body.subarray(0, PNG.length).equals(PNG)) {
        sendJson(res, 400, { ok: false, error: 'body is not a PNG' });
        return;
      }
      const dir = path.join(DATA_DIR, 'screenshots');
      fs.mkdirSync(dir, { recursive: true });
      const label = String(req.headers['x-shot-name'] || 'screen')
        .replace(/[^A-Za-z0-9_-]/g, '').slice(0, 40) || 'screen';
      const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
      const file = path.join(dir, `${label}-${stamp}.png`);
      fs.writeFile(file, body, (error) => {
        if (error) {
          sendJson(res, 500, { ok: false, error: String(error.message) });
          return;
        }
        console.log(`screenshot saved: ${path.relative(ROOT, file)} (${bytes} bytes)`);
        sendJson(res, 200, { ok: true, file: path.basename(file), bytes });
      });
    });
    return true;
  }

  // Race recordings to swap with a friend. Stored as small JSON files under data/ghosts - copy one to
  // someone else's laptop and they can race it. No accounts and nothing leaves the network unless a
  // person moves the file themselves.
  if (pathname === '/api/ghosts' && req.method === 'GET') {
    const dir = path.join(DATA_DIR, 'ghosts');
    let list = [];
    try {
      list = fs.readdirSync(dir).filter((f) => f.endsWith('.json')).map((f) => {
        try {
          const g = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
          return { id: f.slice(0, -5), name: g.name, meters: g.meters, time: g.time, savedAt: g.savedAt };
        } catch (error) {
          return null;
        }
      }).filter(Boolean).sort((a, b) => String(b.savedAt).localeCompare(String(a.savedAt)));
    } catch (error) {
      list = [];
    }
    sendJson(res, 200, { ghosts: list });
    return true;
  }

  if (pathname.startsWith('/api/ghosts/') && req.method === 'GET') {
    const id = pathname.slice('/api/ghosts/'.length);
    if (!/^[A-Za-z0-9_-]{1,80}$/.test(id)) {
      sendJson(res, 400, { ok: false, error: 'bad id' });
      return true;
    }
    const file = path.join(DATA_DIR, 'ghosts', id + '.json');
    fs.readFile(file, 'utf8', (error, text) => {
      if (error) {
        sendJson(res, 404, { ok: false, error: 'no such recording' });
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(text);
    });
    return true;
  }

  if (pathname === '/api/ghosts' && req.method === 'POST') {
    const chunks = [];
    let bytes = 0;
    req.on('data', (chunk) => {
      bytes += chunk.length;
      if (bytes > 256 * 1024) {
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      let g;
      try {
        g = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      } catch (error) {
        sendJson(res, 400, { ok: false, error: 'not JSON' });
        return;
      }
      const meters = Number(g.meters);
      const time = Number(g.time);
      const name = String(g.name || 'WAKE rower').replace(/[^A-Za-z0-9 _-]/g, '').slice(0, 40) || 'WAKE rower';
      if (!Number.isInteger(meters) || meters < 100 || meters > 50000 || !(time > 0)
          || typeof g.samples !== 'string' || !/^[0-9,.]{1,200000}$/.test(g.samples)) {
        sendJson(res, 400, { ok: false, error: 'not a race recording' });
        return;
      }
      const dir = path.join(DATA_DIR, 'ghosts');
      fs.mkdirSync(dir, { recursive: true });
      const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
      const id = (name.replace(/ /g, '_') + '-' + meters + '-' + stamp).slice(0, 80);
      const record = { name, meters, time, samples: g.samples, savedAt: new Date().toISOString() };
      fs.writeFile(path.join(dir, id + '.json'), JSON.stringify(record), (error) => {
        if (error) {
          sendJson(res, 500, { ok: false, error: String(error.message) });
          return;
        }
        console.log(`race recording saved: ${id}`);
        sendJson(res, 200, { ok: true, id });
      });
    });
    return true;
  }

  // The tablet pushes its whole store here - profiles, records, progress, session log - so an
  // app update or a fresh tablet does not lose the rower's history. Two copies are written: a
  // timestamped one for rolling back, and <profile>-latest.json which is what a restore reads.
  if (pathname === '/api/backup' && req.method === 'POST') {
    readJsonBody(req, res, BACKUP_MAX_BYTES, (body, bytes) => {
      const profile = profileId(body.profile);
      if (!profile) {
        sendJson(res, 400, { ok: false, error: 'profile must match [a-z0-9-]{1,40}' });
        return;
      }
      const blob = { ...body, profile, savedAt: nowIso() };
      const text = JSON.stringify(blob);
      const stampedPath = insideData(path.join(BACKUP_DIR, `${profile}-${stampForFile()}.json`));
      const latestPath = insideData(path.join(BACKUP_DIR, `${profile}-latest.json`));
      const tempPath = insideData(path.join(BACKUP_DIR, `${profile}-latest.json.tmp`));
      if (!stampedPath || !latestPath || !tempPath) {
        sendJson(res, 400, { ok: false, error: 'bad path' });
        return;
      }
      (async () => {
        await fsp.mkdir(BACKUP_DIR, { recursive: true });
        await fsp.writeFile(stampedPath, text);
        // -latest.json is the one a restore reads, so it is never half-written: the new copy
        // lands beside it and is renamed over the top, which is atomic on this filesystem.
        await fsp.writeFile(tempPath, text);
        await fsp.rename(tempPath, latestPath);
        // Cap the rolling copies. Only names that match this profile's timestamp pattern are
        // touched, so "dustin-latest.json" and another profile's files are never candidates.
        const owned = await timestampedBackups(profile);
        let dropped = 0;
        for (const name of owned.slice(BACKUPS_PER_PROFILE)) {
          const victim = insideData(path.join(BACKUP_DIR, name));
          if (!victim) continue;
          try {
            await fsp.unlink(victim);
            dropped += 1;
          } catch {
            // already gone
          }
        }
        console.log(`backup saved: ${path.basename(stampedPath)} (${bytes} bytes, ${dropped} dropped)`);
        sendJson(res, 200, {
          ok: true,
          profile,
          file: path.basename(stampedPath),
          bytes: Buffer.byteLength(text),
          kept: Math.min(BACKUPS_PER_PROFILE, owned.length),
          dropped,
        });
      })().catch((error) => {
        sendJson(res, 500, { ok: false, error: String(error && error.message ? error.message : error) });
      });
    });
    return true;
  }

  // Restore: the newest blob for one profile. `download=1` makes the browser save it.
  if (pathname === '/api/backup' && req.method === 'GET') {
    const profile = profileId(query && query.get('profile'));
    if (!profile) {
      sendJson(res, 400, { ok: false, error: 'profile must match [a-z0-9-]{1,40}' });
      return true;
    }
    const file = insideData(path.join(BACKUP_DIR, `${profile}-latest.json`));
    if (!file) {
      sendJson(res, 400, { ok: false, error: 'bad path' });
      return true;
    }
    fs.readFile(file, 'utf8', (error, text) => {
      if (error) {
        sendJson(res, 404, { ok: false, error: 'no backup for that profile' });
        return;
      }
      const headers = {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(text),
        'Access-Control-Allow-Origin': '*',
        'Cache-Control': 'no-store',
      };
      if (query && query.get('download')) {
        headers['Content-Disposition'] =
          `attachment; filename="wake-backup-${profile}-${stampForFile().slice(0, 10)}.json"`;
      }
      res.writeHead(200, headers);
      res.end(text);
    });
    return true;
  }

  if (pathname === '/api/backups' && req.method === 'GET') {
    (async () => {
      let names = [];
      try {
        names = (await fsp.readdir(BACKUP_DIR)).filter((name) => name.endsWith('.json'));
      } catch {
        names = [];
      }
      const list = [];
      let parsed = 0;
      for (const name of names.sort().reverse().slice(0, BACKUP_LIST_MAX)) {
        const file = insideData(path.join(BACKUP_DIR, name));
        if (!file) continue;
        let stat;
        try {
          stat = await fsp.stat(file);
        } catch {
          continue;
        }
        const match = new RegExp('^([a-z0-9-]{1,40})-(latest|' + TIMESTAMP_PART + ')\\.json$').exec(name);
        // Opening a blob is the expensive part of this listing, so only the newest few are read
        // for a session count; the rest report null rather than stalling the whole server.
        let sessions = null;
        if (stat.size <= BACKUP_PARSE_MAX_BYTES && parsed < BACKUP_LIST_PARSE_MAX) {
          parsed += 1;
          try {
            sessions = backupSessionCount(JSON.parse(await fsp.readFile(file, 'utf8')));
          } catch {
            sessions = null;
          }
        }
        list.push({
          file: name,
          profile: match ? match[1] : name.replace(/\.json$/, ''),
          latest: Boolean(match && match[2] === 'latest'),
          when: stat.mtime.toISOString(),
          bytes: stat.size,
          sessions,
        });
      }
      sendJson(res, 200, { ok: true, backups: list, perProfileCap: BACKUPS_PER_PROFILE });
    })().catch((error) => {
      sendJson(res, 500, { ok: false, error: String(error && error.message ? error.message : error) });
    });
    return true;
  }

  // One finished row, appended as a line. Small enough that the file stays readable by hand.
  if (pathname === '/api/history' && req.method === 'POST') {
    readJsonBody(req, res, HISTORY_POST_MAX_BYTES, (body) => {
      const profile = profileId(body.profile
        || (body.session && typeof body.session === 'object' ? body.session.profile : null));
      if (!profile) {
        sendJson(res, 400, { ok: false, error: 'profile must match [a-z0-9-]{1,40}' });
        return;
      }
      const record = normaliseSession(body, profile);
      if (!record) {
        sendJson(res, 400, { ok: false, error: 'session needs metres or seconds' });
        return;
      }
      appendHistory(profile, record).then(() => {
        broadcast('history-session', record);
        sendJson(res, 200, { ok: true, profile, session: record });
      }).catch((error) => {
        sendJson(res, 500, { ok: false, error: String(error && error.message ? error.message : error) });
      });
    });
    return true;
  }

  // Which profiles have rowed, for the dashboard's profile chips. No passwords anywhere: a
  // profile is just a name the rower taps on the tablet.
  if (pathname === '/api/history/profiles' && req.method === 'GET') {
    (async () => {
      let names = [];
      try {
        names = (await fsp.readdir(HISTORY_DIR))
          .filter((name) => /^[a-z0-9-]{1,40}\.jsonl$/.test(name));
      } catch {
        names = [];
      }
      const profiles = [];
      for (const name of names) {
        const profile = name.replace(/\.jsonl$/, '');
        const sessions = await readHistory(profile);
        let meters = 0;
        let seconds = 0;
        for (const s of sessions) {
          meters += Number(s.meters) || 0;
          seconds += Number(s.seconds) || 0;
        }
        const last = sessions.length ? sessions[sessions.length - 1] : null;
        profiles.push({
          profile,
          sessions: sessions.length,
          meters,
          seconds,
          lastAt: last ? last.at : null,
        });
      }
      profiles.sort((a, b) => String(b.lastAt || '').localeCompare(String(a.lastAt || '')));
      sendJson(res, 200, { ok: true, profiles });
    })().catch((error) => {
      sendJson(res, 500, { ok: false, error: String(error && error.message ? error.message : error) });
    });
    return true;
  }

  if (pathname === '/api/history' && req.method === 'GET') {
    const profile = profileId(query && query.get('profile'));
    if (!profile) {
      sendJson(res, 400, { ok: false, error: 'profile must match [a-z0-9-]{1,40}' });
      return true;
    }
    const bound = (value) => (/^[0-9T:.Z+-]{4,32}$/.test(String(value || '')) ? String(value) : '');
    const from = bound(query && query.get('from'));
    const to = bound(query && query.get('to'));
    // Number(null) is 0, not NaN - read the raw param before converting or the default collapses.
    const limitRaw = query && query.get('limit');
    const limit = limitRaw && Number.isFinite(Number(limitRaw))
      ? Math.max(1, Math.min(5000, Math.round(Number(limitRaw))))
      : 2000;
    (async () => {
      // `at` is written as a canonical UTC ISO string by normaliseSession, so ISO strings sort
      // as text and a plain compare does date filtering. A date-only `to` should include that
      // whole day, hence the high sentinel appended to it.
      const all = await readHistory(profile);
      const sessions = all.filter((s) => {
        const at = String(s.at || '');
        if (from && at < from) return false;
        if (to && at > to + '￿') return false;
        return true;
      });
      const trimmed = sessions.slice(-limit);
      sendJson(res, 200, {
        ok: true,
        profile,
        from: from || null,
        to: to || null,
        total: sessions.length,
        count: trimmed.length,
        sessions: trimmed,
      });
    })().catch((error) => {
      sendJson(res, 500, { ok: false, error: String(error && error.message ? error.message : error) });
    });
    return true;
  }

  if (pathname === '/api/cesium-token' && req.method === 'GET') {
    sendJson(res, 200, { ionToken: cesiumToken() });
    return true;
  }

  if (pathname === '/api/events/stream' && req.method === 'GET') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-store',
      Connection: 'keep-alive',
      'Access-Control-Allow-Origin': '*',
    });
    res.write(`event: hello\ndata: ${JSON.stringify({ serverTime: nowIso(), serverUrls: localUrls() })}\n\n`);
    clients.add(res);
    req.on('close', () => clients.delete(res));
    return true;
  }

  if (pathname === '/api/event' && req.method === 'POST') {
    readJson(req, res, (body) => {
      const stored = rememberEvent({
        remoteAddress: clientIp(req),
        type: typeof body.type === 'string' ? body.type : 'unknown',
        body,
      });
      sendJson(res, 200, { ok: true, id: stored.id });
    });
    return true;
  }

  if (pathname === '/api/export' && req.method === 'GET') {
    const stamp = nowIso().replace(/[:.]/g, '-');
    res.writeHead(200, {
      'Content-Type': 'application/x-ndjson; charset=utf-8',
      'Content-Disposition': `attachment; filename="ergatta-events-${stamp}.jsonl"`,
      'Cache-Control': 'no-store',
    });
    fs.createReadStream(EVENTS_FILE).on('error', () => res.end()).pipe(res);
    return true;
  }

  if (pathname === '/api/session/reset' && req.method === 'POST') {
    // Archive rather than delete: a capture is the whole point of this tool.
    const stamp = nowIso().replace(/[:.]/g, '-');
    let archived = null;
    if (fs.existsSync(EVENTS_FILE) && fs.statSync(EVENTS_FILE).size > 0) {
      archived = path.join(DATA_DIR, `events-${stamp}.jsonl`);
      fs.renameSync(EVENTS_FILE, archived);
    }
    events.length = 0;
    broadcast('session-reset', { archived: archived ? path.basename(archived) : null });
    sendJson(res, 200, { ok: true, archived: archived ? path.basename(archived) : null });
    return true;
  }

  if (pathname === '/api/browser-checkin' && req.method === 'POST') {
    readJson(req, res, (body) => {
      const key = `${clientIp(req)}|${body.userAgent || req.headers['user-agent'] || 'unknown'}`;
      const checkin = {
        key,
        remoteAddress: clientIp(req),
        lastSeen: nowIso(),
        userAgent: String(body.userAgent || req.headers['user-agent'] || ''),
        screen: body.screen || null,
        page: body.page || '/',
      };
      browserCheckins.set(key, checkin);
      broadcast('browser-checkin', checkin);
      sendJson(res, 200, { ok: true });
    });
    return true;
  }

  return false;
}

function serveStatic(req, res, pathname) {
  // Friendly URL for the flight view; everything else maps straight to a file.
  const relative = pathname === '/' ? '/index.html'
    : pathname === '/fly' || pathname === '/fly/' ? '/fly.html'
    : pathname;
  let decoded;
  try {
    decoded = decodeURIComponent(relative);
  } catch {
    res.writeHead(400);
    res.end('bad path');
    return;
  }

  const resolved = path.resolve(PUBLIC_DIR, '.' + decoded);
  if (resolved !== PUBLIC_DIR && !resolved.startsWith(PUBLIC_DIR + path.sep)) {
    res.writeHead(403);
    res.end('forbidden');
    return;
  }

  if (resolved.endsWith('fly.html')) {
    fs.readFile(resolved, 'utf8', (error, html) => {
      if (error) {
        res.writeHead(404);
        res.end('not found');
        return;
      }
      const token = cesiumToken();
      const body = html.replace('__CESIUM_ION_TOKEN__', token.replace(/[\\"]/g, ''));
      res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'no-store',
      });
      res.end(body);
    });
    return;
  }

  fs.readFile(resolved, (error, data) => {
    if (error) {
      logDownload(req, pathname, 404, 0);
      res.writeHead(404);
      res.end('not found');
      return;
    }

    // Content-Length and Accept-Ranges matter for the rower tablet: Android's download manager on
    // Chrome 70 wants a size it can show and a download it can resume, and fails quietly without
    // them. Node would otherwise send this chunked, with no length at all.
    const headers = {
      'Content-Type': contentType(resolved),
      'Cache-Control': resolved.endsWith('.apk') ? 'no-store' : 'no-cache',
      'Access-Control-Allow-Origin': '*',
      'Accept-Ranges': 'bytes',
    };

    const range = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range || '');
    if (range && (range[1] !== '' || range[2] !== '')) {
      const last = data.length - 1;
      let start = range[1] === '' ? last - Number(range[2]) + 1 : Number(range[1]);
      let end = range[2] === '' ? last : Number(range[2]);
      start = Math.max(0, start);
      end = Math.min(last, end);
      if (start > end) {
        logDownload(req, pathname, 416, 0);
        res.writeHead(416, { 'Content-Range': `bytes */${data.length}` });
        res.end();
        return;
      }
      const slice = data.subarray(start, end + 1);
      headers['Content-Length'] = slice.length;
      headers['Content-Range'] = `bytes ${start}-${end}/${data.length}`;
      logDownload(req, pathname, 206, slice.length);
      res.writeHead(206, headers);
      res.end(slice);
      return;
    }

    headers['Content-Length'] = data.length;
    logDownload(req, pathname, 200, data.length);
    res.writeHead(200, headers);
    res.end(data);
  });
}

/**
 * Logs downloads (the APK and version.json), so a tap on the tablet's Install button is visible
 * here. Nothing else is logged per request; this is for diagnosing "the link did nothing".
 */
function logDownload(req, pathname, status, bytes) {
  if (!pathname.startsWith('/downloads/')) return;
  const agent = (req.headers['user-agent'] || 'unknown').slice(0, 80);
  const range = req.headers.range ? ` range=${req.headers.range}` : '';
  console.log(`download ${status} ${pathname} ${bytes}B${range} from ${req.socket.remoteAddress} "${agent}"`);
}

function contentType(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  if (ext === '.html') return 'text/html; charset=utf-8';
  if (ext === '.css') return 'text/css; charset=utf-8';
  if (ext === '.js') return 'application/javascript; charset=utf-8';
  if (ext === '.json') return 'application/json; charset=utf-8';
  if (ext === '.svg') return 'image/svg+xml';
  if (ext === '.apk') return 'application/vnd.android.package-archive';
  return 'application/octet-stream';
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || `localhost:${PORT}`}`);
  if (handleApi(req, res, url.pathname, url.searchParams)) {
    return;
  }
  serveStatic(req, res, url.pathname);
});

server.listen(PORT, HOST, () => {
  console.log('Ergatta diagnostic dashboard is running:');
  for (const url of localUrls()) {
    console.log(`  ${url}`);
  }
  console.log('');
  console.log('Open the non-localhost address on the rower tablet.');
  console.log(cesiumToken()
    ? 'Coast Flight: Cesium Ion token found - photorealistic maps available at /fly'
    : 'Coast Flight: no Cesium Ion token - /fly will use OpenStreetMap. Set CESIUM_ION_TOKEN'
      + ' or create server/cesium.local.json with {"ionToken":"..."}');
});

const discoveryServer = dgram.createSocket('udp4');
discoveryServer.on('message', (message, remote) => {
  if (message.toString('utf8').trim() !== DISCOVERY_REQUEST) return;
  const response = Buffer.from(DISCOVERY_RESPONSE + bestLocalUrlFor(remote.address), 'utf8');
  discoveryServer.send(response, remote.port, remote.address);
});
discoveryServer.on('error', (error) => {
  console.error(`Laptop discovery error: ${error.message}`);
});
discoveryServer.bind(DISCOVERY_PORT, HOST, () => {
  discoveryServer.setBroadcast(true);
  console.log(`Automatic laptop discovery is listening on UDP ${DISCOVERY_PORT}.`);
});

function shutdown() {
  for (const client of clients) {
    try {
      client.end();
    } catch {
      // already gone
    }
  }
  discoveryServer.close();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1500).unref();
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
