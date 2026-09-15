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
    state.agl = Math.max(MIN_AGL, Math.min(MAX_AGL, state.agl + climb * dt));
    var targetAir = BASE_AIRSPEED + boat * AIRSPEED_PER_MPS;
    state.airspeed += (targetAir - state.airspeed) * Math.min(1, 1.5 * dt);
    state.along += state.airspeed * dt;
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
  if (viewer && (!LEGACY || now - lastCameraMs >= 28)) {
    lastCameraMs = now;
    viewer.camera.setView({
      destination: Cesium.Cartesian3.fromDegrees(here.lon, here.lat, altitude),
      orientation: {
        heading: Cesium.Math.toRadians(here.heading + (state.hovering ? 0 : Math.cos(state.weavePhase) * 6)),
        pitch: Cesium.Math.toRadians(-8 - Math.min(16, state.agl / 60)),
        roll: Cesium.Math.toRadians(state.bank)
      }
    });
  }

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
    fill.style.background = 'var(--accent)';
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
el('splashState').textContent = 'Take three strokes to take off.';
if (bridge && bridge.ready) {
  try {
    bridge.ready();
  } catch (e) { /* the feed just will not start; the splash says so */ }
}
window.requestAnimationFrame(frame);
