'use strict';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const nf = new Intl.NumberFormat('de-DE');
const fmt = n => nf.format(Math.round(n || 0));
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const time = iso => iso ? new Date(iso).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' }) : '';
const dateTime = iso => iso ? new Date(iso).toLocaleString('de-DE', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) + ' Uhr' : '–';
const SCREENS = { overview: 'Übersicht', channels: 'Kanäle', drops: 'Drops', raffles: 'Raffles', bot: 'Bot & Login', settings: 'Einstellungen' };

const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
const bootTime = performance.now();
let pendingEnter = null;

// Recreated elements pick up looping animations where the old ones were (see motion.css --phase).
function setPhase() {
  document.documentElement.style.setProperty('--phase', `${((performance.now() - bootTime) / 1000).toFixed(2)}s`);
}

function stagger(root, selector) {
  $$(selector, root).forEach((el, i) => el.style.setProperty('--i', i));
}

// Entrance animations run once per screen opening, never on background refreshes.
function enter(id) {
  if (pendingEnter !== id) return;
  pendingEnter = null;
  const section = document.getElementById(id);
  for (const group of ['.page-heading>div', '.stats-strip>div', '.slot', '.feed li', '.channel-row', '.campaign', '.inventory-item', '.raffle-entry', '.bot-grid>.panel', '.settings-grid>.panel', '.raffle-layout .stack>.panel']) {
    stagger(section, group);
  }
  section.classList.remove('is-entering');
  void section.offsetWidth;
  section.classList.add('is-entering');
  clearTimeout(enter.timer);
  enter.timer = setTimeout(() => section.classList.remove('is-entering'), 1600);
}

function animateNumber(el, value, { bump = false, prefix = '' } = {}) {
  const prev = el.dataset.value === undefined ? null : Number(el.dataset.value);
  el.dataset.value = value;
  if (prev === null || prev === value || reducedMotion.matches) {
    el.textContent = prefix + fmt(value);
    return;
  }
  const start = performance.now(), dur = 600;
  const step = now => {
    const t = Math.min(1, (now - start) / dur);
    const eased = 1 - Math.pow(1 - t, 3);
    el.textContent = prefix + fmt(prev + (value - prev) * eased);
    if (t < 1 && el.dataset.value == value) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
  if (bump && value > prev) {
    const chip = document.createElement('span');
    chip.className = 'stat-bump';
    chip.textContent = '+' + fmt(value - prev);
    el.append(chip);
    requestAnimationFrame(() => chip.classList.add('show'));
    setTimeout(() => chip.remove(), 1500);
  }
}

function moveNavIndicator() {
  const active = $('nav a[aria-current]');
  if (active && $('nav').scrollWidth > $('nav').clientWidth) {
    active.scrollIntoView({ block: 'nearest', inline: 'center', behavior: reducedMotion.matches ? 'auto' : 'smooth' });
  }
}

const state = { overview: null, channels: [], drops: null, raffles: null, bot: null, twitch: null, settings: null, logs: [] };

// ---------- API ----------
function csrfToken() {
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : null;
}

async function api(path, { method = 'GET', body } = {}) {
  const headers = { Accept: 'application/json' };
  if (method !== 'GET') {
    if (!csrfToken()) await fetch('/api/me', { credentials: 'same-origin' });
    headers['X-XSRF-TOKEN'] = csrfToken();
  }
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(path, { method, headers, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body) });
  if (res.status === 401) {
    location.href = '/oauth2/authorization/authentik';
    throw new Error('Nicht angemeldet');
  }
  const data = res.headers.get('content-type')?.includes('json') ? await res.json() : null;
  if (!res.ok) {
    const err = new Error(data?.errors?.join(' · ') || data?.detail || data?.error || `Fehler ${res.status}`);
    err.data = data;
    throw err;
  }
  return data;
}

function toast(text, error = false) {
  const t = $('#toast');
  t.textContent = text;
  t.classList.toggle('error', error);
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.hidden = true; }, 4200);
}

// ---------- Navigation & theme ----------
function currentScreen() {
  const id = location.hash.slice(1);
  return SCREENS[id] ? id : 'overview';
}

function showScreen() {
  const id = currentScreen();
  const apply = () => {
    $$('.screen').forEach(s => { s.hidden = s.id !== id; });
    $$('nav a').forEach(a => a.toggleAttribute('aria-current', a.getAttribute('href') === '#' + id));
    $$('nav a[aria-current]').forEach(a => a.setAttribute('aria-current', 'page'));
    $('#breadcrumb').textContent = SCREENS[id];
    document.title = `${SCREENS[id]} · twitchlurker`;
    moveNavIndicator();
  };
  pendingEnter = id;
  if (document.startViewTransition && !reducedMotion.matches) document.startViewTransition(apply);
  else apply();
  loadScreen(id);
}

function loadScreen(id) {
  const loaders = { overview: loadOverview, channels: loadChannels, drops: loadDrops, raffles: loadRaffles, bot: loadBot, settings: loadSettings };
  loaders[id]().catch(e => toast(e.message, true));
}

function initTheme() {
  const saved = localStorage.getItem('theme');
  if (saved) document.documentElement.dataset.theme = saved;
  $('#theme-toggle').addEventListener('click', () => {
    const dark = document.documentElement.dataset.theme
      ? document.documentElement.dataset.theme === 'dark'
      : matchMedia('(prefers-color-scheme: dark)').matches;
    const next = dark ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    localStorage.setItem('theme', next);
  });
}

// ---------- Shared bits ----------
const BOT_TEXT = {
  RUNNING: ['Bot läuft', 'Läuft'],
  STARTING: ['Bot startet', 'Startet …'],
  BACKOFF: ['Bot startet neu', 'Kurze Pause'],
  STOPPED: ['Bot pausiert', 'Pausiert'],
  NEEDS_LOGIN: ['Twitch fehlt', 'Wartet auf Twitch'],
};

function since(iso) {
  if (!iso) return '';
  const mins = Math.max(0, Math.round((Date.now() - new Date(iso)) / 60000));
  if (mins < 60) return `Seit ${mins} Min.`;
  const h = Math.floor(mins / 60);
  if (h < 48) return `Seit ${h} Std., ${mins % 60} Min.`;
  return `Seit ${Math.floor(h / 24)} Tagen, ${h % 24} Std.`;
}

function renderBotMini(bot) {
  if (!bot) return;
  const [short] = BOT_TEXT[bot.status] || ['…'];
  $('#sidebar-state').textContent = short;
  $('#sidebar-uptime').textContent = bot.status === 'RUNNING' ? since(bot.startedAt) : '';
  document.body.classList.toggle('paused', bot.status !== 'RUNNING');
}

function renderBanner(twitch) {
  const banner = $('#state-banner');
  if (!twitch || twitch.state === 'READY' || twitch.state === 'PENDING') {
    banner.hidden = true;
    return;
  }
  $('#state-banner-text').innerHTML = twitch.state === 'EXPIRED'
    ? '<strong>Twitch-Token abgelaufen.</strong> Einmal neu verbinden, dein Loot bleibt erhalten.'
    : '<strong>Noch kein Twitch-Account verbunden.</strong> Ohne ihn sammelt der Bot nichts.';
  banner.hidden = false;
}

function previewUrl(login) {
  // Twitch refreshes live thumbnails every few minutes; the minute bucket busts the browser cache.
  return `https://static-cdn.jtvnw.net/previews-ttv/live_user_${encodeURIComponent(login)}-640x360.jpg?t=${Math.floor(Date.now() / 300000)}`;
}

function playerUrl(login) {
  return `https://player.twitch.tv/?channel=${encodeURIComponent(login)}&parent=${encodeURIComponent(location.hostname)}&muted=true&autoplay=true`;
}

function videoWanted() {
  const saved = localStorage.getItem('video');
  return saved === null ? !matchMedia('(max-width: 900px)').matches : saved === '1';
}

function initial(name) {
  return esc((name || '?').charAt(0).toUpperCase());
}

// ---------- Overview ----------
function eventView(e) {
  const who = esc(e.login || '');
  switch (e.type) {
    case 'POINTS': {
      const titles = { WATCH: 'Watch-Time gutgeschrieben', CLAIM: 'Bonus eingesammelt', WATCH_STREAK: 'Watch-Streak gesichert', RAID: 'Raid-Bonus' };
      return ['+', titles[e.detail] || 'Kanalpunkte', `${who} · +${fmt(e.amount)} Kanalpunkte`];
    }
    case 'BONUS': return ['+', 'Bonus-Truhe geöffnet', who];
    case 'MOMENT': return ['✧', 'Moment mitgenommen', who];
    case 'RAID': return ['↪', 'Beim Raid mitgezogen', `${who} → ${esc(e.detail || '?')}`];
    case 'DROP': return ['◇', 'Drop geclaimt', esc(e.detail || '')];
    case 'ADDED': return ['↗', e.detail === 'drops' ? 'Für Drops aufgenommen' : 'Kanal aufgenommen', who];
    case 'ONLINE': return ['●', 'Ist jetzt live', who];
    case 'RAFFLE': return ['✓', 'Bei der Verlosung dabei', `${who} · ${esc(e.detail || '')} gesendet`];
    case 'RAFFLE_WON': return ['★', 'Verlosung gewonnen', `${who} · ${esc(e.detail || '')}`];
    default: return ['·', esc(e.type), who];
  }
}

const FEED_HIDDEN = e => e.type === 'OFFLINE' || (e.type === 'POINTS' && e.detail === 'WATCH');

function feedItem(e) {
  const [sym, title, sub] = eventView(e);
  return `<li><span class="event-symbol" aria-hidden="true">${sym}</span><div><strong>${title}</strong><p>${sub}</p></div><time datetime="${esc(e.ts)}">${time(e.ts)}</time></li>`;
}

// Slots keep their DOM between refreshes so a running player is never reloaded.
const slotEls = [];
const playing = new Set();

function slotShell(index) {
  const el = document.createElement('article');
  el.className = 'panel slot';
  el.innerHTML = `<div class="slot-head"><p class="eyebrow"><svg class="pin-icon" hidden><use href="#i-pin"/></svg><span class="slot-label"></span></p><label class="visually-hidden" for="slot-select-${index}">Platz ${index + 1} zuweisen</label><select id="slot-select-${index}" data-slot="${index}"></select></div>
<div class="slot-media"></div>
<div class="slot-body"><div class="slot-identity"><span class="avatar"></span><div><h3></h3><p></p></div><button class="icon-button" type="button" aria-label="Kanal öffnen"><svg><use href="#i-arrow"/></svg></button></div><div class="slot-meta"></div><p class="slot-note" hidden></p><div class="slot-foot"><span class="meta slot-tags"></span><strong></strong></div></div>`;
  el.querySelector('select').addEventListener('change', ev => assignSlot(index, ev.target.value || null));
  return el;
}

function renderSlotMedia(media, login) {
  if (media.dataset.login === (login || '') && media.dataset.mode === (login && (videoWanted() || playing.has(login)) ? 'video' : 'image')) return;
  media.dataset.login = login || '';
  if (!login) {
    media.dataset.mode = 'none';
    media.innerHTML = '<p class="slot-empty-media">Frei. Sobald jemand live ist, lurkt der Bot hier.</p>';
    return;
  }
  const video = videoWanted() || playing.has(login);
  media.dataset.mode = video ? 'video' : 'image';
  if (video) {
    media.innerHTML = `<span class="live-tag">LIVE</span><iframe src="${esc(playerUrl(login))}" title="Stream von ${esc(login)}" allow="autoplay; fullscreen" allowfullscreen loading="lazy"></iframe>`;
  } else {
    media.innerHTML = `<span class="live-tag">LIVE</span><img src="${esc(previewUrl(login))}" alt="" loading="lazy" referrerpolicy="no-referrer"><button class="play" type="button"><svg><use href="#i-play"/></svg>Stream ansehen</button>`;
    media.querySelector('.play').addEventListener('click', () => {
      playing.add(login);
      renderSlotMedia(media, login);
    });
  }
}

function renderSlots() {
  const o = state.overview;
  const box = $('#slots');
  const channels = state.channels.filter(c => c.source !== 'drops' || c.slot);
  for (let i = 0; i < 2; i++) {
    if (!slotEls[i]) {
      slotEls[i] = slotShell(i);
      box.append(slotEls[i]);
    }
    const el = slotEls[i];
    const slot = o.slots[i];
    const s = slot.streamer;
    el.querySelector('.slot-label').textContent = `Platz ${i + 1} · ${slot.pinned ? 'fest' : 'automatisch'}`;
    el.querySelector('.pin-icon').hidden = !slot.pinned;
    const select = el.querySelector('select');
    if (document.activeElement !== select) {
      const options = ['<option value="">Automatisch</option>', ...channels.map(c => `<option value="${esc(c.login)}">${esc(c.login)}${c.online ? ' · live' : ''}</option>`)];
      if (slot.pinned && !channels.some(c => c.login === slot.pinned)) options.push(`<option value="${esc(slot.pinned)}">${esc(slot.pinned)}</option>`);
      const html = options.join('');
      if (select.dataset.html !== html) {
        select.innerHTML = html;
        select.dataset.html = html;
      }
      select.value = slot.pinned || '';
    }
    renderSlotMedia(el.querySelector('.slot-media'), s?.login);
    el.querySelector('.avatar').textContent = s ? s.login.charAt(0).toUpperCase() : '·';
    el.querySelector('h3').textContent = s ? s.login : 'Gerade niemand';
    el.querySelector('.slot-identity p').textContent = s ? (s.title || '') : (o.bot.status !== 'RUNNING' ? 'Der Bot läuft gerade nicht.' : 'Keiner deiner Kanäle ist live.');
    const open = el.querySelector('.slot-identity .icon-button');
    open.hidden = !s;
    if (s) open.dataset.channel = s.login;
    el.querySelector('.slot-meta').innerHTML = s ? `<span>${esc(s.game || '–')}</span><span>${fmt(s.viewers)} Zuschauer</span><span>${fmt(s.minutesWatched)} Min. gelurkt</span>` : '';
    const note = el.querySelector('.slot-note');
    note.hidden = !(slot.pinned && !slot.pinnedOnline);
    note.textContent = slot.pinned && !slot.pinnedOnline ? `${slot.pinned} ist offline. Der Platz läuft solange automatisch.` : '';
    el.querySelector('.slot-tags').innerHTML = s ? [s.streakPending ? '<span class="tag hot">Streak</span>' : '', s.dropsEligible ? '<span class="tag hot">Drops</span>' : '', s.source === 'drops' ? '<span class="tag">Drop-Suche</span>' : ''].join(' ') : '';
    const gained = s ? state.channels.find(c => c.login === s.login)?.gainedToday : null;
    const strong = el.querySelector('.slot-foot strong');
    if (s) animateNumber(strong, gained ?? 0, { bump: true, prefix: '+' });
    else {
      strong.textContent = '';
      delete strong.dataset.value;
    }
  }
}

async function assignSlot(index, login) {
  const slots = [...state.overview.slots.map(s => s.pinned)];
  slots[index] = login;
  try {
    await api('/api/slots', { method: 'PUT', body: { slots } });
    state.settings = null;
    toast(login ? `Platz ${index + 1} gehört jetzt ${login}.` : `Platz ${index + 1} läuft wieder automatisch.`);
    setTimeout(() => loadOverview().catch(() => {}), 800);
  } catch (e) {
    toast(e.message, true);
  }
}


function nextDrop(drops) {
  let best = null;
  for (const c of drops?.campaigns || []) {
    for (const d of c.drops) {
      if (d.claimed || !d.required) continue;
      const pct = d.watched / d.required;
      if (!best || pct > best.pct) best = { c, d, pct };
    }
  }
  return best;
}

function renderNextDrop() {
  const box = $('#next-drop');
  const best = nextDrop(state.drops);
  const icon = '<span class="drop-icon"><svg><use href="#i-chest"/></svg></span>';
  if (!best) {
    box.innerHTML = `${icon}<p class="eyebrow">Nächster Drop</p><h2>Gerade keine Kampagne offen.</h2><p class="meta">Sobald Twitch Drops für deine Spiele verteilt, taucht der Fortschritt hier auf.</p><a class="text-link" href="#drops">Zu den Drops <svg><use href="#i-arrow"/></svg></a>`;
    return;
  }
  const pct = Math.round(best.pct * 100);
  box.innerHTML = `${icon}<p class="eyebrow">Nächster Drop · ${esc(best.c.game || '')}</p><h2>${esc(best.d.name)}</h2><div class="progress-label"><span>${fmt(best.d.watched)} von ${fmt(best.d.required)} Minuten</span><strong>${pct} %</strong></div><progress value="${pct}" max="100" aria-label="${esc(best.d.name)}, ${pct} Prozent"></progress><p class="meta">Noch ${fmt(best.d.required - best.d.watched)} Minuten Watch-Time.</p><a class="text-link" href="#drops">Zu den Drops <svg><use href="#i-arrow"/></svg></a>`;
}

async function loadOverview() {
  const [overview, channels, drops] = await Promise.all([api('/api/overview'), api('/api/channels'), api('/api/drops')]);
  Object.assign(state, { overview, channels, drops, twitch: overview.twitch, bot: overview.bot });
  renderOverview();
}

function renderOverview() {
  const o = state.overview;
  if (!o) return;
  renderBotMini(o.bot);
  renderBanner(o.twitch);
  const neverConnected = o.twitch.state === 'NONE' && o.stats.pointsTotal === 0;
  $('#empty-state').hidden = !neverConnected;
  $('#overview-content').hidden = neverConnected;

  $('#today-label').textContent = new Date().toLocaleDateString('de-DE', { weekday: 'long', day: 'numeric', month: 'long' });
  $('#overview-sub').textContent = o.bot.status === 'RUNNING'
    ? `${o.online} von ${o.tracked} Kanälen sind live. Der Bot lurkt in zweien davon.`
    : 'Der Bot macht gerade Pause. Unter „Bot & Login“ geht’s weiter.';
  $('#session-state').textContent = `${BOT_TEXT[o.bot.status]?.[0] || ''}${o.bot.user ? ' als ' + o.bot.user : ''}`;

  setPhase();
  animateNumber($('#stat-today'), o.stats.pointsToday, { bump: true });
  animateNumber($('#stat-week'), o.stats.pointsWeek);
  animateNumber($('#stat-total'), o.stats.pointsTotal);
  animateNumber($('#stat-drops'), o.stats.dropsTotal);
  animateNumber($('#stat-raffles'), o.stats.rafflesToday);

  renderSlots();
  const feed = o.feed.filter(e => !FEED_HIDDEN(e)).slice(0, 12);
  $('#feed').innerHTML = feed.length ? feed.map(feedItem).join('') : '<li class="feed-empty">Noch ruhig hier. Die ersten Punkte kommen, sobald jemand live ist.</li>';
  $('#nav-channels').textContent = o.tracked || '';
  $('#nav-drops').hidden = !(state.drops?.campaigns?.length);
  renderNextDrop();
  enter('overview');
}

// ---------- Channels ----------
function sparkline(values, w = 120, h = 34) {
  if (!values || values.length < 2) return '';
  const min = Math.min(...values), max = Math.max(...values), span = max - min || 1;
  return values.map((v, i) => `${(i / (values.length - 1) * (w - 4) + 2).toFixed(1)},${(h - 3 - (v - min) / span * (h - 6)).toFixed(1)}`).join(' ');
}

function channelRow(c, sortable) {
  const status = c.watching ? 'wird gelurkt' : c.online ? `live · ${c.game || ''}` : 'offline';
  const tags = [
    c.slot ? `<span class="tag hot">Platz ${c.slot}</span>` : '',
    c.streakPending ? '<span class="tag hot">Streak</span>' : '',
    c.dropsEligible ? '<span class="tag hot">Drops</span>' : '',
    c.source === 'drops' ? '<span class="tag">Drop-Suche</span>' : c.source === 'extra' ? '<span class="tag">Extra</span>' : '',
    c.raffles ? '' : '<span class="tag">Raffles aus</span>',
  ].join('');
  const spark = sparkline(c.spark);
  const order = sortable
    ? `<button class="grip" type="button" aria-label="${esc(c.login)} verschieben" data-grip="${esc(c.login)}"><svg><use href="#i-grip"/></svg></button><span class="rank">${c.rank}</span><span class="move"><button type="button" data-move="-1" data-login="${esc(c.login)}" aria-label="${esc(c.login)} nach oben"><svg><use href="#i-up"/></svg></button><button type="button" data-move="1" data-login="${esc(c.login)}" aria-label="${esc(c.login)} nach unten"><svg><use href="#i-down"/></svg></button></span>`
    : `<span class="rank">${c.rank}</span>`;
  return `<li class="channel-row" data-login="${esc(c.login)}" data-live="${c.online}"><div class="order-cell">${order}</div>
<div class="channel-name"><span class="avatar">${initial(c.login)}</span><div><strong>${esc(c.login)}</strong><small>${c.online ? '<span class="live-dot"></span>' : ''}${esc(status)} ${tags}</small></div></div>
<div class="value"><span class="mobile-label">Punkte </span>${fmt(c.points)}</div><div class="gain"><span class="mobile-label">Heute </span>+${fmt(c.gainedToday)}</div>
${spark ? `<svg class="sparkline" viewBox="0 0 120 34" role="img" aria-label="Punkteverlauf ${esc(c.login)}, 7 Tage"><polyline points="${spark}" pathLength="1"/></svg>` : '<span class="meta spark-empty">noch kein Verlauf</span>'}
<button class="icon-button channel-open" data-channel="${esc(c.login)}" aria-label="${esc(c.login)} öffnen"><svg><use href="#i-arrow"/></svg></button></li>`;
}

async function loadChannels() {
  const [channels, settings] = await Promise.all([api('/api/channels'), state.settings ? state.settings : api('/api/settings')]);
  state.channels = channels;
  state.settings = settings;
  renderChannels();
}

let dragging = false;

function renderChannels() {
  if (dragging) {
    renderChannels.pending = true;
    return;
  }
  const q = $('#channel-search').value.trim().toLowerCase();
  const filter = $('#channel-filter').value;
  const sortable = !q && filter === 'all';
  const list = state.channels.filter(c => (!q || c.login.includes(q)) && (filter === 'all' || (filter === 'live') === c.online));
  const before = renderChannels.points || {};
  const html = list.map(c => channelRow(c, sortable)).join('');
  if (renderChannels.html !== html) {
    $('#channel-list').innerHTML = html;
    renderChannels.html = html;
  }
  $$('#channel-list .channel-row').forEach((row, i) => {
    const c = list[i];
    if (before[c.login] !== undefined && before[c.login] !== c.points) row.classList.add('updated');
  });
  renderChannels.points = Object.fromEntries(state.channels.map(c => [c.login, c.points]));
  $('#no-channels').hidden = list.length > 0;
  $('#order-hint').textContent = sortable
    ? 'Ziehen am Griff (oder die Pfeile) ändert die Reihenfolge. Sie entscheidet, wer einen automatischen Platz bekommt, nachdem Streaks und Drops versorgt sind.'
    : 'Sortieren geht nur ohne Suche und Filter.';
  const online = state.channels.filter(c => c.online).length;
  $('#channels-sub').textContent = `${state.channels.length} Kanäle, ${online} davon live.`;
  if (state.settings) $('#priority-label').textContent = state.settings.priority.map(p => ({ STREAK: 'Streak', DROPS: 'Drops', ORDER: 'Reihenfolge', SUBSCRIBED: 'Abo-Bonus', POINTS_ASCENDING: 'wenigste Punkte', POINTS_DESCENDING: 'meiste Punkte' }[p] || p)).join(' → ');
  enter('channels');
}

async function saveOrder(logins) {
  const byLogin = Object.fromEntries(state.channels.map(c => [c.login, c]));
  state.channels = logins.map((l, i) => ({ ...byLogin[l], rank: i + 1 }));
  renderChannels();
  try {
    await api('/api/order', { method: 'PUT', body: { order: logins } });
    state.settings = null;
    toast('Reihenfolge gespeichert. Der Bot richtet sich sofort danach.');
  } catch (e) {
    toast(e.message, true);
    loadChannels().catch(() => {});
  }
}

function moveChannel(login, delta) {
  const logins = state.channels.map(c => c.login);
  const from = logins.indexOf(login);
  const to = from + delta;
  if (from < 0 || to < 0 || to >= logins.length) return;
  logins.splice(to, 0, logins.splice(from, 1)[0]);
  saveOrder(logins).then(() => $(`#channel-list [data-move="${delta}"][data-login="${CSS.escape(login)}"]`)?.focus());
}

// Pointer-based drag: works for mouse, pen and touch without the HTML5 DnD quirks.
function wireDrag() {
  const list = $('#channel-list');
  let drag = null;
  list.addEventListener('pointerdown', ev => {
    const grip = ev.target.closest('[data-grip]');
    if (!grip || ev.button > 0) return;
    ev.preventDefault();
    grip.setPointerCapture(ev.pointerId);
    dragging = true;
    drag = { login: grip.dataset.grip, row: grip.closest('.channel-row'), target: null, after: false };
    drag.row.classList.add('dragging');
  });
  list.addEventListener('pointermove', ev => {
    if (!drag) return;
    const rows = $$('.channel-row', list).filter(r => r !== drag.row);
    $$('.drop-before,.drop-after', list).forEach(r => r.classList.remove('drop-before', 'drop-after'));
    let target = null, after = false;
    for (const r of rows) {
      const box = r.getBoundingClientRect();
      if (ev.clientY < box.top + box.height / 2) { target = r; break; }
      target = r;
      after = true;
    }
    drag.target = target;
    drag.after = after;
    target?.classList.add(after ? 'drop-after' : 'drop-before');
  });
  const finish = () => {
    if (!drag) return;
    const { login, row, target, after } = drag;
    drag = null;
    dragging = false;
    row.classList.remove('dragging');
    $$('.drop-before,.drop-after', list).forEach(r => r.classList.remove('drop-before', 'drop-after'));
    if (!target) {
      if (renderChannels.pending) renderChannels();
      renderChannels.pending = false;
      return;
    }
    const logins = state.channels.map(c => c.login).filter(l => l !== login);
    let idx = logins.indexOf(target.dataset.login);
    if (after) idx++;
    logins.splice(idx, 0, login);
    if (logins.join() !== state.channels.map(c => c.login).join()) saveOrder(logins);
    else if (renderChannels.pending) renderChannels();
    renderChannels.pending = false;
  };
  list.addEventListener('pointerup', finish);
  list.addEventListener('pointercancel', finish);
}

async function openChannel(login) {
  const dialog = $('#channel-dialog');
  const detail = await api(`/api/channels/${encodeURIComponent(login)}?days=30`);
  const c = detail.channel;
  $('#detail-name').textContent = login;
  $('#detail-info').textContent = c ? (c.online ? `Live · ${c.game || ''} · ${fmt(c.viewers)} Zuschauer` : 'Gerade offline') : '';
  $('#detail-points').textContent = c ? fmt(c.points) : '–';
  $('#detail-gain').textContent = c ? '+' + fmt(c.gainedToday) : '–';
  $('#detail-week').textContent = c ? '+' + fmt(c.gainedWeek) : '–';
  $('#detail-chart').innerHTML = detailChart(detail.history);
  const events = detail.events.filter(e => !(e.type === 'POINTS' && e.detail === 'WATCH')).slice(0, 30);
  $('#detail-events').innerHTML = events.length ? events.map(feedItem).join('') : '<li class="feed-empty">Noch nichts passiert.</li>';
  const toggle = $('#detail-raffle');
  toggle.checked = c ? c.raffles : true;
  toggle.onchange = () => setRaffle(login, toggle.checked);
  if (!dialog.open) dialog.showModal();
}

function detailChart(points) {
  if (!points || points.length < 2) return '<p class="muted">Für einen Verlauf braucht es noch ein paar Messpunkte.</p>';
  const W = 640, H = 240, L = 60, R = 604, T = 35, B = 190;
  const t0 = new Date(points[0].ts).getTime(), t1 = new Date(points.at(-1).ts).getTime() || t0 + 1;
  const vals = points.map(p => p.points);
  const min = Math.min(...vals), max = Math.max(...vals), span = max - min || 1;
  const x = t => L + (t - t0) / ((t1 - t0) || 1) * (R - L);
  const y = v => B - (v - min) / span * (B - T);
  const line = points.map(p => `${x(new Date(p.ts).getTime()).toFixed(1)},${y(p.points).toFixed(1)}`).join(' ');
  const d = ts => new Date(ts).toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' });
  return `<svg class="detail-chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="Punktestand von ${fmt(vals[0])} auf ${fmt(vals.at(-1))}"><path class="gridline" d="M${L} ${T}H${R}M${L} ${(T + B) / 2}H${R}M${L} ${B}H${R}"/><text class="axis" x="0" y="${T + 5}">${fmt(max)}</text><text class="axis" x="0" y="${B + 5}">${fmt(min)}</text><polyline class="chart-path" points="${line}" pathLength="1"/><text class="axis" x="${L}" y="223">${d(points[0].ts)}</text><text class="axis" x="${R - 40}" y="223">${d(points.at(-1).ts)}</text></svg>`;
}

async function setRaffle(login, enabled) {
  try {
    await api(`/api/channels/${encodeURIComponent(login)}/raffles`, { method: 'POST', body: { enabled } });
    const c = state.channels.find(ch => ch.login === login);
    if (c) c.raffles = enabled;
    state.settings = null;
    toast(enabled ? `Raffles bei ${login} wieder an.` : `Keine Raffles mehr bei ${login}.`);
  } catch (e) {
    toast(e.message, true);
  }
}

// ---------- Drops ----------
async function loadDrops() {
  state.drops = await api('/api/drops');
  renderDrops();
}

function renderDrops() {
  const d = state.drops;
  if (!d) return;
  $('#campaign-count').textContent = d.campaigns.length;
  $('#inventory-count').textContent = d.claimed.length;
  $('#campaigns').innerHTML = d.campaigns.length ? d.campaigns.map(c => {
    const ends = c.endsAt ? new Date(c.endsAt).toLocaleString('de-DE', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : '';
    const drops = c.drops.map(dr => {
      const pct = dr.required ? Math.min(100, Math.round(dr.watched / dr.required * 100)) : 0;
      return `<li><div class="progress-label"><span>${esc(dr.name)}</span><strong>${dr.claimed ? '<span class="claimed">Geclaimt</span>' : pct + ' %'}</strong></div><progress value="${dr.claimed ? 100 : pct}" max="100" aria-label="${esc(dr.name)} Fortschritt"></progress><small>${fmt(dr.watched)} / ${fmt(dr.required)} Minuten</small></li>`;
    }).join('');
    const art = c.image ? `<img class="box-art" src="${esc(c.image)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : '<span class="box-fallback"><svg><use href="#i-chest"/></svg></span>';
    return `<article class="campaign panel">${art}<div class="campaign-body"><div class="row-between"><span class="${c.linked ? 'positive' : 'warning-text'}">${c.linked ? '✓ Account verknüpft' : 'Account nicht verknüpft'}</span><span class="meta">${ends ? 'bis ' + esc(ends) : ''}</span></div><p class="eyebrow">${esc(c.game || '')}</p><h2>${esc(c.name)}</h2><ul class="drop-list">${drops}</ul></div></article>`;
  }).join('') : `<div class="panel"><h2>Gerade keine laufende Kampagne.</h2><p class="meta">Kampagnen erscheinen, sobald du bei einem Drop-Kanal Watch-Time sammelst.${d.updatedAt ? ' Zuletzt geprüft ' + esc(dateTime(d.updatedAt)) + '.' : ''}</p></div>`;

  $('#inventory-grid').innerHTML = d.claimed.length ? d.claimed.map(i => `<article class="inventory-item">${i.image ? `<img src="${esc(i.image)}" alt="" loading="lazy" referrerpolicy="no-referrer" width="48" height="48">` : '<svg aria-hidden="true"><use href="#i-chest"/></svg>'}<h3>${esc(i.name)}</h3><p>${esc(i.game || '')}</p><small>${i.at ? esc(dateTime(i.at)) : ''}</small></article>`).join('') : '<p class="meta">Noch nichts im Inventar.</p>';

  const scouted = d.scouted || [];
  $('#scouted').innerHTML = `<h2>Drop-Suche</h2><p class="meta">${d.scoutEnabled ? (scouted.length ? 'Diese Kanäle hat der Bot für laufende Kampagnen dazugeholt.' : 'Aktiv. Noch keine zusätzlichen Kanäle nötig.') : 'Ausgeschaltet. Nur deine eigenen Kanäle sammeln Drops.'}</p>${scouted.length ? `<ul>${scouted.map(s => `<li class="${s.online ? 'live' : ''}">${esc(s.login)} · ${esc(s.game || 'offline')}</li>`).join('')}</ul>` : ''}`;
  $('#nav-drops').hidden = !d.campaigns.length;
  setPhase();
  enter('drops');
}

// ---------- Raffles ----------
const RAFFLE_STATUS = { PENDING: ['Gleich dabei', ''], JOINED: ['Eingetragen', ''], WON: ['Gewonnen', 'won'], SKIPPED: ['Übersprungen', 'skipped'], FAILED: ['Abgelehnt', 'failed'] };

async function loadRaffles() {
  const [raffles, channels, settings] = await Promise.all([api('/api/raffles'), api('/api/channels'), api('/api/settings')]);
  Object.assign(state, { raffles, channels, settings });
  renderRaffles();
}

function renderRaffles() {
  const r = state.raffles;
  if (!r) return;
  $('#raffle-summary').textContent = `${fmt(r.joinedToday)} heute eingetragen · ${fmt(r.wonTotal)} Gewinne erkannt`;
  const problem = $('#raffle-problem');
  problem.hidden = !r.status.problem;
  problem.textContent = r.status.problem || '';
  $('#raffle-chat-state').textContent = !r.status.enabled ? 'Ausgeschaltet' : r.status.connected ? `Hört in ${r.status.channels.length} Chats mit` : 'Chat nicht verbunden';
  $('#raffle-log').innerHTML = r.entries.length ? r.entries.map(e => {
    const [label, cls] = RAFFLE_STATUS[e.status] || [e.status, ''];
    return `<article class="raffle-entry"><header><strong>${esc(e.channel)}</strong><time>${esc(dateTime(e.ts))}</time></header><blockquote>„${esc(e.triggerMessage)}“</blockquote><p>${esc(e.triggerUser)} · Befehl: <strong>${esc(e.command)}</strong>${e.detail ? ' · ' + esc(e.detail) : ''}</p><span class="result ${cls}">${label}</span></article>`;
  }).join('') : '<p class="muted">Noch keine Verlosung erkannt. Sobald ein Bot oder Mod eine ankündigt, landet sie hier.</p>';

  const toggles = $('#raffle-toggles');
  toggles.replaceChildren(...state.channels.filter(c => c.source !== 'drops').map(c => {
    const label = document.createElement('label');
    label.className = 'switch-row';
    const text = document.createElement('span');
    text.textContent = c.login + (c.online ? ' · live' : '');
    const input = document.createElement('input');
    input.type = 'checkbox';
    input.setAttribute('role', 'switch');
    input.checked = c.raffles;
    input.addEventListener('change', () => setRaffle(c.login, input.checked));
    label.append(text, input);
    return label;
  }));
  if (!toggles.children.length) toggles.innerHTML = '<p class="muted">Sobald der Bot deine Kanäle kennt, kannst du sie hier einzeln schalten.</p>';

  const s = state.settings?.raffle;
  $('#raffle-patterns').innerHTML = s ? `<li><strong>Befehle</strong><span>${s.joinCommands.map(c => '!' + esc(c)).join(', ')}</span></li><li><strong>Bots</strong><span>${s.bots.map(esc).join(', ')}</span></li><li><strong>Timing</strong><span>${s.minDelaySeconds}–${s.maxDelaySeconds} s Verzögerung, ${Math.round(s.cooldownSeconds / 60)} Min. Pause</span></li>` : '';
  enter('raffles');
}

// ---------- Bot & Twitch login ----------
async function loadBot() {
  const [bot, twitch, overview] = await Promise.all([api('/api/bot?logs=300'), api('/api/twitch'), api('/api/overview')]);
  state.bot = bot.bot;
  state.logs = bot.logs;
  state.twitch = twitch;
  state.overview = overview;
  renderBot();
  renderLogs();
  enter('bot');
}

function renderBot() {
  const b = state.bot;
  if (!b) return;
  renderBotMini(b);
  $('#bot-state').textContent = BOT_TEXT[b.status]?.[1] || b.status;
  $('#account-state').textContent = b.user ? `Eingeloggt als ${b.user}` : 'Kein Twitch-Account verbunden';
  $('#started-at').textContent = b.startedAt ? dateTime(b.startedAt) : '–';
  $('#active-count').textContent = state.overview ? `${state.overview.watching.length} von 2` : '–';
  $('#restart-count').textContent = fmt(b.restarts);
  const toggle = $('#bot-toggle');
  const running = ['RUNNING', 'STARTING', 'BACKOFF'].includes(b.status);
  toggle.textContent = running ? 'Bot stoppen' : 'Bot starten';
  toggle.dataset.action = running ? 'stop' : 'start';
  toggle.disabled = b.status === 'NEEDS_LOGIN';
  $('#bot-restart').disabled = !running;
  renderLogin();
}

function renderLogin() {
  const t = state.twitch;
  const box = $('#device-login');
  if (!t) return;
  clearTimeout(renderLogin.poll);
  if (t.state === 'PENDING') {
    $('#login-text').textContent = 'Öffne Twitch auf einem Gerät deiner Wahl und gib diesen Code ein. Der Bot wartet.';
    box.innerHTML = `<div class="device-code waiting-code" aria-label="Code ${esc(t.userCode)}">${esc(t.userCode)}</div><a class="primary external-link" href="${esc(t.verificationUri)}" target="_blank" rel="noopener noreferrer">twitch.tv/activate <span aria-hidden="true">↗</span></a><p class="waiting">Wartet auf Bestätigung · gültig bis ${esc(time(t.codeExpiresAt))} Uhr</p><button class="secondary" data-twitch="cancel">Abbrechen</button>`;
    renderLogin.poll = setTimeout(async () => {
      state.twitch = await api('/api/twitch');
      if (state.twitch.state === 'READY') {
        toast('Twitch ist verbunden. Der Bot startet.');
        loadBot();
      } else renderLogin();
    }, 3000);
    return;
  }
  if (t.state === 'READY') {
    $('#login-text').textContent = 'Verbunden. Der Token liegt nur auf dem Server, nie im Browser.';
    box.innerHTML = `<div class="login-done"><p><strong>${esc(t.login)}</strong></p>${t.canChat ? '<p class="positive">✓ Chat-Rechte für Raffles vorhanden</p>' : '<p class="scope-warning">Dem Token fehlt chat:edit. Für Raffles einmal neu verbinden.</p>'}<div class="button-row"><button class="secondary" data-twitch="login">Neu verbinden</button><button class="secondary" data-twitch="logout">Trennen</button></div></div>`;
    return;
  }
  $('#login-text').textContent = t.state === 'EXPIRED'
    ? 'Twitch hat den Zugang beendet. Einmal neu verbinden, dann läuft alles weiter.'
    : 'Einmal verbinden. Du bekommst einen Code, den du auf twitch.tv/activate eingibst.';
  box.innerHTML = `${t.error ? `<p class="scope-warning">${esc(t.error)}</p>` : ''}<button class="primary" data-twitch="login">Mit Twitch verbinden</button>`;
}

const IMPORTANT = /(\+\d+|claim|raid|drop|join|login|start|load|error|fehl|streak|online|offline|moment|bonus)/i;

function logItem(l) {
  const li = document.createElement('li');
  li.className = `level-${l.level}`;
  const t = document.createElement('time');
  t.textContent = new Date(l.ts).toLocaleTimeString('de-DE');
  const level = document.createElement('span');
  level.className = 'log-level';
  level.textContent = l.level === 'STDERR' ? 'SYS' : l.level;
  const msg = document.createElement('span');
  msg.textContent = l.msg;
  li.append(t, level, msg);
  return li;
}

function renderLogs() {
  const onlyImportant = $('#log-filter').checked;
  const logs = state.logs.filter(l => !onlyImportant || l.level !== 'INFO' || IMPORTANT.test(l.msg)).slice(-300).reverse();
  $('#bot-log').replaceChildren(...logs.map(logItem));
}

async function botAction(action) {
  try {
    state.bot = await api(`/api/bot/${action}`, { method: 'POST' });
    renderBot();
    toast({ start: 'Bot startet.', stop: 'Bot pausiert.', restart: 'Bot startet neu.' }[action]);
    setTimeout(() => loadBot().catch(() => {}), 4000);
  } catch (e) {
    toast(e.message, true);
  }
}

async function twitchAction(action) {
  try {
    if (action === 'logout' && !confirmLogout()) return;
    state.twitch = await api(`/api/twitch/${action}`, { method: 'POST' });
    renderLogin();
    if (action === 'logout') loadBot();
  } catch (e) {
    toast(e.message, true);
  }
}

function confirmLogout() {
  // Native confirm is fine here: rare, destructive-ish, and keyboard accessible.
  return window.confirm('Twitch wirklich trennen? Der Bot hört dann auf zu sammeln.');
}

// ---------- Settings ----------
const splitList = v => v.split(/[\s,;]+/).map(s => s.trim()).filter(Boolean);

async function loadSettings() {
  state.settings = await api('/api/settings');
  fillSettings(state.settings);
  enter('settings');
}

function fillSettings(s) {
  const f = $('#settings-form');
  const prio = s.priority.join(',');
  if (![...f.priority.options].some(o => o.value === prio)) f.priority.add(new Option(s.priority.join(' → '), prio));
  f.priority.value = prio;
  f.followers.checked = s.followers;
  f.watchStreak.checked = s.watchStreak;
  f.followRaid.checked = s.followRaid;
  f.claimMoments.checked = s.claimMoments;
  f.scoutEnabled.checked = s.dropScout.enabled;
  f.scoutLinked.checked = s.dropScout.requireLinked;
  f.scoutCount.value = s.dropScout.channelsPerGame;
  f.raffleEnabled.checked = s.raffle.enabled;
  f.joinCommands.value = s.raffle.joinCommands.map(c => '!' + c).join(', ');
  f.bots.value = s.raffle.bots.join(', ');
  f.minDelay.value = s.raffle.minDelaySeconds;
  f.maxDelay.value = s.raffle.maxDelaySeconds;
  f.cooldown.value = s.raffle.cooldownSeconds;
  f.streamers.value = s.streamers.join(', ');
  f.blacklist.value = s.blacklist.join(', ');
}

async function saveSettings(ev) {
  ev.preventDefault();
  const f = ev.target;
  const s = state.settings;
  const body = {
    followers: f.followers.checked,
    streamers: splitList(f.streamers.value),
    blacklist: splitList(f.blacklist.value),
    priority: f.priority.value.split(','),
    followRaid: f.followRaid.checked,
    claimMoments: f.claimMoments.checked,
    watchStreak: f.watchStreak.checked,
    dropScout: { enabled: f.scoutEnabled.checked, channelsPerGame: Number(f.scoutCount.value), requireLinked: f.scoutLinked.checked },
    raffle: {
      enabled: f.raffleEnabled.checked,
      joinCommands: splitList(f.joinCommands.value),
      bots: splitList(f.bots.value),
      disabledChannels: s?.raffle.disabledChannels || [],
      minDelaySeconds: Number(f.minDelay.value),
      maxDelaySeconds: Number(f.maxDelay.value),
      cooldownSeconds: Number(f.cooldown.value),
    },
  };
  const msg = $('#save-message');
  try {
    state.settings = await api('/api/settings', { method: 'PUT', body });
    fillSettings(state.settings);
    msg.textContent = 'Gespeichert. Der Bot übernimmt die Änderungen.';
    toast('Einstellungen gespeichert.');
  } catch (e) {
    msg.textContent = e.message;
    toast('Nicht gespeichert: ' + e.message, true);
  }
}

// ---------- Live updates ----------
let refreshTimer = null;
function scheduleRefresh() {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(() => loadScreen(currentScreen()), 1500);
}

function connectLive() {
  const es = new EventSource('/api/live');
  const label = $('#live-label');
  es.onopen = () => { label.textContent = 'Live'; };
  es.onerror = () => { label.textContent = 'Verbindung weg, versuche neu …'; };
  es.addEventListener('state', () => {
    if (['overview', 'channels', 'bot'].includes(currentScreen())) scheduleRefresh();
  });
  es.addEventListener('event', ev => {
    const e = JSON.parse(ev.data);
    if (state.overview) state.overview.feed.unshift(e);
    if (currentScreen() === 'overview' && !FEED_HIDDEN(e)) {
      $('#feed').insertAdjacentHTML('afterbegin', feedItem(e));
      $('#feed li')?.classList.add('fresh');
      $('.feed-empty')?.remove();
      scheduleRefresh();
    }
    if (currentScreen() === 'raffles' && e.type.startsWith('RAFFLE')) scheduleRefresh();
  });
  es.addEventListener('drops', ev => {
    const d = JSON.parse(ev.data);
    if (state.drops) Object.assign(state.drops, { campaigns: d.campaigns, claimed: d.claimed, updatedAt: d.receivedAt });
    if (currentScreen() === 'drops') renderDrops();
    if (currentScreen() === 'overview') renderNextDrop();
  });
  es.addEventListener('log', ev => {
    const l = JSON.parse(ev.data);
    state.logs.push(l);
    if (state.logs.length > 1000) state.logs.shift();
    if (currentScreen() === 'bot' && (!$('#log-filter').checked || l.level !== 'INFO' || IMPORTANT.test(l.msg))) {
      $('#bot-log').prepend(logItem(l));
    }
  });
  es.addEventListener('bot', ev => {
    state.bot = JSON.parse(ev.data);
    renderBotMini(state.bot);
    if (currentScreen() === 'bot') renderBot();
  });
}

// ---------- Wiring ----------
function wire() {
  window.addEventListener('hashchange', showScreen);
  window.addEventListener('resize', moveNavIndicator);
  $('#channel-search').addEventListener('input', renderChannels);
  $('#channel-filter').addEventListener('change', renderChannels);
  $('#log-filter').addEventListener('change', renderLogs);
  const videoToggle = $('#video-toggle');
  videoToggle.checked = videoWanted();
  videoToggle.addEventListener('change', () => {
    localStorage.setItem('video', videoToggle.checked ? '1' : '0');
    playing.clear();
    if (state.overview) renderSlots();
  });
  wireDrag();
  document.addEventListener('click', ev => {
    const move = ev.target.closest('[data-move]');
    if (move) {
      moveChannel(move.dataset.login, Number(move.dataset.move));
      return;
    }
    const open = ev.target.closest('[data-channel]');
    if (open) openChannel(open.dataset.channel).catch(e => toast(e.message, true));
    const tw = ev.target.closest('[data-twitch]');
    if (tw) twitchAction(tw.dataset.twitch);
    const tab = ev.target.closest('[data-drop-tab]');
    if (tab) {
      $$('[data-drop-tab]').forEach(b => { const sel = b === tab; b.classList.toggle('selected', sel); b.setAttribute('aria-pressed', sel); });
      $('#campaigns').hidden = tab.dataset.dropTab !== 'campaigns';
      $('#inventory').hidden = tab.dataset.dropTab !== 'inventory';
    }
  });
  $('#bot-toggle').addEventListener('click', ev => botAction(ev.currentTarget.dataset.action));
  $('#bot-restart').addEventListener('click', () => botAction('restart'));
  $('#settings-form').addEventListener('submit', saveSettings);
  $('#add-channel').addEventListener('submit', async ev => {
    ev.preventDefault();
    const input = $('#add-channel-input');
    try {
      state.channels = await api('/api/channels', { method: 'POST', body: { login: input.value.trim() } });
      toast(`${input.value.trim()} kommt dazu.`);
      input.value = '';
      state.settings = null;
      renderChannels();
    } catch (e) {
      toast(e.message, true);
    }
  });
  $('#logout').addEventListener('click', async () => {
    await fetch('/logout', { method: 'POST', credentials: 'same-origin', headers: { 'X-XSRF-TOKEN': csrfToken() || '' } });
    location.href = '/bye.html';
  });
  setInterval(() => { if (!document.hidden) loadScreen(currentScreen()); }, 60_000);
}

async function init() {
  initTheme();
  wire();
  try {
    const me = await api('/api/me');
    $('#me-name').textContent = me.name || 'Du';
    $('#me-initial').textContent = (me.name || '?').charAt(0).toUpperCase();
  } catch (e) {
    return;
  }
  showScreen();
  connectLive();
}

init();
