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
  // The three tiers need different viewer construction, so start the page over. Java does it:
  // an in-page reload has to survive shouldOverrideUrlLoading, which the app blocks wholesale.
  if (bridge && bridge.reload) {
    bridge.reload();
  } else {
    window.location.reload();
  }
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

/* 3.16.0, from the rower: "start paused in the air until rowing solidly begins, see some flapping,
   not a straight line but move with the row, fly low, terrain follow a little, preload maps, higher
   resolution, labels on the ground". Altitude is now height above the ground or sea, and level
   flight is set from the rower's own typical power rather than a fixed 60 W. */
var TAKEOFF_STROKES = 3;     // strokes before the bird leaves its hover
var MIN_AGL = 60;            // metres above the ground or sea (was 35: too close to unloaded hills)
var MAX_AGL = 700;
var START_AGL = 140;
var CLIMB_SCALE = 22;        // m/s of climb per typical-watts above level flight
var LEVEL_SHARE = 0.6;       // 60% of typical power holds your height (at 90% an easy row only skimmed the water)
// Low flight feels fast: ~50 m/s (180 km/h) at a typical pace keeps the coast readable. It was
// 250 km/h at 600 m, and 550 km/h before that, which blurred.
var BASE_AIRSPEED = 15;      // m/s gliding with the boat stopped
var AIRSPEED_PER_MPS = 9;    // m/s of airspeed per m/s of boat speed
var WEAVE_METRES = 260;      // how far either side of the route the flight swings

var state = {
  watts: 0,
  spm: 0,
  boatSpeed: 0,
  clock: 0,
  strokes: -1,
  altitude: START_AGL,
  airspeed: 0,
  along: loadTrip(),   // metres along the route, carried across sessions
  bank: 0,
  flapPhase: 0,
  lastData: 0,
  ditched: false,
  started: false,
  typicalWatts: 129,     // replaced by the rower's profile from the feed
  smoothWatts: 0,
  agl: START_AGL,
  ground: 0,
  hovering: true,
  strokesAtStart: -1,
  weavePhase: 0,
  weaveOffset: 0,
  bob: 0,
  wingPhase: 0
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

// The activity calls this on the way out, so a trip is never lost by leaving the screen. The ghost
// and the lift record are banked on the way out too (see the flight extras below).
window.wakeSave = function () {
  saveTrip();
  saveGhost();
  saveLift();
};

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
    shadows: false,
    // Chrome 70 on tablet silicon. Half-resolution rendering is by far the largest single win
    // and at 250 km/h over a coastline it is barely visible.
    contextOptions: { webgl: { antialias: false, alpha: false } }
  };

  if (LEGACY) {
    // CesiumJS 1.95 takes provider instances up front and has no async factories.
    if (mode === 'satellite') {
      options.imageryProvider = Cesium.createWorldImagery();
      // No vertex normals: they double the terrain payload and only feed the lighting, which is
      // switched off below for the same performance reason.
      options.terrainProvider = Cesium.createWorldTerrain({ requestVertexNormals: false });
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

  // ---- performance, all of it aimed at the tablet ----
  // Sharper than the 0.6 / SSE 6 this started at ("higher resolutions"); adaptQuality() steps the
  // scale down if the frame rate drops and back up when there is headroom.
  viewer.resolutionScale = LEGACY ? 0.75 : 1.0;
  viewer.scene.globe.maximumScreenSpaceError = LEGACY ? 4 : 2;
  viewer.scene.globe.tileCacheSize = LEGACY ? 150 : 200;
  viewer.scene.globe.preloadSiblings = true;   // tiles beside the view are ready when the flight weaves
  viewer.scene.globe.showGroundAtmosphere = !LEGACY;
  viewer.scene.fxaa = false;
  viewer.scene.postProcessStages.fxaa.enabled = false;
  // Lighting costs a full extra pass and needs terrain normals to look right; it is the first
  // thing to go on the old engine.
  viewer.scene.globe.enableLighting = !LEGACY;
  // Pin the sun to a California afternoon. The real clock often put the coast on the night
  // side and the whole scene went black.
  viewer.clock.currentTime = Cesium.JulianDate.fromIso8601('2026-06-21T01:40:00Z');
  viewer.clock.shouldAnimate = false;
  viewer.scene.skyAtmosphere.show = true;
  viewer.scene.fog.enabled = !LEGACY;
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
    var marker = {
      position: Cesium.Cartesian3.fromDegrees(ROUTE[p].lon, ROUTE[p].lat),
      point: { pixelSize: 6, color: Cesium.Color.fromCssColorString('#35d0ba').withAlpha(0.8) }
    };
    // Names are not added here: 26 outlined labels re-laid out every frame is real cost on the old
    // engine. updateLabels() keeps only the few nearest places ahead labelled.
    viewer.entities.add(marker);
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
  // 3.19.5: how hard the paddle is being driven right now (0..1), from the pulse meter.
  state.drive = Math.max(0, Math.min(1, Number(d.d) || 0));
  if (Number(d.p) > 0) {
    state.typicalWatts = Number(d.p);
  }
  var strokes = Number(d.k) || 0;
  if (state.strokesAtStart < 0) {
    state.strokesAtStart = strokes;
    // The ghost race runs on this page's own share of the rowing clock, so a page that loads
    // mid-session (a map change) still races from zero.
    race.clockAtStart = state.clock;
  }
  if (state.strokes >= 0 && strokes > state.strokes) {
    state.flapPhase = 0;          // a stroke is a big wingbeat
    state.bob = 14;               // and a lift you can feel, settling before the next
  }
  if (state.hovering && strokes - state.strokesAtStart >= TAKEOFF_STROKES) {
    state.hovering = false;
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
var lastCameraMs = 0;

var lastPreloadMs = 0;
var lastLabelMs = 0;

/** Ground or sea height under a point, if its terrain tile is loaded; null otherwise. */
function groundAt(lat, lon) {
  if (!viewer || !viewer.scene.globe.show) {
    return null;
  }
  try {
    var h = viewer.scene.globe.getHeight(Cesium.Cartographic.fromDegrees(lon, lat));
    return typeof h === 'number' && isFinite(h) ? Math.max(0, h) : null;
  } catch (e) {
    return null;
  }
}

/** A point `metres` to the right of the heading (negative: to the left). */
function offsetPoint(p, metres) {
  var brg = toRad(p.heading + 90);
  var dLat = (metres * Math.cos(brg)) / R;
  var dLon = (metres * Math.sin(brg)) / (R * Math.cos(toRad(p.lat)));
  return { lat: p.lat + toDeg(dLat), lon: p.lon + toDeg(dLon), heading: p.heading };
}

function frame(now) {
  var dt = lastFrame ? Math.min(0.1, (now - lastFrame) / 1000) : 0;
  lastFrame = now;

  // No feed for a while means the app stopped sending; treat it as no power, never as a hold.
  var fresh = Date.now() - state.lastData < 3000;
  var watts = fresh ? state.watts : 0;
  var boat = fresh ? state.boatSpeed : 0;
  // The monitor's watts land once a stroke and read zero between some; average over ~3 s so
  // height does not jitter stroke to stroke.
  state.smoothWatts += (watts - state.smoothWatts) * Math.min(1, dt / 3);
  var typical = Math.max(40, state.typicalWatts);

  var climb = 0;
  if (!state.hovering) {
    // Hold 90% of your typical power and you hold your height; more climbs, less sinks.
    climb = ((state.smoothWatts - typical * LEVEL_SHARE) / typical) * CLIMB_SCALE;
    climb = Math.max(-14, Math.min(9, climb));
    // Ridge lift over the cliffs: a small free climb, and a big one if you pull through it.
    // A steady rower sits at the 700 m ceiling, so lift is also the only way above it: up to
    // LIFT_CEILING more, where the wind aloft is worth airspeed, settling back once out of the lift.
    climb += updateLift(dt, typical, watts);
    if (state.agl > MAX_AGL && lifting.strength < 0.05) {
      climb = Math.min(climb, -ALOFT_SETTLE);
    }
    var ceiling = state.agl > MAX_AGL || lifting.strength > 0.05 ? MAX_AGL + LIFT_CEILING : MAX_AGL;
    state.agl = Math.max(MIN_AGL, Math.min(ceiling, state.agl + climb * dt));
    // The flock drafts you along (+1.5% a bird in the V); the fog's damp air drags you back.
    var targetAir = (BASE_AIRSPEED + boat * AIRSPEED_PER_MPS)
      * (1 + FLOCK_DRAFT * flock.inSlot + ALOFT_BONUS * aloft()) * (1 - FOG_DRAG * state.fog);
    state.airspeed += (targetAir - state.airspeed) * Math.min(1, 1.5 * dt);
    state.along += state.airspeed * dt;
    race.flown += state.airspeed * dt;
    // The swing across the route quickens with stroke rate: the flight moves with the row.
    state.weavePhase += dt * (0.18 + Math.min(40, state.spm) * 0.012);
  } else {
    state.airspeed += (0 - state.airspeed) * Math.min(1, dt);
  }
  state.ditched = !state.hovering && state.agl <= MIN_AGL + 1;
  state.bob *= Math.exp(-dt * 1.6);
  var targetOffset = state.hovering ? 0 : Math.sin(state.weavePhase) * WEAVE_METRES;
  state.weaveOffset += (targetOffset - state.weaveOffset) * Math.min(1, dt * 1.2);

  var onRoute = atDistance(state.along);
  var here = offsetPoint(onRoute, state.weaveOffset);
  var ahead = atDistance(state.along + 900);
  // Bank into the turn, and into each swing of the weave.
  var turn = ahead.heading - onRoute.heading;
  if (turn > 180) { turn -= 360; }
  if (turn < -180) { turn += 360; }
  var weaveBank = state.hovering ? 0 : Math.cos(state.weavePhase) * 12;
  var targetBank = Math.max(-32, Math.min(32, turn * 1.6 + weaveBank));
  state.bank += (targetBank - state.bank) * Math.min(1, 2.0 * dt);

  // Terrain following, a little: the ground under you and a little ahead, smoothed so hills
  // lift you gently and let you down slowly.
  var probe = offsetPoint(atDistance(state.along + 500), state.weaveOffset);
  var under = groundAt(here.lat, here.lon);
  var front = groundAt(probe.lat, probe.lon);
  // Until a terrain tile has loaded its height is unknown; never assume the ground has dropped away
  // then, or the camera can end up inside a hill it cannot see yet.
  var known = under !== null || front !== null;
  var target = Math.max(under === null ? state.ground : under, front === null ? 0 : front * 0.8);
  if (known || target > state.ground) {
    state.ground += (target - state.ground) * Math.min(1, dt * (target > state.ground ? 1.4 : 0.5));
  }
  var hoverBob = state.hovering ? Math.sin(now / 900) * 4 : 0;
  var altitude = state.ground + state.agl + state.bob + hoverBob;
  state.altitude = state.agl;

  // ~30Hz camera on the old engine: each setView is a full scene traversal, and the flight is
  // smooth long before 60. Stamp the clock only when a frame actually goes through.
  var camHeading = here.heading + (state.hovering ? 0 : Math.cos(state.weavePhase) * 6);
  var camPitch = -8 - Math.min(16, state.agl / 60);
  var camRoll = state.bank;
  // A postcard stop turns the head toward the landmark for the picture, then back.
  var look = postcardLook(now, here, altitude);
  if (look) {
    var dh = ((look.heading - camHeading + 540) % 360) - 180;
    camHeading += dh * look.w;
    camPitch += (look.pitch - camPitch) * look.w;
    camRoll *= 1 - look.w;
  }
  if (viewer && (!LEGACY || now - lastCameraMs >= 28)) {
    lastCameraMs = now;
    viewer.camera.setView({
      destination: Cesium.Cartesian3.fromDegrees(here.lon, here.lat, altitude),
      orientation: {
        heading: Cesium.Math.toRadians(camHeading),
        pitch: Cesium.Math.toRadians(camPitch),
        roll: Cesium.Math.toRadians(camRoll)
      }
    });
  }
  updateFog(dt, fresh);
  updateFlock(dt, now, fresh);
  updatePostcards(now);
  updateGhost(dt, here, altitude);
  drawLift(now);

  adaptQuality(dt);
  if (now - lastPreloadMs > 700) {
    lastPreloadMs = now;
    planPreload(state.along);
  }
  pumpPreload();
  if (now - lastLabelMs > 1000) {
    lastLabelMs = now;
    updateLabels(onRoute);
  }
  drawWings(dt, fresh);
  updateHud(fresh, climb);
  updateBanner(now);
  if (Math.floor(now / 1000) % 10 === 0) {
    saveTrip();
  }
  window.requestAnimationFrame(frame);
}

/* ---------------- keeping it sharp and loaded ---------------- */

var quality = { avg: 1 / 30, lastChange: 0 };

/** Steps the render resolution to hold roughly 20-27 frames a second. */
function adaptQuality(dt) {
  if (!viewer || dt <= 0) {
    return;
  }
  quality.avg += (dt - quality.avg) * 0.05;
  var now = Date.now();
  if (now - quality.lastChange < 4000) {
    return;
  }
  var fps = 1 / quality.avg;
  var scale = viewer.resolutionScale;
  var top = LEGACY ? 0.9 : 1.0;
  var bottom = LEGACY ? 0.55 : 0.75;
  if (fps < 20 && scale > bottom) {
    viewer.resolutionScale = Math.max(bottom, scale - 0.05);
    quality.lastChange = now;
  } else if (fps > 27 && scale < top) {
    viewer.resolutionScale = Math.min(top, scale + 0.05);
    quality.lastChange = now;
  }
}

/* Preloading: the map tiles the camera will need over the next 12 km are requested ahead of time,
   so they come from the browser's cache when the flight gets there instead of popping in late. */
var preload = { keys: {}, queue: [], done: 0, total: 0 };

function preloadProvider() {
  if (!viewer || viewer.imageryLayers.length === 0) {
    return null;
  }
  var provider = viewer.imageryLayers.get(0).imageryProvider;
  // CesiumJS 1.95 providers are not usable until ready; current ones are ready on construction.
  if (!provider || provider.ready === false) {
    return null;
  }
  return provider;
}

function planPreload(alongMetres) {
  var provider = preloadProvider();
  if (!provider) {
    return;
  }
  var maxLevel = 18;
  try {
    maxLevel = provider.maximumLevel || 18;
  } catch (e) { /* some providers only know after ready */ }
  for (var d = 0; d <= 12000; d += 600) {
    var pos = atDistance(alongMetres + d);
    var carto = Cesium.Cartographic.fromDegrees(pos.lon, pos.lat);
    for (var level = 12; level <= Math.min(15, maxLevel); level++) {
      var xy;
      try {
        xy = provider.tilingScheme.positionToTileXY(carto, level);
      } catch (e) {
        return;
      }
      if (!xy) {
        continue;
      }
      var key = level + '/' + xy.x + '/' + xy.y;
      if (!preload.keys[key]) {
        preload.keys[key] = 1;
        preload.queue.push([xy.x, xy.y, level]);
        preload.total++;
      }
    }
  }
}

function pumpPreload() {
  var provider = preloadProvider();
  if (!provider) {
    return;
  }
  for (var n = 0; n < 3 && preload.queue.length > 0; n++) {
    var tile = preload.queue.shift();
    var result;
    try {
      result = provider.requestImage(tile[0], tile[1], tile[2]);
    } catch (e) {
      preload.done++;
      continue;
    }
    if (result === undefined) {
      // The request scheduler is busy with visible tiles, which come first. Try again later.
      preload.queue.unshift(tile);
      return;
    }
    Promise.resolve(result).then(function () { preload.done++; }, function () { preload.done++; });
  }
}

/* Place names on the ground: the route's landmarks plus the towns between them. Only the few
   nearest places ahead carry a label at any moment, so the old engine never lays out dozens. */
var PLACES = ROUTE.concat([
  { name: 'Pescadero', lat: 37.2552, lon: -122.3830 },
  { name: 'Davenport', lat: 37.0116, lon: -122.1919 },
  { name: 'Capitola', lat: 36.9752, lon: -121.9533 },
  { name: 'Moss Landing', lat: 36.8044, lon: -121.7869 },
  { name: 'Pacific Grove', lat: 36.6177, lon: -121.9166 },
  { name: 'Carmel-by-the-Sea', lat: 36.5552, lon: -121.9233 },
  { name: 'McWay Falls', lat: 36.1582, lon: -121.6719 },
  { name: 'San Simeon', lat: 35.6436, lon: -121.1900 },
  { name: 'Cambria', lat: 35.5641, lon: -121.0808 },
  { name: 'Cayucos', lat: 35.4428, lon: -120.8921 },
  { name: 'Avila Beach', lat: 35.1799, lon: -120.7318 },
  { name: 'Gaviota', lat: 34.4719, lon: -120.2285 },
  { name: 'Carpinteria', lat: 34.3989, lon: -119.5185 },
  { name: 'Oxnard', lat: 34.1975, lon: -119.1771 },
  { name: 'Redondo Beach', lat: 33.8492, lon: -118.3884 },
  { name: 'Long Beach', lat: 33.7701, lon: -118.1937 },
  { name: 'Dana Point', lat: 33.4669, lon: -117.6981 },
  { name: 'Oceanside', lat: 33.1959, lon: -117.3795 },
  { name: 'Carlsbad', lat: 33.1581, lon: -117.3506 },
  { name: 'Del Mar', lat: 32.9595, lon: -117.2653 }
]);
var activeLabels = {};

function updateLabels(pos) {
  if (!viewer) {
    return;
  }
  var near = [];
  for (var i = 0; i < PLACES.length; i++) {
    var d = haversine(pos, PLACES[i]);
    if (d < 28000) {
      var off = Math.abs(((bearing(pos, PLACES[i]) - pos.heading + 540) % 360) - 180);
      if (off < 110 || d < 3000) {
        near.push({ i: i, d: d });
      }
    }
  }
  near.sort(function (a, b) { return a.d - b.d; });
  var keep = {};
  for (var n = 0; n < near.length && n < 5; n++) {
    var place = PLACES[near[n].i];
    keep[place.name] = true;
    if (!activeLabels[place.name]) {
      var h = groundAt(place.lat, place.lon);
      activeLabels[place.name] = viewer.entities.add({
        position: Cesium.Cartesian3.fromDegrees(place.lon, place.lat, (h === null ? 0 : h) + 25),
        label: {
          text: place.name.toUpperCase(),
          font: 'bold 18px sans-serif',
          fillColor: Cesium.Color.WHITE,
          style: Cesium.LabelStyle.FILL_AND_OUTLINE,
          outlineWidth: 3,
          outlineColor: Cesium.Color.fromCssColorString('#03080f'),
          verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
          scaleByDistance: new Cesium.NearFarScalar(1500, 1.2, 25000, 0.55)
        }
      });
    }
  }
  for (var name in activeLabels) {
    if (Object.prototype.hasOwnProperty.call(activeLabels, name) && !keep[name]) {
      viewer.entities.remove(activeLabels[name]);
      delete activeLabels[name];
    }
  }
}

/**
 * Slim wings tucked into the bottom corners, beating once per stroke and flexing with the bank.
 * They frame the view rather than fill it - an earlier version stretched across half the screen.
 */
function drawWings(dt, rowing) {
  state.flapPhase = Math.min(1.4, state.flapPhase + dt * 2.2);
  var beat = Math.exp(-state.flapPhase * 2.6) * Math.sin(state.flapPhase * Math.PI * 2);
  // Continuous flapping while rowing - faster with rate, bigger with power - small wingbeats while
  // hovering, and wings held up in a glide once the rowing stops. Each stroke adds a big beat.
  // 3.19.5, the rower's ask: in flight the wings beat on the DRIVE and glide on the recovery.
  // `drive` comes from the pulse meter (the paddle accelerating), eased so a beat has a shape.
  var active = state.hovering ? 0.55 : rowing ? 1 : 0;
  var driveNow = state.hovering ? 1 : (rowing ? (state.drive || 0) : 0);
  state.driveShown = (state.driveShown || 0) + (driveNow - (state.driveShown || 0)) * Math.min(1, dt * 8);
  var flapping = state.hovering ? 1 : state.driveShown;
  var freq = state.hovering ? 0.6 + (Math.min(40, state.spm) / 60) * 1.2 : 2.2;
  state.wingPhase += dt * Math.PI * 2 * freq * (0.15 + 0.85 * flapping);
  var typical = Math.max(40, state.typicalWatts);
  var amp = active * (12 + 18 * Math.min(1.3, state.smoothWatts / typical));
  var glide = (!state.hovering && rowing) ? (1 - flapping) * 16 : 0;
  var lift = beat * 30 + Math.sin(state.wingPhase) * amp * flapping + glide - (active > 0 ? 0 : 8);
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
    fill.style.background = lifting.strength > 0.05 ? 'var(--warn)' : 'var(--accent)';
  } else {
    fill.style.top = half + 'px';
    fill.style.height = (-frac * half) + 'px';
    fill.style.background = 'var(--bad)';
  }

  var next = nextLandmark(state.along);
  var level = Math.round(Math.max(40, state.typicalWatts) * LEVEL_SHARE);
  if (state.hovering) {
    var left = TAKEOFF_STROKES - Math.max(0, state.strokes - state.strokesAtStart);
    var loaded = preload.total > 0 ? Math.round((100 * preload.done) / preload.total) : 0;
    el('landmark').textContent = 'Hovering over ' + next.name + ' — row to take off';
    el('hint').textContent = left + (left === 1 ? ' stroke' : ' strokes') + ' to take off · map ahead '
      + loaded + '% loaded';
  } else {
    el('landmark').textContent = fresh
      ? next.name + ' — ' + (next.remaining / 1000).toFixed(1) + ' km ahead'
      : 'Resting — gliding down';
    el('hint').textContent = !fresh
      ? 'Take a stroke to climb again.'
      : state.ditched
        ? 'Skimming the water — ' + level + ' W holds your height, more climbs'
        : state.smoothWatts < level
          ? 'Sinking — ' + level + ' W holds you level'
          : '';
  }

  el('progressFill').style.width =
    (((state.along % routeLength) / routeLength) * 100).toFixed(2) + '%';
  el('trip').textContent = 'Trip: ' + (state.along / 1000).toFixed(0) + ' km of '
    + (routeLength / 1000).toFixed(0) + ' km · lap '
    + (Math.floor(state.along / routeLength) + 1);
}

/* ---------------- flight extras: flock, ridge lift, fog, postcards, ghost ----------------

   The rower's five: a flock that flies with you, rising air over the cliffs, postcard stops at
   Big Sur and the Golden Gate, fog banks you sink into when you ease off, and a ghost of your last
   flight to race. Each one puts something at stake in the next few seconds:
     - the flock joins while you row solidly and drops back when you ease off; every bird in the V
       is +1.5% airspeed (drafting),
     - a ridge-lift column over a cliff gives a small free climb, and a big one if you pull
       through it,
     - fog banks lie low over the sea; ease off, sink into one, and you lose the view, 20% of
       your airspeed and your flock,
     - a postcard stop grades the picture on your height in a window and the flock in the shot,
     - the ghost is last session's flight on the same rowing clock, drawn in the sky ahead.
   Everything is drawn here or by Cesium from data already on the page: nothing new is fetched. */

function clamp(v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; }
function ease(x) { x = clamp(x, 0, 1); return x * x * (3 - 2 * x); }
function mod(m) { return ((m % routeLength) + routeLength) % routeLength; }

/** Metres along the route nearest a point. Boot only: it samples the whole route. */
function alongOf(lat, lon) {
  var target = { lat: lat, lon: lon };
  var best = 0;
  var bestD = Infinity;
  var m;
  for (m = 0; m < routeLength; m += 500) {
    var d = haversine(atDistance(m), target);
    if (d < bestD) { bestD = d; best = m; }
  }
  var base = best;
  for (m = Math.max(0, base - 500); m <= Math.min(routeLength - 1, base + 500); m += 25) {
    var d2 = haversine(atDistance(m), target);
    if (d2 < bestD) { bestD = d2; best = m; }
  }
  return best;
}

/* Text writes only when the text changes: the HUD is rewritten every frame otherwise. */
var shownText = {};
function setText(id, text) {
  if (shownText[id] !== text) {
    shownText[id] = text;
    el(id).textContent = text;
  }
}

var toastUntil = 0;
function showToast(text, ms) {
  el('toast').textContent = text;
  el('toast').className = 'overlay';
  toastUntil = Date.now() + (ms || 2600);
}

/* ---- 1. the flock ---- */

var FLOCK_MAX = 6;
var FLOCK_DRAFT = 0.015;        // +1.5% airspeed for each bird holding its place in the V
var FLOCK_JOIN_SHARE = 0.8;     // smoothed power, as a share of typical, that draws birds in
var FLOCK_JOIN_S = 3;
var FLOCK_LEAVE_S = 4;          // a short dive for a postcard costs a bird or two, not the flock
var FLOCK_FOG_LEAVE_S = 1.2;
/* V formation off the right wing, over the sea. viewBox 1000x640, bottom-anchored slice: at the
   tablet's aspect the visible band is roughly y 145..640. Trailing birds are nearer, so lower and
   larger. */
var FLOCK_SLOTS = [
  [700, 250, 1.0], [645, 272, 1.1], [755, 272, 1.1], [590, 294, 1.2], [810, 294, 1.2], [535, 316, 1.3]
];
var FLOCK_ENTRY = [1150, 760, 3.2];  // birds arrive from behind the camera, and drop back to it

var flock = { birds: [], want: 0, inSlot: 0, timer: 0, fullToasted: false, rotShown: '', opShown: '' };

function initFlock() {
  var g = el('flockG');
  for (var i = 0; i < FLOCK_MAX; i++) {
    var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', '');
    g.appendChild(path);
    flock.birds.push({ el: path, pres: 0, phase: i * 1.3, visible: false });
  }
}

function updateFlock(dt, now, fresh) {
  var typical = Math.max(40, state.typicalWatts);
  var flying = !state.hovering;
  var strong = flying && fresh && state.smoothWatts >= typical * FLOCK_JOIN_SHARE && state.fog < 0.3;
  var weak = flying && (!fresh || state.smoothWatts < typical * LEVEL_SHARE);
  // Easing off to drop into a postcard's height window is the point of the approach, not a lapse:
  // the flock holds while you descend toward it, or the third star (4+ birds) is out of reach.
  if (weak && fresh && pc.next && pc.nextIn < POSTCARD_APPROACH && state.agl > pc.next.hi) {
    weak = false;
  }
  var fogged = state.fog > 0.5;
  var before = flock.want;
  if (fogged || weak) {
    flock.timer += dt;
    var every = fogged ? FLOCK_FOG_LEAVE_S : FLOCK_LEAVE_S;
    if (flock.timer >= every && flock.want > 0) {
      flock.timer = 0;
      flock.want--;
    }
  } else if (strong) {
    flock.timer += dt;
    if (flock.timer >= FLOCK_JOIN_S && flock.want < FLOCK_MAX) {
      flock.timer = 0;
      flock.want++;
    }
  } else {
    flock.timer = 0;   // holding level: the flock holds too
  }
  if (flock.want < before && before === FLOCK_MAX) {
    showToast(fogged ? 'THE FLOCK IS SCATTERING IN THE FOG' : 'A BIRD DROPS BACK — PULL TO KEEP THEM');
  }
  if (flock.want === FLOCK_MAX && !flock.fullToasted) {
    flock.fullToasted = true;
    showToast('FULL FLOCK — +' + Math.round(FLOCK_MAX * FLOCK_DRAFT * 100) + '% DRAFT');
  } else if (flock.want < FLOCK_MAX - 1) {
    flock.fullToasted = false;
  }

  // The birds beat on your drive and glide on your recovery, like your own wings.
  var beat = state.hovering ? 0 : (state.driveShown || 0);
  var inSlot = 0;
  for (var i = 0; i < flock.birds.length; i++) {
    var b = flock.birds[i];
    var target = i < flock.want ? 1 : 0;
    b.pres += (target - b.pres) * Math.min(1, dt / (target > b.pres ? 1.4 : 2.0));
    if (b.pres > 0.9) {
      inSlot++;
    }
    if (b.pres < 0.01) {
      if (b.visible) {
        b.visible = false;
        b.el.setAttribute('d', '');
      }
      continue;
    }
    b.visible = true;
    b.phase += dt * Math.PI * 2 * (0.5 + 1.9 * beat);
    var flap = Math.sin(b.phase) * (0.25 + 0.75 * beat) + (1 - beat) * 0.35;
    var tip = (-10 * flap).toFixed(1);
    var elbow = (-3 - 5 * flap).toFixed(1);
    b.el.setAttribute('d', 'M -22 ' + tip + ' Q -11 ' + elbow + ' 0 2 Q 11 ' + elbow + ' 22 ' + tip);
    var slot = FLOCK_SLOTS[i];
    var k = ease(b.pres);
    var x = FLOCK_ENTRY[0] + (slot[0] - FLOCK_ENTRY[0]) * k;
    var y = FLOCK_ENTRY[1] + (slot[1] - FLOCK_ENTRY[1]) * k + Math.sin(now / 700 + i * 1.7) * 3;
    var s = FLOCK_ENTRY[2] + (slot[2] - FLOCK_ENTRY[2]) * k;
    b.el.setAttribute('transform', 'translate(' + x.toFixed(1) + ' ' + y.toFixed(1) + ') scale(' + s.toFixed(2) + ')');
  }
  flock.inSlot = inSlot;
  var g = el('flockG');
  var rot = 'rotate(' + (-state.bank * 0.6).toFixed(1) + ' 500 400)';
  var op = (1 - 0.85 * state.fog).toFixed(2);
  if (rot !== flock.rotShown) {
    flock.rotShown = rot;
    g.setAttribute('transform', rot);
  }
  if (op !== flock.opShown) {
    flock.opShown = op;
    g.setAttribute('opacity', op);
  }
}

/* ---- 2. ridge lift over the cliffs ---- */

/* Wind off the Pacific rides up the sea cliffs: the classic ridge lift gliders use at Torrey
   Pines and Big Sur. Stretches of cliff coast, each broken into columns you can see coming. */
var CLIFFS = [
  { name: "Devil's Slide", a: [37.600, -122.518], b: [37.545, -122.505] },
  { name: 'the Davenport cliffs', a: [37.080, -122.270], b: [36.975, -122.100] },
  { name: 'Big Sur', a: [36.520, -121.960], b: [35.790, -121.350] },
  { name: 'the Gaviota coast', a: [34.470, -120.380], b: [34.420, -119.900] },
  { name: 'the Malibu bluffs', a: [34.030, -118.880], b: [34.000, -118.600] },
  { name: 'Palos Verdes', a: [33.800, -118.420], b: [33.705, -118.330] },
  { name: 'the Laguna cliffs', a: [33.560, -117.830], b: [33.450, -117.690] },
  { name: 'Torrey Pines', a: [32.960, -117.270], b: [32.840, -117.280] },
  { name: 'Point Loma', a: [32.760, -117.270], b: [32.670, -117.262] }
];
var UPDRAFT_HALF = 700;         // each column is 1.4 km long, ~28 s at a typical pace
var UPDRAFT_SPACING = 3500;
var LIFT_FREE = 1.5;            // m/s of climb just for being there
var LIFT_PULL = 5.5;            // more, at full, for pulling half your typical power above level
var LIFT_CEILING = 300;         // lift can carry you this far above the 700 m ceiling
var ALOFT_SETTLE = 2.5;         // m/s back down toward the ceiling once out of the lift
var ALOFT_BONUS = 0.15;         // airspeed from the wind aloft at the very top of the lift

/** 0..1, how far above the normal ceiling ridge lift has carried you. */
function aloft() {
  return clamp((state.agl - MAX_AGL) / LIFT_CEILING, 0, 1);
}
var UPDRAFTS = [];

function buildUpdrafts() {
  for (var i = 0; i < CLIFFS.length; i++) {
    var c = CLIFFS[i];
    var s = alongOf(c.a[0], c.a[1]);
    var e = alongOf(c.b[0], c.b[1]);
    if (e < s) { var t = s; s = e; e = t; }
    if (e - s < UPDRAFT_HALF * 2 + 200) {
      var mid = (s + e) / 2;
      UPDRAFTS.push({ start: mid - UPDRAFT_HALF, end: mid + UPDRAFT_HALF, name: c.name });
    } else {
      for (var m = s + UPDRAFT_HALF; m <= e - UPDRAFT_HALF; m += UPDRAFT_SPACING) {
        UPDRAFTS.push({ start: m - UPDRAFT_HALF, end: m + UPDRAFT_HALF, name: c.name });
      }
    }
  }
  UPDRAFTS.sort(function (x, y) { return x.start - y.start; });
}

var lifting = { strength: 0, rate: 0, gained: 0, inside: null, next: null, nextIn: Infinity,
  colGain: 0, quickWatts: 0, preview: 0 };

/** The extra climb (m/s) from ridge lift this frame. Called only while flying. */
function updateLift(dt, typical, watts) {
  // Faster than the 3 s height average: a push into the column should pay off inside it.
  lifting.quickWatts += (watts - lifting.quickWatts) * Math.min(1, dt / 1.2);
  var m = mod(state.along);
  var inside = null;
  var next = null;
  var nextIn = Infinity;
  for (var i = 0; i < UPDRAFTS.length; i++) {
    var u = UPDRAFTS[i];
    if (m >= u.start && m < u.end) {
      inside = u;
    } else if (u.start > m && u.start - m < nextIn) {
      nextIn = u.start - m;
      next = u;
    }
  }
  if (!next && UPDRAFTS.length > 0) {
    next = UPDRAFTS[0];
    nextIn = UPDRAFTS[0].start + routeLength - m;
  }
  if (lifting.inside && inside !== lifting.inside) {
    if (lifting.colGain >= 10) {
      showToast('+' + Math.round(lifting.colGain) + ' m OF RIDGE LIFT');
    }
    lifting.colGain = 0;
  }
  lifting.inside = inside;
  lifting.next = next;
  lifting.nextIn = nextIn;
  var target = inside ? clamp(Math.min(m - inside.start, inside.end - m) / 250, 0, 1) : 0;
  lifting.strength += (target - lifting.strength) * Math.min(1, dt * 3);
  var level = typical * LEVEL_SHARE;
  var effort = clamp((lifting.quickWatts - level) / (typical * 0.5), 0, 1);
  lifting.rate = lifting.strength * (LIFT_FREE + LIFT_PULL * effort);
  if (state.agl < MAX_AGL + LIFT_CEILING) {
    lifting.gained += lifting.rate * dt;
    lifting.colGain += lifting.rate * dt;
  }
  return lifting.rate;
}

function saveLift() {
  if (bridge && bridge.saveLift && lifting.gained >= 1) {
    try {
      bridge.saveLift(Math.round(lifting.gained));
    } catch (e) { /* a record is a nicety */ }
  }
}

var WISPS = 10;
var wispEls = [];
var wispsOn = false;

function initLift() {
  var g = el('liftWisps');
  for (var i = 0; i < WISPS; i++) {
    var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    g.appendChild(path);
    wispEls.push(path);
  }
}

/** Rising air drawn as warm wisps climbing the cliff side (the left, where the land is). */
function drawLift(now) {
  var preview = !state.hovering && lifting.nextIn < 600 ? 0.25 * (1 - lifting.nextIn / 600) : 0;
  var show = Math.max(lifting.strength, preview);
  if (show < 0.02) {
    if (wispsOn) {
      wispsOn = false;
      el('liftWisps').setAttribute('opacity', '0');
    }
    return;
  }
  wispsOn = true;
  el('liftWisps').setAttribute('opacity', show.toFixed(2));
  var speed = 0.25 + lifting.rate * 0.06;
  for (var i = 0; i < WISPS; i++) {
    var frac = ((now / 1000) * speed + i * 0.137) % 1;
    var x = 70 + ((i * 67) % 290) + Math.sin(now / 500 + i) * 6;
    var y = 630 - frac * 440;
    var sway = (6 + (i % 3) * 3).toFixed(0);
    wispEls[i].setAttribute('d', 'M ' + x.toFixed(0) + ' ' + y.toFixed(0)
      + ' q ' + sway + ' -14 0 -28 q -' + sway + ' -14 0 -28');
    wispEls[i].setAttribute('stroke-opacity', (Math.sin(Math.PI * frac) * 0.85).toFixed(2));
  }
}

/* ---- 4. fog banks ---- */

var FOG_DRAG = 0.2;             // airspeed lost at full fog
var FOG_FREE_START = 4500;
/**
 * Top of the marine layer (m above the sea) at a distance along the route, or 0 in clear air.
 * Tall on purpose: rowing at your typical power holds the 700 m ceiling, so a bank tops out a
 * little below it (~400-650 m) - ten or twenty seconds of easing off and you are in it.
 */
function fogTopAt(m) {
  if (m < FOG_FREE_START) {
    return 0;   // the Golden Gate is clear for take-off and its postcard
  }
  var x = m / 1000;
  var n = Math.sin(x * 0.29 + 0.7) + 0.6 * Math.sin(x * 0.83 + 2.1) + 0.35 * Math.sin(x * 2.1);
  if (n < 0.45) {
    return 0;
  }
  var body = clamp((n - 0.45) / 0.25, 0, 1);   // banks thin out at their edges
  return (560 + 90 * Math.sin(x * 0.11)) * (0.7 + 0.3 * body);
}

var fogState = { top: 0, inFog: false, aheadIn: Infinity, aheadTop: 0, deck: 0, fogShown: -1, deckShown: -1 };

function updateFog(dt, fresh) {
  var m = mod(state.along);
  var top = state.hovering ? 0 : fogTopAt(m);
  // 10 m of hysteresis: holding level right at a bank's top must not flicker in and out of it
  // (and toast INTO / BROKE OUT every frame).
  var inFog = top > 0 && state.agl < (fogState.inFog ? top + 10 : top);
  var depth = inFog ? clamp((top - state.agl) / 35 + 0.45, 0.3, 1) : 0;
  state.fog += (depth - state.fog) * Math.min(1, dt * (depth > state.fog ? 1.8 : 1.2));
  if (inFog && !fogState.inFog) {
    showToast('INTO THE FOG — PULL TO CLIMB OUT');
  } else if (!inFog && fogState.inFog && fresh) {
    showToast('BROKE OUT OF THE FOG');
  }
  fogState.inFog = inFog;
  fogState.top = top;
  // Above a bank you look down on the marine layer.
  var deck = top > 0 && !inFog ? clamp(1 - (state.agl - top) / 220, 0, 1) : 0;
  fogState.deck += (deck - fogState.deck) * Math.min(1, dt * 1.5);
  // Look ahead for the next bank you would sink into at your current height.
  fogState.aheadIn = Infinity;
  if (!inFog && !state.hovering) {
    for (var d = 250; d <= 2000; d += 250) {
      var t = fogTopAt(m + d);
      if (t > 0 && state.agl < t + 25) {
        fogState.aheadIn = d;
        fogState.aheadTop = t;
        break;
      }
    }
  }
  var fogOp = state.fog * 0.8;   // a whiteout, but the coast still shows through it
  if (Math.abs(fogOp - fogState.fogShown) > 0.01) {
    fogState.fogShown = fogOp;
    el('fog').style.opacity = fogOp.toFixed(3);
  }
  var deckOp = fogState.deck * 0.85;
  if (Math.abs(deckOp - fogState.deckShown) > 0.01) {
    fogState.deckShown = deckOp;
    el('deck').style.opacity = deckOp.toFixed(3);
  }
}

/* ---- 3. postcard stops ---- */

var POSTCARDS = [
  // Taken looking back north from 3.2 km out: the bridge is behind you as the trip starts.
  { id: 'goldengate', name: 'the Golden Gate', lat: 37.8199, lon: -122.4783, h: 150, lo: 250, hi: 500, shotAt: 3200 },
  { id: 'bixby', name: 'Bixby Bridge, Big Sur', lat: 36.3716, lon: -121.9018, h: 80, lo: 200, hi: 420, shotAt: -1 },
  { id: 'mcway', name: 'McWay Falls, Big Sur', lat: 36.1580, lon: -121.6721, h: 30, lo: 150, hi: 360, shotAt: -1 }
];
var POSTCARD_APPROACH = 3000;   // ~60 s of warning at a typical pace
var LOOK_IN = 1300;
var LOOK_HOLD = 1500;
var LOOK_OUT = 1300;
var CARD_SHOW_MS = 9000;

var pc = { active: null, t0: 0, captured: false, pending: false, prevAlong: -1, next: null, nextIn: Infinity,
  stars: 0, total: 0, hideAt: 0, look: { heading: 0, pitch: 0, w: 0 } };

function initPostcards() {
  for (var i = 0; i < POSTCARDS.length; i++) {
    if (POSTCARDS[i].shotAt < 0) {
      // Just before abeam, so the landmark sits ahead and to the left.
      POSTCARDS[i].shotAt = Math.max(0, alongOf(POSTCARDS[i].lat, POSTCARDS[i].lon) - 500);
    }
  }
  pc.total = storedStars();
  setText('stars', pc.total + '/' + (POSTCARDS.length * 3));
}

function storedStars() {
  if (!bridge || !bridge.cards) {
    return 0;
  }
  var total = 0;
  try {
    var parts = String(bridge.cards() || '').split(',');
    for (var i = 0; i < parts.length; i++) {
      var n = Number(parts[i].split(':')[1]);
      if (isFinite(n)) { total += n; }
    }
  } catch (e) { /* no cards yet */ }
  return total;
}

function updatePostcards(now) {
  if (pc.hideAt && Date.now() > pc.hideAt) {
    pc.hideAt = 0;
    el('postcard').className = 'overlay hidden';
  }
  if (pc.active && now - pc.t0 > LOOK_IN + LOOK_HOLD + LOOK_OUT) {
    pc.active = null;
  }
  pc.next = null;
  pc.nextIn = Infinity;
  if (state.hovering) {
    pc.prevAlong = state.along;
    return;
  }
  var lap = Math.floor(state.along / routeLength);
  for (var i = 0; i < POSTCARDS.length; i++) {
    var card = POSTCARDS[i];
    var at = lap * routeLength + card.shotAt;
    if (!pc.active && pc.prevAlong >= 0 && pc.prevAlong < at && state.along >= at) {
      takePostcard(card, now);
    }
    var ahead = at > state.along ? at - state.along : at + routeLength - state.along;
    if (ahead < pc.nextIn) {
      pc.nextIn = ahead;
      pc.next = card;
    }
  }
  pc.prevAlong = state.along;
}

function takePostcard(card, now) {
  var alt = state.agl;
  var inWindow = alt >= card.lo && alt <= card.hi;
  var stars = 1;
  if (state.fog < 0.5) {
    if (inWindow) { stars++; }
    if (inWindow && flock.inSlot >= 4) { stars++; }
  }
  pc.active = card;
  pc.t0 = now;
  pc.captured = false;
  pc.pending = false;
  pc.stars = stars;
  pc.alt = alt;
  pc.inWindow = inWindow;
}

/** Where the head turns during a postcard stop, or null. Reuses one object: no per-frame garbage. */
function postcardLook(now, here, altitude) {
  if (!pc.active) {
    return null;
  }
  var e = now - pc.t0;
  var w = e < LOOK_IN ? ease(e / LOOK_IN)
    : e < LOOK_IN + LOOK_HOLD ? 1
      : ease(1 - (e - LOOK_IN - LOOK_HOLD) / LOOK_OUT);
  if (e >= LOOK_IN && !pc.captured && !pc.pending) {
    pc.pending = true;   // taken in the next postRender, while the frame is still in the buffer
  }
  var card = pc.active;
  var dist = Math.max(200, haversine(here, card));
  pc.look.heading = bearing(here, card);
  pc.look.pitch = clamp(-toDeg(Math.atan2(altitude - card.h, dist)), -35, 5);
  pc.look.w = w;
  return pc.look;
}

/** Copies the frame Cesium just drew into the postcard. Runs inside scene.postRender. */
function capturePostcard() {
  pc.pending = false;
  pc.captured = true;
  var card = pc.active;
  if (!card) {
    return;
  }
  var cv = el('pcCanvas');
  var ctx = cv.getContext('2d');
  try {
    var src = viewer.scene.canvas;
    var aspect = cv.width / cv.height;
    var cw = src.width;
    var ch = cw / aspect;
    if (ch > src.height) { ch = src.height; cw = ch * aspect; }
    ctx.drawImage(src, (src.width - cw) / 2, Math.max(0, (src.height - ch) * 0.45), cw, ch,
      0, 0, cv.width, cv.height);
  } catch (e) {
    var sky = ctx.createLinearGradient(0, 0, 0, cv.height);
    sky.addColorStop(0, '#8fb3cf');
    sky.addColorStop(1, '#3f6a86');
    ctx.fillStyle = sky;
    ctx.fillRect(0, 0, cv.width, cv.height);
  }
  // The flock is in the picture if it is with you.
  ctx.strokeStyle = '#1d1a17';
  ctx.lineCap = 'round';
  for (var i = 0; i < flock.birds.length; i++) {
    if (flock.birds[i].pres <= 0.9) {
      continue;
    }
    var slot = FLOCK_SLOTS[i];
    var x = (slot[0] / 1000) * cv.width - 60;
    var y = ((slot[1] - 145) / 495) * cv.height - 20;
    var s = slot[2] * 0.6;
    ctx.lineWidth = 2 * s;
    ctx.beginPath();
    ctx.moveTo(x - 22 * s, y - 6 * s);
    ctx.quadraticCurveTo(x - 11 * s, y - 7 * s, x, y + 2 * s);
    ctx.quadraticCurveTo(x + 11 * s, y - 7 * s, x + 22 * s, y - 6 * s);
    ctx.stroke();
  }
  if (state.fog > 0.05) {
    ctx.fillStyle = 'rgba(226,231,236,' + (state.fog * 0.9).toFixed(2) + ')';
    ctx.fillRect(0, 0, cv.width, cv.height);
  }

  var newBest = false;
  if (bridge && bridge.saveCard) {
    try {
      newBest = !!bridge.saveCard(card.id, pc.stars);
    } catch (e) { /* the card still shows */ }
  }
  pc.total = storedStars();
  setText('stars', pc.total + '/' + (POSTCARDS.length * 3));
  el('pcTitle').textContent = 'Greetings from ' + card.name;
  var starText = '';
  for (var k = 0; k < 3; k++) {
    starText += k < pc.stars ? '★' : '☆';
  }
  el('pcStars').textContent = starText;
  el('pcLine').textContent = Math.round(pc.alt) + ' m (window ' + card.lo + '–' + card.hi + ' m) · '
    + Math.round(state.airspeed * 3.6) + ' km/h · flock ' + flock.inSlot + '/' + FLOCK_MAX
    + (state.fog >= 0.5 ? ' · lost in the fog' : '')
    + (newBest ? ' · NEW BEST' : '');
  el('postcard').className = 'overlay';
  pc.hideAt = Date.now() + CARD_SHOW_MS;
}

/* ---- 5. the ghost of your last flight ---- */

var GHOST_MIN_SAMPLES = 30;     // 30 s of rowing before a flight is worth keeping as a ghost
var GHOST_MAX_SAMPLES = 3600;
var race = { clockAtStart: -1, lastClock: 0, flown: 0, rec: [], recAgl: [], ghost: null, ghostAgl: null,
  gap: 0, sign: 0, bb: null, label: null, pos: null, lastBehind: '' };

function parseGhost(text) {
  var parts = String(text || '').split(';');
  if (parts.length !== 3 || parts[0] !== 'v1') {
    return false;
  }
  var flown = parts[1].split(',').map(Number);
  var agl = parts[2].split(',').map(Number);
  if (flown.length < GHOST_MIN_SAMPLES || agl.length !== flown.length) {
    return false;
  }
  for (var i = 0; i < flown.length; i++) {
    if (!isFinite(flown[i]) || !isFinite(agl[i])) {
      return false;
    }
  }
  race.ghost = flown;
  race.ghostAgl = agl;
  return true;
}

function loadGhost() {
  if (!bridge || !bridge.ghost) {
    return;
  }
  try {
    parseGhost(bridge.ghost());
  } catch (e) { /* no ghost this time */ }
}

function saveGhost() {
  if (!bridge || !bridge.saveGhost || race.rec.length < GHOST_MIN_SAMPLES) {
    return;
  }
  try {
    bridge.saveGhost('v1;' + race.rec.join(',') + ';' + race.recAgl.join(','));
  } catch (e) { /* the next session just races the older ghost */ }
}

/** The rowing clock went backwards: a new session on the same page. Last one becomes the ghost. */
function restartRace() {
  if (race.rec.length >= GHOST_MIN_SAMPLES) {
    race.ghost = race.rec;
    race.ghostAgl = race.recAgl;
    ensureGhostBird();
  }
  race.rec = [];
  race.recAgl = [];
  race.flown = 0;
  race.sign = 0;
  race.clockAtStart = state.clock;
}

function raceT() {
  return race.clockAtStart < 0 ? 0 : Math.max(0, state.clock - race.clockAtStart);
}

/** How far the ghost had flown at this point on the rowing clock; holds at its landing. */
function ghostAt(series, t) {
  if (t >= series.length - 1) {
    return series[series.length - 1];
  }
  var i = Math.floor(t);
  return series[i] + (series[i + 1] - series[i]) * (t - i);
}

function ghostBird() {
  var c = document.createElement('canvas');
  c.width = 96;
  c.height = 48;
  var g = c.getContext('2d');
  var glow = g.createRadialGradient(48, 26, 2, 48, 26, 40);
  glow.addColorStop(0, 'rgba(170,200,255,0.55)');
  glow.addColorStop(1, 'rgba(170,200,255,0)');
  g.fillStyle = glow;
  g.fillRect(0, 0, 96, 48);
  g.strokeStyle = 'rgba(225,236,255,0.95)';
  g.lineWidth = 4;
  g.lineCap = 'round';
  g.beginPath();
  g.moveTo(10, 18);
  g.quadraticCurveTo(30, 12, 48, 30);
  g.quadraticCurveTo(66, 12, 86, 18);
  g.stroke();
  return c;
}

function initGhost() {
  loadGhost();
  ensureGhostBird();
}

/** The ghost's billboard, made once there is a ghost to show (at boot, or when a session ends). */
function ensureGhostBird() {
  if (!viewer || !race.ghost || race.bb) {
    return;
  }
  race.pos = new Cesium.Cartesian3();
  var bbs = viewer.scene.primitives.add(new Cesium.BillboardCollection());
  race.bb = bbs.add({
    position: Cesium.Cartesian3.fromDegrees(ROUTE[0].lon, ROUTE[0].lat, 200),
    image: ghostBird(),
    show: false,
    scaleByDistance: new Cesium.NearFarScalar(150, 1.8, 4000, 0.45),
    disableDepthTestDistance: Number.POSITIVE_INFINITY
  });
  var labels = viewer.scene.primitives.add(new Cesium.LabelCollection());
  race.label = labels.add({
    position: Cesium.Cartesian3.fromDegrees(ROUTE[0].lon, ROUTE[0].lat, 200),
    text: 'LAST FLIGHT',
    font: 'bold 14px sans-serif',
    fillColor: Cesium.Color.fromCssColorString('#cfe0ff'),
    style: Cesium.LabelStyle.FILL_AND_OUTLINE,
    outlineWidth: 3,
    outlineColor: Cesium.Color.fromCssColorString('#03080f'),
    pixelOffset: new Cesium.Cartesian2(0, 26),
    show: false,
    scaleByDistance: new Cesium.NearFarScalar(150, 1.2, 4000, 0.6),
    disableDepthTestDistance: Number.POSITIVE_INFINITY
  });
}

function updateGhost(dt, here, altitude) {
  if (!state.started) {
    return;
  }
  if (state.clock + 1 < race.lastClock) {
    restartRace();
  }
  race.lastClock = state.clock;
  var t = raceT();
  // Record this flight, one sample per rowing second.
  var sec = Math.floor(t);
  while (race.rec.length <= sec && race.rec.length < GHOST_MAX_SAMPLES) {
    race.rec.push(Math.round(race.flown));
    race.recAgl.push(Math.round(state.agl));
  }
  if (!race.ghost) {
    setText('ghostGap', '—');
    setText('ghostCap', 'Ghost next time');
    return;
  }
  var ghostFlown = ghostAt(race.ghost, t);
  var landed = t >= race.ghost.length - 1;
  race.gap = race.flown - ghostFlown;
  setText('ghostGap', (race.gap >= 0 ? '+' : '−') + Math.abs(Math.round(race.gap)) + ' m');
  setText('ghostCap', landed ? 'Ghost landed' : 'vs ghost');
  if (!state.hovering && Math.abs(race.gap) > 15) {
    var sign = race.gap > 0 ? 1 : -1;
    if (race.sign !== 0 && sign !== race.sign) {
      showToast(sign > 0 ? 'YOU PASSED YOUR GHOST' : 'YOUR GHOST PASSED YOU');
    }
    race.sign = sign;
  }

  var ahead = -race.gap;
  var visible = !state.hovering && ahead > 40 && ahead < 4000;
  if (race.bb) {
    race.bb.show = visible;
    race.label.show = visible;
    if (visible) {
      var p = offsetPoint(atDistance(state.along + ahead), state.weaveOffset * 0.4 + 60);
      var agl = ghostAt(race.ghostAgl, t);
      Cesium.Cartesian3.fromDegrees(p.lon, p.lat,
        state.ground + agl + Math.sin(Date.now() / 420) * 3, undefined, race.pos);
      race.bb.position = race.pos;
      race.label.position = race.pos;
    }
  }
  var note = '';
  if (!state.hovering) {
    if (ahead > 4000) {
      note = 'Ghost ' + (ahead / 1000).toFixed(1) + ' km ahead';
    } else if (ahead > 40) {
      note = 'Ghost ' + Math.round(ahead) + ' m ahead — catch it';
    } else if (ahead < -40) {
      note = 'Ghost ' + Math.round(-ahead) + ' m behind ▼';
    }
  }
  if (note !== race.lastBehind) {
    race.lastBehind = note;
    el('ghostBehind').textContent = note;
  }
}

/* ---- the one line that says what matters in the next few seconds ---- */

function updateBanner(now) {
  if (toastUntil && Date.now() > toastUntil) {
    toastUntil = 0;
    el('toast').className = 'overlay hidden';
  }
  setText('flockN', flock.inSlot + '/' + FLOCK_MAX);
  setText('liftM', String(Math.round(lifting.gained)));

  var text = '';
  var cls = '';
  if (!state.hovering) {
    if (pc.active) {
      text = 'Postcard from ' + pc.active.name + '!';
      cls = 'card good';
    } else if (pc.next && pc.nextIn < POSTCARD_APPROACH) {
      var win = state.agl >= pc.next.lo && state.agl <= pc.next.hi;
      text = 'POSTCARD · ' + pc.next.name + ' in ' + (pc.nextIn / 1000).toFixed(1) + ' km · frame it at '
        + pc.next.lo + '–' + pc.next.hi + ' m' + (win ? ' ✔' : state.agl < pc.next.lo ? ' ↑ climb' : ' ↓ ease off');
      cls = win ? 'card good' : 'card';
    } else if (lifting.strength > 0.1 && lifting.inside) {
      text = 'RIDGE LIFT over ' + lifting.inside.name + ' · +' + lifting.rate.toFixed(1) + ' m/s · pull to ride it';
      cls = 'lift';
    } else if (fogState.inFog) {
      text = 'IN THE FOG · climb above ' + Math.round(fogState.top) + ' m to break out';
      cls = 'fog';
    } else if (lifting.next && lifting.nextIn < 1200) {
      text = 'Rising air over ' + lifting.next.name + ' in ' + (lifting.nextIn / 1000).toFixed(1) + ' km — pull through it';
      cls = 'lift';
    } else if (fogState.aheadIn < 2000) {
      text = 'Fog bank ahead in ' + (fogState.aheadIn / 1000).toFixed(1) + ' km — stay above '
        + Math.round(fogState.aheadTop) + ' m';
      cls = 'fog';
    }
  }
  setText('bannerText', text);
  var className = text ? 'overlay ' + cls : 'overlay hidden';
  if (shownText.__banner !== className) {
    shownText.__banner = className;
    el('banner').className = className;
  }
}

function initExtras() {
  state.fog = 0;
  initFlock();
  initLift();
  buildUpdrafts();
  initPostcards();
  initGhost();
  if (viewer) {
    viewer.scene.postRender.addEventListener(function () {
      if (pc.pending) {
        capturePostcard();
      }
    });
  }
}

/* ---------------- boot ---------------- */

try {
  initGlobe();
  report('globe-ready', 'mode=' + currentMode() + ' cesium=' + CESIUM_VERSION);
} catch (e) {
  el('splashState').textContent = 'The globe failed to start: ' + (e && e.message);
  report('globe-failed', (e && e.message) + ' cesium=' + CESIUM_VERSION);
}
try {
  initExtras();
} catch (e) {
  report('extras-failed', e && e.message);
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
el('splashState').textContent = 'Take three strokes to take off.';
if (bridge && bridge.ready) {
  try {
    bridge.ready();
  } catch (e) { /* the feed just will not start; the splash says so */ }
}
window.requestAnimationFrame(frame);
