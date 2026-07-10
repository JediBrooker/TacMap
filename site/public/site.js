/* TacticalMaps — site interactions */
(function () {
  'use strict';

  /* ---- mobile nav ---- */
  var burger = document.querySelector('.nav__burger');
  var links = document.getElementById('navlinks');
  if (burger && links) {
    burger.addEventListener('click', function () {
      links.classList.toggle('open');
    });
    links.addEventListener('click', function (e) {
      if (e.target.tagName === 'A') links.classList.remove('open');
    });
  }

  /* ---- reveal on scroll ---- */
  var revealEls = Array.prototype.slice.call(document.querySelectorAll('.reveal'));
  if ('IntersectionObserver' in window && revealEls.length) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) {
          en.target.classList.add('in');
          io.unobserve(en.target);
        }
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -8% 0px' });
    revealEls.forEach(function (el) { io.observe(el); });
    // Safety net: never let content stay hidden if the observer doesn't fire.
    var failsafe = function () { revealEls.forEach(function (el) { el.classList.add('in'); }); };
    window.addEventListener('load', function () { setTimeout(failsafe, 400); });
    setTimeout(failsafe, 1600);
  } else {
    revealEls.forEach(function (el) { el.classList.add('in'); });
  }

  /* ---- live MGRS ticker (hero readout + HUD) ---- */
  // cycle the last two 5-digit easting/northing groups subtly so it feels live
  var grids = [
    ['55HGA', '02543', '92487'],
    ['55HGA', '02548', '92482'],
    ['55HGA', '02545', '92494'],
    ['55HGA', '02541', '92490']
  ];
  var mils = ['0000', '0008', '0012', '0004'];
  var elevs = ['662', '663', '661', '662'];
  var gi = 0;
  var gridOut = document.querySelectorAll('[data-mgrs]');
  var milOut = document.querySelectorAll('[data-mils]');
  var elevOut = document.querySelectorAll('[data-elev]');
  function tick() {
    gi = (gi + 1) % grids.length;
    var g = grids[gi];
    gridOut.forEach(function (n) { n.textContent = g[0] + ' ' + g[1] + ' ' + g[2]; });
    milOut.forEach(function (n) { n.textContent = mils[gi] + ' mils'; });
    elevOut.forEach(function (n) { n.textContent = elevs[gi] + ' m MSL'; });
  }
  var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (!reduce && gridOut.length) {
    setInterval(tick, 2600);
  }

  /* ---- active nav link on scroll ---- */
  var sections = Array.prototype.slice.call(document.querySelectorAll('section[id]'));
  var navAnchors = {};
  document.querySelectorAll('.nav__links a').forEach(function (a) {
    var id = a.getAttribute('href');
    if (id && id.charAt(0) === '#') navAnchors[id.slice(1)] = a;
  });
  if ('IntersectionObserver' in window && sections.length) {
    var so = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) {
          for (var k in navAnchors) navAnchors[k].style.color = '';
          var a = navAnchors[en.target.id];
          if (a) a.style.color = 'var(--green)';
        }
      });
    }, { threshold: 0.5 });
    sections.forEach(function (s) { so.observe(s); });
  }

  /* ---- footer year ---- */
  var yr = document.getElementById('year');
  if (yr) yr.textContent = new Date().getFullYear();
})();
