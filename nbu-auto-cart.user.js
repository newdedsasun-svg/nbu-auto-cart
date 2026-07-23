// ==UserScript==
// @name         НБУ — автоматично додати товар у кошик
// @namespace    local.nbu.autocart
// @version      1.1.0
// @description  Перевіряє одне або кілька посилань і додає перший доступний товар у кошик.
// @match        https://coins.bank.gov.ua/*
// @grant        none
// @run-at       document-idle
// ==/UserScript==

(function () {
  'use strict';

  const STORAGE_KEY = 'nbuAutoCartStateV1';
  const DEFAULT_INTERVAL = 5;
  const BUY_TEXT = /^(додати\s+(?:до|у|в)\s+кошик|у\s+кошик|в\s+кошик|купити)$/i;
  const STOP_TEXT = /(оформити|оплатити|замовити|перейти\s+до\s+кошика|кошик\s*\()/i;

  let state = readState();
  let busy = false;
  let timer = null;
  let observer = null;

  function readState() {
    try {
      return { running: false, interval: DEFAULT_INTERVAL, urls: [], index: 0, ...JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}') };
    } catch (_) {
      return { running: false, interval: DEFAULT_INTERVAL, urls: [], index: 0 };
    }
  }

  function saveState() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  }

  function normalize(text) {
    return String(text || '').replace(/\s+/g, ' ').trim();
  }

  function visible(element) {
    const style = getComputedStyle(element);
    const rect = element.getBoundingClientRect();
    return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
  }

  function enabled(element) {
    return !element.disabled && element.getAttribute('aria-disabled') !== 'true' && !element.classList.contains('disabled');
  }

  function findBuyButton() {
    const candidates = [...document.querySelectorAll('button, input[type="button"], input[type="submit"], a[role="button"], a.btn, .btn')];
    return candidates.find((element) => {
      const text = normalize(element.value || element.textContent || element.getAttribute('aria-label'));
      return text && BUY_TEXT.test(text) && !STOP_TEXT.test(text) && visible(element) && enabled(element);
    }) || null;
  }

  function cleanUrl(value) {
    try {
      const url = new URL(value.trim());
      if (url.protocol !== 'https:' || url.hostname !== 'coins.bank.gov.ua') return null;
      url.hash = '';
      return url.href;
    } catch (_) {
      return null;
    }
  }

  function currentUrl() {
    return cleanUrl(location.href);
  }

  function setStatus(message, kind = '') {
    const status = document.getElementById('nbu-ac-status');
    if (!status) return;
    status.textContent = message;
    status.dataset.kind = kind;
    updateControls();
  }

  function updateControls() {
    const start = document.getElementById('nbu-ac-start');
    const stop = document.getElementById('nbu-ac-stop');
    if (start) start.disabled = state.running;
    if (stop) stop.disabled = !state.running;
  }

  function notifySuccess() {
    try {
      const audio = new AudioContext();
      const oscillator = audio.createOscillator();
      oscillator.connect(audio.destination);
      oscillator.frequency.value = 880;
      oscillator.start();
      oscillator.stop(audio.currentTime + 0.35);
    } catch (_) { /* звук може бути заблокований браузером */ }
    if ('Notification' in window && Notification.permission === 'granted') {
      new Notification('Товар додано в кошик', { body: document.title });
    }
  }

  function stop(message = 'Зупинено') {
    state.running = false;
    saveState();
    clearTimeout(timer);
    observer?.disconnect();
    setStatus(message, message.includes('Додано') ? 'success' : '');
  }

  function tryAdd() {
    if (!state.running || busy) return false;
    if (!state.urls.includes(currentUrl())) return false;
    const button = findBuyButton();
    if (!button) return false;
    busy = true;
    button.scrollIntoView({ block: 'center', behavior: 'instant' });
    button.click();
    stop('Додано в кошик — перевірте кошик');
    notifySuccess();
    return true;
  }

  function scheduleReload() {
    clearTimeout(timer);
    timer = setTimeout(() => {
      if (!state.running || tryAdd()) return;
      state.index = (Number(state.index) + 1) % state.urls.length;
      saveState();
      const next = state.urls[state.index];
      if (currentUrl() === next) location.reload();
      else location.assign(next);
    }, Math.max(3, Number(state.interval) || DEFAULT_INTERVAL) * 1000);
  }

  function resume() {
    if (!state.running) return;
    if (!Array.isArray(state.urls) || !state.urls.length) {
      stop('Зупинено: додайте хоча б одне посилання');
      return;
    }
    if (!state.urls.includes(currentUrl())) {
      location.assign(state.urls[state.index] || state.urls[0]);
      return;
    }
    setStatus('Пошук активний… сторінка оновлюється автоматично');
    observer = new MutationObserver(() => tryAdd());
    observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true });
    if (!tryAdd()) scheduleReload();
  }

  function start() {
    const intervalInput = document.getElementById('nbu-ac-interval');
    const linksInput = document.getElementById('nbu-ac-links');
    const rawLinks = linksInput.value.split(/[\n,;]+/).map(cleanUrl).filter(Boolean);
    state.urls = [...new Set(rawLinks)];
    if (!state.urls.length) {
      setStatus('Вставте коректне посилання з coins.bank.gov.ua');
      return;
    }
    state.interval = Math.min(60, Math.max(3, Number(intervalInput.value) || DEFAULT_INTERVAL));
    intervalInput.value = state.interval;
    state.index = 0;
    state.running = true;
    saveState();
    if ('Notification' in window && Notification.permission === 'default') Notification.requestPermission().catch(() => {});
    resume();
  }

  function createPanel() {
    const panel = document.createElement('section');
    panel.id = 'nbu-ac-panel';
    panel.innerHTML = `
      <div class="nbu-ac-title">Автокошик НБУ</div>
      <div id="nbu-ac-status">Вставте посилання на товар</div>
      <textarea id="nbu-ac-links" rows="4" placeholder="Одне посилання в кожному рядку">${(state.urls || []).join('\n')}</textarea>
      <label>Оновлення кожні <input id="nbu-ac-interval" type="number" min="3" max="60" value="${state.interval}"> с</label>
      <div class="nbu-ac-actions">
        <button id="nbu-ac-start" type="button">Запустити</button>
        <button id="nbu-ac-stop" type="button">Зупинити</button>
      </div>`;

    const style = document.createElement('style');
    style.textContent = `
      #nbu-ac-panel{position:fixed;right:14px;bottom:14px;z-index:2147483647;width:290px;padding:14px;border-radius:12px;background:#fff;color:#172033;box-shadow:0 5px 28px #0005;font:14px/1.35 Arial,sans-serif;border:1px solid #d9e0ea}
      #nbu-ac-panel .nbu-ac-title{font-size:17px;font-weight:700;margin-bottom:7px}
      #nbu-ac-status{min-height:38px;margin-bottom:8px;color:#475569} #nbu-ac-status[data-kind="success"]{color:#087a39;font-weight:700}
      #nbu-ac-panel label{display:block;margin:7px 0} #nbu-ac-interval{width:55px;padding:4px;border:1px solid #aab5c3;border-radius:5px}
      #nbu-ac-links{box-sizing:border-box;width:100%;padding:7px;resize:vertical;border:1px solid #aab5c3;border-radius:6px;font:12px/1.3 Arial,sans-serif}
      .nbu-ac-actions{display:flex;gap:8px;margin-top:10px}.nbu-ac-actions button{flex:1;padding:9px;border:0;border-radius:7px;cursor:pointer;font-weight:700}
      #nbu-ac-start{background:#1769d2;color:#fff}#nbu-ac-stop{background:#e8edf4;color:#243244}.nbu-ac-actions button:disabled{opacity:.45;cursor:default}
    `;
    document.head.appendChild(style);
    document.body.appendChild(panel);
    document.getElementById('nbu-ac-start').addEventListener('click', start);
    document.getElementById('nbu-ac-stop').addEventListener('click', () => stop());
    updateControls();
  }

  createPanel();
  resume();
})();
