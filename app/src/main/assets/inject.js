/* TubeLite page script. Local file, bundled in the APK (nothing is loaded from the internet).
   Runs at document start on m.youtube.com / www.youtube.com only, in two kinds of pages:
     role "browse": the page you scroll/search in. Video links are handed to the app (it opens the player page).
     role "player": the watch page. Keeps playing in the background, reports state, accepts commands.
   Common: strips ad data from YouTube's responses, hides ad UI. */
(function () {
  'use strict';
  if (window.__tubeInjected) return;
  window.__tubeInjected = true;
  var role = window.__tubeRole || 'browse';

  /* ---- player only: always "visible" so YouTube does not pause in the background ---- */
  if (role === 'player') {
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
  }

  /* ---- remove ad data from responses ---- */
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

  /* ---- hide ad UI, remove tap flash ---- */
  var CSS = [
    'ytm-promoted-sparkles-web-renderer', 'ytm-promoted-video-renderer', 'ytm-companion-slot',
    'ad-slot-renderer', 'ytm-statement-banner-renderer', 'ytm-brand-video-singleton-renderer',
    'ytm-in-feed-ad-layout-renderer', '#player-ads', '.video-ads', '.ytp-ad-overlay-container',
    '.ytp-ad-image-overlay'
  ].join(',') + '{display:none !important;}' +
    '*{-webkit-tap-highlight-color:transparent;}html{touch-action:manipulation;}';
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

  /* ---- link routing between the two pages (needs the app bridge) ----
     browse page: a click on a video link -> app opens it in the player page.
     player page: a click on any other YouTube link (channel, search...) -> app shows it in the browse page. */
  document.addEventListener('click', function (e) {
    try {
      if (!window.TubeBridge) return;
      var t = e.target;
      var a = t && t.closest ? t.closest('a[href]') : null;
      if (!a) return;
      var u = new URL(a.href, location.href);
      if (u.hostname !== 'm.youtube.com' && u.hostname !== 'www.youtube.com' && u.hostname !== 'youtube.com') return;
      var isWatch = u.pathname === '/watch';
      if (role === 'browse' && isWatch) {
        e.preventDefault();
        e.stopImmediatePropagation();
        window.TubeBridge.postMessage('open|' + u.href);
      } else if (role === 'player' && !isWatch) {
        e.preventDefault();
        e.stopImmediatePropagation();
        window.TubeBridge.postMessage('browse|' + u.href);
      }
    } catch (err) {}
  }, true);

  if (role !== 'player') return;

  /* ---- player: fixed video quality chosen in the app (window.__tubeQuality = auto | small | medium | large | hd720 | hd1080) ---- */
  var qualAppliedId = '';
  function curId() {
    try { return new URL(location.href).searchParams.get('v') || ''; } catch (e) { return ''; }
  }
  function applyQuality(explicit) {
    try {
      var q = window.__tubeQuality || 'auto';
      var p = document.querySelector('.html5-video-player') || document.getElementById('movie_player');
      if (!p || !p.setPlaybackQualityRange) return;
      var id = curId();
      if (!explicit && (id === '' || id === qualAppliedId)) return;
      if (q === 'auto') {
        if (explicit) p.setPlaybackQualityRange('tiny', 'highres');
      } else {
        p.setPlaybackQualityRange(q, q);
        if (p.setPlaybackQuality) p.setPlaybackQuality(q);
      }
      qualAppliedId = id;
      if (!explicit) setTimeout(function () { applyQuality(true); }, 2000); // YouTube sometimes resets it
    } catch (e) {}
  }
  window.__tubeSetQuality = function (q) {
    window.__tubeQuality = q;
    applyQuality(true);
  };

  /* ---- player: skip ads that still get through ---- */
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

  /* ---- player: report state to the app ----
     message: "<1|0>|<videoId>|<positionMs>|<durationMs>|<title>" */
  var lastKey = '';
  function report(force) {
    try {
      if (!window.TubeBridge) return;
      var v = document.querySelector('video');
      var s, key;
      if (!v) {
        s = '0||0|0|';
        key = 'none';
      } else {
        var playing = !v.paused && !v.ended && v.readyState > 2;
        var title = (document.title || '').replace(/ - YouTube$/, '');
        var id = '';
        try { id = new URL(location.href).searchParams.get('v') || ''; } catch (e) {}
        var pos = Math.round((v.currentTime || 0) * 1000);
        var dur = isFinite(v.duration) ? Math.round(v.duration * 1000) : 0;
        s = (playing ? '1|' : '0|') + id + '|' + pos + '|' + dur + '|' + title;
        key = (playing ? '1|' : '0|') + id + '|' + dur + '|' + title;
      }
      if (!force && key === lastKey) return;
      lastKey = key;
      window.TubeBridge.postMessage(s);
    } catch (e) {}
  }

  /* ---- player: report whether the page is mirrored (Arabic UI): "dir|rtl|how" / "dir|ltr|how" ----
     The app uses it to put its quality button over the site's "more" button (right on LTR pages, left on RTL pages).
     Layout is checked first (the YouTube logo is the widest link in the 64px top strip; on the right half = mirrored),
     then the page language, then dir attributes. "how" only helps debugging (it goes to the in-app log). */
  var lastDir = '';
  function detectRtl() {
    try {
      var w = window.innerWidth || document.documentElement.clientWidth || 0;
      var els = document.querySelectorAll('a, button, [role="button"], ytm-home-logo');
      var bestW = 0, bestC = -1;
      for (var i = 0; i < els.length; i++) {
        var r = els[i].getBoundingClientRect();
        if (r.width <= 0 || r.height <= 0 || r.top < -2 || r.bottom > 64 || r.width > w * 0.6) continue;
        if (r.width > bestW) { bestW = r.width; bestC = r.left + r.width / 2; }
      }
      if (w > 0 && bestW >= 60) return { rtl: bestC > w / 2, how: 'layout' };
    } catch (e) {}
    try {
      var lang = (document.documentElement.getAttribute('lang') || '').toLowerCase();
      if (/^(ar|he|fa|ur|ps|sd|ug|yi|dv)(-|_|$)/.test(lang)) return { rtl: true, how: 'lang' };
      var da = (document.documentElement.getAttribute('dir') || (document.body && document.body.getAttribute('dir')) || '').toLowerCase();
      if (da === 'rtl') return { rtl: true, how: 'dir' };
      if (document.body && (getComputedStyle(document.body).direction || '').toLowerCase() === 'rtl') return { rtl: true, how: 'css' };
    } catch (e) {}
    return { rtl: false, how: 'default' };
  }
  function reportDir() {
    try {
      if (!window.TubeBridge) return;
      var r = detectRtl();
      var msg = 'dir|' + (r.rtl ? 'rtl' : 'ltr') + '|' + r.how;
      if (msg !== lastDir) { lastDir = msg; window.TubeBridge.postMessage(msg); }
    } catch (e) {}
  }
  document.addEventListener('DOMContentLoaded', reportDir);

  // Media events instead of constant polling: light on CPU and battery.
  ['play', 'playing', 'pause', 'ended', 'emptied', 'loadstart', 'loadedmetadata', 'durationchange', 'seeked', 'ratechange']
    .forEach(function (t) {
      document.addEventListener(t, function () { report(true); skipAd(); applyQuality(false); reportDir(); }, true);
    });
  setInterval(function () { report(false); applyQuality(false); reportDir(); }, 3000); // safety net: title changes, missed events
  setInterval(skipAd, 1000);        // safety net: ads that appear without a media event

  /* ---- player: commands from the app ---- */
  window.__tubeCmd = function (c) {
    try {
      var v = document.querySelector('video');
      var p = document.querySelector('.html5-video-player') || document.getElementById('movie_player');
      if (c === 'next') {
        if (p && p.nextVideo) p.nextVideo();
        else { var nb = document.querySelector('.ytp-next-button'); if (nb) nb.click(); }
        return;
      }
      if (c === 'prev') {
        if (p && p.previousVideo) p.previousVideo();
        else if (v) v.currentTime = 0;
        return;
      }
      if (c.indexOf('seek:') === 0) {
        if (v) v.currentTime = parseInt(c.substring(5), 10) / 1000;
        return;
      }
      if (!v) return;
      if (c === 'play') v.play();
      else if (c === 'pause') v.pause();
      else if (v.paused) v.play();
      else v.pause();
    } catch (e) {}
  };
})();
