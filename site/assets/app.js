(function () {
  'use strict';

  var y = document.getElementById('year');
  if (y) y.textContent = String(new Date().getFullYear());

  var toggle = document.querySelector('.menu-toggle');
  var nav = document.querySelector('.nav-links');
  if (toggle && nav) {
    toggle.addEventListener('click', function () {
      var open = nav.classList.toggle('open');
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
    nav.querySelectorAll('a').forEach(function (a) {
      a.addEventListener('click', function () { nav.classList.remove('open'); });
    });
  }

  // 内容永远不能因为动画而消失：只有顶层窗口且脚本正常执行时才短暂开启动画，
  // 1.6 秒后无条件撤销；iframe 预览 / 无观察器 / 脚本异常时全部直接可见。
  var reveals = document.querySelectorAll('.reveal');
  if ('IntersectionObserver' in window && window.top === window) {
    var root = document.documentElement;
    root.classList.add('anim');
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
      });
    }, { threshold: 0.08 });
    reveals.forEach(function (el) { io.observe(el); });
    setTimeout(function () { root.classList.remove('anim'); }, 1600);
  } else {
    reveals.forEach(function (el) { el.classList.add('in'); });
  }

  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function mb(bytes) { return (bytes / 1048576).toFixed(1) + ' MB'; }

  var dataEl = document.getElementById('release-data');
  var grid = document.getElementById('apk-grid');
  if (!dataEl || !grid) return;
  var data;
  try { data = JSON.parse(dataEl.textContent.trim()); } catch (e) { return; }
  if (!data || !Array.isArray(data.apks) || data.apks.length === 0) return;

  grid.innerHTML = data.apks.map(function (a) {
    var digest = a.digest || '';
    return '<article class="apk reveal' + (a.featured ? ' featured' : '') + '">' +
      '<div class="apk-top"><div><h3>' + esc(a.title) + '</h3><div class="kind mono">' + esc(a.name) + '</div></div>' +
      (a.featured ? '<span class="tag">推荐</span>' : '<span class="tag">' + esc(a.abiLabel) + '</span>') + '</div>' +
      '<div class="facts"><div>体积 <b>' + mb(a.size) + '</b></div>' +
      '<div>架构 <b>' + esc(a.abiLabel) + '</b></div>' +
      '<div>包名 <b class="mono">' + esc(a.packageName) + '</b></div>' +
      '<div>' + esc(a.note) + '</div></div>' +
      '<div class="hash mono"><span title="' + esc(digest) + '">' + esc(digest) + '</span>' +
      '<button class="copy" type="button" data-copy="' + esc(digest) + '">复制</button></div>' +
      '<a class="btn btn-primary btn-sm" href="' + esc(a.url) + '" download><svg><use href="#i-download"/></svg>下载 APK</a>' +
      '</article>';
  }).join('');

  grid.querySelectorAll('.reveal').forEach(function (el) { el.classList.add('in'); });
  grid.querySelectorAll('.copy').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var value = btn.getAttribute('data-copy') || '';
      var done = function () {
        var old = btn.textContent;
        btn.textContent = '已复制';
        setTimeout(function () { btn.textContent = old; }, 1600);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(value).then(done, function () { fallback(value, done); });
      } else { fallback(value, done); }
    });
  });

  function fallback(value, done) {
    var t = document.createElement('textarea');
    t.value = value; t.style.position = 'fixed'; t.style.opacity = '0';
    document.body.appendChild(t); t.select();
    try { document.execCommand('copy'); done(); } catch (e) { /* 浏览器不支持时用户仍可手动选中 */ }
    document.body.removeChild(t);
  }
})();
