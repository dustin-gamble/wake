'use strict';

const $ = (id) => document.getElementById(id);

const el = {
  pillStream: $('pillStream'),
  pillTablet: $('pillTablet'),
  pillMonitor: $('pillMonitor'),
  themeToggle: $('themeToggle'),
  vTime: $('vTime'), vDistance: $('vDistance'),
  vPace: $('vPace'), vRate: $('vRate'), vWatts: $('vWatts'), vStrokes: $('vStrokes'),
  sPackets: $('sPackets'), sPulses: $('sPulses'), sAge: $('sAge'), sRate: $('sRate'),
  sCommand: $('sCommand'), sRetired: $('sRetired'), sDropped: $('sDropped'), sVersion: $('sVersion'),
  sourceLine: $('sourceLine'),
  mapBody: $('mapBody'),
  addressList: $('addressList'),
  browserList: $('browserList'),
  eventList: $('eventList'),
  filters: $('filters'),
  openAppLink: $('openAppLink'),
  apkLine: $('apkLine'),
  resetButton: $('resetButton'),
  pauseButton: $('pauseButton'),
  chart: $('chart'),
  paddle: $('paddle'),
  sPulseHz: $('sPulseHz'),
  sPulseValue: $('sPulseValue'),
};

let targetSpeed = 0;
let shownSpeed = 0;
let speedUpdatedAt = 0;
let flywheelMoving = false;
let pulseAngle = 0;
let paddleLastFrame = 0;

const CHART_WINDOW_MS = 120000;
const MAX_EVENT_NODES = 120;

const seenEvents = new Set();
const eventTypes = new Map();
const activeFilters = new Set();
const samples = [];

let paused = false;
let lastStatus = null;
let lastStatusAt = 0;
let lastTablet = null;
let eventTimestamps = [];
let lastSignature = '';
let lastChangeAt = 0;

/* ---------------- formatting ---------------- */

const num = (value) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.max(0, Math.round(parsed)) : 0;
};

const pad2 = (value) => String(value).padStart(2, '0');

function formatElapsed(totalSeconds) {
  const total = num(totalSeconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  return hours > 0
    ? `${hours}:${pad2(minutes)}:${pad2(seconds)}`
    : `${minutes}:${pad2(seconds)}`;
}

function formatPace(secondsPer500m) {
  const seconds = num(secondsPer500m);
  if (seconds <= 0 || seconds > 3599) return '--:--';
  return `${Math.floor(seconds / 60)}:${pad2(seconds % 60)}`;
}

function formatAge(ageMs) {
  const ms = Number(ageMs);
  if (!Number.isFinite(ms) || ms < 0) return '--';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60000)}m`;
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function withUnit(value, unit) {
  return `${value}<span class="unit">${unit}</span>`;
}

/* ---------------- connection pills ---------------- */

function setPill(pill, state, text) {
  pill.className = `pill${state ? ` is-${state}` : ''}`;
  pill.lastElementChild.textContent = text;
}

function refreshPills() {
  const now = Date.now();

  if (!lastTablet) {
    setPill(el.pillTablet, '', 'No tablet');
  } else {
    const age = Math.round((now - lastTablet.at) / 1000);
    setPill(
      el.pillTablet,
      age <= 8 ? 'live' : 'bad',
      age <= 8 ? `Tablet ${lastTablet.address}` : `Tablet quiet ${age}s`,
    );
  }

  if (!lastStatus) {
    setPill(el.pillMonitor, '', 'No monitor');
  } else {
    const age = now - lastStatusAt + Number(lastStatus.lastPacketAgeMs || 0);
    const frozenFor = lastChangeAt ? now - lastChangeAt : 0;
    if (!lastStatus.monitorConnected) {
      setPill(el.pillMonitor, 'bad', 'Monitor silent');
    } else if (frozenFor > 10000) {
      // Heartbeats still arrive when polling has stalled; unchanging values are the real tell.
      setPill(el.pillMonitor, 'bad', `Data frozen ${formatAge(frozenFor)}`);
    } else if (age > 5000) {
      setPill(el.pillMonitor, 'warn', `Monitor idle ${formatAge(age)}`);
    } else {
      setPill(el.pillMonitor, 'live', lastStatus.rowing ? 'Rowing' : 'Monitor ready');
    }
  }

  const rate = eventTimestamps.filter((t) => now - t < 5000).length / 5;
  eventTimestamps = eventTimestamps.filter((t) => now - t < 10000);
  el.sRate.textContent = rate.toFixed(1);

  if (lastStatus) {
    el.sAge.textContent = formatAge(Number(lastStatus.lastPacketAgeMs) + (now - lastStatusAt));
    const frozenFor = lastChangeAt ? now - lastChangeAt : 0;
    el.sourceLine.textContent = frozenFor > 10000
      ? `${lastTablet ? lastTablet.address : 'Tablet'} is still sending, but no decoded value has `
        + `changed in ${formatAge(frozenFor)}. The monitor poll has stalled.`
      : `Receiving from ${lastTablet ? lastTablet.address : 'tablet'}`
        + `${lastStatus.lastCommand ? `, last command ${lastStatus.lastCommand}` : ''}.`;
  }
}

/* ---------------- live metrics ---------------- */

function renderStatus(payload, at) {
  lastStatus = payload;
  lastStatusAt = Date.now();
  const sampleAt = Number.isFinite(at) ? at : Date.now();

  el.vTime.textContent = formatElapsed(payload.elapsedSeconds);
  const reported = num(payload.distanceMeters);
  const derived = num(payload.derivedDistanceMeters);
  el.vDistance.innerHTML = reported > 0
    ? withUnit(reported, 'm')
    : withUnit(derived > 0 ? `~${derived}` : 0, 'm');
  el.vPace.textContent = formatPace(payload.paceSecondsPer500m);
  el.vRate.innerHTML = withUnit(num(payload.strokeRate), 'spm');
  el.vWatts.innerHTML = withUnit(num(payload.watts), 'W');
  el.vStrokes.textContent = num(payload.strokes);

  const signature = [
    payload.lastPacket, payload.watts, payload.distanceMeters,
    payload.strokes, payload.elapsedSeconds, payload.strokeRate,
  ].join('|');
  if (signature !== lastSignature) {
    lastSignature = signature;
    lastChangeAt = Date.now();
  }

  targetSpeed = Number(payload.waterSpeedMps) || 0;
  flywheelMoving = Boolean(payload.flywheelMoving);
  speedUpdatedAt = Date.now();
  el.sPulseValue.textContent = payload.pulseHz
    ? `${Number(payload.pulseHz).toFixed(1)} Hz reporting · Pxx 0x${Number(payload.lastPulseValue || 0).toString(16).toUpperCase()}`
    : '--';

  el.sPackets.textContent = num(payload.packetsSeen);
  el.sPulses.textContent = num(payload.pulsesSeen);
  el.sCommand.textContent = payload.lastCommand || '--';
  el.sRetired.textContent = num(payload.retiredFieldCount);
  el.sDropped.textContent = num(payload.droppedUploads);

  if (Date.now() - sampleAt <= CHART_WINDOW_MS) {
    samples.push({ t: sampleAt, watts: num(payload.watts), rate: num(payload.strokeRate) });
    samples.sort((a, b) => a.t - b.t);
  }
  while (samples.length && Date.now() - samples[0].t > CHART_WINDOW_MS) samples.shift();

  renderFieldTable(payload.fields);
  drawChart();
}

/**
 * The app reports each polled address as "IRD055 distance = 1234 (0x4D2, 3s ago)".
 * Parsing it here keeps the wire format human-readable in the raw capture.
 */
const FIELD_PATTERN = /^(\S+)\s+(\S+)\s+=\s+(.*)$/;

function renderFieldTable(fields) {
  if (!Array.isArray(fields) || !fields.length) return;

  el.mapBody.innerHTML = fields.map((line) => {
    const match = FIELD_PATTERN.exec(String(line));
    if (!match) return '';
    const [, request, label, rest] = match;

    let value = '--';
    let hex = '';
    let age = '';
    let tag = '<span class="tag wait">waiting</span>';

    if (rest === 'retired') {
      tag = '<span class="tag retired">refused</span>';
    } else if (rest === 'no reply yet') {
      // leave defaults
    } else {
      const detail = /^(-?\d+)\s+\(0x([0-9A-Fa-f]+),\s*(\d+)s ago\)$/.exec(rest);
      if (detail) {
        value = detail[1];
        hex = `0x${detail[2]}`;
        age = `${detail[3]}s`;
        tag = '<span class="tag ok">answered</span>';
      } else {
        value = rest;
      }
    }

    return `<tr>
      <td class="addr">${escapeHtml(request)}</td>
      <td class="addr">${escapeHtml(label)}</td>
      <td class="val">${escapeHtml(value)}</td>
      <td class="addr">${escapeHtml(hex)}</td>
      <td class="age">${escapeHtml(age)}</td>
      <td>${tag}</td>
    </tr>`;
  }).join('');
}

/* ---------------- chart ---------------- */

function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function drawChart() {
  const canvas = el.chart;
  const ratio = window.devicePixelRatio || 1;
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  if (!width || !height) return;

  if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
  }

  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, width, height);

  const padLeft = 34;
  const padRight = 34;
  const padTop = 8;
  const padBottom = 18;
  const plotW = width - padLeft - padRight;
  const plotH = height - padTop - padBottom;

  const line = cssVar('--line-soft') || '#1a2230';
  const faint = cssVar('--text-faint') || '#5d6b80';

  ctx.strokeStyle = line;
  ctx.lineWidth = 1;
  ctx.font = '10px ui-monospace, Menlo, monospace';
  ctx.fillStyle = faint;

  for (let i = 0; i <= 4; i++) {
    const y = padTop + (plotH * i) / 4;
    ctx.beginPath();
    ctx.moveTo(padLeft, y + 0.5);
    ctx.lineTo(width - padRight, y + 0.5);
    ctx.stroke();
  }

  if (samples.length < 2) {
    ctx.fillText('Waiting for rowing data', padLeft + 6, padTop + plotH / 2);
    return;
  }

  const now = Date.now();
  const maxWatts = Math.max(60, ...samples.map((s) => s.watts));
  const maxRate = Math.max(20, ...samples.map((s) => s.rate));

  const xOf = (t) => padLeft + plotW * (1 - (now - t) / CHART_WINDOW_MS);

  const series = (key, max, color, fill) => {
    ctx.beginPath();
    samples.forEach((sample, index) => {
      const x = xOf(sample.t);
      const y = padTop + plotH * (1 - sample[key] / max);
      if (index === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.8;
    ctx.lineJoin = 'round';
    ctx.stroke();

    if (fill) {
      ctx.lineTo(xOf(samples[samples.length - 1].t), padTop + plotH);
      ctx.lineTo(xOf(samples[0].t), padTop + plotH);
      ctx.closePath();
      const gradient = ctx.createLinearGradient(0, padTop, 0, padTop + plotH);
      gradient.addColorStop(0, `${color}33`);
      gradient.addColorStop(1, `${color}00`);
      ctx.fillStyle = gradient;
      ctx.fill();
    }
  };

  const accent = cssVar('--accent') || '#35d0ba';
  const pulse = cssVar('--pulse') || '#6f8cff';
  series('watts', maxWatts, accent, true);
  series('rate', maxRate, pulse, false);

  ctx.fillStyle = faint;
  ctx.textAlign = 'right';
  ctx.fillText(String(Math.round(maxWatts)), padLeft - 5, padTop + 8);
  ctx.fillText('0', padLeft - 5, padTop + plotH);
  ctx.textAlign = 'left';
  ctx.fillText(String(Math.round(maxRate)), width - padRight + 5, padTop + 8);
  ctx.textAlign = 'center';
  ctx.fillText('-2m', padLeft, height - 5);
  ctx.fillText('now', width - padRight, height - 5);
}

/* ---------------- water paddle ---------------- */

const BLADES = 8;
const WATER_DRAG = 0.12;
const DEGREES_PER_MPS = 105;

/**
 * Mirrors the tablet's paddle view: blades turn at the measured pulse rate and the swirl
 * intensifies with speed. Angle advances by elapsed time so the spin matches the real rate
 * regardless of frame pacing.
 */
function drawPaddle(timestamp) {
  const canvas = el.paddle;
  const ratio = window.devicePixelRatio || 1;
  const size = canvas.clientWidth || 130;
  if (canvas.width !== Math.round(size * ratio)) {
    canvas.width = Math.round(size * ratio);
    canvas.height = Math.round(size * ratio);
  }

  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, size, size);

  const cx = size / 2;
  const cy = size / 2;
  const radius = size / 2 - 8;
  const dt = paddleLastFrame ? Math.min(0.1, (timestamp - paddleLastFrame) / 1000) : 0;
  paddleLastFrame = timestamp;

  // The monitor reports speed 0 and stops sending pulses the moment the drive stops, even
  // though the paddle is still turning, so the wind-down is modelled rather than measured.
  // Quadratic fluid drag: v(t) = v0 / (1 + k*v0*t) - quick initial drop, long tail.
  const stale = !speedUpdatedAt || Date.now() - speedUpdatedAt > 1500;
  const coasting = stale || !flywheelMoving;
  const aim = coasting ? 0 : targetSpeed;
  if (coasting && shownSpeed > 0) {
    shownSpeed -= WATER_DRAG * shownSpeed * shownSpeed * dt;
  } else {
    shownSpeed += (aim - shownSpeed) * Math.min(1, (aim > shownSpeed ? 4.5 : 0.5) * dt);
  }
  if (shownSpeed < 0.02) shownSpeed = 0;
  pulseAngle = (pulseAngle + shownSpeed * DEGREES_PER_MPS * dt) % 360;

  el.sPulseHz.innerHTML = shownSpeed > 0.02
    ? `${shownSpeed.toFixed(1)}<span class="unit" style="font-size:0.45em;color:var(--text-faint)"> m/s</span>`
    : '--';

  const accent = cssVar('--accent') || '#35d0ba';
  const pulse = cssVar('--pulse') || '#6f8cff';
  const line = cssVar('--line') || '#212b3b';
  const intensity = Math.min(1, shownSpeed / 4.5);

  ctx.strokeStyle = line;
  ctx.lineWidth = 1.5;
  ctx.beginPath();
  ctx.arc(cx, cy, radius, 0, Math.PI * 2);
  ctx.stroke();

  ctx.strokeStyle = pulse;
  ctx.globalAlpha = 0.15 + 0.6 * intensity;
  ctx.lineWidth = 1.5 + 2.5 * intensity;
  for (let i = 0; i < 3; i++) {
    const r = radius * (0.62 + i * 0.14);
    const from = ((pulseAngle * (1.4 + i * 0.3)) * Math.PI) / 180;
    ctx.beginPath();
    ctx.arc(cx, cy, r, from, from + ((55 + 40 * intensity) * Math.PI) / 180);
    ctx.stroke();
  }
  ctx.globalAlpha = 1;

  ctx.save();
  ctx.translate(cx, cy);
  ctx.rotate((pulseAngle * Math.PI) / 180);
  ctx.fillStyle = accent;
  ctx.globalAlpha = 0.4 + 0.6 * intensity;
  for (let i = 0; i < BLADES; i++) {
    ctx.save();
    ctx.rotate((i * 2 * Math.PI) / BLADES);
    const inner = radius * 0.26;
    const outer = radius * 0.86;
    ctx.beginPath();
    ctx.roundRect(-3, -outer, 6, outer - inner, 3);
    ctx.fill();
    ctx.restore();
  }
  ctx.restore();
  ctx.globalAlpha = 1;

  ctx.fillStyle = accent;
  ctx.beginPath();
  ctx.arc(cx, cy, radius * 0.18, 0, Math.PI * 2);
  ctx.fill();

  requestAnimationFrame(drawPaddle);
}

/* ---------------- events ---------------- */

const SEVERITY = {
  's4-write-failed': 'warn',
  's4-monitor-error': 'warn',
  's4-polling-paused': 'bad',
  's4-write-path-stalled': 'bad',
  's4-polling-stopped': 'bad',
  'serial-open-failed': 'bad',
  'serial-read-stopped': 'bad',
  'open-failed': 'bad',
  'permission-denied': 'bad',
  'claim-interface-failed': 'bad',
  'no-readable-endpoint': 'bad',
};

function registerType(type) {
  if (eventTypes.has(type)) return;
  const button = document.createElement('button');
  button.className = 'chip';
  button.type = 'button';
  button.textContent = type;
  button.setAttribute('aria-pressed', 'false');
  button.addEventListener('click', () => {
    if (activeFilters.has(type)) activeFilters.delete(type);
    else activeFilters.add(type);
    button.setAttribute('aria-pressed', activeFilters.has(type) ? 'true' : 'false');
    applyFilters();
  });
  eventTypes.set(type, button);
  [...eventTypes.keys()].sort().forEach((key) => el.filters.appendChild(eventTypes.get(key)));
}

function applyFilters() {
  for (const node of el.eventList.children) {
    if (!node.dataset || !node.dataset.type) continue;
    const show = activeFilters.size === 0 || activeFilters.has(node.dataset.type);
    node.hidden = !show;
  }
}

function addEvent(event) {
  if (!event || seenEvents.has(event.id)) return;
  seenEvents.add(event.id);

  const type = event.type || (event.body && event.body.type) || 'event';
  const payload = (event.body && event.body.payload) || {};
  const appVersion = (event.body && event.body.appVersion) || '';

  eventTimestamps.push(Date.now());
  registerType(type);

  lastTablet = {
    address: event.remoteAddress || 'tablet',
    version: appVersion,
    at: Date.parse(event.receivedAt || '') || Date.now(),
  };
  if (appVersion) {
    el.sVersion.textContent = appVersion;
    renderApkLine();
  }

  if (type === 'rowing-status') renderStatus(payload, lastTablet.at);
  refreshPills();

  if (paused) return;

  const emptyNode = el.eventList.querySelector('.empty');
  if (emptyNode) emptyNode.remove();

  const node = document.createElement('div');
  const severity = SEVERITY[type];
  const compact = type === 'rowing-status' || type === 'raw-bytes';
  node.className = `event${severity ? ` sev-${severity}` : ''}${compact ? ' compact' : ''}`;
  node.dataset.type = type;
  node.innerHTML = compact
    ? `<div class="event-title"><span class="event-type">${escapeHtml(type)}</span></div>
       <span class="line">${escapeHtml(oneLine(type, payload))}</span>
       <span class="event-time">${escapeHtml(shortTime(event.receivedAt))}</span>`
    : `<div class="event-title">
         <span class="event-type">${escapeHtml(type)}</span>
         <span class="event-time">${escapeHtml(shortTime(event.receivedAt))}</span>
       </div>
       <pre>${escapeHtml(summarize(type, payload))}</pre>`;
  node.hidden = activeFilters.size > 0 && !activeFilters.has(type);
  el.eventList.prepend(node);

  while (el.eventList.children.length > MAX_EVENT_NODES) {
    el.eventList.lastElementChild.remove();
  }
}

function shortTime(iso) {
  const parsed = Date.parse(iso || '');
  if (!Number.isFinite(parsed)) return '';
  const date = new Date(parsed);
  return `${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`;
}

/** High-rate events collapse to one scannable line instead of a JSON block. */
function oneLine(type, payload) {
  if (type === 'raw-bytes') {
    return `${payload.source || '?'} ${num(payload.count)}B  ${payload.ascii || ''}`;
  }
  const state = payload.monitorConnected ? (payload.rowing ? 'rowing' : 'ready') : 'waiting';
  return [
    state,
    formatElapsed(payload.elapsedSeconds),
    `${num(payload.distanceMeters) || `~${num(payload.derivedDistanceMeters)}`}m`,
    formatPace(payload.paceSecondsPer500m),
    payload.pulseHz ? `${Number(payload.pulseHz).toFixed(1)}Hz` : '',
    `${num(payload.strokeRate)}spm`,
    `${num(payload.watts)}W`,
    payload.lastPacket || '',
    payload.lastCommand ? `<- ${payload.lastCommand}` : '',
    payload.heartbeat ? '(hb)' : '',
  ].filter(Boolean).join('  ');
}

function summarize(type, payload) {
  if (type === 'usb-snapshot') {
    return JSON.stringify({
      reason: payload.reason,
      deviceCount: payload.deviceCount,
      devices: (payload.devices || []).map((device) => ({
        name: device.friendlyName,
        vidPid: `${device.vendorIdHex}:${device.productIdHex}`,
        driver: device.serialDriver,
        permission: device.hasPermission,
      })),
    }, null, 1);
  }
  return JSON.stringify(payload, null, 1);
}

/* ---------------- panels ---------------- */

function renderAddresses(urls = []) {
  const external = urls.filter((url) => !url.includes('localhost'));
  const ordered = external.length ? [...external, ...urls.filter((u) => u.includes('localhost'))] : urls;
  el.addressList.innerHTML = ordered.length
    ? ordered.map((url, index) => `<a class="address" href="${escapeHtml(url)}"><span class="url">${escapeHtml(url)}</span>${
        index === 0 && external.length ? '<span class="hint">use this one</span>' : ''}</a>`).join('')
    : '<span class="empty">No network addresses reported.</span>';
}

function renderBrowsers(checkins = []) {
  if (!checkins.length) {
    el.browserList.innerHTML = '<span class="empty">No check-ins yet.</span>';
    return;
  }
  el.browserList.innerHTML = checkins
    .sort((a, b) => String(b.lastSeen).localeCompare(String(a.lastSeen)))
    .map((item) => {
      const screen = item.screen ? `${item.screen.width}x${item.screen.height}` : 'screen unknown';
      return `<div class="browser">
        <strong>${escapeHtml(item.remoteAddress || 'unknown')}</strong>
        <span class="hint" style="color:var(--text-faint)"> ${escapeHtml(screen)}</span>
        <pre>${escapeHtml(item.userAgent || '')}</pre>
      </div>`;
    })
    .join('');
}

/* ---------------- wiring ---------------- */

function connectLink() {
  return `ergattarowdiag://connect?server=${encodeURIComponent(window.location.origin)}`;
}

async function browserCheckin() {
  await fetch('/api/browser-checkin', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      page: window.location.pathname,
      userAgent: navigator.userAgent,
      screen: {
        width: window.screen ? window.screen.width : null,
        height: window.screen ? window.screen.height : null,
        devicePixelRatio: window.devicePixelRatio || 1,
      },
    }),
  });
}

/**
 * Say which build the Install button hands out, and whether the tablet is running it.
 *
 * The tablet cannot see its own update status and the version is easy to lose track of across
 * rebuilds, so the comparison is made here rather than left to memory.
 */
let publishedApk = null;

function renderApkLine() {
  if (!el.apkLine) return;
  if (!publishedApk) {
    el.apkLine.textContent = 'No version.json yet - run tools/publish-apk.sh.';
    el.apkLine.style.color = '';
    return;
  }
  const built = publishedApk.builtAt ? new Date(publishedApk.builtAt).toLocaleString() : 'unknown';
  const onTablet = lastTablet && lastTablet.version ? lastTablet.version : null;
  let line = `Published: ${publishedApk.versionName} (code ${publishedApk.versionCode}), built ${built}.`;
  if (onTablet && onTablet !== publishedApk.versionName) {
    line += `  Tablet is running ${onTablet} - install to update.`;
    el.apkLine.style.color = 'var(--warn)';
  } else if (onTablet) {
    line += `  Tablet is up to date.`;
    el.apkLine.style.color = 'var(--accent)';
  } else {
    el.apkLine.style.color = '';
  }
  el.apkLine.textContent = line;
}

async function loadState() {
  const response = await fetch('/api/state', { cache: 'no-store' });
  const state = await response.json();
  publishedApk = state.apk || null;
  renderApkLine();
  renderAddresses(state.serverUrls || []);
  renderBrowsers(state.browserCheckins || []);
  for (const event of state.events || []) addEvent(event);
}

function startStream() {
  const source = new EventSource('/api/events/stream');
  source.addEventListener('open', () => setPill(el.pillStream, 'live', 'Stream live'));
  source.addEventListener('error', () => setPill(el.pillStream, 'bad', 'Reconnecting'));
  source.addEventListener('hello', (message) => {
    renderAddresses(JSON.parse(message.data).serverUrls || []);
  });
  source.addEventListener('event', (message) => addEvent(JSON.parse(message.data)));
  source.addEventListener('browser-checkin', () => loadState().catch(() => {}));
  source.addEventListener('session-reset', () => {
    seenEvents.clear();
    samples.length = 0;
    el.eventList.innerHTML = '<span class="empty">New session started.</span>';
    drawChart();
  });
}

el.openAppLink.href = connectLink();
// The rower tablet is kiosk-locked with no app switcher: this is how you get back to WAKE.
const backTop = document.getElementById('backToAppTop');
if (backTop) {
  backTop.href = connectLink();
}

el.themeToggle.addEventListener('click', () => {
  const current = document.documentElement.getAttribute('data-theme');
  const next = current === 'light' ? 'dark' : current === 'dark' ? '' : 'light';
  if (next) document.documentElement.setAttribute('data-theme', next);
  else document.documentElement.removeAttribute('data-theme');
  try {
    localStorage.setItem('ergatta-theme', next);
  } catch {
    // private mode: the choice just does not persist
  }
  drawChart();
});

try {
  const saved = localStorage.getItem('ergatta-theme');
  if (saved) document.documentElement.setAttribute('data-theme', saved);
} catch {
  // ignore
}

el.pauseButton.addEventListener('click', () => {
  paused = !paused;
  el.pauseButton.setAttribute('aria-pressed', String(paused));
  el.pauseButton.textContent = paused ? 'Resume view' : 'Pause view';
});

el.resetButton.addEventListener('click', async () => {
  if (!window.confirm('Archive the current capture and start a new session?')) return;
  const response = await fetch('/api/session/reset', { method: 'POST' });
  const result = await response.json();
  document.getElementById('captureNote').textContent = result.archived
    ? `Archived as ${result.archived}. Recording a new session.`
    : 'Nothing to archive. Recording a new session.';
});

window.addEventListener('resize', drawChart);
requestAnimationFrame(drawPaddle);

// drawPaddle handles its own decay, so no separate staleness timer is needed here.

browserCheckin().catch(() => {});
loadState().catch(() => {});
startStream();
setInterval(() => browserCheckin().catch(() => {}), 5000);
setInterval(refreshPills, 1000);
setInterval(drawChart, 1000);
drawChart();
