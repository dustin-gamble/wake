'use strict';

/**
 * Coast Flight: a bird's-eye run down the California coast, driven by the rower.
 *
 * Power is lift, boat speed is airspeed, and the route flies itself. Live data arrives on the
 * dashboard's existing SSE stream, so this page needs nothing from the tablet beyond WAKE having
 * "STREAM" switched on.
 *
 * Maps come from the internet and there are three tiers, best first:
 *
 *   photo3d    Google Photorealistic 3D Tiles - real 3D mesh of the world, via Ion
 *   satellite  Cesium World Imagery (Bing aerial) draped on Cesium World Terrain
 *   osm        OpenStreetMap on a smooth ellipsoid - the no-token fallback
 *
 * The token is injected by the server from CESIUM_ION_TOKEN or server/cesium.local.json, so no
 * credential is stored in this file. Tapping the Map card cycles modes and reloads, because the
 * three need different viewer setups.
 *
 * Attribution note: Google and Bing both require their credit line to stay visible, so the
 * Cesium credit container is restyled in fly.html rather than hidden.
 */

const RAW_TOKEN = window.__WAKE_CESIUM_ION_TOKEN__
  || window.__AEROVERSE_CESIUM_ION_TOKEN__ || '';
// The server substitutes an empty string when it has no token; guard against the raw
// placeholder too, in case the page is ever opened straight off disk.
const ION_TOKEN = RAW_TOKEN && !RAW_TOKEN.startsWith('__') ? RAW_TOKEN : '';
const GOOGLE_3D_ASSET_ID = 2275207;
const MODE_KEY = 'wake.coastflight.map';
const MODES = ION_TOKEN ? ['photo3d', 'satellite', 'osm'] : ['osm'];
const MODE_LABEL = {
  photo3d: 'Photorealistic 3D',
  satellite: 'Satellite + terrain',
  osm: 'OpenStreetMap',
};

function currentMode() {
  let m = 'osm';
  try {
    m = window.localStorage.getItem(MODE_KEY) || MODES[0];
  } catch {
    m = MODES[0];
  }
  return MODES.includes(m) ? m : MODES[0];
}

function cycleMode() {
  const next = MODES[(MODES.indexOf(currentMode()) + 1) % MODES.length];
  try {
    window.localStorage.setItem(MODE_KEY, next);
  } catch {
    // no persistence available; the reload below still applies it for this view
  }
  window.location.reload();
}

/** The route, north to south, nudged just offshore so the coastline sits off the left wing. */
const ROUTE = [
  { name: 'Golden Gate Bridge', lat: 37.8199, lon: -122.4883 },
  { name: 'Pacifica', lat: 37.6138, lon: -122.5200 },
  { name: 'Half Moon Bay', lat: 37.4636, lon: -122.4586 },
  { name: 'Pigeon Point', lat: 37.1820, lon: -122.4000 },
  { name: 'Santa Cruz', lat: 36.9541, lon: -122.0308 },
  { name: 'Monterey Bay', lat: 36.7100, lon: -121.9300 },
  { name: 'Point Lobos', lat: 36.5216, lon: -121.9630 },
  { name: 'Bixby Bridge', lat: 36.3717, lon: -121.9219 },
  { name: 'Point Sur', lat: 36.3072, lon: -121.9138 },
  { name: 'Big Sur cliffs', lat: 36.0000, lon: -121.6000 },
  { name: 'Ragged Point', lat: 35.7767, lon: -121.3447 },
  { name: 'Piedras Blancas', lat: 35.6660, lon: -121.2870 },
  { name: 'Morro Rock', lat: 35.3688, lon: -120.8828 },
  { name: 'Pismo Beach', lat: 35.1428, lon: -120.6613 },
  { name: 'Point Conception', lat: 34.4486, lon: -120.4916 },
  { name: 'Santa Barbara', lat: 34.3885, lon: -119.6929 },
  { name: 'Ventura', lat: 34.2546, lon: -119.2932 },
  { name: 'Malibu', lat: 34.0059, lon: -118.7798 },
  { name: 'Santa Monica Pier', lat: 33.9889, lon: -118.4973 },
  { name: 'Palos Verdes', lat: 33.7245, lon: -118.4126 },
  { name: 'Huntington Beach', lat: 33.6395, lon: -118.0200 },
  { name: 'Newport Beach', lat: 33.5989, lon: -117.9289 },
  { name: 'Laguna Beach', lat: 33.5227, lon: -117.7854 },
  { name: 'San Clemente', lat: 33.4070, lon: -117.6320 },
  { name: 'La Jolla', lat: 32.8128, lon: -117.2913 },
  { name: 'Point Loma, San Diego', lat: 32.6622, lon: -117.2616 },
];

/* ---------------- flight model ---------------- */

const GLIDE_WATTS = 60;        // below this the bird sinks
const CLIMB_PER_WATT = 0.055;  // m/s of climb per watt above glide
const MAX_ALT = 4200;
const MIN_ALT = 25;
// Tuned for a sense of place rather than raw pace: a good rowing speed gives about 250 km/h,
// so landmarks arrive every minute or two. An earlier setting hit 550 km/h and the coast blurred.
const BASE_AIRSPEED = 22;      // m/s with the boat stopped but airborne
const AIRSPEED_PER_MPS = 16;   // m/s of airspeed per m/s of boat speed
const TRIP_KEY = 'wake.coastflight.along';

const state = {
  watts: 0,
  spm: 0,
  boatSpeed: 0,
  clock: 0,
  clockRunning: false,
  altitude: 600,
  airspeed: BASE_AIRSPEED,
  along: loadTrip(),   // metres along the route, carried across sessions
  bank: 0,
  flapPhase: 0,
  lastStrokes: -1,
  lastData: 0,
  ditched: false,
  connected: false,
};

/** The coast is a long trip; progress is kept so it accumulates over many rows. */
function loadTrip() {
  try {
    const v = Number(window.localStorage.getItem(TRIP_KEY));
    return Number.isFinite(v) && v > 0 ? v : 0;
  } catch {
    return 0;   // private mode: the trip just starts fresh
  }
}

function saveTrip() {
  try {
    window.localStorage.setItem(TRIP_KEY, String(Math.round(state.along)));
  } catch {
    // nothing to do; progress is a nicety
  }
}

/* ---------------- geo helpers ---------------- */

const R = 6371000;
const toRad = (d) => (d * Math.PI) / 180;
const toDeg = (r) => (r * 180) / Math.PI;

function haversine(a, b) {
  const dLat = toRad(b.lat - a.lat);
  const dLon = toRad(b.lon - a.lon);
  const la1 = toRad(a.lat);
  const la2 = toRad(b.lat);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

function bearing(a, b) {
  const la1 = toRad(a.lat);
  const la2 = toRad(b.lat);
  const dLon = toRad(b.lon - a.lon);
  const y = Math.sin(dLon) * Math.cos(la2);
  const x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dLon);
  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}

// Cumulative distances so a position along the route is a simple lookup.
const LEGS = [];
let routeLength = 0;
for (let i = 0; i < ROUTE.length - 1; i++) {
  const d = haversine(ROUTE[i], ROUTE[i + 1]);
  LEGS.push({ from: ROUTE[i], to: ROUTE[i + 1], length: d, start: routeLength });
  routeLength += d;
}

/** Position and heading at a distance along the route; loops back to the start at the end. */
function atDistance(metres) {
  const m = ((metres % routeLength) + routeLength) % routeLength;
  let leg = LEGS[LEGS.length - 1];
  for (const l of LEGS) {
    if (m >= l.start && m < l.start + l.length) {
      leg = l;
      break;
    }
  }
  const f = leg.length > 0 ? (m - leg.start) / leg.length : 0;
  return {
    lat: leg.from.lat + (leg.to.lat - leg.from.lat) * f,
    lon: leg.from.lon + (leg.to.lon - leg.from.lon) * f,
    heading: bearing(leg.from, leg.to),
    leg,
  };
}

function nextLandmark(metres) {
  const m = ((metres % routeLength) + routeLength) % routeLength;
  for (const l of LEGS) {
    if (m < l.start + l.length) {
      return { name: l.to.name, remaining: l.start + l.length - m };
    }
  }
  return { name: ROUTE[ROUTE.length - 1].name, remaining: 0 };
}

/* ---------------- Cesium ---------------- */

let viewer = null;

function initGlobe() {
  const mode = currentMode();
  if (ION_TOKEN) {
    Cesium.Ion.defaultAccessToken = ION_TOKEN;
  }
  viewer = new Cesium.Viewer('globe', {
    animation: false,
    baseLayerPicker: false,
    fullscreenButton: false,
    geocoder: false,
    homeButton: false,
    infoBox: false,
    navigationHelpButton: false,
    sceneModePicker: false,
    selectionIndicator: false,
    timeline: false,
    // Photorealistic tiles carry their own imagery, and OSM is the no-token fallback.
    baseLayer: mode === 'osm' ? new Cesium.ImageryLayer(
      new Cesium.OpenStreetMapImageryProvider({ url: 'https://tile.openstreetmap.org/' }))
      : false,
  });

  if (mode === 'photo3d') {
    // Google's 3D mesh replaces the globe entirely; leaving the globe on z-fights with it.
    viewer.scene.globe.show = false;
    Cesium.Cesium3DTileset.fromIonAssetId(GOOGLE_3D_ASSET_ID)
      .then((tileset) => viewer.scene.primitives.add(tileset))
      .catch(() => {
        // No entitlement for the Google asset: fall back rather than showing a blank sky.
        viewer.scene.globe.show = true;
        Cesium.createWorldImageryAsync()
          .then((p) => viewer.imageryLayers.addImageryProvider(p))
          .catch(() => { /* leave it bare */ });
        el('mapMode').textContent = 'Satellite (3D unavailable)';
      });
  } else if (mode === 'satellite') {
    Cesium.createWorldImageryAsync()
      .then((p) => viewer.imageryLayers.addImageryProvider(p))
      .catch(() => { /* imagery is a nicety; the flight still works */ });
    Cesium.createWorldTerrainAsync({ requestVertexNormals: true })
      .then((t) => { viewer.terrainProvider = t; })
      .catch(() => { /* smooth ellipsoid is fine */ });
  }

  viewer.scene.globe.enableLighting = true;
  // Pin the sun to late afternoon over California. Using the real clock meant the coast was
  // often on the night side and the whole scene went black.
  viewer.clock.currentTime = Cesium.JulianDate.fromIso8601('2026-06-21T01:40:00Z');
  viewer.clock.shouldAnimate = false;
  viewer.scene.skyAtmosphere.show = true;
  viewer.scene.fog.enabled = true;
  viewer.scene.screenSpaceCameraController.enableInputs = false;   // the course flies itself

  // The route, drawn on the surface, plus a dot per landmark.
  viewer.entities.add({
    polyline: {
      positions: Cesium.Cartesian3.fromDegreesArray(ROUTE.flatMap((p) => [p.lon, p.lat])),
      width: 2,
      clampToGround: currentMode() !== 'photo3d',
      material: new Cesium.PolylineDashMaterialProperty({
        color: Cesium.Color.fromCssColorString('#35d0ba').withAlpha(0.55),
      }),
    },
  });
  for (const p of ROUTE) {
    viewer.entities.add({
      position: Cesium.Cartesian3.fromDegrees(p.lon, p.lat),
      point: { pixelSize: 6, color: Cesium.Color.fromCssColorString('#35d0ba').withAlpha(0.8) },
      label: {
        text: p.name,
        font: '12px sans-serif',
        fillColor: Cesium.Color.WHITE.withAlpha(0.85),
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        outlineWidth: 2,
        outlineColor: Cesium.Color.fromCssColorString('#03080f'),
        pixelOffset: new Cesium.Cartesian2(0, -16),
        distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 120000),
      },
    });
  }
}

/* ---------------- live data ---------------- */

const el = (id) => document.getElementById(id);

function connect() {
  const source = new EventSource('/api/events/stream');
  source.addEventListener('open', () => {
    state.connected = true;
    el('splashState').textContent = 'Connected. Turn on STREAM in WAKE, then take a stroke.';
  });
  source.addEventListener('error', () => {
    state.connected = false;
    el('splashState').textContent = 'Lost the dashboard — is the laptop server running?';
  });
  source.addEventListener('event', (msg) => {
    let ev;
    try {
      ev = JSON.parse(msg.data);
    } catch {
      return;
    }
    if (ev.type !== 'rowing-status') {
      return;
    }
    const p = (ev.body && ev.body.payload) || {};
    state.watts = Number(p.watts) || 0;
    state.spm = Number(p.strokeRate) || 0;
    state.boatSpeed = Number(p.waterSpeedMps) || 0;
    if (typeof p.strokes === 'number') {
      if (state.lastStrokes >= 0 && p.strokes > state.lastStrokes) {
        state.flapPhase = 0;            // a stroke is a wingbeat
      }
      state.lastStrokes = p.strokes;
    }
    state.lastData = performance.now();
    el('splash').classList.add('hidden');
  });
}

/* ---------------- frame ---------------- */

let lastFrame = 0;

function frame(now) {
  const dt = lastFrame ? Math.min(0.1, (now - lastFrame) / 1000) : 0;
  lastFrame = now;

  // Stale data means the rower stopped sending; treat it as no power rather than freezing.
  const fresh = now - state.lastData < 4000;
  const watts = fresh ? state.watts : 0;
  const boat = fresh ? state.boatSpeed : 0;
  state.clockRunning = fresh && watts > 0;
  if (state.clockRunning) {
    state.clock += dt;
  }

  // Lift: power above the glide threshold climbs, below it sinks. Thinner air up high trims
  // the climb rate so the ceiling is earned.
  const thin = 1 - Math.min(0.55, state.altitude / MAX_ALT * 0.55);
  const climb = (watts - GLIDE_WATTS) * CLIMB_PER_WATT * thin;
  state.altitude = Math.max(MIN_ALT, Math.min(MAX_ALT, state.altitude + climb * dt * 12));
  state.ditched = state.altitude <= MIN_ALT + 1;

  // Airspeed from boat speed, eased so the camera never snaps.
  const targetAir = BASE_AIRSPEED + boat * AIRSPEED_PER_MPS;
  state.airspeed += (targetAir - state.airspeed) * Math.min(1, 1.5 * dt);
  state.along += state.airspeed * dt;

  const here = atDistance(state.along);
  const ahead = atDistance(state.along + 900);
  // Bank into the turn: compare the heading now with the heading a little way ahead.
  let turn = ahead.heading - here.heading;
  if (turn > 180) turn -= 360;
  if (turn < -180) turn += 360;
  const targetBank = Math.max(-32, Math.min(32, turn * 1.6));
  state.bank += (targetBank - state.bank) * Math.min(1, 2.0 * dt);

  if (viewer) {
    // Camera sits at the bird, pitched down a touch, rolled into the bank.
    viewer.camera.setView({
      destination: Cesium.Cartesian3.fromDegrees(here.lon, here.lat, state.altitude),
      orientation: {
        heading: Cesium.Math.toRadians(here.heading),
        pitch: Cesium.Math.toRadians(-10 - Math.min(14, state.altitude / 300)),
        roll: Cesium.Math.toRadians(state.bank),
      },
    });
  }

  drawWings(dt);
  updateHud(fresh, climb);
  if (state.clockRunning && Math.floor(state.clock) % 10 === 0) {
    saveTrip();
  }
  requestAnimationFrame(frame);
}

/**
 * Slim wings tucked into the bottom corners, beating once per stroke and flexing with the bank.
 * They frame the view rather than fill it - an earlier version stretched across half the screen.
 */
function drawWings(dt) {
  state.flapPhase = Math.min(1.4, state.flapPhase + dt * 2.2);
  const beat = Math.exp(-state.flapPhase * 2.6) * Math.sin(state.flapPhase * Math.PI * 2);
  const lift = beat * 26;
  const bankL = -state.bank * 0.9;
  const bankR = state.bank * 0.9;

  // Leading edge sweeps up and inward; trailing edge notched for primaries.
  el('wingL').setAttribute('d',
    `M -10 ${612 + bankL}`
    + ` C 70 ${556 - lift + bankL}, 190 ${528 - lift * 0.8 + bankL}, 300 ${540 - lift * 0.5 + bankL}`
    + ` L 276 ${560 + bankL} L 292 ${558 + bankL} L 262 ${576 + bankL}`
    + ` L 278 ${574 + bankL} L 236 ${590 + bankL}`
    + ` C 150 ${600 + bankL}, 60 ${606 + bankL}, -10 ${640 + bankL} Z`);
  el('wingR').setAttribute('d',
    `M 1010 ${612 + bankR}`
    + ` C 930 ${556 - lift + bankR}, 810 ${528 - lift * 0.8 + bankR}, 700 ${540 - lift * 0.5 + bankR}`
    + ` L 724 ${560 + bankR} L 708 ${558 + bankR} L 738 ${576 + bankR}`
    + ` L 722 ${574 + bankR} L 764 ${590 + bankR}`
    + ` C 850 ${600 + bankR}, 940 ${606 + bankR}, 1010 ${640 + bankR} Z`);
}

function updateHud(fresh, climb) {
  el('alt').textContent = Math.round(state.altitude);
  el('spd').textContent = Math.round(state.airspeed * 3.6);
  el('watts').textContent = Math.round(fresh ? state.watts : 0);
  el('spm').textContent = Math.round(fresh ? state.spm : 0);
  el('dist').textContent = (state.along / 1000).toFixed(1);
  const m = Math.floor(state.clock / 60);
  const s = Math.floor(state.clock % 60);
  el('clock').textContent = `${m}:${String(s).padStart(2, '0')}`;

  // Climb indicator: fills up from the middle, or down when sinking.
  const fill = el('climbFill');
  const frac = Math.max(-1, Math.min(1, climb / 8));
  const half = 110;
  if (frac >= 0) {
    fill.style.top = `${half - frac * half}px`;
    fill.style.height = `${frac * half}px`;
    fill.style.background = 'var(--accent)';
  } else {
    fill.style.top = `${half}px`;
    fill.style.height = `${-frac * half}px`;
    fill.style.background = 'var(--bad)';
  }

  const next = nextLandmark(state.along);
  el('landmark').textContent = fresh
    ? `${next.name} — ${(next.remaining / 1000).toFixed(1)} km ahead`
    : 'Rower idle — gliding down';
  el('hint').textContent = !fresh
    ? 'No data. Turn on STREAM in WAKE and take a stroke.'
    : state.ditched
      ? 'On the deck — pull harder than 60 W to climb'
      : state.watts < GLIDE_WATTS
        ? `Sinking — ${GLIDE_WATTS} W holds you level`
        : '';

  el('progressFill').style.width =
    `${(((state.along % routeLength) / routeLength) * 100).toFixed(2)}%`;
  el('trip').textContent = `Trip: ${(state.along / 1000).toFixed(0)} km of `
    + `${(routeLength / 1000).toFixed(0)} km · lap ${Math.floor(state.along / routeLength) + 1}`;
}

/* ---------------- boot ---------------- */

try {
  initGlobe();
} catch (e) {
  el('splashState').textContent = 'Cesium failed to load — is this machine online?';
}
// Landmark ticks along the progress bar.
const track = el('progressTrack');
for (const leg of LEGS) {
  const tick = document.createElement('div');
  tick.className = 'tick';
  tick.style.left = `${(leg.start / routeLength) * 100}%`;
  track.appendChild(tick);
}
el('mapMode').textContent = MODE_LABEL[currentMode()]
  + (ION_TOKEN ? '' : ' · no Ion token');
el('mapCard').addEventListener('click', () => {
  if (MODES.length > 1) {
    cycleMode();
  } else {
    el('hint').textContent = 'Set CESIUM_ION_TOKEN on the laptop for satellite and 3D maps.';
  }
});
connect();
el('reset').addEventListener('click', (e) => {
  e.preventDefault();
  state.along = 0;
  saveTrip();
});
window.addEventListener('beforeunload', saveTrip);
requestAnimationFrame(frame);
