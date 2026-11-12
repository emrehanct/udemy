// EduCast TV — subtitle overlay / altyazı katmanı.
// Takes the lecture's captions in the TV's language if available; otherwise translates the
// English (or any) captions through the app and shows them in large type over the video.
// Dersin altyazısını TV'nin dilinde alır; yoksa İngilizce (veya mevcut) altyazıyı uygulama
// üzerinden çevirir ve videonun üstünde büyük yazıyla gösterir.
(function () {
  if (window.__dtvSub) { window.__dtvSub.kick(); return; }
  // mode: 'auto' = yalnızca ders TV'nin dilinde değilse / only when the lecture is in another language
  //       'on'   = her zaman / always,  'off' = hiç / never (Udemy's own captions are hidden too)
  var S = window.__dtvSub = { mode: 'auto', lang: 'tr' };
  var cbs = {}, seq = 0, cur = null, box = null, span = null, lastText = null;

  window.__dtvSubCb = function (id, ok, data) {
    var f = cbs[id]; delete cbs[id]; if (f) f(ok, data);
  };
  function bridge(method, arg) {
    return new Promise(function (res, rej) {
      var id = 'j' + (++seq);
      cbs[id] = function (ok, data) { if (ok) res(data); else rej(new Error(data)); };
      try { DTVSub[method](id, arg); } catch (e) { delete cbs[id]; rej(e); }
    });
  }
  function status(m) { if (S.mode !== 'off') { try { DTVSub.status(m); } catch (e) {} } }

  // ---------------------------------------------------------------- görünüm
  function ui() {
    if (!document.getElementById('dtv-sub-style')) {
      var st = document.createElement('style');
      st.id = 'dtv-sub-style';
      st.textContent =
        '#dtv-sub{position:fixed!important;left:0;right:0;bottom:6vh;z-index:2147483647!important;' +
        'text-align:center;pointer-events:none;visibility:visible!important}' +
        '#dtv-sub span{display:inline-block;max-width:88vw;background:rgba(0,0,0,.75);color:#fff;' +
        'font-family:sans-serif;font-weight:600;font-size:2.7vw;line-height:1.35;padding:.25em .7em;' +
        'border-radius:.3em;white-space:pre-line;visibility:visible!important}' +
        'html.dtv-sub-on [class*="captions-display--captions-container"],' +
        'html.dtv-sub-on [data-purpose="captions-cue-text"]{display:none!important}';
      (document.head || document.documentElement).appendChild(st);
    }
    if (!box || !box.isConnected) {
      box = document.createElement('div');
      box.id = 'dtv-sub';
      span = document.createElement('span');
      box.appendChild(span);
      document.documentElement.appendChild(box);
    }
  }
  function show(t) {
    if (!t && !box) return;
    if (t === lastText && box && box.isConnected) return;
    lastText = t;
    ui();
    span.textContent = t;
    box.style.display = t ? 'block' : 'none';
  }

  // ---------------------------------------------------------------- Udemy verisi
  function lectureInfo() {
    var m = location.pathname.match(/\/course\/(.+?)\/learn\/lecture\/(\d+)/);
    return m ? { slug: m[1], id: m[2] } : null;
  }
  function authHeaders() {
    var h = { 'Accept': 'application/json, text/plain, */*', 'X-Requested-With': 'XMLHttpRequest' };
    var m = document.cookie.match(/(?:^|;\s*)access_token=([^;]+)/);
    if (m) { h['Authorization'] = 'Bearer ' + m[1]; h['X-Udemy-Authorization'] = 'Bearer ' + m[1]; }
    return h;
  }
  function getJSON(u) {
    return fetch(u, { credentials: 'include', headers: authHeaders() }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    });
  }
  var courseIds = {};
  function courseId(slug) {
    if (courseIds[slug]) return Promise.resolve(courseIds[slug]);
    var d = slug.match(/^draft\/(\d+)$/);   // taslak kurslar: /course/draft/<id>/learn/...
    if (d) return Promise.resolve(d[1]);
    var els = document.querySelectorAll('[data-module-args]');
    for (var i = 0; i < els.length; i++) {
      var m = (els[i].getAttribute('data-module-args') || '').match(/"courseId"\s*:\s*(\d+)/);
      if (m) { courseIds[slug] = m[1]; return Promise.resolve(m[1]); }
    }
    return getJSON('/api-2.0/courses/' + slug + '/?fields[course]=id').then(function (j) {
      courseIds[slug] = j.id; return j.id;
    });
  }
  // Dersin konuşma dili (kurs dili) / the course's spoken language, e.g. "tr_tr"
  var courseLangs = {};
  function courseLang(slug, cid) {
    if (courseLangs[slug] !== undefined) return Promise.resolve(courseLangs[slug]);
    try {
      var c = localStorage.getItem('dtvloc:' + slug);
      if (c !== null) { courseLangs[slug] = c; return Promise.resolve(c); }
    } catch (e) {}
    return getJSON('/api-2.0/courses/' + cid + '/?fields[course]=locale').then(function (j) {
      var l = j && j.locale;
      l = String((l && l.locale) || l || '').toLowerCase();
      courseLangs[slug] = l;
      try { localStorage.setItem('dtvloc:' + slug, l); } catch (e) {}
      return l;
    }, function () { return ''; });
  }
  function captionsFor(cid, lid) {
    return getJSON('/api-2.0/users/me/subscribed-courses/' + cid + '/lectures/' + lid +
      '/?fields[lecture]=asset&fields[asset]=captions').then(function (j) {
      return (j && j.asset && j.asset.captions) || [];
    });
  }
  function pick(caps, lang) {
    var tr = null, en = null, enAuto = null, any = null;
    caps.forEach(function (c) {
      if (!c || !c.url || (c.status !== undefined && c.status !== 1)) return;
      var loc = String(c.locale_id || (c.locale && c.locale.locale) || '').toLowerCase();
      any = any || c;
      if (loc.indexOf(lang) === 0) tr = tr || c;
      else if (loc.indexOf('en') === 0) {
        if (c.source === 'auto') enAuto = enAuto || c; else en = en || c;
      }
    });
    if (tr) return { c: tr, translate: false };
    var s = en || enAuto || any;
    return s ? { c: s, translate: true } : null;
  }
  function fetchVtt(url) {
    return fetch(url).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.text();
    }).catch(function () { return bridge('fetchText', url); });
  }

  // ---------------------------------------------------------------- VTT
  function ts(t) {
    var p = t.trim().split(':'), s = 0;
    for (var i = 0; i < p.length; i++) s = s * 60 + parseFloat(p[i].replace(',', '.'));
    return s;
  }
  function clean(s) {
    return s.replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
      .replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim();
  }
  function parseVtt(txt) {
    var out = [];
    txt.replace(/\r/g, '').split(/\n\s*\n/).forEach(function (b) {
      var lines = b.split('\n'), i = 0;
      while (i < lines.length && lines[i].indexOf('-->') < 0) i++;
      if (i >= lines.length) return;
      var tm = lines[i].split('-->');
      var s = ts(tm[0]), e = ts(tm[1].trim().split(/\s+/)[0]);
      var text = clean(lines.slice(i + 1).join(' '));
      if (text && isFinite(s) && isFinite(e)) out.push([s, e, text]);
    });
    out.sort(function (a, b) { return a[0] - b[0]; });
    return out;
  }
  // Kısa altyazı parçalarını cümlelere birleştirir (çeviri kalitesi için).
  function group(cues) {
    var g = [], c = null;
    cues.forEach(function (q) {
      if (c && q[0] - c[1] > 1.5) { g.push(c); c = null; }
      if (!c) c = [q[0], q[1], q[2]];
      else { c[1] = Math.max(c[1], q[1]); c[2] += ' ' + q[2]; }
      if (/[.?!…]["')\]]?$/.test(c[2]) || c[1] - c[0] > 6.5 || c[2].length > 150) { g.push(c); c = null; }
    });
    if (c) g.push(c);
    return g;
  }

  // ---------------------------------------------------------------- çeviri
  function video() {
    var vs = document.querySelectorAll('video'), v = null, b = 0;
    for (var i = 0; i < vs.length; i++) {
      var r = vs[i].getBoundingClientRect(), s = r.width * r.height;
      if (s >= b) { b = s; v = vs[i]; }
    }
    return v;
  }
  function translateAll(st) {
    var chunks = [], i = 0;
    while (i < st.groups.length) {
      var start = i, len = 0;
      while (i < st.groups.length && (i === start || len + st.groups[i][2].length < 1400)) {
        len += st.groups[i][2].length + 1; i++;
      }
      chunks.push([start, i]);
    }
    // Önce videonun o an oynayan kısmını, sonra ilerisini, en son gerisini çevir.
    var v = video(), t = v ? v.currentTime : 0;
    function dist(ch) {
      var s = st.groups[ch[0]][0], e = st.groups[ch[1] - 1][1];
      return t < s ? s - t : (t > e ? 100000 + (t - e) : 0);
    }
    chunks.sort(function (a, b) { return dist(a) - dist(b); });
    var k = 0, fails = 0;
    function next() {
      if (st !== cur || k >= chunks.length) return Promise.resolve();
      var ch = chunks[k];
      var texts = st.groups.slice(ch[0], ch[1]).map(function (g) { return g[2]; });
      return bridge('translate', JSON.stringify(texts)).then(function (out) {
        var arr = JSON.parse(out);
        for (var j = 0; j < arr.length && ch[0] + j < ch[1]; j++) st.tr[ch[0] + j] = arr[j];
        k++; fails = 0;
        return next();
      }, function (e) {
        if (++fails > 3) throw e;
        return new Promise(function (r) { setTimeout(r, 4000 * fails); }).then(next);
      });
    }
    return next();
  }

  function load(info) {
    var st = cur = { id: info.id, mode: S.mode, groups: null, tr: [], dead: false, same: false };
    if (S.mode === 'off') return;
    var key = 'dtvsub2:' + S.lang + ':' + info.id;
    function save() {
      try { localStorage.setItem(key, JSON.stringify({ g: st.groups, t: st.tr })); } catch (e) {}
    }
    courseId(info.slug).then(function (cid) {
      return courseLang(info.slug, cid).then(function (cl) {
        if (st !== cur) return;
        st.same = !!cl && cl.indexOf(S.lang) === 0;
        // Ders zaten TV'nin dilindeyse otomatik modda altyazı gösterme.
        // Lecture already in the TV language: no subtitles in auto mode.
        if (st.same && S.mode === 'auto') return;
        try {
          var c = JSON.parse(localStorage.getItem(key) || 'null');
          if (c && c.g && c.t) { st.groups = c.g; st.tr = c.t; return; }
        } catch (e) {}
        return captionsFor(cid, info.id).then(function (caps) {
          var p = pick(caps, S.lang);
          if (!p) throw new Error('nocap');
          return fetchVtt(p.c.url).then(function (txt) {
            if (st !== cur) return;
            var cues = parseVtt(txt);
            if (!cues.length) throw new Error('nocap');
            if (!p.translate) {
              st.groups = cues;
              st.tr = cues.map(function (q) { return q[2]; });
              save();
              return;
            }
            st.groups = group(cues);
            st.tr = new Array(st.groups.length);
            status('preparing');
            return translateAll(st).then(function () {
              if (st !== cur) return;
              save();
              status('ready');
            });
          });
        });
      });
    }).catch(function (e) {
      if (st !== cur) return;
      st.dead = true;
      status(e && e.message === 'nocap' ? 'nocap' : 'fail');
    });
  }

  // ---------------------------------------------------------------- senkron
  function find(gs, t) {
    var lo = 0, hi = gs.length - 1, r = -1;
    while (lo <= hi) {
      var m = (lo + hi) >> 1;
      if (gs[m][0] <= t) { r = m; lo = m + 1; } else hi = m - 1;
    }
    return r;
  }
  function tick() {
    var info = lectureInfo();
    if (!info) { show(''); document.documentElement.classList.remove('dtv-sub-on'); return; }
    if (!cur || cur.id !== info.id || cur.mode !== S.mode) { show(''); load(info); }
    var skip = S.mode === 'off' || (S.mode === 'auto' && cur.same);
    var ours = !skip && !cur.dead && !!cur.groups;
    // Udemy'nin kendi altyazısı: bizimki görünürken veya altyazı kapalıyken gizlenir.
    // Udemy's own captions are hidden while ours are shown or when subtitles are off.
    document.documentElement.classList.toggle('dtv-sub-on', skip || ours);
    if (!ours) { show(''); return; }
    var v = video();
    if (!v) { show(''); return; }
    var t = v.currentTime, i = find(cur.groups, t), txt = '';
    if (i >= 0 && t <= cur.groups[i][1] + 0.4 && cur.tr[i]) txt = cur.tr[i];
    show(txt);
  }
  S.setMode = function (m) {
    S.mode = (m === 'on' || m === 'off') ? m : 'auto';
    lastText = null;
    tick();
  };
  S.setLang = function (l) { if (l && l !== S.lang) { S.lang = l; cur = null; } };
  S.kick = function () { lastText = null; tick(); };
  setInterval(tick, 200);
  tick();
})();
