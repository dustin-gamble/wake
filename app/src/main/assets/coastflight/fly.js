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
/* Airspeed, 3.24.0, after the rower flew it on the tablet: "maybe go faster over the ground".
   That flight measured 218 km/h with the next waypoint 10.7 km off - three minutes of holding a
   heading between places, which is what "faster" meant. The shape is unchanged (power drives boat
   speed, boat speed drives airspeed); only the two constants move, 15/9 -> 24/22.

   What it now gives, taking boat speed from watts by the cube law the flywheel obeys
   (P = k w^3, so speed scales as watts^(1/3)), anchored on this machine's measured median of
   129 W at 3.85 m/s:

     119 W -> 3.75 m/s boat -> 24 + 82.5  = 106.5 m/s = 384 km/h
     130 W -> 3.86 m/s boat -> 24 + 84.9  = 108.9 m/s = 392 km/h
     p90 162 W -> 4.06 m/s  -> 113.3 m/s  = 408 km/h
     max 205 W -> 4.19 m/s  -> 116.2 m/s  = 418 km/h
     boat stopped, gliding  ->  24.0 m/s  =  86 km/h

   So a solid pace sits in the 380-420 km/h the rower asked for, and the old 550 km/h that blurred
   the coast is still out of reach: AIRSPEED_MAX caps the stacked bonuses (flock draft, wind aloft,
   a leader's slipstream, a front's tailwind - up to +50% together) at 522 km/h. */
var BASE_AIRSPEED = 24;      // m/s gliding with the boat stopped
var AIRSPEED_PER_MPS = 22;   // m/s of airspeed per m/s of boat speed
var AIRSPEED_MAX = 145;      // m/s ceiling, 522 km/h: below the 550 that blurred the coastline
var WEAVE_METRES = 260;      // how far either side of the route the flight swings
// The bank leads the corner by this many seconds of flight rather than by a fixed distance, so
// it still anticipates the turn now that a second of flight is three times as far as it was.
var TURN_LEAD_S = 8;

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
  typicalSpeed: 3.85,    // ditto - the measured median on this machine until the profile lands
  highWatts: 205,
  smoothWatts: 0,
  agl: START_AGL,
  ground: 0,
  hovering: true,
  strokesAtStart: -1,
  weavePhase: 0,
  weaveOffset: 0,
  bob: 0,
  wingPhase: 0,
  // 3.24.0 camera motion: the stroke rhythm and the rough air. Both are plain phase accumulators
  // advanced by dt, read by a couple of sines a frame, and allocate nothing.
  strokePhase: 0,
  airPhase: 0,
  driveShown: 0
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
  saveFlightRecords();
};

/* ---------------- geo helpers ---------------- */

var R = 6371000;
var TWO_PI = Math.PI * 2;
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
    // Chrome 70 on tablet silicon: no multisampling, and no alpha channel to composite.
    // The resolution scale below is the large dial, and 3.24.0 turns it up (see there).
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
  // 3.24.0, the rower's second ask: "better satellite resolution". This started at 0.6 / SSE 6,
  // went to 0.75 / SSE 4, and is now 0.85 / SSE 3.5 - the imagery it was soft, and the softness
  // was these two numbers, not the tiles. Everything else the old engine gives up stays given up
  // (lighting off, fog off, no ground atmosphere, terrain without vertex normals, a ~30Hz camera):
  // those are what buy the frame rate this spends. adaptQuality() steps the scale down if the
  // frame rate drops and back up when there is headroom, and its floor is 0.7 - below that the
  // imagery is soft again, so it is better to drop frames than to go back there.
  viewer.resolutionScale = LEGACY ? 0.85 : 1.0;
  viewer.scene.globe.maximumScreenSpaceError = LEGACY ? 3.5 : 2;
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
  // 3.23.0: the rower's own typical boat speed sets the migration leader's pace, and their high
  // power sets what "pushing through" a weather front means. Both from the profile, never guessed.
  if (Number(d.q) > 0) {
    state.typicalSpeed = Number(d.q);
  }
  if (Number(d.h) > 0) {
    state.highWatts = Number(d.h);
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
    // A weather front presses you down as well as back, unless you push through it.
    climb += updateFronts(dt, typical, watts);
    if (state.agl > MAX_AGL && lifting.strength < 0.05) {
      climb = Math.min(climb, -ALOFT_SETTLE);
    }
    var ceiling = state.agl > MAX_AGL || lifting.strength > 0.05 ? MAX_AGL + LIFT_CEILING : MAX_AGL;
    state.agl = Math.max(MIN_AGL, Math.min(ceiling, state.agl + climb * dt));
    // The flock drafts you along (+1.5% a bird in the V); the fog's damp air drags you back.
    // 3.23.0 adds the leader's slipstream and the tailwind behind a front you punched through,
    // against the headwind inside one.
    var targetAir = (BASE_AIRSPEED + boat * AIRSPEED_PER_MPS)
      * (1 + FLOCK_DRAFT * flock.inSlot + ALOFT_BONUS * aloft()
        + MIG_DRAFT * mig.draft + frontTailBonus())
      * (1 - FOG_DRAG * state.fog) * (1 - fronts.drag);
    // Those bonuses stack to +50%. Unclamped that would put a strong row back over the 550 km/h
    // that blurred the coast and was reverted; the cap is the one place that cannot happen.
    targetAir = Math.min(AIRSPEED_MAX, targetAir);
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

  /* ---- 3.24.0 camera motion, the rower's "more movement" ----
     Three separate things, all read off values that already exist and none of them allocating:
       1. the eased drive (0..1), shared with the wings below so both sit on the same frame;
       2. a stroke-rhythm phase advanced by the feed's rate, which sways the camera;
       3. an air phase, which roughens the ride as the airspeed builds. */
  var driveNow = state.hovering ? 1 : (fresh ? (state.drive || 0) : 0);
  state.driveShown += (driveNow - state.driveShown) * Math.min(1, dt * 8);

  state.strokePhase += dt * TWO_PI * (Math.min(40, state.spm) / 60);
  if (state.strokePhase > TWO_PI) { state.strokePhase -= TWO_PI; }
  // Deliberately tiny: a degree and a half of roll, under a degree of heading, three metres of
  // rise. Enough to feel the row in the view; not enough to make a tablet at arm's length
  // unpleasant. It fades out with the drive, so a rest is still a steady glide.
  var swayAmp = (!state.hovering && fresh) ? (0.3 + 0.7 * state.driveShown) : 0;
  var swayRoll = Math.sin(state.strokePhase) * 1.5 * swayAmp;
  var swayHeading = Math.sin(state.strokePhase + 1.9) * 0.7 * swayAmp;
  var swayPitch = Math.sin(state.strokePhase * 2 + 0.4) * 0.5 * swayAmp;
  var swayBob = Math.sin(state.strokePhase - 0.6) * 3 * swayAmp;

  // Rough air. Two sines at unrelated frequencies read as wind for the price of two trig calls;
  // a real noise field would want a table, and a per-frame lookup is not worth it for this.
  state.airPhase += dt;
  if (state.airPhase > 100000) { state.airPhase -= 100000; }
  var rough = clamp((state.airspeed - BASE_AIRSPEED) / 80, 0, 1);
  var turbRoll = (Math.sin(state.airPhase * 2.3)
    + 0.55 * Math.sin(state.airPhase * 5.9 + 1.3)) * 0.85 * rough;
  var turbPitch = Math.sin(state.airPhase * 3.1 + 0.7) * 0.45 * rough;

  var onRoute = atDistance(state.along);
  var here = offsetPoint(onRoute, state.weaveOffset);
  // Look ahead by a fixed TIME, not a fixed 900 m. 900 m used to arrive in eight seconds and at
  // the 3.24.0 speeds arrives in under three, so a fixed distance would put the roll inside the
  // corner instead of ahead of it. Scaling with airspeed keeps the same anticipation at any pace.
  var leadM = clamp(state.airspeed * TURN_LEAD_S, 700, 2600);
  var ahead = atDistance(state.along + leadM);
  // Bank into the turn, and into each swing of the weave. Because the lead is a time, `turn` is
  // now proportional to the route's turn RATE, so the roll is a continuous lean through a bend
  // rather than a step at each waypoint.
  var turn = ahead.heading - onRoute.heading;
  if (turn > 180) { turn -= 360; }
  if (turn < -180) { turn += 360; }
  var weaveBank = state.hovering ? 0 : Math.cos(state.weavePhase) * 12;
  var targetBank = Math.max(-32, Math.min(32, turn * 1.6 + weaveBank));
  // Eased at 1.7 rather than 2.0: gentler, so the lean arrives over about a second.
  state.bank += (targetBank - state.bank) * Math.min(1, 1.7 * dt);

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
  var altitude = state.ground + state.agl + state.bob + hoverBob + swayBob;
  state.altitude = state.agl;

  // ~30Hz camera on the old engine: each setView is a full scene traversal, and the flight is
  // smooth long before 60. Stamp the clock only when a frame actually goes through.
  var camHeading = here.heading + (state.hovering ? 0 : Math.cos(state.weavePhase) * 6)
    + swayHeading;
  // Nose down as the airspeed builds - the horizon rides up, more ground fills the frame, and
  // that is most of what actually reads as speed. Six degrees at the ceiling, on top of the
  // altitude term, and none of it while gliding at 86 km/h.
  var camPitch = -8 - Math.min(16, state.agl / 60)
    - clamp((state.airspeed - 55) / 14, 0, 6) + turbPitch + swayPitch;
  var camRoll = state.bank + turbRoll + swayRoll;
  // A postcard stop turns the head toward the landmark for the picture, then back. A photo
  // challenge gives the same landmark a shorter glance.
  var look = postcardLook(now, here, altitude);
  if (!look) {
    look = photoLook(here, altitude);
  }
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
  updateLegs(dt);
  updateMigration(dt);
  updatePhoto();
  drawWeather();
  drawRush();
  updatePassportPage();

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
  // 3.24.0: the band moves up with the starting scale. The floor is 0.7 rather than 0.55 because
  // "better satellite resolution" is the point of this pass - a soft picture at 24 fps is not the
  // trade the rower asked for, and the old floor could quietly undo the whole change.
  var top = LEGACY ? 0.95 : 1.0;
  var bottom = LEGACY ? 0.7 : 0.75;
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
  // 3.24.0: the easing of `drive` moved into frame(), because the camera sway rides on the same
  // value and the two must not drift apart by a frame. This only reads it.
  var active = state.hovering ? 0.55 : rowing ? 1 : 0;
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

/* ---------------- the ground rush ---------------- */

/**
 * Streaks down the left and right edges of the frame: the near-field motion that sells ground
 * speed when everything else in view is a mile away and barely moving.
 *
 * <p>The layers are CSS animations on composited transforms - the rain's pattern, for the rain's
 * reason: an animated background-position would repaint the whole viewport over the WebGL globe
 * every frame, which this tablet cannot spare. All this function does is set one opacity, at most
 * once a frame and only when it has actually moved, and park the overlay with display:none below
 * a glide so it costs nothing at all while hovering or resting.
 */
var rush = { shown: -1, on: null };

function drawRush() {
  // Nothing at a stopped-boat glide (24 m/s), a third of the way up at a solid pace (106 m/s),
  // full only at the airspeed ceiling.
  var want = state.hovering ? 0 : clamp((state.airspeed - 34) / 110, 0, 1) * 0.5;
  if (Math.abs(want - rush.shown) < 0.02) {
    return;
  }
  rush.shown = want;
  el('rush').style.opacity = want.toFixed(3);
  var on = want > 0.01;
  if (on !== rush.on) {
    rush.on = on;
    el('rush').style.display = on ? 'block' : 'none';
  }
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
var UPDRAFT_HALF = 700;         // each column is 1.4 km long: ~13 s at 3.24.0 speeds, was ~28 s
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
  { id: 'goldengate', name: 'the Golden Gate', lat: 37.8199, lon: -122.4783, h: 150, lo: 250, hi: 500, shotAt: 3200, stamp: 'Golden Gate Bridge' },
  { id: 'bixby', name: 'Bixby Bridge, Big Sur', lat: 36.3716, lon: -121.9018, h: 80, lo: 200, hi: 420, shotAt: -1, stamp: 'Bixby Bridge' },
  { id: 'mcway', name: 'McWay Falls, Big Sur', lat: 36.1580, lon: -121.6721, h: 30, lo: 150, hi: 360, shotAt: -1, stamp: 'McWay Falls' }
];
/* A warning DISTANCE whose job is a warning TIME: from the 700 m ceiling a rower sinking at the
   full 14 m/s needs about 46 s to reach a low postcard window, so a short warning is no warning.
   3000 m was 60 s at 180 km/h and would be 28 s at the 3.24.0 airspeeds; 6800 m restores it. */
var POSTCARD_APPROACH = 6800;
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
  // The postcard stops are passport landmarks too, and their stars are the ones that count there.
  if (card.stamp) {
    stampLandmark({ name: card.stamp }, pc.stars, 'POSTCARD');
  }
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

/* ================= 3.23.0: legs, migration, photo challenges, fronts, the passport =================

   Five more things the rower asked for, all of them built on data the page already has:
     - every landmark is a LEG with a destination, a live ETA at the pace you are holding, and a
       best time you are racing,
     - MIGRATION mode puts a leader bird ahead of you flying at a pace taken from your own profile;
       hold its slipstream for +10% airspeed, drop 1.5 km back and it goes on without you,
     - PHOTO CHALLENGES ask you to be at a given height as a landmark goes by, and print the frame
       Cesium just drew,
     - WEATHER FRONTS are a headwind and a downdraught you can either wallow in or pull through,
       and pushing through pays a twenty-second tailwind,
     - the PASSPORT stamps every landmark reached and keeps the stars its photo earned.
   Nothing here fetches anything. Written for Chrome 70: no optional chaining, no flex gap. */

function fmtClock(seconds) {
  if (!isFinite(seconds) || seconds < 0 || seconds > 5999) {
    return '—';
  }
  var total = Math.round(seconds);
  var s = total % 60;
  return Math.floor(total / 60) + ':' + (s < 10 ? '0' + s : s);
}

function starText(n) {
  var out = '';
  for (var i = 0; i < 3; i++) {
    out += i < n ? '★' : '☆';
  }
  return out;
}

/* ---- 6. legs, destinations and an ETA at your pace ---- */

var LEG_ETA_SMOOTH = 8;         // seconds: an ETA that jumps every stroke is unreadable
var legNav = { idx: -1, startClock: 0, clean: false, done: 0, best: {}, air: 0, bar: -1 };

function legIndexAt(m) {
  for (var i = 0; i < LEGS.length; i++) {
    if (m >= LEGS[i].start && m < LEGS[i].start + LEGS[i].length) {
      return i;
    }
  }
  return LEGS.length - 1;
}

function loadLegTimes() {
  if (!bridge || !bridge.legTimes) {
    return;
  }
  try {
    var parts = String(bridge.legTimes() || '').split(',');
    for (var i = 0; i < parts.length; i++) {
      var bits = parts[i].split(':');
      var k = Number(bits[0]);
      var v = Number(bits[1]);
      if (isFinite(k) && isFinite(v) && v > 0 && k >= 0 && k < LEGS.length) {
        legNav.best[k] = v;
      }
    }
  } catch (e) { /* no times yet */ }
}

function saveLegTimes() {
  if (!bridge || !bridge.saveLegTimes) {
    return;
  }
  var out = [];
  for (var k in legNav.best) {
    if (Object.prototype.hasOwnProperty.call(legNav.best, k)) {
      out.push(k + ':' + Math.round(legNav.best[k]));
    }
  }
  if (out.length === 0) {
    return;
  }
  try {
    bridge.saveLegTimes(out.join(','));
  } catch (e) { /* the table is a nicety */ }
}

function updateLegs(dt) {
  var m = mod(state.along);
  var i = legIndexAt(m);
  // The rowing clock can go backwards - a fresh session behind the same page, the same thing
  // restartRace() watches for. A leg started before that is not a run at the record, and without
  // this the elapsed time would go negative and the ETA comparison would read "ahead" forever.
  if (state.clock + 1 < legNav.startClock) {
    legNav.startClock = state.clock;
    legNav.clean = false;
  }
  if (legNav.idx < 0) {
    legNav.idx = i;
    legNav.startClock = state.clock;
    // A leg entered part-way (a restart, or last session's trip) is not a run at its record.
    legNav.clean = m - LEGS[i].start < 200;
  } else if (i !== legNav.idx) {
    if (i === (legNav.idx + 1) % LEGS.length && !state.hovering) {
      arriveAtLeg(legNav.idx);
    }
    legNav.idx = i;
    legNav.startClock = state.clock;
    // Normally this lands a metre or two past the new leg's start, so it is a clean run at its
    // record. The restart link jumps the trip back to the Golden Gate, which also lands on a
    // start; anything else that moved you mid-leg would not, and must not set a time.
    legNav.clean = m - LEGS[i].start < 200;
  }

  // The ETA runs on the airspeed you are actually holding, smoothed over 8 s so it counts down
  // steadily and quickens visibly when you pull.
  legNav.air += (state.airspeed - legNav.air) * Math.min(1, dt / LEG_ETA_SMOOTH);
  var leg = LEGS[legNav.idx];
  var flown = clamp(m - leg.start, 0, leg.length);
  var remain = Math.max(0, leg.length - flown);
  var eta = legNav.air > 1.5 ? remain / legNav.air : -1;
  var elapsed = Math.max(0, state.clock - legNav.startClock);
  var best = legNav.best[legNav.idx];

  setText('legDest', leg.to.name);
  setText('legLine', (remain / 1000).toFixed(1) + ' km · ETA '
    + (eta < 0 ? '—' : fmtClock(eta)));
  setText('legBest', best ? 'best ' + fmtClock(best) : 'no time yet');
  var cls = '';
  if (best && eta >= 0 && legNav.clean && !state.hovering) {
    cls = elapsed + eta < best ? 'ahead' : 'behind';
  }
  if (shownText.__legCls !== cls) {
    shownText.__legCls = cls;
    el('legLine').className = cls;
  }
  var frac = leg.length > 0 ? flown / leg.length : 0;
  if (Math.abs(frac - legNav.bar) > 0.003) {
    legNav.bar = frac;
    el('legBarFill').style.width = (frac * 100).toFixed(1) + '%';
  }
}

function arriveAtLeg(idx) {
  var secs = state.clock - legNav.startClock;
  var timed = legNav.clean && secs > 20;
  var beat = false;
  if (timed) {
    var old = legNav.best[idx];
    if (!old || secs < old) {
      legNav.best[idx] = secs;
      beat = true;
      saveLegTimes();
    }
  }
  legNav.done++;
  if (bridge && bridge.recordLegs) {
    try {
      bridge.recordLegs(legNav.done);
    } catch (e) { /* a record is a nicety */ }
  }
  showToast((beat ? 'NEW BEST LEG — ' : 'REACHED ') + LEGS[idx].to.name.toUpperCase()
    + (timed ? ' · ' + fmtClock(secs) : ''));
  stampLandmark(LEGS[idx].to, 0, beat ? 'BEST LEG ' + fmtClock(secs) : 'LANDMARK REACHED');
}

/* ---- 7. the passport ---- */

var MONTHS = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];
/* The roll the passport can stamp: every named place on the coast, because all three ways of
   earning a stamp - reaching a leg destination, framing a photo challenge and taking a postcard -
   land on one of these. PLACES is the route's own landmarks followed by the towns between them. */
var PASSPORT_PLACES = PLACES;
var book = { entries: {}, count: 0, stars: 0, lastSlug: '', lastMs: 0 };
var passPage = { shown: false, hideAt: 0 };

function slugOf(name) {
  var s = String(name).toLowerCase().replace(/[^a-z]/g, '');
  return s.length > 18 ? s.substring(0, 18) : s;
}

function pad2(n) { return n < 10 ? '0' + n : String(n); }

function todayCode() {
  var d = new Date();
  return String(d.getFullYear()) + pad2(d.getMonth() + 1) + pad2(d.getDate());
}

function prettyDay(code) {
  if (!code || code.length !== 8) {
    return 'STAMPED';
  }
  var mo = Number(code.substring(4, 6));
  return Number(code.substring(6, 8)) + ' ' + (MONTHS[mo - 1] || '');
}

/* Only these slugs can be in the book: a stamp is always a place on the coast. A stored entry
   for anything else is not one of ours, and counting it would make the card read 12/46 with
   eleven cells filled and inflate coast.stamps to match. */
var KNOWN_SLUGS = {};
for (var ps = 0; ps < PASSPORT_PLACES.length; ps++) {
  KNOWN_SLUGS[slugOf(PASSPORT_PLACES[ps].name)] = true;
}

function loadPassport() {
  if (bridge && bridge.passport) {
    try {
      var parts = String(bridge.passport() || '').split(',');
      for (var i = 0; i < parts.length; i++) {
        var bits = parts[i].split(':');
        if (bits[0] && KNOWN_SLUGS[bits[0]] === true) {
          book.entries[bits[0]] = { stars: clamp(Number(bits[1]) || 0, 0, 3), day: bits[2] || '' };
        }
      }
    } catch (e) { /* an empty book is a fine place to start */ }
  }
  countBook();
  showPassportCount();
}

function countBook() {
  book.count = 0;
  book.stars = 0;
  for (var k in book.entries) {
    if (Object.prototype.hasOwnProperty.call(book.entries, k)) {
      book.count++;
      book.stars += book.entries[k].stars;
    }
  }
}

function showPassportCount() {
  setText('passN', book.count + '/' + PASSPORT_PLACES.length + ' ★' + book.stars);
}

/** A landmark was reached (or photographed): stamp it, keep the better star count, and thump. */
function stampLandmark(place, stars, note) {
  var slug = slugOf(place.name);
  if (!slug) {
    return;
  }
  var entry = book.entries[slug];
  var isNew = !entry;
  if (!entry) {
    entry = { stars: 0, day: '' };
    book.entries[slug] = entry;
  }
  if (stars > entry.stars) {
    entry.stars = stars;
  }
  entry.day = todayCode();
  countBook();
  if (bridge && bridge.savePassport) {
    try {
      bridge.savePassport(serializeBook());
    } catch (e) { /* the stamp still shows this flight */ }
  }
  showPassportCount();
  // A photo is taken 300 m before the landmark, so its stamp and the leg's arrival are seconds
  // apart. One thump per place.
  var now = Date.now();
  if (book.lastSlug === slug && now - book.lastMs < 30000) {
    return;
  }
  book.lastSlug = slug;
  book.lastMs = now;
  thumpStamp(place.name, isNew ? 'NEW STAMP' : (note || 'STAMPED'), entry.stars);
}

function serializeBook() {
  var out = [];
  for (var k in book.entries) {
    if (Object.prototype.hasOwnProperty.call(book.entries, k)) {
      out.push(k + ':' + clamp(Math.round(book.entries[k].stars), 0, 3) + ':'
        + (book.entries[k].day || ''));
    }
  }
  return out.join(',');
}

/** The stamp slams down over the view, rocks, and lifts off again. */
function thumpStamp(name, sub, stars) {
  var e = el('stamp');
  el('stampName').textContent = String(name).toUpperCase();
  el('stampSub').textContent = sub + (stars > 0 ? ' · ' + starText(stars) : '');
  e.className = 'overlay';
  // Reading a layout property restarts the animation; without it a second stamp does nothing.
  var restart = e.offsetWidth;
  if (restart >= 0) {
    e.className = 'overlay go';
  }
}

function togglePassport() {
  if (passPage.shown) {
    closePassport();
    return;
  }
  buildPassport();
  el('passport').className = 'overlay';
  passPage.shown = true;
  passPage.hideAt = Date.now() + 16000;
}

function closePassport() {
  el('passport').className = 'overlay hidden';
  passPage.shown = false;
  passPage.hideAt = 0;
}

/** Closes itself: the book must not sit over the coast while the rower is still rowing. */
function updatePassportPage() {
  if (passPage.hideAt && Date.now() > passPage.hideAt) {
    closePassport();
  }
}

function buildPassport() {
  var grid = el('passGrid');
  grid.innerHTML = '';
  var cells = [];
  for (var i = 0; i < PASSPORT_PLACES.length; i++) {
    var entry = book.entries[slugOf(PASSPORT_PLACES[i].name)];
    var cell = document.createElement('div');
    cell.className = 'pcell ' + (entry ? 'got' : 'miss');
    var n = document.createElement('div');
    n.className = 'pn';
    n.textContent = PASSPORT_PLACES[i].name;
    cell.appendChild(n);
    var s = document.createElement('div');
    s.className = 'ps';
    s.textContent = entry ? starText(entry.stars) : '';
    cell.appendChild(s);
    var d = document.createElement('div');
    d.className = 'pd';
    d.textContent = entry ? prettyDay(entry.day) : 'NOT YET';
    cell.appendChild(d);
    cell.style.transitionDelay = i * 22 + 'ms';
    grid.appendChild(cell);
    cells.push(cell);
  }
  el('passSub').textContent = book.count + ' of ' + PASSPORT_PLACES.length + ' landmarks stamped · '
    + book.stars + ' photo stars · ' + legNav.done + ' legs this flight · '
    + fronts.count + ' fronts beaten';
  // One frame later, so the transition has a state to move from.
  window.setTimeout(function () {
    for (var j = 0; j < cells.length; j++) {
      cells[j].className += ' in';
    }
  }, 24);
}

/* ---- 8. migration: a leader bird that sets the pace ---- */

/* +8% in the slipstream. It has to be worth chasing and small enough to trim: the bonus makes
   you faster than the leader, so holding the slot means easing off as you reach its front and
   pulling again as you slide out of its back. That trimming is the whole exercise. */
var MIG_DRAFT = 0.08;
var MIG_SLOT_AHEAD = 30;        // you may nose this far past it and still be drafting
var MIG_SLOT_BACK = 150;        // ...and hang this far back
var MIG_ALT_BAND = 140;         // and be this far off its height
var MIG_LOST = 1500;            // this far off its pace, either way, and the pair splits up
var MIG_RAMP_S = 300;           // five minutes from a gentle pace to a demanding one
var mig = { armed: false, flying: false, lost: false, along: 0, agl: 260, held: 0, gap: 0,
  draft: 0, speed: 0, flownS: 0, bb: null, label: null, pos: null, you: -1, cardOn: null };

/** The leader's airspeed: what this rower makes at their own typical boat speed, ramped. */
function leaderPace() {
  var base = BASE_AIRSPEED + Math.max(2.2, state.typicalSpeed) * AIRSPEED_PER_MPS;
  return base * (0.95 + 0.13 * clamp(mig.flownS / MIG_RAMP_S, 0, 1));
}

/**
 * The split the leader's pace asks for, in the /500m the rower is already watching on the strip.
 * Airspeed is BASE + boat speed x PER_MPS, so this simply runs that backwards.
 */
function leaderSplit() {
  var boatNeeded = (mig.speed - BASE_AIRSPEED) / AIRSPEED_PER_MPS;
  if (boatNeeded < 0.5) {
    return '--:--';
  }
  var secs = Math.round(500 / boatNeeded);
  return Math.floor(secs / 60) + ':' + (secs % 60 < 10 ? '0' : '') + (secs % 60) + ' /500m';
}

function loadMigration() {
  if (bridge && bridge.migrateMode) {
    try {
      mig.armed = bridge.migrateMode() === '1';
    } catch (e) { /* off is the safe default */ }
  }
}

function saveMigrationMode() {
  if (bridge && bridge.setMigrateMode) {
    try {
      bridge.setMigrateMode(mig.armed ? '1' : '');
    } catch (e) { /* remembered next time, or not */ }
  }
}

function tapMigration() {
  if (!mig.armed) {
    mig.armed = true;
    mig.lost = false;
    mig.flying = false;
    saveMigrationMode();
    showToast('MIGRATION ON — HOLD THE LEADER’S SLIPSTREAM');
  } else if (mig.lost) {
    callLeader();
  } else {
    mig.armed = false;
    mig.flying = false;
    saveMigrationMode();
    recordMigration();
    showToast('MIGRATION OFF');
  }
}

function callLeader() {
  mig.lost = false;
  mig.flying = true;
  mig.flownS = 0;
  mig.along = state.along + 80;
  mig.agl = state.agl;
  showToast('THE LEADER CIRCLES BACK — GO');
}

function recordMigration() {
  if (bridge && bridge.recordMigration && mig.held >= 100) {
    try {
      bridge.recordMigration(Math.round(mig.held));
    } catch (e) { /* a record is a nicety */ }
  }
}

function updateMigration(dt) {
  var cardOn = mig.armed;
  if (cardOn !== mig.cardOn) {
    mig.cardOn = cardOn;
    el('migCard').className = cardOn ? 'card on' : 'card';
  }
  if (!mig.armed) {
    mig.draft = 0;
    setText('migState', 'MIGRATION OFF');
    setText('migCap', 'Tap to follow a leader');
    hideLeader();
    return;
  }
  if (state.hovering || !state.started) {
    mig.along = state.along + 80;
    mig.agl = state.agl;
    mig.flying = false;
    mig.draft = 0;
    setText('migState', 'LEADER WAITING');
    setText('migCap', 'It leaves when you do');
    hideLeader();
    return;
  }
  if (mig.lost) {
    mig.draft = 0;
    setText('migState', 'LEADER GONE');
    setText('migCap', 'Tap to call it back');
    hideLeader();
    return;
  }
  if (!mig.flying) {
    mig.flying = true;
    mig.flownS = 0;
    mig.along = state.along + 80;
    mig.agl = state.agl;
  }

  mig.flownS += dt;
  // It flies in the same air you do - at the head of the same flock, in the same fog, in the wind
  // at the same height - so the only thing between you is how hard you are pulling. A front is
  // deliberately left out: pushing through one is exactly how you gain on it.
  mig.speed = leaderPace() * (1 + FLOCK_DRAFT * flock.inSlot + ALOFT_BONUS * aloft())
    * (1 - FOG_DRAG * state.fog);
  mig.along += mig.speed * dt;
  // It flies at the height you fly at, so the slipstream is about pace and not about altitude -
  // except that it climbs over the fog banks ahead, so following it keeps you out of them. A
  // photo challenge pulls you down out of the draft, which is a choice worth having.
  var want = clamp(state.agl, MIN_AGL + 40, MAX_AGL);
  var fogAhead = fogTopAt(mod(mig.along + 1200));
  if (fogAhead > 0) {
    want = Math.max(want, fogAhead + 70);
  }
  want = clamp(want, MIN_AGL + 40, MAX_AGL);
  mig.agl += (want - mig.agl) * Math.min(1, dt * 0.5);

  mig.gap = mig.along - state.along;
  var offAlt = Math.abs(state.agl - mig.agl);
  var inSlot = mig.gap > -MIG_SLOT_AHEAD && mig.gap < MIG_SLOT_BACK && offAlt < MIG_ALT_BAND;
  var was = mig.draft;
  mig.draft += ((inSlot ? 1 : 0) - mig.draft) * Math.min(1, dt * 3);
  if (inSlot) {
    mig.held += state.airspeed * dt;
  }
  if (was < 0.5 && mig.draft >= 0.5) {
    showToast('IN THE SLIPSTREAM — +' + Math.round(MIG_DRAFT * 100) + '%');
  }
  // Losing it works both ways: the slipstream is a place, and you can miss it in front as
  // easily as behind. The leader does not cheat to stay ahead of a hard row.
  if (Math.abs(mig.gap) > MIG_LOST) {
    mig.lost = true;
    recordMigration();
    showToast((mig.gap > 0 ? 'THE LEADER LEFT YOU — ' : 'YOU LEFT THE LEADER — ')
      + (mig.held / 1000).toFixed(1) + ' km TOGETHER');
  }

  setText('migState', inSlot ? 'IN THE DRAFT'
    : mig.gap >= 0 ? Math.round(mig.gap) + ' m BEHIND' : Math.round(-mig.gap) + ' m AHEAD');
  setText('migCap', (mig.held / 1000).toFixed(1) + ' km drafted · hold ' + leaderSplit());
  // The marker runs from 1200 m behind the leader (left) to 400 m in front of it (right).
  var x = clamp((1200 - mig.gap) / 1600, 0, 1) * 100;
  if (Math.abs(x - mig.you) > 0.4) {
    mig.you = x;
    el('migYou').style.left = x.toFixed(1) + '%';
  }
  var youCls = inSlot ? 'draft' : '';
  if (shownText.__migYou !== youCls) {
    shownText.__migYou = youCls;
    el('migYou').className = youCls;
  }
  drawLeader();
}

function leaderBirdImage() {
  var c = document.createElement('canvas');
  c.width = 112;
  c.height = 60;
  var g = c.getContext('2d');
  var glow = g.createRadialGradient(56, 32, 3, 56, 32, 50);
  glow.addColorStop(0, 'rgba(240,177,50,0.5)');
  glow.addColorStop(1, 'rgba(240,177,50,0)');
  g.fillStyle = glow;
  g.fillRect(0, 0, 112, 60);
  g.strokeStyle = 'rgba(32,24,16,0.96)';
  g.lineWidth = 6;
  g.lineCap = 'round';
  g.beginPath();
  g.moveTo(10, 20);
  g.quadraticCurveTo(34, 12, 56, 34);
  g.quadraticCurveTo(78, 12, 102, 20);
  g.stroke();
  g.strokeStyle = 'rgba(240,177,50,0.9)';
  g.lineWidth = 2;
  g.stroke();
  return c;
}

function ensureLeaderBird() {
  if (!viewer || mig.bb) {
    return;
  }
  mig.pos = new Cesium.Cartesian3();
  var bbs = viewer.scene.primitives.add(new Cesium.BillboardCollection());
  mig.bb = bbs.add({
    position: Cesium.Cartesian3.fromDegrees(ROUTE[0].lon, ROUTE[0].lat, 200),
    image: leaderBirdImage(),
    show: false,
    scaleByDistance: new Cesium.NearFarScalar(120, 2.2, 5000, 0.5),
    disableDepthTestDistance: Number.POSITIVE_INFINITY
  });
  var labels = viewer.scene.primitives.add(new Cesium.LabelCollection());
  mig.label = labels.add({
    position: Cesium.Cartesian3.fromDegrees(ROUTE[0].lon, ROUTE[0].lat, 200),
    text: 'LEADER',
    font: 'bold 15px sans-serif',
    fillColor: Cesium.Color.fromCssColorString('#f0b132'),
    style: Cesium.LabelStyle.FILL_AND_OUTLINE,
    outlineWidth: 3,
    outlineColor: Cesium.Color.fromCssColorString('#03080f'),
    pixelOffset: new Cesium.Cartesian2(0, 28),
    show: false,
    scaleByDistance: new Cesium.NearFarScalar(120, 1.2, 5000, 0.6),
    disableDepthTestDistance: Number.POSITIVE_INFINITY
  });
}

function hideLeader() {
  if (mig.bb) {
    mig.bb.show = false;
    mig.label.show = false;
  }
}

function drawLeader() {
  ensureLeaderBird();
  if (!mig.bb) {
    return;
  }
  // Only ever drawn ahead of you: the camera never looks back.
  var visible = mig.gap > 15 && mig.gap < 6000;
  mig.bb.show = visible;
  mig.label.show = visible && mig.gap > 90;
  if (!visible) {
    return;
  }
  var p = offsetPoint(atDistance(state.along + mig.gap), state.weaveOffset * 0.5 - 40);
  Cesium.Cartesian3.fromDegrees(p.lon, p.lat,
    state.ground + mig.agl + Math.sin(Date.now() / 520) * 4, undefined, mig.pos);
  mig.bb.position = mig.pos;
  mig.label.position = mig.pos;
}

/* ---- 9. weather fronts ---- */

var FRONT_FIRST = 9000;
var FRONT_SPACING = 14000;      // ~2.1 minutes apart at 3.24.0 speeds, was ~4.5
var FRONT_HALF = 1000;          // 2 km across: about 18 s of pushing at 3.24.0 speeds, was 40
var FRONT_DRAG = 0.34;          // airspeed lost at the heart of one while easing off
var FRONT_SINK = 2.8;           // m/s pressed down, same condition
var FRONT_TAIL = 0.18;          // tailwind for punching through
var FRONT_TAIL_S = 20;
/* Average push over the crossing that counts as through it. Across the level-to-high band this
   works out at about 1.05x typical watts - a real lift above a normal row, still well under the
   p90 this rower reaches, and reached by pulling harder rather than by sprinting. */
var FRONT_THROUGH = 0.45;
var FRONT_NAMES = ['a squall line', 'a rain band', 'a headwind front', 'a sea squall'];
var FRONTS = [];
var fronts = { quickW: 0, strength: 0, inside: null, next: null, nextIn: Infinity, pushSum: 0,
  time: 0, drag: 0, tail: 0, count: 0, push: 0, squallShown: -1, rainShown: -1, rainOn: true };

function buildFronts() {
  var i = 0;
  for (var m = FRONT_FIRST; m < routeLength - 2500; m += FRONT_SPACING) {
    // A fixed spacing reads as a metronome; a deterministic jitter keeps them a surprise while
    // staying in the same place every flight, so a stretch you know is a stretch you can learn.
    var centre = m + ((i * 7919) % 4200) - 2100;
    if (centre < FRONT_HALF + 3000) {
      i++;
      continue;
    }
    FRONTS.push({ start: centre - FRONT_HALF, end: centre + FRONT_HALF,
      name: FRONT_NAMES[i % FRONT_NAMES.length] });
    i++;
  }
}

/**
 * The band a front is scored against: from level flight (what merely holds your height) up to
 * this rower's own high power, the profile's 90th percentile, sent in the feed as `h`.
 *
 * <p>Measured against `typical` instead, the ask came out at 0.82x typical - 106 W for a rower
 * whose median is 129 - so a front was punched through by rowing normally and "push through it"
 * asked for nothing. Simulated over 55 minutes that was 10 fronts out of 10 at a flat median row.
 * Against the high band the ask lands just above a normal row and a front is a real effort.
 */
function frontLevel(typical) {
  return typical * LEVEL_SHARE;
}

function frontSpan(typical) {
  var high = Math.max(typical * 1.15, state.highWatts || 0);
  return Math.max(25, high - frontLevel(typical));
}

/** The push a front asks for, 0..1, against this rower's own power. */
function frontPush(typical) {
  return clamp((fronts.quickW - frontLevel(typical)) / frontSpan(typical), 0, 1);
}

/** The watts that carry you through, shown in the banner so the ask is never a mystery. */
function frontWatts(typical) {
  return Math.round(frontLevel(typical) + frontSpan(typical) * FRONT_THROUGH);
}

/** Returns the downdraught (m/s, negative) this frame. Called only while flying. */
function updateFronts(dt, typical, watts) {
  // The monitor's watts really are zero between some strokes, so a short window makes the push
  // figure - and with it the drag and the banner's percentage - flicker once a stroke. 2.5 s is
  // still a fraction of the ~40 s crossing, so easing off inside a front shows up immediately.
  fronts.quickW += (watts - fronts.quickW) * Math.min(1, dt / 2.5);
  var m = mod(state.along);
  var inside = null;
  var next = null;
  var nextIn = Infinity;
  for (var i = 0; i < FRONTS.length; i++) {
    var f = FRONTS[i];
    if (m >= f.start && m < f.end) {
      inside = f;
    } else if (f.start > m && f.start - m < nextIn) {
      nextIn = f.start - m;
      next = f;
    }
  }
  if (!next && FRONTS.length > 0) {
    next = FRONTS[0];
    nextIn = FRONTS[0].start + routeLength - m;
  }
  fronts.push = frontPush(typical);
  if (inside !== fronts.inside) {
    if (fronts.inside && fronts.time > 3) {
      if (fronts.pushSum / fronts.time > FRONT_THROUGH) {
        fronts.count++;
        fronts.tail = FRONT_TAIL_S;
        showToast('PUNCHED THROUGH — TAILWIND FOR ' + FRONT_TAIL_S + ' s');
        if (bridge && bridge.recordFronts) {
          try {
            bridge.recordFronts(fronts.count);
          } catch (e) { /* a record is a nicety */ }
        }
      } else {
        showToast('THE FRONT PUSHED YOU BACK');
      }
    }
    if (inside) {
      showToast('INTO ' + inside.name.toUpperCase() + ' — PUSH THROUGH IT');
    }
    fronts.pushSum = 0;
    fronts.time = 0;
  }
  fronts.inside = inside;
  fronts.next = next;
  fronts.nextIn = nextIn;
  var target = inside ? clamp(Math.min(m - inside.start, inside.end - m) / 300, 0, 1) : 0;
  fronts.strength += (target - fronts.strength) * Math.min(1, dt * 2.5);
  if (inside) {
    fronts.pushSum += fronts.push * dt;
    fronts.time += dt;
  }
  fronts.tail = Math.max(0, fronts.tail - dt);
  fronts.drag = FRONT_DRAG * fronts.strength * (1 - fronts.push);
  return -FRONT_SINK * fronts.strength * (1 - fronts.push);
}

function frontTailBonus() {
  return FRONT_TAIL * (fronts.tail / FRONT_TAIL_S);
}

/** The squall itself: a darkening and rain that eases as you push through it. */
function drawWeather() {
  // Snapped to zero at the tail: the strength decays exponentially, so without this the last
  // step below the 0.01 threshold is never written and a faint squall sits over the coast for
  // the rest of the flight.
  var dark = fronts.strength > 0.012 ? fronts.strength * (0.78 - 0.3 * fronts.push) : 0;
  if (Math.abs(dark - fronts.squallShown) > 0.004) {
    fronts.squallShown = dark;
    el('squall').style.opacity = dark.toFixed(3);
  }
  var rain = fronts.strength > 0.012 ? fronts.strength * (0.9 - 0.35 * fronts.push) : 0;
  if (Math.abs(rain - fronts.rainShown) > 0.004) {
    fronts.rainShown = rain;
    el('rain').style.opacity = rain.toFixed(3);
    // The rain is a running CSS animation; park it entirely in clear air.
    var want = rain > 0.01;
    if (want !== fronts.rainOn) {
      fronts.rainOn = want;
      el('rain').style.display = want ? 'block' : 'none';
    }
  }
}

/* ---- 10. photo challenges ---- */

/* Warning distance, not warning time: at the 3.24.0 airspeeds 3200 m is about 30 s rather than
   the 65 s it was, and from the 700 m ceiling a rower needs most of a minute to sink into a
   window. The gauge is no use if it arrives after the only chance to act on it, so the approach
   is stretched with the speed. */
var PHOTO_APPROACH = 7000;
var PHOTO_LEAD = 300;           // the shutter goes just before the landmark is abeam
var PHOTO_FINAL = 900;          // hold the height over this last stretch for the third star
var PHOTO_SHOW_MS = 7000;
var PHOTO_TARGETS = [];
var photo = { target: null, at: 0, ahead: Infinity, prevAlong: -1, finalM: 0, finalIn: 0,
  pending: false, shotTarget: null, stars: 0, shotAlt: 0, steadyPct: 0, hideAt: 0, count: 0,
  lookAt: 0, lookPlace: null, look: { heading: 0, pitch: 0, w: 0 }, gaugeOn: -1, winShown: '',
  youShown: -1, hitShown: '' };

/**
 * Metres along the route nearest a point, by projecting onto each leg.
 *
 * <p>alongOf() above samples the whole route every 500 m, which is fine for the nine cliffs and
 * three postcards that use it at boot. Forty-six places is another matter, so this does the same
 * job analytically: over tens of kilometres a leg is straight enough to project onto flat, and a
 * route landmark lands exactly on its own leg start.
 */
function alongOfFast(lat, lon) {
  var best = 0;
  var bestD = Infinity;
  for (var i = 0; i < LEGS.length; i++) {
    var a = LEGS[i].from;
    var b = LEGS[i].to;
    var kx = Math.cos(toRad((a.lat + b.lat) / 2));   // degrees of longitude are shorter up here
    var dx = (b.lon - a.lon) * kx;
    var dy = b.lat - a.lat;
    var px = (lon - a.lon) * kx;
    var py = lat - a.lat;
    var len2 = dx * dx + dy * dy;
    var t = len2 > 0 ? clamp((px * dx + py * dy) / len2, 0, 1) : 0;
    var ex = px - dx * t;
    var ey = py - dy * t;
    var d = ex * ex + ey * ey;
    if (d < bestD) {
      bestD = d;
      best = LEGS[i].start + LEGS[i].length * t;
    }
  }
  return best;
}

/**
 * Every named place on the coast is a challenge, not just the leg destinations.
 *
 * <p>The legs are 36 km apart, which at the 3.24.0 airspeeds (~390 km/h at a typical pace) is
 * about five and a half minutes - it was twelve at 180 km/h. The towns in between halve that
 * again, which is about the spacing of the weather fronts.
 */
function buildPhotoTargets() {
  var taken = [];
  for (var j = 0; j < POSTCARDS.length; j++) {
    taken.push(POSTCARDS[j].shotAt);
  }
  var found = [];
  for (var i = 0; i < PLACES.length; i++) {
    var at = alongOfFast(PLACES[i].lat, PLACES[i].lon);
    if (at < 3000) {
      continue;   // no challenge before take-off
    }
    // Five different windows around the route, deterministic so a place always asks the same
    // height every time you fly past it. They run 180-660 m: a rower holding their typical power
    // sits at the 700 m ceiling, so every one of them is reached by easing off on the approach
    // and none of them needs a dive to the deck.
    var lo = 180 + ((i * 137) % 5) * 70;
    found.push({ place: PLACES[i], at: Math.max(0, at - PHOTO_LEAD), lo: lo, hi: lo + 200 });
  }
  found.sort(function (a, b) { return a.at - b.at; });
  for (var k = 0; k < found.length; k++) {
    var clash = false;
    for (var t = 0; t < taken.length; t++) {
      // A postcard stop already owns its landmark, and two approaches cannot overlap.
      if (Math.abs(taken[t] - found[k].at) < 4000) {
        clash = true;
      }
    }
    if (!clash) {
      taken.push(found[k].at);
      PHOTO_TARGETS.push(found[k]);
    }
  }
}

function photoTopPct(metres) {
  return clamp((760 - metres) / 720, 0, 1) * 100;
}

function updatePhoto() {
  if (photo.hideAt && Date.now() > photo.hideAt) {
    photo.hideAt = 0;
    el('photo').className = 'overlay hidden';
  }
  if (state.hovering || PHOTO_TARGETS.length === 0 || !state.started) {
    photo.prevAlong = state.along;
    setGauge(false);
    return;
  }
  var lap = Math.floor(state.along / routeLength);
  var nearest = null;
  var nearestAt = 0;
  var nearestAhead = Infinity;
  for (var i = 0; i < PHOTO_TARGETS.length; i++) {
    var t = PHOTO_TARGETS[i];
    var at = lap * routeLength + t.at;
    if (photo.prevAlong >= 0 && photo.prevAlong < at && state.along >= at) {
      shootPhoto(t);
    }
    var ahead = at > state.along ? at - state.along : at + routeLength - state.along;
    if (ahead < nearestAhead) {
      nearestAhead = ahead;
      nearest = t;
      nearestAt = at > state.along ? at : at + routeLength;
    }
  }
  if (nearest !== photo.target) {
    photo.target = nearest;
    photo.finalM = 0;
    photo.finalIn = 0;
  }
  photo.at = nearestAt;
  photo.ahead = nearestAhead;

  var inWin = nearest && state.agl >= nearest.lo && state.agl <= nearest.hi;
  var moved = Math.max(0, state.along - photo.prevAlong);
  if (nearest && nearestAhead < PHOTO_FINAL) {
    photo.finalM += moved;
    if (inWin) {
      photo.finalIn += moved;
    }
  }
  if (nearest && nearestAhead < 900) {
    photo.lookAt = nearestAt;
    photo.lookPlace = nearest.place;
  }
  photo.prevAlong = state.along;

  if (!nearest || nearestAhead > PHOTO_APPROACH) {
    setGauge(false);
    return;
  }
  setGauge(true);
  var win = photoTopPct(nearest.hi).toFixed(1) + '|'
    + (photoTopPct(nearest.lo) - photoTopPct(nearest.hi)).toFixed(1);
  if (win !== photo.winShown) {
    photo.winShown = win;
    el('frameWin').style.top = photoTopPct(nearest.hi).toFixed(1) + '%';
    el('frameWin').style.height =
      (photoTopPct(nearest.lo) - photoTopPct(nearest.hi)).toFixed(1) + '%';
  }
  var you = photoTopPct(state.agl);
  if (Math.abs(you - photo.youShown) > 0.3) {
    photo.youShown = you;
    el('frameYou').style.top = you.toFixed(1) + '%';
  }
  var hit = inWin ? 'hit' : '';
  if (hit !== photo.hitShown) {
    photo.hitShown = hit;
    el('frameYou').className = hit;
  }
  setText('frameCap', nearestAhead < 400 ? 'SHUTTER'
    : nearest.lo + '–' + nearest.hi + ' m');
}

function setGauge(on) {
  var v = on ? 1 : 0;
  if (v !== photo.gaugeOn) {
    photo.gaugeOn = v;
    el('frame').style.opacity = on ? '1' : '0';
  }
}

function shootPhoto(target) {
  photo.shotTarget = target;
  photo.shotAlt = state.agl;
  var inWin = state.agl >= target.lo && state.agl <= target.hi;
  var steady = photo.finalM > 100 ? photo.finalIn / photo.finalM : 0;
  photo.steadyPct = Math.round(steady * 100);
  var stars = 1;
  if (inWin && state.fog < 0.5) {
    stars++;
    if (steady > 0.75 && state.fog < 0.4) {
      stars++;
    }
  }
  photo.stars = stars;
  photo.pending = true;   // taken in the next postRender, while the frame is still in the buffer
}

/** A glance at the landmark either side of the shutter. Reuses one object: no per-frame garbage. */
function photoLook(here, altitude) {
  if (!photo.lookPlace || photo.lookAt <= 0 || pc.active) {
    return null;
  }
  var d = photo.lookAt - state.along;
  var w = 1 - clamp(Math.abs(d) / 750, 0, 1);
  if (w <= 0.02) {
    return null;
  }
  var place = photo.lookPlace;
  var dist = Math.max(200, haversine(here, place));
  photo.look.heading = bearing(here, place);
  photo.look.pitch = clamp(-toDeg(Math.atan2(altitude - 20, dist)), -35, 5);
  photo.look.w = w * 0.8;   // a glance, not the postcard's full turn of the head
  return photo.look;
}

/** Prints the frame Cesium just drew. Runs inside scene.postRender, like the postcards. */
function capturePhoto() {
  photo.pending = false;
  var target = photo.shotTarget;
  if (!target) {
    return;
  }
  var cv = el('phCanvas');
  var ctx = cv.getContext('2d');
  try {
    var src = viewer.scene.canvas;
    var aspect = cv.width / cv.height;
    var cw = src.width;
    var ch = cw / aspect;
    if (ch > src.height) {
      ch = src.height;
      cw = ch * aspect;
    }
    ctx.drawImage(src, (src.width - cw) / 2, Math.max(0, (src.height - ch) * 0.45), cw, ch,
      0, 0, cv.width, cv.height);
  } catch (e) {
    var sky = ctx.createLinearGradient(0, 0, 0, cv.height);
    sky.addColorStop(0, '#8fb3cf');
    sky.addColorStop(1, '#3f6a86');
    ctx.fillStyle = sky;
    ctx.fillRect(0, 0, cv.width, cv.height);
  }
  if (fronts.strength > 0.05 || state.fog > 0.05) {
    ctx.fillStyle = 'rgba(214,224,234,' + clamp(state.fog * 0.8 + fronts.strength * 0.4, 0, 0.9).toFixed(2) + ')';
    ctx.fillRect(0, 0, cv.width, cv.height);
  }
  photo.count++;
  el('phTitle').textContent = target.place.name;
  el('phStars').textContent = starText(photo.stars);
  el('phLine').textContent = Math.round(photo.shotAlt) + ' m · window ' + target.lo
    + '–' + target.hi + ' m · ' + photo.steadyPct + '% framed';
  el('photo').className = 'overlay';
  photo.hideAt = Date.now() + PHOTO_SHOW_MS;
  shutterFlash();
  stampLandmark(target.place, photo.stars, 'PHOTO ' + starText(photo.stars));
}

function shutterFlash() {
  var f = el('flash');
  f.className = 'overlay';
  var restart = f.offsetWidth;
  if (restart >= 0) {
    f.className = 'overlay go';
  }
}

/* ---- what the five of them save on the way out ---- */

function saveFlightRecords() {
  saveLegTimes();
  recordMigration();
  if (bridge && bridge.recordFronts && fronts.count > 0) {
    try {
      bridge.recordFronts(fronts.count);
    } catch (e) { /* a record is a nicety */ }
  }
}

function initFlightExtras() {
  loadLegTimes();
  loadPassport();
  loadMigration();
  buildFronts();
  buildPhotoTargets();   // after initPostcards: it skips the landmarks that have a postcard
  el('migCard').addEventListener('click', tapMigration);
  el('passCard').addEventListener('click', togglePassport);
  el('passport').addEventListener('click', closePassport);
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
    } else if (photo.target && photo.ahead < 1000) {
      var shot = state.agl >= photo.target.lo && state.agl <= photo.target.hi;
      text = 'PHOTO · ' + photo.target.place.name + ' in ' + Math.round(photo.ahead) + ' m · '
        + photo.target.lo + '–' + photo.target.hi + ' m'
        + (shot ? ' ✔ hold it' : state.agl < photo.target.lo ? ' ↑ climb' : ' ↓ ease off');
      cls = shot ? 'card good' : 'card';
    } else if (fronts.inside && fronts.strength > 0.1) {
      text = 'PUSH THROUGH · ' + fronts.inside.name + ' · '
        + frontWatts(Math.max(40, state.typicalWatts)) + ' W gets you out · '
        + Math.round(fronts.push * 100) + '%';
      cls = 'front';
    } else if (mig.armed && mig.flying && !mig.lost && mig.gap > 300) {
      text = 'THE LEADER IS PULLING AWAY · ' + Math.round(mig.gap) + ' m · '
        + Math.round(mig.speed * 3.6) + ' km/h';
      cls = 'lift';
    } else if (mig.armed && mig.flying && !mig.lost && mig.gap < -300) {
      text = 'YOU ARE AHEAD OF THE LEADER · ' + Math.round(-mig.gap) + ' m · ease back into its draft';
      cls = 'lift';
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
    } else if (fronts.next && fronts.nextIn < 1800) {
      text = fronts.next.name.charAt(0).toUpperCase() + fronts.next.name.substring(1) + ' in '
        + (fronts.nextIn / 1000).toFixed(1) + ' km — wind up to '
        + frontWatts(Math.max(40, state.typicalWatts)) + ' W';
      cls = 'front';
    } else if (fogState.aheadIn < 2000) {
      text = 'Fog bank ahead in ' + (fogState.aheadIn / 1000).toFixed(1) + ' km — stay above '
        + Math.round(fogState.aheadTop) + ' m';
      cls = 'fog';
    } else if (photo.target && photo.ahead < PHOTO_APPROACH) {
      text = 'Photo challenge · ' + photo.target.place.name + ' in '
        + (photo.ahead / 1000).toFixed(1) + ' km · be at ' + photo.target.lo + '–'
        + photo.target.hi + ' m';
      cls = 'card';
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
  initFlightExtras();
  if (viewer) {
    viewer.scene.postRender.addEventListener(function () {
      if (pc.pending) {
        capturePostcard();
      }
      if (photo.pending) {
        capturePhoto();
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
