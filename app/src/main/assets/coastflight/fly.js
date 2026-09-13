'use strict';

/**
 * Coast Flight, in-app edition: a bird's-eye run down the California coast driven by the rower.
 *
 * This runs in WAKE's own WebView, loaded from app assets. There is no dashboard and no event
 * stream involved: the activity calls window.wakeFeed() about twelve times a second with the
 * same coasted boat speed the gauges use, and persistence goes through the WakeNative bridge
 * into the app's SharedPreferences. The laptop can be switched off.
 *
 * Two things come from the internet and only those: the CesiumJS library and the map tiles.
 *
 * The tablet's WebView is Chrome 70, so fly.html picks CesiumJS 1.95 there and the current
 * release on a modern engine. The two have different setup APIs, hence LEGACY below. Keep
 * anything new inside the branches rather than assuming the modern signature.
 */

var LEGACY = !!window.__WAKE_LEGACY__;
var CESIUM_VERSION = window.__WAKE_CESIUM_VERSION__ || '?';

/* ---------------- bridge ---------------- */

var bridge = window.WakeNative || null;

function report(stage, detail) {
  if (bridge && bridge.report) {
    try {
      bridge.report(stage, String(detail));
    } catch (e) { /* the flight matters more than the telemetry */ }
  }
}

function ionToken() {
  if (!bridge || !bridge.token) {
    return '';
  }
  try {
    var t = bridge.token();
    return t && t.length > 20 ? t : '';
  } catch (e) {
    return '';
  }
}

var ION_TOKEN = ionToken();
var GOOGLE_3D_ASSET_ID = 2275207;

/* Photorealistic 3D needs a current CesiumJS, so it is only on the table when the engine can
   run one. On Chrome 70 the honest menu is Bing satellite with real terrain, or plain OSM. */
var MODES = ION_TOKEN
  ? (LEGACY ? ['satellite', 'osm'] : ['photo3d', 'satellite', 'osm'])
  : ['osm'];
var MODE_LABEL = {
  photo3d: 'Photorealistic 3D',
  satellite: 'Satellite + terrain',
  osm: 'OpenStreetMap'
};

function currentMode() {
  var m = null;
  if (bridge && bridge.mapMode) {
    try {
      m = bridge.mapMode();
    } catch (e) {
      m = null;
    }
  }
  return MODES.indexOf(m) >= 0 ? m : MODES[0];
}

function cycleMode() {
  var next = MODES[(MODES.indexOf(currentMode()) + 1) % MODES.length];
  if (bridge && bridge.setMapMode) {
    try {
      bridge.setMapMode(next);
    } catch (e) { /* falls back to the default on the next entry */ }
  }
  // The three tiers need different viewer construction, so start the page over.
  window.location.reload();
}

/** The route, north to south, nudged just offshore so the coastline sits off the left wing. */
var ROUTE = [
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
  { name: 'Point Loma, San Diego', lat: 32.6622, lon: -117.2616 }
];

/* ---------------- flight model ---------------- */

var GLIDE_WATTS = 60;        // below this the bird sinks
var CLIMB_PER_WATT = 0.055;  // m/s of climb per watt above glide
var MAX_ALT = 4200;
var MIN_ALT = 25;
// Tuned for a sense of place rather than raw pace: a good rowing speed gives about 250 km/h,
// so landmarks arrive every minute or two. An earlier setting hit 550 km/h and the coast blurred.
var BASE_AIRSPEED = 22;      // m/s with the boat stopped but airborne
var AIRSPEED_PER_MPS = 16;   // m/s of airspeed per m/s of boat speed

var state = {
  watts: 0,
  spm: 0,
  boatSpeed: 0,
  clock: 0,
  strokes: -1,
  altitude: 600,
  airspeed: BASE_AIRSPEED,
  along: loadTrip(),   // metres along the route, carried across sessions
  bank: 0,
  flapPhase: 0,
  lastData: 0,
  ditched: false,
  started: false
};

function loadTrip() {
  if (!bridge || !bridge.trip) {
    return 0;
  }
  try {
    var v = Number(bridge.trip());
    return isFinite(v) && v > 0 ? v : 0;
  } catch (e) {
    return 0;
  }
}

function saveTrip() {
  if (!bridge || !bridge.saveTrip) {
    return;
  }
  try {
    bridge.saveTrip(Math.round(state.along));
  } catch (e) { /* progress is a nicety, not worth failing the frame over */ }
}

// The activity calls this on the way out, so a trip is never lost by leaving the screen.
window.wakeSave = saveTrip;

/* ---------------- geo helpers ---------------- */

var R = 6371000;
function toRad(d) { return (d * Math.PI) / 180; }
function toDeg(r) { return (r * 180) / Math.PI; }

function haversine(a, b) {
  var dLat = toRad(b.lat - a.lat);
  var dLon = toRad(b.lon - a.lon);
  var la1 = toRad(a.lat);
  var la2 = toRad(b.lat);
  var s1 = Math.sin(dLat / 2);
  var s2 = Math.sin(dLon / 2);
  var h = s1 * s1 + Math.cos(la1) * Math.cos(la2) * s2 * s2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

function bearing(a, b) {
  var la1 = toRad(a.lat);
  var la2 = toRad(b.lat);
  var dLon = toRad(b.lon - a.lon);
  var y = Math.sin(dLon) * Math.cos(la2);
  var x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dLon);
  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}

// Cumulative distances so a position along the route is a simple lookup.
var LEGS = [];
var routeLength = 0;
for (var i = 0; i < ROUTE.length - 1; i++) {
  var legLength = haversine(ROUTE[i], ROUTE[i + 1]);
  LEGS.push({ from: ROUTE[i], to: ROUTE[i + 1], length: legLength, start: routeLength });
  routeLength += legLength;
}

/** Position and heading at a distance along the route; loops back to the start at the end. */
function atDistance(metres) {
  var m = ((metres % routeLength) + routeLength) % routeLength;
  var leg = LEGS[LEGS.length - 1];
  for (var k = 0; k < LEGS.length; k++) {
    if (m >= LEGS[k].start && m < LEGS[k].start + LEGS[k].length) {
      leg = LEGS[k];
      break;
    }
  }
  var f = leg.length > 0 ? (m - leg.start) / leg.length : 0;
  return {
    lat: leg.from.lat + (leg.to.lat - leg.from.lat) * f,
    lon: leg.from.lon + (leg.to.lon - leg.from.lon) * f,
    heading: bearing(leg.from, leg.to)
  };
}

function nextLandmark(metres) {
  var m = ((metres % routeLength) + routeLength) % routeLength;
  for (var k = 0; k < LEGS.length; k++) {
    if (m < LEGS[k].start + LEGS[k].length) {
      return { name: LEGS[k].to.name, remaining: LEGS[k].start + LEGS[k].length - m };
    }
  }
  return { name: ROUTE[ROUTE.length - 1].name, remaining: 0 };
}

/* ---------------- Cesium ---------------- */

var viewer = null;
var el = function (id) { return document.getElementById(id); };

function initGlobe() {
  var mode = currentMode();
  if (ION_TOKEN) {
    Cesium.Ion.defaultAccessToken = ION_TOKEN;
  }

  var options = {
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
    // Cheap tablet GPU: no shadows, no anti-aliasing pass.
    requestRenderMode: false,
    shadows: false
  };

  if (LEGACY) {
    // CesiumJS 1.95 takes provider instances up front and has no async factories.
    if (mode === 'satellite') {
      options.imageryProvider = Cesium.createWorldImagery();
      options.terrainProvider = Cesium.createWorldTerrain({ requestVertexNormals: true });
    } else {
      options.imageryProvider = new Cesium.OpenStreetMapImageryProvider({
        url: 'https://tile.openstreetmap.org/'
      });
    }
    viewer = new Cesium.Viewer('globe', options);
  } else {
    options.baseLayer = mode === 'osm'
      ? new Cesium.ImageryLayer(new Cesium.OpenStreetMapImageryProvider({
        url: 'https://tile.openstreetmap.org/'
      }))
      : false;
    viewer = new Cesium.Viewer('globe', options);
    if (mode === 'photo3d') {
      // Google's 3D mesh replaces the globe entirely; leaving the globe on z-fights with it.
      viewer.scene.globe.show = false;
      Cesium.Cesium3DTileset.fromIonAssetId(GOOGLE_3D_ASSET_ID)
        .then(function (tileset) { viewer.scene.primitives.add(tileset); })
        .catch(function (e) {
          // No entitlement for the Google asset: fall back rather than showing blank sky.
          report('photo3d-unavailable', e && e.message);
          viewer.scene.globe.show = true;
          Cesium.createWorldImageryAsync()
            .then(function (p) { viewer.imageryLayers.addImageryProvider(p); })
            .catch(function () { /* leave it bare */ });
          el('mapMode').textContent = 'Satellite (3D unavailable)';
        });
    } else if (mode === 'satellite') {
      Cesium.createWorldImageryAsync()
        .then(function (p) { viewer.imageryLayers.addImageryProvider(p); })
        .catch(function () { /* imagery is a nicety; the flight still works */ });
      Cesium.createWorldTerrainAsync({ requestVertexNormals: true })
        .then(function (t) { viewer.terrainProvider = t; })
        .catch(function () { /* smooth ellipsoid is fine */ });
    }
  }

  viewer.scene.globe.enableLighting = true;
  // Pin the sun to a California afternoon. The real clock often put the coast on the night
  // side and the whole scene went black.
  viewer.clock.currentTime = Cesium.JulianDate.fromIso8601('2026-06-21T01:40:00Z');
  viewer.clock.shouldAnimate = false;
  viewer.scene.skyAtmosphere.show = true;
  viewer.scene.fog.enabled = true;
  viewer.scene.screenSpaceCameraController.enableInputs = false;   // the course flies itself

  var line = [];
  for (var k = 0; k < ROUTE.length; k++) {
    line.push(ROUTE[k].lon, ROUTE[k].lat);
  }
  viewer.entities.add({
    polyline: {
      positions: Cesium.Cartesian3.fromDegreesArray(line),
      width: 2,
      // Nothing to clamp to when the globe is hidden under the photorealistic tileset.
      clampToGround: mode !== 'photo3d',
      material: new Cesium.PolylineDashMaterialProperty({
        color: Cesium.Color.fromCssColorString('#35d0ba').withAlpha(0.55)
      })
    }
  });
  for (var p = 0; p < ROUTE.length; p++) {
    viewer.entities.add({
      position: Cesium.Cartesian3.fromDegrees(ROUTE[p].lon, ROUTE[p].lat),
      point: { pixelSize: 6, color: Cesium.Color.fromCssColorString('#35d0ba').withAlpha(0.8) },
      label: {
        text: ROUTE[p].name,
        font: '12px sans-serif',
        fillColor: Cesium.Color.WHITE.withAlpha(0.85),
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        outlineWidth: 2,
        outlineColor: Cesium.Color.fromCssColorString('#03080f'),
        pixelOffset: new Cesium.Cartesian2(0, -16),
        distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 120000)
      }
    });
  }
}

/* ---------------- live data, straight from the app ---------------- */

/**
 * Called by MainActivity at about 12Hz. Everything here is already correct: `s` is the coasted
 * boat speed from the same BoatSpeedModel the gauges use, and `t` is the rowing clock with its
 * 15-second pause rule. Do not re-derive either - decaying speed is the whole instrument.
 */
window.wakeFeed = function (d) {
  state.watts = Number(d.w) || 0;
  state.spm = Number(d.r) || 0;
  state.boatSpeed = Number(d.s) || 0;
  state.clock = Number(d.t) || 0;
  var strokes = Number(d.k) || 0;
  if (state.strokes >= 0 && strokes > state.strokes) {
    state.flapPhase = 0;          // a stroke is a wingbeat
  }
  state.strokes = strokes;
  state.lastData = Date.now();
  if (!state.started) {
    state.started = true;
    el('splash').className = 'hidden';
  }
};

/* ---------------- frame ---------------- */

var lastFrame = 0;

function frame(now) {
  var dt = lastFrame ? Math.min(0.1, (now - lastFrame) / 1000) : 0;
  lastFrame = now;

  // No feed for a while means the app stopped sending; treat it as no power, never as a hold.
  var fresh = Date.now() - state.lastData < 3000;
  var watts = fresh ? state.watts : 0;
  var boat = fresh ? state.boatSpeed : 0;

  // Lift: power above the glide threshold climbs, below it sinks. Thinner air up high trims
  // the climb rate so the ceiling is earned.
  var thin = 1 - Math.min(0.55, (state.altitude / MAX_ALT) * 0.55);
  var climb = (watts - GLIDE_WATTS) * CLIMB_PER_WATT * thin;
  state.altitude = Math.max(MIN_ALT, Math.min(MAX_ALT, state.altitude + climb * dt * 12));
  state.ditched = state.altitude <= MIN_ALT + 1;

  // Airspeed from boat speed, eased so the camera never snaps.
  var targetAir = BASE_AIRSPEED + boat * AIRSPEED_PER_MPS;
  state.airspeed += (targetAir - state.airspeed) * Math.min(1, 1.5 * dt);
  state.along += state.airspeed * dt;

  var here = atDistance(state.along);
  var ahead = atDistance(state.along + 900);
  // Bank into the turn: compare the heading now with the heading a little way ahead.
  var turn = ahead.heading - here.heading;
  if (turn > 180) { turn -= 360; }
  if (turn < -180) { turn += 360; }
  var targetBank = Math.max(-32, Math.min(32, turn * 1.6));
  state.bank += (targetBank - state.bank) * Math.min(1, 2.0 * dt);

  if (viewer) {
    viewer.camera.setView({
      destination: Cesium.Cartesian3.fromDegrees(here.lon, here.lat, state.altitude),
      orientation: {
        heading: Cesium.Math.toRadians(here.heading),
        pitch: Cesium.Math.toRadians(-10 - Math.min(14, state.altitude / 300)),
        roll: Cesium.Math.toRadians(state.bank)
      }
    });
  }

  drawWings(dt);
  updateHud(fresh, climb);
  if (Math.floor(now / 1000) % 10 === 0) {
    saveTrip();
  }
  window.requestAnimationFrame(frame);
}

/**
 * Slim wings tucked into the bottom corners, beating once per stroke and flexing with the bank.
 * They frame the view rather than fill it - an earlier version stretched across half the screen.
 */
function drawWings(dt) {
  state.flapPhase = Math.min(1.4, state.flapPhase + dt * 2.2);
  var beat = Math.exp(-state.flapPhase * 2.6) * Math.sin(state.flapPhase * Math.PI * 2);
  var lift = beat * 26;
  var bankL = -state.bank * 0.9;
  var bankR = state.bank * 0.9;

  el('wingL').setAttribute('d',
    'M -10 ' + (612 + bankL)
    + ' C 70 ' + (556 - lift + bankL) + ', 190 ' + (528 - lift * 0.8 + bankL)
    + ', 300 ' + (540 - lift * 0.5 + bankL)
    + ' L 276 ' + (560 + bankL) + ' L 292 ' + (558 + bankL) + ' L 262 ' + (576 + bankL)
    + ' L 278 ' + (574 + bankL) + ' L 236 ' + (590 + bankL)
    + ' C 150 ' + (600 + bankL) + ', 60 ' + (606 + bankL) + ', -10 ' + (640 + bankL) + ' Z');
  el('wingR').setAttribute('d',
    'M 1010 ' + (612 + bankR)
    + ' C 930 ' + (556 - lift + bankR) + ', 810 ' + (528 - lift * 0.8 + bankR)
    + ', 700 ' + (540 - lift * 0.5 + bankR)
    + ' L 724 ' + (560 + bankR) + ' L 708 ' + (558 + bankR) + ' L 738 ' + (576 + bankR)
    + ' L 722 ' + (574 + bankR) + ' L 764 ' + (590 + bankR)
    + ' C 850 ' + (600 + bankR) + ', 940 ' + (606 + bankR) + ', 1010 ' + (640 + bankR) + ' Z');
}

function updateHud(fresh, climb) {
  el('alt').textContent = Math.round(state.altitude);
  el('spd').textContent = Math.round(state.airspeed * 3.6);
  el('watts').textContent = Math.round(fresh ? state.watts : 0);
  el('spm').textContent = Math.round(fresh ? state.spm : 0);
  el('dist').textContent = (state.along / 1000).toFixed(1);

  // Climb indicator: fills up from the middle, or down when sinking.
  var fill = el('climbFill');
  var frac = Math.max(-1, Math.min(1, climb / 8));
  var half = 110;
  if (frac >= 0) {
    fill.style.top = (half - frac * half) + 'px';
    fill.style.height = (frac * half) + 'px';
    fill.style.background = 'var(--accent)';
  } else {
    fill.style.top = half + 'px';
    fill.style.height = (-frac * half) + 'px';
    fill.style.background = 'var(--bad)';
  }

  var next = nextLandmark(state.along);
  el('landmark').textContent = fresh
    ? next.name + ' — ' + (next.remaining / 1000).toFixed(1) + ' km ahead'
    : 'Resting — gliding down';
  el('hint').textContent = !fresh
    ? 'Take a stroke to climb again.'
    : state.ditched
      ? 'On the deck — pull harder than ' + GLIDE_WATTS + ' W to climb'
      : state.watts < GLIDE_WATTS
        ? 'Sinking — ' + GLIDE_WATTS + ' W holds you level'
        : '';

  el('progressFill').style.width =
    (((state.along % routeLength) / routeLength) * 100).toFixed(2) + '%';
  el('trip').textContent = 'Trip: ' + (state.along / 1000).toFixed(0) + ' km of '
    + (routeLength / 1000).toFixed(0) + ' km · lap '
    + (Math.floor(state.along / routeLength) + 1);
}

/* ---------------- boot ---------------- */

try {
  initGlobe();
  report('globe-ready', 'mode=' + currentMode() + ' cesium=' + CESIUM_VERSION);
} catch (e) {
  el('splashState').textContent = 'The globe failed to start: ' + (e && e.message);
  report('globe-failed', (e && e.message) + ' cesium=' + CESIUM_VERSION);
}

// Landmark ticks along the progress bar.
var track = el('progressTrack');
var fillBar = document.createElement('div');
fillBar.id = 'progressFill';
track.appendChild(fillBar);
for (var t = 0; t < LEGS.length; t++) {
  var tick = document.createElement('div');
  tick.className = 'tick';
  tick.style.left = ((LEGS[t].start / routeLength) * 100) + '%';
  track.appendChild(tick);
}

el('mapMode').textContent = MODE_LABEL[currentMode()]
  + (ION_TOKEN ? '' : ' · no Ion token');
el('mapCard').addEventListener('click', function () {
  if (MODES.length > 1) {
    cycleMode();
  } else {
    el('hint').textContent = ION_TOKEN
      ? 'This WebView is too old for the 3D tiles.'
      : 'No Ion token yet - start the laptop server once to fetch it.';
  }
});
el('reset').addEventListener('click', function (ev) {
  ev.preventDefault();
  state.along = 0;
  saveTrip();
});
el('splashState').textContent = 'Take a stroke to launch.';
if (bridge && bridge.ready) {
  try {
    bridge.ready();
  } catch (e) { /* the feed just will not start; the splash says so */ }
}
window.requestAnimationFrame(frame);
