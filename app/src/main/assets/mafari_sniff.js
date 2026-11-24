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

    function report(u, ct, len, el) {
      try {
        if (typeof u !== 'string' || u.length > 3500 || !/^https?:/i.test(u) || seen[u] || count >= 40) return;
        seen[u] = 1;
        count++;
        bridge.postMessage(JSON.stringify({ u: u, ct: ct || '', len: len >= 0 ? len : -1, el: !!el }));
      } catch (e) {}
    }

    function check(u, ctRaw, lenRaw) {
      var ct = String(ctRaw || '').split(';')[0].trim().toLowerCase();
      if (!ct || SEG.test(ct) || !CT.test(ct)) return;
      var len = parseInt(lenRaw, 10);
      report(u, ct, isNaN(len) ? -1 : len, false);
    }

    // <video> 实际在播的地址（blob: 是 MSE，没有可直接播的地址，靠请求拦截抓它背后的 m3u8 / 分片清单）
    function onMedia(e) {
      try {
        var t = e.target;
        if (!t || t.tagName !== 'VIDEO') return;
        var u = t.currentSrc || t.src;
        if (u && !/^(blob|data):/i.test(u)) report(u, '', -1, true);
      } catch (err) {}
    }
    document.addEventListener('loadstart', onMedia, true);
    document.addEventListener('play', onMedia, true);

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
