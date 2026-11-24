/*
 * 视频嗅探的页面侧补充（只在顶层框架运行；WebView 的 shouldInterceptRequest 已经能按地址后缀抓到大多数视频，
 * 这里补三种它看不到的：没有后缀的地址（靠响应头 Content-Type 判断）、<video> 实际在播的地址、fetch / XHR 拿到的 HLS）。
 * 只读不改：不改请求、不改响应，不读响应内容；报告走单向消息桥 MafariSniff（原生只受理主框架 http(s) 来源）。
 */
(function () {
  'use strict';
  try {
    if (window.top !== window || !window.MafariSniff || typeof window.MafariSniff.postMessage !== 'function') return;
    var bridge = window.MafariSniff;
    var seen = Object.create(null);
    var count = 0;
    var CT = /mpegurl|^video\//i;
    var SEG = /^video\/(mp2t|mpeg2t|iso\.segment|x-mpegts)/i;

    // 同一地址可以多次报告（先报地址，之后 <video> 的时长 / 是否预览 / 是否用户点播陆续补上）：
    // 只在信息有变化时再发，每个地址最多 6 次，总量有上限
    var sig = Object.create(null);
    var sent = Object.create(null);
    function report(u, ct, len, el, extra) {
      try {
        if (typeof u !== 'string' || u.length > 3500 || !/^https?:/i.test(u)) return;
        extra = extra || {};
        var s = (extra.d === undefined ? '' : extra.d) + '|' + (extra.pv === undefined ? '' : extra.pv) + '|' + (extra.ua ? 1 : 0);
        if (sig[u] === s || (sent[u] || 0) >= 6) return;
        if (!sent[u]) { if (count >= 40) return; count++; }
        sig[u] = s;
        sent[u] = (sent[u] || 0) + 1;
        var msg = { u: u, ct: ct || '', len: len >= 0 ? len : -1, el: !!el };
        if (extra.d !== undefined) msg.d = extra.d;
        if (extra.pv !== undefined) msg.pv = extra.pv;
        if (extra.ua) msg.ua = 1;
        bridge.postMessage(JSON.stringify(msg));
      } catch (e) {}
    }

    function check(u, ctRaw, lenRaw) {
      var ct = String(ctRaw || '').split(';')[0].trim().toLowerCase();
      if (!ct || SEG.test(ct) || !CT.test(ct)) return;
      var len = parseInt(lenRaw, 10);
      report(u, ct, isNaN(len) ? -1 : len, false);
    }

    // <video> 实际在播的地址（blob: 是 MSE，没有可直接播的地址，靠请求拦截抓它背后的 m3u8 / 分片清单）。
    // 同时带上这个元素的特征，原生据此区分「正片」和「预览 / 封面循环 / 页面里的动态小元素」：
    //   d  时长（秒；直播 / 无限为 -2；还不知道不带）
    //   pv 看起来是预览 / 装饰：loop、渲染尺寸很小、不可见（布局还没出来、尺寸为 0 时不据此判断）
    //   ua 用户刚在页面里点过（navigator.userActivation）
    function vinfo(t) {
      var x = {};
      var d = t.duration;
      if (d === Infinity) x.d = -2;
      else if (isFinite(d) && d > 0) x.d = Math.round(d * 10) / 10;
      var pv = !!t.loop;
      try {
        var r = t.getBoundingClientRect();
        if (r.width > 0 && r.height > 0) {
          if (r.width < 220 || r.height < 124) pv = true;
          var cs = getComputedStyle(t);
          if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) pv = true;
        }
      } catch (e) {}
      x.pv = pv;
      try { if (navigator.userActivation && navigator.userActivation.isActive) x.ua = true; } catch (e) {}
      return x;
    }
    function onMedia(e) {
      try {
        var t = e.target;
        if (!t || t.tagName !== 'VIDEO') return;
        var u = t.currentSrc || t.src;
        if (u && !/^(blob|data):/i.test(u)) report(u, '', -1, true, vinfo(t));
      } catch (err) {}
    }
    ['loadstart', 'loadedmetadata', 'durationchange', 'play', 'playing'].forEach(function (n) {
      document.addEventListener(n, onMedia, true);
    });

    var xo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function () {
      try {
        var x = this;
        x.addEventListener('readystatechange', function () {
          if (x.readyState === 2) check(x.responseURL, x.getResponseHeader('content-type'), x.getResponseHeader('content-length'));
        });
      } catch (e) {}
      return xo.apply(this, arguments);
    };

    var of = window.fetch;
    if (typeof of === 'function') {
      window.fetch = function () {
        var p = of.apply(this, arguments);
        try {
          p.then(function (r) {
            try { check(r.url, r.headers.get('content-type'), r.headers.get('content-length')); } catch (e) {}
          }, function () {});
        } catch (e) {}
        return p;
      };
    }
  } catch (e) {}
})();
