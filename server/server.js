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

function handleApi(req, res, pathname) {
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
      const dir = path.join(DATA_DIR, 'screenshots');
      fs.mkdirSync(dir, { recursive: true });
      const label = String(req.headers['x-shot-name'] || 'screen')
        .replace(/[^A-Za-z0-9_-]/g, '').slice(0, 40) || 'screen';
      const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
      const file = path.join(dir, `${label}-${stamp}.png`);
      fs.writeFile(file, Buffer.concat(chunks), (error) => {
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
      res.writeHead(404);
      res.end('not found');
      return;
    }

    res.writeHead(200, {
      'Content-Type': contentType(resolved),
      'Cache-Control': resolved.endsWith('.apk') ? 'no-store' : 'no-cache',
      'Access-Control-Allow-Origin': '*',
    });
    res.end(data);
  });
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
  if (handleApi(req, res, url.pathname)) {
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
