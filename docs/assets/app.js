(function () {
  'use strict';

  var root = document.documentElement;
  var themeButton = document.getElementById('themeToggle');
  var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: light)') : null;
  var storageKey = 'nt-theme';

  function readSavedTheme() {
    try { return localStorage.getItem(storageKey); } catch (e) { return null; }
  }

  function saveTheme(theme) {
    try { localStorage.setItem(storageKey, theme); } catch (e) { /* private mode */ }
  }

  function applyTheme(theme) {
    root.setAttribute('data-theme', theme);
    document.querySelector('meta[name="theme-color"]').setAttribute(
      'content', theme === 'light' ? '#f4f8fc' : '#08111f');
    if (themeButton) {
      themeButton.setAttribute('aria-label', theme === 'light' ? '切换到深色主题' : '切换到浅色主题');
      themeButton.setAttribute('title', themeButton.getAttribute('aria-label'));
    }
  }

  var saved = readSavedTheme();
  applyTheme(saved || (media && media.matches ? 'light' : 'dark'));

  if (themeButton) {
    themeButton.addEventListener('click', function () {
      var next = root.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
      applyTheme(next);
      saveTheme(next);
    });
  }

  if (media) {
    var onSystemChange = function (event) {
      if (!readSavedTheme()) applyTheme(event.matches ? 'light' : 'dark');
    };
    if (media.addEventListener) media.addEventListener('change', onSystemChange);
    else if (media.addListener) media.addListener(onSystemChange);
  }

  var header = document.querySelector('.site-header');
  function updateHeader() {
    if (header) header.setAttribute('data-elevated', window.scrollY > 12 ? 'true' : 'false');
  }
  updateHeader();
  window.addEventListener('scroll', updateHeader, { passive: true });

  var revealItems = document.querySelectorAll('.reveal');
  if ('IntersectionObserver' in window) {
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-visible');
          observer.unobserve(entry.target);
        }
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });
    revealItems.forEach(function (item, index) {
      item.style.transitionDelay = Math.min(index % 6, 4) * 50 + 'ms';
      observer.observe(item);
    });
  } else {
    revealItems.forEach(function (item) { item.classList.add('is-visible'); });
  }

  document.querySelectorAll('a[href^="#"]').forEach(function (link) {
    link.addEventListener('click', function (event) {
      var id = link.getAttribute('href');
      if (!id || id === '#') return;
      var target = document.querySelector(id);
      if (!target) return;
      event.preventDefault();
      target.scrollIntoView({ behavior: 'smooth', block: 'start' });
      if (history.replaceState) history.replaceState(null, '', id);
    });
  });
})();
