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

  function dbg(t) {
    try { if (window.TubeBridge) window.TubeBridge.postMessage('dbg|' + String(t).slice(0, 200)); } catch (e) {}
  }

  /* ---- keep the two pages independent ----
     Both WebViews share the same site storage. YouTube remembers the last chosen quality there ("yt-player-quality"),
     so a Shorts quality set in the browse page leaked into the regular player (and the other way round).
     The app applies the right quality itself for every video, so YouTube's own memory is switched off. */
  try {
    var origSetItem = Storage.prototype.setItem;
    Storage.prototype.setItem = function (k) {
      if (k === 'yt-player-quality') return;
      return origSetItem.apply(this, arguments);
    };
    localStorage.removeItem('yt-player-quality'); // a value written by an earlier version
  } catch (e) {}

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

  /* ---- browse page: Shorts play here, so the default Shorts quality is applied here (speed is kept at 1x) ---- */
  if (role === 'browse') {
    var shortsAppliedId = '';
    var shortsId = function () {
      var m = /^\/shorts\/([^\/?#]+)/.exec(location.pathname);
      return m ? m[1] : '';
    };
    var applyShorts = function (explicit) {
      try {
        var id = shortsId();
        if (id === '') return;
        if (!explicit && id === shortsAppliedId) return;
        var q = window.__tubeShortsQuality || 'auto';
        var players = document.querySelectorAll('.html5-video-player');
        var n = 0;
        for (var i = 0; i < players.length; i++) {
          var pl = players[i];
          if (!pl.setPlaybackQualityRange) continue;
          n++;
          if (q === 'auto') {
            pl.setPlaybackQualityRange('tiny', 'highres');
          } else {
            pl.setPlaybackQualityRange(q, q);
            if (pl.setPlaybackQuality) pl.setPlaybackQuality(q);
          }
        }
        var vids = document.querySelectorAll('video');
        for (var k = 0; k < vids.length; k++) { if (vids[k].playbackRate !== 1) vids[k].playbackRate = 1; }
        if (n > 0) {
          shortsAppliedId = id;
          if (!explicit) {
            dbg('shorts: quality ' + q + ' on ' + n + ' player(s)');
            setTimeout(function () { applyShorts(true); }, 1500); // YouTube sometimes resets it
          }
        }
      } catch (e) {}
    };
    window.__tubeApplyPrefs = function () { shortsAppliedId = ''; applyShorts(true); };
    ['playing', 'loadedmetadata'].forEach(function (t) {
      document.addEventListener(t, function () { applyShorts(false); }, true);
    });
    setInterval(function () { applyShorts(false); }, 400); // cheap: returns at once when not on a Short or already applied
    return;
  }

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
        p.setPlaybackQualityRange('tiny', 'highres'); // full range = YouTube chooses; also clears a stale fixed quality
      } else {
        p.setPlaybackQualityRange(q, q);
        if (p.setPlaybackQuality) p.setPlaybackQuality(q);
      }
      qualAppliedId = id;
      if (!explicit) {
        var cur = '?';
        try { if (p.getPlaybackQuality) cur = p.getPlaybackQuality(); } catch (e2) {}
        dbg('quality ' + q + ' set ' + Math.round(performance.now()) + 'ms after page start (player was at ' + cur + ')');
        setTimeout(function () { applyQuality(true); }, 2000); // YouTube sometimes resets it
      }
    } catch (e) {}
  }

  /* ---- player: apply the quality EARLY ----
     YouTube starts every video at its own quality (about 360p) and the player API only exists a moment after the page
     starts. Waiting for the first media event or the 3 s timer meant the first seconds played at the wrong quality.
     So after page start and after every new video, the player is polled every 150 ms (for at most 20 s) and the quality
     is set the moment the API is there. The poll stops by itself once the quality is applied. */
  var qFastTimer = 0, qFastUntil = 0;
  function kickQuality() {
    qFastUntil = Date.now() + 20000;
    if (qFastTimer) return;
    qFastTimer = setInterval(function () {
      try {
        var id = curId();
        if (Date.now() > qFastUntil || (id !== '' && id === qualAppliedId)) {
          clearInterval(qFastTimer);
          qFastTimer = 0;
          return;
        }
        applyQuality(false);
      } catch (e) {}
    }, 150);
  }

  /* ---- player: default playback speed (window.__tubeSpeed, e.g. "1.5"), once per video so a manual change sticks ---- */
  var speedAppliedId = '';
  function applySpeed(explicit) {
    try {
      var sp = parseFloat(window.__tubeSpeed || '1');
      if (!(sp > 0)) sp = 1;
      var id = curId();
      if (!explicit && (id === '' || id === speedAppliedId)) return;
      var v = document.querySelector('video');
      if (!v) return;
      var p = document.querySelector('.html5-video-player') || document.getElementById('movie_player');
      if (p && p.setPlaybackRate) p.setPlaybackRate(sp);
      v.playbackRate = sp;
      speedAppliedId = id;
      if (!explicit) {
        setTimeout(function () { applySpeed(true); }, 700); // YouTube sometimes resets it right after starting
        setTimeout(function () { applySpeed(true); }, 2000);
      }
    } catch (e) {}
  }

  // The app calls this after any change in its settings dialog.
  window.__tubeApplyPrefs = function () {
    qualAppliedId = '';
    speedAppliedId = '';
    applyQuality(true);
    applySpeed(true);
    kickQuality();
  };
  kickQuality(); // this page just started

  /* ---- player: start every video with sound (YouTube starts muted when the page has no user gesture) ---- */
  var unmutedId = '';
  function unmuteNow() {
    try {
      var v = document.querySelector('video');
      var p = document.querySelector('.html5-video-player') || document.getElementById('movie_player');
      if (!v) return;
      if (v.muted || (p && p.isMuted && p.isMuted())) {
        if (p && p.unMute) p.unMute();
        v.muted = false;
      }
      if (p && p.getVolume && p.setVolume && p.getVolume() === 0) p.setVolume(100);
    } catch (e) {}
  }
  function ensureUnmuted() {
    try {
      var id = curId();
      if (id === '' || id === unmutedId) return;
      var v = document.querySelector('video');
      if (!v || v.paused) return; // wait until it really plays
      unmutedId = id;
      unmuteNow();
      setTimeout(unmuteNow, 700);  // YouTube sometimes mutes again right after starting
      setTimeout(unmuteNow, 2000);
    } catch (e) {}
  }

  /* ---- player: "zoom to fill" in fullscreen (pinch out in the app): scale the video until it covers the screen ---- */
  var zoomStyle = null, zoomEl = null;
  function clearZoom() {
    try {
      if (zoomEl) { zoomEl.classList.remove('__tube_zoom'); zoomEl = null; }
      if (zoomStyle) zoomStyle.textContent = '';
    } catch (e) {}
  }
  // The scale lives in OUR stylesheet with !important, so it beats any inline style YouTube rewrites on the element.
  function applyZoom(el, s) {
    if (!zoomStyle) {
      zoomStyle = document.createElement('style');
      zoomStyle.id = '__tube_zoom_css';
      (document.head || document.documentElement).appendChild(zoomStyle);
    }
    zoomStyle.textContent = '.__tube_zoom{transform:scale(' + s + ') !important;transform-origin:50% 50% !important;}';
    if (zoomEl && zoomEl !== el) zoomEl.classList.remove('__tube_zoom');
    el.classList.add('__tube_zoom');
    zoomEl = el;
  }
  function setZoom(on) {
    try {
      var v = document.querySelector('video');
      clearZoom();
      if (!on) return;
      if (!v) { dbg('zoom: no video'); return; }
      var fs = document.fullscreenElement || document.webkitFullscreenElement;
      var boxW, boxH;
      if (fs) {
        boxW = window.innerWidth; boxH = window.innerHeight; // in fullscreen the page box is the screen
      } else {
        var p = document.querySelector('.html5-video-player') || document.getElementById('movie_player');
        if (!p) { dbg('zoom: no box'); return; }
        var pr = p.getBoundingClientRect();
        boxW = pr.width; boxH = pr.height;
      }
      var vr = v.getBoundingClientRect(); // measured without our scale (cleared above)
      if (!vr.width || !vr.height) { dbg('zoom: video has no size'); return; }
      // The <video> element may fill the whole screen while the picture inside is letterboxed (object-fit: contain),
      // so work out the size of the PICTURE from the video's own dimensions.
      var vw = v.videoWidth, vh = v.videoHeight;
      var cw = vr.width, ch = vr.height;
      if (vw > 0 && vh > 0) {
        var fit = Math.min(vr.width / vw, vr.height / vh);
        cw = vw * fit; ch = vh * fit;
      }
      var s = Math.max(boxW / cw, boxH / ch);
      dbg('zoom: fs=' + !!fs + ' box=' + Math.round(boxW) + 'x' + Math.round(boxH) +
        ' element=' + Math.round(vr.width) + 'x' + Math.round(vr.height) +
        ' picture=' + Math.round(cw) + 'x' + Math.round(ch) + ' scale=' + s.toFixed(2));
      if (!(s > 1.01 && s < 3)) return;
      // First choice: scale the video's container (YouTube rewrites the video element's own style).
      var target = v.parentElement || v;
      applyZoom(target, s);
      var after = v.getBoundingClientRect().width;
      if (after < vr.width * (1 + (s - 1) * 0.5)) {
        applyZoom(v, s); // container scaling had no effect: scale the video element itself
        after = v.getBoundingClientRect().width;
        dbg('zoom: container ineffective, video element used, width ' + Math.round(vr.width) + ' -> ' + Math.round(after));
      } else {
        dbg('zoom: container ' + target.tagName + '.' + String(target.className).slice(0, 30) +
          ' width ' + Math.round(vr.width) + ' -> ' + Math.round(after));
      }
      setTimeout(function () {
        try {
          dbg('zoom later: width=' + Math.round(v.getBoundingClientRect().width) +
            ' class=' + !!(zoomEl && zoomEl.classList.contains('__tube_zoom')));
        } catch (e) {}
      }, 500);
    } catch (e) { dbg('zoom error: ' + e); }
  }

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
  var lastDir = '', dirUrl = '', dirDone = false, dirTries = 0, dirLastAt = 0;
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
  // Measuring touches every link/button of the page (forces layout), so it is rate-limited, stops as soon as the
  // layout measurement succeeded, and starts again only when the page URL changes.
  function reportDir() {
    try {
      if (!window.TubeBridge) return;
      var href = location.href;
      if (href !== dirUrl) { dirUrl = href; dirDone = false; dirTries = 0; }
      if (dirDone) return;
      var nowMs = Date.now();
      if (nowMs - dirLastAt < 1500) return;
      dirLastAt = nowMs;
      if (++dirTries > 20) { dirDone = true; return; }
      var r = detectRtl();
      if (r.how === 'layout') dirDone = true;
      var msg = 'dir|' + (r.rtl ? 'rtl' : 'ltr') + '|' + r.how;
      if (msg !== lastDir) { lastDir = msg; window.TubeBridge.postMessage(msg); }
    } catch (e) {}
  }
  document.addEventListener('DOMContentLoaded', reportDir);

  var qPlayingId = '';

  /* ---- player: diagnostic for the "gap between the top bar and the video" glitch (seen once, cause unknown) ----
     Logs the numbers (at most once a minute) and nudges the layout with a resize event. At scroll 0 the video must start right
     under the top bar, so a gap there is never normal. The log tells whether the page itself is wrong or only the video layer. */
  var lastGapLog = 0;
  function checkGap() {
    try {
      var v = document.querySelector('video');
      var h = document.querySelector('ytm-mobile-topbar-renderer, header');
      if (!v || !h) return;
      var hr = h.getBoundingClientRect(), vr = v.getBoundingClientRect();
      if (hr.height < 20 || hr.bottom < 20 || vr.height < 20) return; // no visible top bar: nothing to compare
      var vv = window.visualViewport;
      var gap = vr.top - hr.bottom;
      var off = vv ? vv.offsetTop : 0;
      if ((gap > 24 && (window.scrollY || 0) < 2) || Math.abs(off) > 1) {
        var now = Date.now();
        if (now - lastGapLog < 60000) return;
        lastGapLog = now;
        dbg('gap? video.top=' + Math.round(vr.top) + ' bar.bottom=' + Math.round(hr.bottom) + ' scrollY=' + Math.round(window.scrollY || 0) +
          ' vvOffsetTop=' + Math.round(off) + ' inner=' + window.innerWidth + 'x' + window.innerHeight);
        window.dispatchEvent(new Event('resize'));
      }
    } catch (e) {}
  }
  // Media events instead of constant polling: light on CPU and battery.
  ['play', 'playing', 'pause', 'ended', 'emptied', 'loadstart', 'loadedmetadata', 'durationchange', 'seeked', 'ratechange']
    .forEach(function (t) {
      document.addEventListener(t, function (ev) {
        if (ev && (ev.type === 'loadstart' || ev.type === 'emptied')) kickQuality(); // a new video is starting
        if (ev && ev.type === 'playing' && qPlayingId !== curId()) { qPlayingId = curId(); applyQuality(true); } // once more when it really starts
        report(true); skipAd(); applyQuality(false); applySpeed(false); ensureUnmuted(); reportDir();
      }, true);
    });
  setInterval(function () {
    var cid = curId();
    if (cid !== '' && cid !== qualAppliedId) kickQuality();
    checkGap();
    report(false); applyQuality(false); applySpeed(false); ensureUnmuted(); reportDir();
  }, 3000); // safety net: title changes, missed events
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
      if (c === 'zoom:1') { setZoom(true); return; }
      if (c === 'zoom:0') { setZoom(false); return; }
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
