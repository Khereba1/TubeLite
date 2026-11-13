/* TubeLite page script. Local file, bundled in the APK (nothing is loaded from the internet).
   Runs at document start on m.youtube.com / www.youtube.com only.
   1) keeps YouTube from pausing when the app is in the background
   2) strips ad data from YouTube's responses, hides ad UI, skips ads that still show
   3) reports play/pause + title to the app (TubeBridge) and accepts play/pause commands. */
(function () {
  'use strict';
  if (window.__tubeInjected) return;
  window.__tubeInjected = true;

  /* ---- 1) always "visible" ---- */
  try {
    var def = function (prop, val) {
      try {
        Object.defineProperty(document, prop, { get: function () { return val; }, configurable: true });
      } catch (e) {}
    };
    def('hidden', false);
    def('webkitHidden', false);
    def('visibilityState', 'visible');
    def('webkitVisibilityState', 'visible');
    var stopEvt = function (e) { e.stopImmediatePropagation(); };
    document.addEventListener('visibilitychange', stopEvt, true);
    document.addEventListener('webkitvisibilitychange', stopEvt, true);
  } catch (e) {}

  /* ---- 2a) remove ad data from responses ---- */
  var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots'];
  function clean(o, depth) {
    if (!o || typeof o !== 'object' || depth > 4) return;
    for (var i = 0; i < AD_KEYS.length; i++) {
      if (AD_KEYS[i] in o) { try { delete o[AD_KEYS[i]]; } catch (e) {} }
    }
    if (o.playerResponse) clean(o.playerResponse, depth + 1);
    if (o.response) clean(o.response, depth + 1);
  }

  try {
    var origParse = JSON.parse;
    JSON.parse = function () {
      var r = origParse.apply(this, arguments);
      try { clean(r, 0); } catch (e) {}
      return r;
    };
  } catch (e) {}

  try {
    if (window.Response && Response.prototype.json) {
      var origJson = Response.prototype.json;
      Response.prototype.json = function () {
        return origJson.apply(this, arguments).then(function (r) {
          try { clean(r, 0); } catch (e) {}
          return r;
        });
      };
    }
  } catch (e) {}

  try {
    var ipr;
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      get: function () { return ipr; },
      set: function (v) { try { clean(v, 0); } catch (e) {} ipr = v; }
    });
  } catch (e) {}

  /* ---- 2b) hide ad UI ---- */
  var CSS = [
    'ytm-promoted-sparkles-web-renderer', 'ytm-promoted-video-renderer', 'ytm-companion-slot',
    'ad-slot-renderer', 'ytm-statement-banner-renderer', 'ytm-brand-video-singleton-renderer',
    'ytm-in-feed-ad-layout-renderer', '#player-ads', '.video-ads', '.ytp-ad-overlay-container',
    '.ytp-ad-image-overlay'
  ].join(',') + '{display:none !important;}';
  function addStyle() {
    try {
      if (document.getElementById('__tube_css')) return;
      var s = document.createElement('style');
      s.id = '__tube_css';
      s.textContent = CSS;
      (document.head || document.documentElement).appendChild(s);
    } catch (e) {}
  }
  addStyle();
  document.addEventListener('DOMContentLoaded', addStyle);

  /* ---- 2c) skip ads that still get through ---- */
  function skipAd() {
    try {
      var p = document.querySelector('.html5-video-player') || document.getElementById('movie_player');
      if (!p) return;
      if (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')) {
        var v = document.querySelector('video');
        if (v && isFinite(v.duration) && v.duration > 0) { v.currentTime = v.duration; }
        var b = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
        if (b) b.click();
      }
    } catch (e) {}
  }

  /* ---- 3) app bridge ---- */
  var lastState = '';
  function report() {
    try {
      if (!window.TubeBridge) return;
      var v = document.querySelector('video');
      var s;
      if (!v) {
        s = '0|';
      } else {
        var playing = !v.paused && !v.ended && v.readyState > 2;
        var title = (document.title || '').replace(/ - YouTube$/, '');
        s = (playing ? '1|' : '0|') + title;
      }
      if (s !== lastState) { lastState = s; window.TubeBridge.postMessage(s); }
    } catch (e) {}
  }
  // Media events instead of constant polling: much lighter on CPU and battery.
  function onMedia() { report(); skipAd(); }
  ['play', 'playing', 'pause', 'ended', 'emptied', 'loadstart'].forEach(function (t) {
    document.addEventListener(t, onMedia, true);
  });
  setInterval(report, 3000); // safety net: title changes, missed events
  setInterval(skipAd, 1000); // safety net: ads that appear without a media event

  window.__tubeCmd = function (c) {
    try {
      var v = document.querySelector('video');
      if (!v) return;
      if (c === 'play') v.play();
      else if (c === 'pause') v.pause();
      else if (v.paused) v.play();
      else v.pause();
    } catch (e) {}
  };
})();
