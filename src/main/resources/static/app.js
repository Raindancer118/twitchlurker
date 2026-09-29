'use strict';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const nf = new Intl.NumberFormat();
const fmt = n => nf.format(Math.round(n || 0));
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const twitchLink = login => login
  ? `<a class="channel-link" href="https://www.twitch.tv/${encodeURIComponent(login)}" target="_blank" rel="noopener noreferrer">${esc(login)}</a>` : '';
const time = iso => iso ? new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) : '';
const dateTime = iso => iso ? new Date(iso).toLocaleString(undefined, { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : '–';
const SCREENS = { overview: 'Overview', channels: 'Channels', drops: 'Drops', raffles: 'Raffles', bot: 'Bot & Login', settings: 'Settings' };

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
  const bar = $('.tabbar');
  const active = $('.tabbar a[aria-current]');
  if (!bar || !active || !bar.offsetWidth) return;
  let pill = $('.tab-indicator', bar);
  const first = !pill;
  if (first) {
    pill = document.createElement('span');
    pill.className = 'tab-indicator';
    pill.setAttribute('aria-hidden', 'true');
    bar.prepend(pill);
  }
  if (first) pill.style.transition = 'none';
  pill.style.width = active.offsetWidth - 12 + 'px';
  pill.style.transform = `translateX(${active.offsetLeft + 6}px)`;
  if (first) {
    void pill.offsetWidth;
    pill.style.transition = '';
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
    location.href = '/';
    throw new Error('Not signed in');
  }
  const data = res.headers.get('content-type')?.includes('json') ? await res.json() : null;
  if (!res.ok) {
    const err = new Error(data?.errors?.join(' · ') || data?.detail || data?.error || `Error ${res.status}`);
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
  RUNNING: ['Bot running', 'Running'],
  STARTING: ['Bot starting', 'Starting …'],
  BACKOFF: ['Bot restarting', 'Short break'],
  STOPPED: ['Bot paused', 'Paused'],
  NEEDS_LOGIN: ['Twitch missing', 'Waiting for Twitch'],
};

function since(iso) {
  if (!iso) return '';
  const mins = Math.max(0, Math.round((Date.now() - new Date(iso)) / 60000));
  if (mins < 60) return `For ${mins} min`;
  const h = Math.floor(mins / 60);
  if (h < 48) return `For ${h} h ${mins % 60} min`;
  return `For ${Math.floor(h / 24)} days, ${h % 24} h`;
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
    ? '<strong>Twitch token expired.</strong> Reconnect once, your loot is safe.'
    : '<strong>No Twitch account connected yet.</strong> Without it the bot collects nothing.';
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
  const who = twitchLink(e.login);
  switch (e.type) {
    case 'POINTS': {
      const titles = { WATCH: 'Watch time credited', CLAIM: 'Bonus collected', WATCH_STREAK: 'Watch streak kept', RAID: 'Raid bonus' };
      return ['+', titles[e.detail] || 'Channel points', `${who} · +${fmt(e.amount)} points`];
    }
    case 'BONUS': return ['+', 'Bonus chest opened', who];
    case 'MOMENT': return ['✧', 'Moment claimed', who];
    case 'RAID': return ['↪', 'Followed a raid', `${who} → ${twitchLink(e.detail) || '?'}`];
    case 'DROP': return ['◇', 'Drop claimed', esc(e.detail || '')];
    case 'ADDED': return ['↗', e.detail === 'drops' ? 'Added for drops' : e.detail === 'follow' ? 'New follow' : 'Channel added', who];
    case 'REMOVED': return ['↘', 'Unfollowed', who];
    case 'LURK': return ['~', 'Said hi in chat', who];
    case 'ONLINE': return ['●', 'Went live', who];
    case 'RAFFLE': return ['✓', 'Joined a raffle', `${who} · sent ${esc(e.detail || '')}`];
    case 'RAFFLE_WON': return ['★', 'Won a raffle', `${who} · ${esc(e.detail || '')}`];
    default: return ['·', esc(e.type), who];
  }
}

const FEED_HIDDEN = e => e.type === 'OFFLINE' || (e.type === 'POINTS' && e.detail === 'WATCH');

function feedItem(e) {
  const [sym, title, sub] = eventView(e);
  return `<li><span class="event-symbol" data-type="${esc(e.type)}" aria-hidden="true">${sym}</span><div><strong>${title}</strong><p>${sub}</p></div><time datetime="${esc(e.ts)}">${time(e.ts)}</time></li>`;
}

// Slots keep their DOM between refreshes so a running player is never reloaded.
const slotEls = [];
const playing = new Set();

function slotShell(index) {
  const el = document.createElement('article');
  el.className = 'panel slot';
  el.innerHTML = `<div class="slot-head"><p class="eyebrow"><svg class="pin-icon" hidden><use href="#i-pin"/></svg><span class="slot-label"></span></p><label class="visually-hidden" for="slot-select-${index}">Assign slot ${index + 1}</label><select id="slot-select-${index}" data-slot="${index}"></select></div>
<div class="slot-media"></div>
<div class="slot-body"><div class="slot-identity"><span class="avatar"></span><div><h3></h3><p></p></div><button class="icon-button" type="button" aria-label="Open channel"><svg><use href="#i-arrow"/></svg></button></div><div class="slot-meta"></div><p class="slot-note" hidden></p><div class="slot-foot"><span class="meta slot-tags"></span><strong></strong></div></div>`;
  el.querySelector('select').addEventListener('change', ev => assignSlot(index, ev.target.value || null));
  return el;
}

function renderSlotMedia(media, login) {
  if (media.dataset.login === (login || '') && media.dataset.mode === (login && (videoWanted() || playing.has(login)) ? 'video' : 'image')) return;
  media.dataset.login = login || '';
  if (!login) {
    media.dataset.mode = 'none';
    media.innerHTML = '<p class="slot-empty-media">Free. As soon as someone is live, the bot lurks here.</p>';
    return;
  }
  const video = videoWanted() || playing.has(login);
  media.dataset.mode = video ? 'video' : 'image';
  if (video) {
    media.innerHTML = `<span class="live-tag">LIVE</span><iframe src="${esc(playerUrl(login))}" title="Stream of ${esc(login)}" allow="autoplay; fullscreen" allowfullscreen loading="lazy"></iframe>`;
  } else {
    media.innerHTML = `<span class="live-tag">LIVE</span><img src="${esc(previewUrl(login))}" alt="" loading="lazy" referrerpolicy="no-referrer"><button class="play" type="button"><svg><use href="#i-play"/></svg>Watch stream</button>`;
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
    el.querySelector('.slot-label').textContent = `Slot ${i + 1} · ${slot.pinned ? 'pinned' : 'automatic'}`;
    el.querySelector('.pin-icon').hidden = !slot.pinned;
    const select = el.querySelector('select');
    if (document.activeElement !== select) {
      const options = ['<option value="">Automatic</option>', ...channels.map(c => `<option value="${esc(c.login)}">${esc(c.login)}${c.online ? ' · live' : ''}</option>`)];
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
    el.querySelector('h3').innerHTML = s ? twitchLink(s.login) : 'Nobody right now';
    el.querySelector('.slot-identity p').textContent = s ? (s.title || '') : (o.bot.status !== 'RUNNING' ? 'The bot isn’t running.' : 'None of your channels is live.');
    const open = el.querySelector('.slot-identity .icon-button');
    open.hidden = !s;
    if (s) open.dataset.channel = s.login;
    el.querySelector('.slot-meta').innerHTML = s ? `<span>${esc(s.game || '–')}</span><span>${fmt(s.viewers)} viewers</span><span>${fmt(s.minutesWatched)} min lurked</span>` : '';
    const note = el.querySelector('.slot-note');
    note.hidden = !(slot.pinned && !slot.pinnedOnline);
    note.textContent = slot.pinned && !slot.pinnedOnline ? `${slot.pinned} is offline. The slot runs automatically until then.` : '';
    el.querySelector('.slot-tags').innerHTML = s ? [s.streakPending ? '<span class="tag hot">Streak</span>' : '', s.dropsEligible ? '<span class="tag hot">Drops</span>' : '', s.source === 'drops' ? '<span class="tag">Drop hunt</span>' : ''].join(' ') : '';
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
    toast(login ? `Slot ${index + 1} now belongs to ${login}.` : `Slot ${index + 1} is automatic again.`);
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
    box.innerHTML = `${icon}<p class="eyebrow">Next drop</p><h2>No campaign in progress.</h2><p class="meta">As soon as Twitch hands out drops for your games, the progress shows up here.</p><a class="text-link" href="#drops">Go to drops <svg><use href="#i-arrow"/></svg></a>`;
    return;
  }
  const pct = Math.round(best.pct * 100);
  box.innerHTML = `${icon}<p class="eyebrow">Next drop · ${esc(best.c.game || '')}</p><h2>${esc(best.d.name)}</h2><div class="progress-label"><span>${fmt(best.d.watched)} of ${fmt(best.d.required)} minutes</span><strong>${pct}%</strong></div><progress value="${pct}" max="100" aria-label="${esc(best.d.name)}, ${pct} percent"></progress><p class="meta">${fmt(best.d.required - best.d.watched)} minutes of watch time to go.</p><a class="text-link" href="#drops">Go to drops <svg><use href="#i-arrow"/></svg></a>`;
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

  $('#today-label').textContent = new Date().toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' });
  $('#overview-sub').textContent = o.bot.status === 'RUNNING'
    ? `${o.online} of ${o.tracked} channels are live. The bot lurks in two of them.`
    : 'The bot is taking a break. Head to “Bot & Login” to continue.';
  $('#session-state').textContent = `${BOT_TEXT[o.bot.status]?.[0] || ''}${o.bot.user ? ' as ' + o.bot.user : ''}`;

  setPhase();
  animateNumber($('#stat-today'), o.stats.pointsToday, { bump: true });
  animateNumber($('#stat-week'), o.stats.pointsWeek);
  animateNumber($('#stat-total'), o.stats.pointsTotal);
  animateNumber($('#stat-drops'), o.stats.dropsTotal);
  animateNumber($('#stat-raffles'), o.stats.rafflesToday);

  renderSlots();
  const feed = o.feed.filter(e => !FEED_HIDDEN(e)).slice(0, 12);
  $('#feed').innerHTML = feed.length ? feed.map(feedItem).join('') : '<li class="feed-empty">Quiet so far. The first points arrive as soon as someone goes live.</li>';
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
  const status = c.watching ? 'being lurked' : c.online ? `live · ${c.game || ''}` : 'offline';
  const tags = [
    c.slot ? `<span class="tag hot">Slot ${c.slot}</span>` : '',
    c.streakPending ? '<span class="tag hot">Streak</span>' : '',
    c.dropsEligible ? '<span class="tag hot">Drops</span>' : '',
    c.source === 'drops' ? '<span class="tag">Drop hunt</span>' : c.source === 'extra' ? '<span class="tag">Extra</span>' : '',
    c.raffles ? '' : '<span class="tag">Raffles off</span>',
  ].join('');
  const spark = sparkline(c.spark);
  const order = sortable
    ? `<button class="grip" type="button" aria-label="Move ${esc(c.login)}" data-grip="${esc(c.login)}"><svg><use href="#i-grip"/></svg></button><span class="rank">${c.rank}</span><span class="move"><button type="button" data-move="-1" data-login="${esc(c.login)}" aria-label="Move ${esc(c.login)} up"><svg><use href="#i-up"/></svg></button><button type="button" data-move="1" data-login="${esc(c.login)}" aria-label="Move ${esc(c.login)} down"><svg><use href="#i-down"/></svg></button></span>`
    : `<span class="rank">${c.rank}</span>`;
  return `<li class="channel-row" data-login="${esc(c.login)}" data-live="${c.online}"><div class="order-cell">${order}</div>
<div class="channel-name"><span class="avatar">${initial(c.login)}</span><div><strong>${twitchLink(c.login)}</strong><small>${c.online ? '<span class="live-dot"></span>' : ''}${esc(status)} ${tags}</small></div></div>
<div class="value"><span class="mobile-label">Points </span>${fmt(c.points)}</div><div class="gain"><span class="mobile-label">Today </span>+${fmt(c.gainedToday)}</div>
${spark ? `<svg class="sparkline" viewBox="0 0 120 34" role="img" aria-label="Points history of ${esc(c.login)}, 7 days"><polyline points="${spark}" pathLength="1"/></svg>` : '<span class="meta spark-empty">no history yet</span>'}
<button class="icon-button channel-open" data-channel="${esc(c.login)}" aria-label="Open ${esc(c.login)}"><svg><use href="#i-arrow"/></svg></button></li>`;
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
    ? 'Drag the handle (or use the arrows) to reorder. The order decides who gets an automatic slot once streaks and drops are taken care of.'
    : 'Reordering only works without search and filter.';
  const online = state.channels.filter(c => c.online).length;
  $('#channels-sub').textContent = `${state.channels.length} channels, ${online} of them live.`;
  if (state.settings) $('#priority-label').textContent = state.settings.priority.map(p => ({ STREAK: 'Streak', DROPS: 'Drops', ORDER: 'Order', SUBSCRIBED: 'Sub bonus', POINTS_ASCENDING: 'fewest points', POINTS_DESCENDING: 'most points' }[p] || p)).join(' → ');
  enter('channels');
}

async function saveOrder(logins) {
  const byLogin = Object.fromEntries(state.channels.map(c => [c.login, c]));
  state.channels = logins.map((l, i) => ({ ...byLogin[l], rank: i + 1 }));
  renderChannels();
  try {
    await api('/api/order', { method: 'PUT', body: { order: logins } });
    state.settings = null;
    toast('Order saved. The bot follows it right away.');
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
  $('#detail-name').innerHTML = twitchLink(login);
  $('#detail-info').textContent = c ? (c.online ? `Live · ${c.game || ''} · ${fmt(c.viewers)} viewers` : 'Offline right now') : '';
  $('#detail-points').textContent = c ? fmt(c.points) : '–';
  $('#detail-gain').textContent = c ? '+' + fmt(c.gainedToday) : '–';
  $('#detail-week').textContent = c ? '+' + fmt(c.gainedWeek) : '–';
  $('#detail-chart').innerHTML = detailChart(detail.history);
  const events = detail.events.filter(e => !(e.type === 'POINTS' && e.detail === 'WATCH')).slice(0, 30);
  $('#detail-events').innerHTML = events.length ? events.map(feedItem).join('') : '<li class="feed-empty">Nothing happened yet.</li>';
  const toggle = $('#detail-raffle');
  toggle.checked = c ? c.raffles : true;
  toggle.onchange = () => setRaffle(login, toggle.checked);
  if (!dialog.open) dialog.showModal();
}

function detailChart(points) {
  if (!points || points.length < 2) return '<p class="muted">A few more data points are needed for a chart.</p>';
  const W = 640, H = 240, L = 60, R = 604, T = 35, B = 190;
  const t0 = new Date(points[0].ts).getTime(), t1 = new Date(points.at(-1).ts).getTime() || t0 + 1;
  const vals = points.map(p => p.points);
  const min = Math.min(...vals), max = Math.max(...vals), span = max - min || 1;
  const x = t => L + (t - t0) / ((t1 - t0) || 1) * (R - L);
  const y = v => B - (v - min) / span * (B - T);
  const line = points.map(p => `${x(new Date(p.ts).getTime()).toFixed(1)},${y(p.points).toFixed(1)}`).join(' ');
  const d = ts => new Date(ts).toLocaleDateString(undefined, { day: '2-digit', month: '2-digit' });
  return `<svg class="detail-chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="Points from ${fmt(vals[0])} to ${fmt(vals.at(-1))}"><path class="gridline" d="M${L} ${T}H${R}M${L} ${(T + B) / 2}H${R}M${L} ${B}H${R}"/><text class="axis" x="0" y="${T + 5}">${fmt(max)}</text><text class="axis" x="0" y="${B + 5}">${fmt(min)}</text><polyline class="chart-path" points="${line}" pathLength="1"/><text class="axis" x="${L}" y="223">${d(points[0].ts)}</text><text class="axis" x="${R - 40}" y="223">${d(points.at(-1).ts)}</text></svg>`;
}

async function setRaffle(login, enabled) {
  try {
    await api(`/api/channels/${encodeURIComponent(login)}/raffles`, { method: 'POST', body: { enabled } });
    const c = state.channels.find(ch => ch.login === login);
    if (c) c.raffles = enabled;
    state.settings = null;
    toast(enabled ? `Raffles on again for ${login}.` : `No more raffles for ${login}.`);
  } catch (e) {
    toast(e.message, true);
  }
}

// ---------- Drops ----------
async function loadDrops() {
  state.drops = await api('/api/drops');
  renderDrops();
}

function codeButton(campaign, reward, redeemUrl, name) {
  if (!campaign || !reward || !/^https:\/\//.test(redeemUrl || '')) return '';
  return `<button type="button" class="secondary code-button" data-code-campaign="${esc(campaign)}" data-code-reward="${esc(reward)}" data-code-redeem="${esc(redeemUrl)}" data-code-name="${esc(name || '')}">Show code</button>`;
}

async function openCode({ codeCampaign, codeReward, codeRedeem, codeName }) {
  const dialog = $('#code-dialog');
  $('#code-title').textContent = codeName || 'Reward';
  $('#code-status').textContent = 'Asking Twitch for your code…';
  $('#code-box').hidden = true;
  $('#code-expires').textContent = '';
  const redeem = $('#code-redeem');
  redeem.hidden = !/^https:\/\//.test(codeRedeem || '');
  redeem.href = redeem.hidden ? '#' : codeRedeem;
  redeem.querySelector('span').textContent = redeem.hidden ? '' : `Redeem on ${new URL(codeRedeem).hostname.replace(/^www\./, '')}`;
  if (!dialog.open) dialog.showModal();
  try {
    const res = await api(`/api/drops/code?campaign=${encodeURIComponent(codeCampaign)}&reward=${encodeURIComponent(codeReward)}`);
    $('#code-value').textContent = res.code;
    $('#code-box').hidden = false;
    $('#code-status').textContent = 'Straight from your Twitch drops inventory. Keep it to yourself.';
    $('#code-expires').textContent = res.expiresAt ? `Valid until ${dateTime(res.expiresAt)}` : '';
  } catch (e) {
    $('#code-status').textContent = e.message;
  }
}

function renderDrops() {
  const d = state.drops;
  if (!d) return;
  // Claimed rewards live in the inventory; progress only shows what is still to earn or to claim.
  const open = d.campaigns.map(c => ({ ...c, drops: c.drops.filter(dr => !dr.claimed) })).filter(c => c.drops.length);
  $('#campaign-count').textContent = open.length;
  $('#inventory-count').textContent = d.claimed.length;
  $('#campaigns').innerHTML = open.length ? open.map(c => {
    const ends = c.endsAt ? new Date(c.endsAt).toLocaleString(undefined, { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : '';
    const drops = c.drops.map(dr => {
      const pct = dr.required ? Math.min(100, Math.round(dr.watched / dr.required * 100)) : 0;
      const label = dr.expired ? '<span class="warning-text">Expired, can no longer be claimed</span>'
        : dr.claimable ? '<span class="claimed">Ready to claim</span>' : pct + '%';
      const claimLink = dr.claimable ? '<a class="text-link" href="https://www.twitch.tv/drops/inventory" target="_blank" rel="noopener noreferrer">Claim on Twitch <svg><use href="#i-arrow"/></svg></a>' : '';
      return `<li><div class="progress-label"><span>${esc(dr.name)}</span><strong>${label}</strong></div><progress value="${dr.claimable ? 100 : pct}" max="100" aria-label="${esc(dr.name)} progress"></progress><small>${fmt(dr.watched)} / ${fmt(dr.required)} minutes</small>${claimLink}</li>`;
    }).join('');
    const art = c.image ? `<img class="box-art" src="${esc(c.image)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : '<span class="box-fallback"><svg><use href="#i-chest"/></svg></span>';
    const linkState = c.quest ? '<span class="positive">Twitch quest · no link needed</span>'
      : `<span class="${c.linked ? 'positive' : 'warning-text'}">${c.linked ? '✓ Account linked' : 'Account not linked'}</span>`;
    return `<article class="campaign panel">${art}<div class="campaign-body"><div class="row-between">${linkState}<span class="meta">${ends ? 'until ' + esc(ends) : ''}</span></div><p class="eyebrow">${esc(c.game || '')}</p><h2>${esc(c.name)}</h2><ul class="drop-list">${drops}</ul></div></article>`;
  }).join('') : `<div class="panel"><h2>Nothing in progress.</h2><p class="meta">Campaigns show up as soon as you collect watch time on a drop channel. Claimed rewards are in your inventory.${d.updatedAt ? ' Last checked ' + esc(dateTime(d.updatedAt)) + '.' : ''}</p></div>`;

  $('#inventory-grid').innerHTML = d.claimed.length ? d.claimed.map(i => `<article class="inventory-item">${i.image ? `<img src="${esc(i.image)}" alt="" loading="lazy" referrerpolicy="no-referrer" width="48" height="48">` : '<svg aria-hidden="true"><use href="#i-chest"/></svg>'}<h3>${esc(i.name)}</h3><p>${esc(i.game || '')}</p><small>${i.at ? esc(dateTime(i.at)) : ''}</small>${codeButton(i.campaignId, i.id, i.redeemUrl, i.name)}</article>`).join('') : '<p class="meta">Nothing in your inventory yet.</p>';

  const scouted = d.scouted || [];
  $('#scouted').innerHTML = `<h2>Drop hunt</h2><p class="meta">${d.scoutEnabled ? (scouted.length ? 'The bot added these channels for running campaigns.' : 'Active. No extra channels needed yet.') : 'Off. Only your own channels collect drops.'}</p>${scouted.length ? `<ul>${scouted.map(s => `<li class="${s.online ? 'live' : ''}">${twitchLink(s.login)} · ${esc(s.game || 'offline')}</li>`).join('')}</ul>` : ''}`;
  $('#nav-drops').hidden = !d.campaigns.length;
  $('.tab-dot').hidden = !d.campaigns.length;
  renderWatchlist();
  renderCatalogue();
  setPhase();
  enter('drops');
}

function isWatched(game) {
  const g = (game || '').toLowerCase();
  return (state.drops?.watchGames || []).some(w => w.toLowerCase() === g);
}

function renderWatchlist() {
  const games = state.drops?.watchGames || [];
  $('#watch-chips').innerHTML = games.length
    ? games.map(g => `<li class="chip"><span>${esc(g)}</span><button type="button" class="chip-remove" data-unwatch="${esc(g)}" aria-label="Stop watching ${esc(g)}">×</button></li>`).join('')
    : '<li class="meta">Not watching anything yet.</li>';
  const names = [...new Set((state.drops?.catalogue || []).map(c => c.game).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'de'));
  const html = names.map(n => `<option value="${esc(n)}"></option>`).join('');
  if ($('#game-suggestions').dataset.html !== html) {
    $('#game-suggestions').innerHTML = html;
    $('#game-suggestions').dataset.html = html;
  }
}

function timeWindow(c) {
  const fmtD = iso => new Date(iso).toLocaleString(undefined, { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  if (c.status === 'UPCOMING' && c.startAt) return `from ${fmtD(c.startAt)}`;
  if (!c.endAt) return '';
  const hours = Math.round((new Date(c.endAt) - Date.now()) / 3600000);
  return hours < 48 ? `${Math.max(0, hours)} h left` : `until ${fmtD(c.endAt)}`;
}

function renderCatalogue() {
  const all = state.drops?.catalogue || [];
  $('#catalogue-count').textContent = all.length;
  const q = ($('#drop-search').value || '').trim().toLowerCase();
  const filter = $('#drop-filter').value;
  const list = all.filter(c => {
    if (filter === 'watched' && !c.watched && !isWatched(c.game)) return false;
    if (filter === 'active' && c.status !== 'ACTIVE') return false;
    if (filter === 'upcoming' && c.status !== 'UPCOMING') return false;
    if (filter === 'linked' && c.linked !== true) return false;
    if (filter === 'quests' && !c.quest) return false;
    if (!q) return true;
    return [c.game, c.name, ...c.rewards.map(r => r.name)].some(v => (v || '').toLowerCase().includes(q));
  });
  const updated = state.drops?.catalogueUpdatedAt;
  const access = state.drops?.catalogueAccess;
  $('#catalogue-meta').textContent = all.length
    ? `${list.length} of ${all.length} campaigns${updated ? ' · as of ' + time(updated) : ''} · sources: Twitch quests + community list twitch-drops-api.sunkwi.com`
    : access === 'unavailable'
      ? 'The drop list is unreachable right now. The bot still looks for watched games directly on Twitch.'
      : 'The bot is loading the campaigns (takes a minute or two after startup).';
  $('#catalogue-grid').innerHTML = list.map(c => {
    const watched = c.watched || isWatched(c.game);
    const art = c.image ? `<img class="box-art" src="${esc(c.image)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : '<span class="box-fallback"><svg><use href="#i-chest"/></svg></span>';
    const rewards = c.rewards.slice(0, 6).map(r => `<li>${r.image ? `<img src="${esc(r.image)}" alt="" loading="lazy" referrerpolicy="no-referrer" width="28" height="28">` : ''}<span>${esc(r.name)}</span><small>${r.subs ? `${fmt(r.subs)} sub${r.subs > 1 ? 's' : ''}` : `${fmt(r.minutes)} min`}</small></li>`).join('');
    const more = c.rewards.length > 6 ? `<li class="meta">+${c.rewards.length - 6} more</li>` : '';
    const where = c.watchable === false ? 'Needs a bought or gifted sub, lurking can\'t earn it'
      : c.quest ? (c.game ? `Twitch quest · any ${c.game} stream` : 'Twitch quest · any stream')
      : c.channels.length ? `Only on ${c.channels.slice(0, 3).join(', ')}${c.channels.length > 3 ? ` +${c.channels.length - 3}` : ''}` : 'On all drop streams';
    const linkState = c.quest ? 'no account link needed' : c.linked === true ? '<span class="positive">✓ linked</span>' : c.linked === false ? 'not linked' : 'link status unknown';
    const tag = c.completed ? '<span class="tag done">Earned ✓</span>' : `<span class="tag ${c.status === 'ACTIVE' ? 'hot' : ''}">${c.status === 'ACTIVE' ? 'Running' : 'Soon'}</span>`;
    const watchButton = c.game ? `<button type="button" class="secondary watch-toggle" data-watch-game="${esc(c.game)}" aria-pressed="${watched}">${watched ? 'Watching ✓' : 'Watch'}</button>` : '';
    const link = c.linked !== true && c.linkUrl && /^https:\/\//.test(c.linkUrl) ? `<a class="text-link" href="${esc(c.linkUrl)}" target="_blank" rel="noopener noreferrer">Link account <svg><use href="#i-arrow"/></svg></a>` : '';
    return `<article class="campaign panel catalogue-item${watched ? ' watched' : ''}">${art}<div class="campaign-body"><div class="row-between">${tag}<span class="meta">${esc(timeWindow(c))}</span></div><p class="eyebrow">${esc(c.game || (c.quest ? 'All of Twitch' : ''))}</p><h2>${esc(c.name)}</h2><ul class="reward-list">${rewards}${more}</ul><p class="meta">${esc(where)} · ${linkState}</p><div class="button-row">${link}${watchButton}</div></div></article>`;
  }).join('') || '<div class="panel"><p class="meta">Nothing found.</p></div>';
}

async function setWatchGames(games) {
  try {
    const saved = await api('/api/drops/watch', { method: 'PUT', body: { games } });
    state.drops.watchGames = saved.dropScout.games;
    state.settings = null;
    renderWatchlist();
    renderCatalogue();
  } catch (e) {
    toast(e.message, true);
  }
}

function toggleWatch(game) {
  const games = state.drops?.watchGames || [];
  const watched = games.some(g => g.toLowerCase() === game.toLowerCase());
  setWatchGames(watched ? games.filter(g => g.toLowerCase() !== game.toLowerCase()) : [...games, game])
    .then(() => toast(watched ? `No longer watching ${game}.` : `Watching ${game}. When there are drops, the bot lurks matching streams.`));
}

// ---------- Raffles ----------
const RAFFLE_STATUS = { PENDING: ['Joining soon', ''], JOINED: ['Entered', ''], WON: ['Won', 'won'], SKIPPED: ['Skipped', 'skipped'], FAILED: ['Rejected', 'failed'] };

async function loadRaffles() {
  const [raffles, channels, settings] = await Promise.all([api('/api/raffles'), api('/api/channels'), api('/api/settings')]);
  Object.assign(state, { raffles, channels, settings });
  renderRaffles();
}

function renderRaffles() {
  const r = state.raffles;
  if (!r) return;
  $('#raffle-summary').textContent = `${fmt(r.joinedToday)} entered today · ${fmt(r.wonTotal)} wins spotted`;
  const problem = $('#raffle-problem');
  problem.hidden = !r.status.problem;
  problem.textContent = r.status.problem || '';
  $('#raffle-chat-state').textContent = !r.status.enabled ? 'Off' : r.status.connected ? `Listening in ${r.status.channels.length} chats` : 'Chat not connected';
  $('#raffle-log').innerHTML = r.entries.length ? r.entries.map(e => {
    const [label, cls] = RAFFLE_STATUS[e.status] || [e.status, ''];
    return `<article class="raffle-entry"><header><strong>${esc(e.channel)}</strong><time>${esc(dateTime(e.ts))}</time></header><blockquote>„${esc(e.triggerMessage)}“</blockquote><p>${esc(e.triggerUser)} · command: <strong>${esc(e.command)}</strong>${e.detail ? ' · ' + esc(e.detail) : ''}</p><span class="result ${cls}">${label}</span></article>`;
  }).join('') : '<p class="muted">No raffle spotted yet. As soon as a bot or mod announces one, it shows up here.</p>';

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
  if (!toggles.children.length) toggles.innerHTML = '<p class="muted">Once the bot knows your channels, you can switch them here one by one.</p>';

  const s = state.settings?.raffle;
  $('#raffle-patterns').innerHTML = s ? `<li><strong>Commands</strong><span>${s.joinCommands.map(c => '!' + esc(c)).join(', ')}</span></li><li><strong>Bots</strong><span>${s.bots.map(esc).join(', ')}</span></li><li><strong>Timing</strong><span>${s.minDelaySeconds}–${s.maxDelaySeconds} s delay, ${Math.round(s.cooldownSeconds / 60)} min pause</span></li>` : '';
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
  $('#account-state').textContent = b.user ? `Signed in as ${b.user}` : 'No Twitch account connected';
  $('#started-at').textContent = b.startedAt ? dateTime(b.startedAt) : '–';
  $('#active-count').textContent = state.overview ? `${state.overview.watching.length} of 2` : '–';
  $('#restart-count').textContent = fmt(b.restarts);
  const toggle = $('#bot-toggle');
  const running = ['RUNNING', 'STARTING', 'BACKOFF'].includes(b.status);
  toggle.textContent = running ? 'Stop bot' : 'Start bot';
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
    $('#login-text').textContent = 'Open Twitch on any device and enter this code. The bot is waiting.';
    box.innerHTML = `<div class="device-code waiting-code" aria-label="Code ${esc(t.userCode)}">${esc(t.userCode)}</div><a class="primary external-link" href="${esc(t.verificationUri)}" target="_blank" rel="noopener noreferrer">twitch.tv/activate <span aria-hidden="true">↗</span></a><p class="waiting">Waiting for confirmation · valid until ${esc(time(t.codeExpiresAt))}</p><button class="secondary" data-twitch="cancel">Cancel</button>`;
    renderLogin.poll = setTimeout(async () => {
      state.twitch = await api('/api/twitch');
      if (state.twitch.state === 'READY') {
        toast('Twitch is connected. The bot is starting.');
        loadBot();
      } else renderLogin();
    }, 3000);
    return;
  }
  if (t.state === 'READY') {
    $('#login-text').textContent = 'Connected. The token lives on the server only, never in the browser.';
    box.innerHTML = `<div class="login-done"><p><strong>${esc(t.login)}</strong></p>${t.canChat ? '<p class="positive">✓ Chat permission for raffles granted</p>' : '<p class="scope-warning">The token lacks chat:edit. Reconnect once for raffles.</p>'}<div class="button-row"><button class="secondary" data-twitch="login">Reconnect</button><button class="secondary" data-twitch="logout">Disconnect</button></div></div>`;
    return;
  }
  $('#login-text').textContent = t.state === 'EXPIRED'
    ? 'Twitch ended the access. Reconnect once and everything carries on.'
    : 'Connect once. You get a code to enter at twitch.tv/activate.';
  box.innerHTML = `${t.error ? `<p class="scope-warning">${esc(t.error)}</p>` : ''}<button class="primary" data-twitch="login">Connect Twitch</button>`;
}

const IMPORTANT = /(\+\d+|claim|raid|drop|join|login|start|load|error|fehl|streak|online|offline|moment|bonus)/i;

function logItem(l) {
  const li = document.createElement('li');
  li.className = `level-${l.level}`;
  const t = document.createElement('time');
  t.textContent = new Date(l.ts).toLocaleTimeString(undefined);
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
    toast({ start: 'Bot starting.', stop: 'Bot paused.', restart: 'Bot restarting.' }[action]);
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
  return window.confirm('Really disconnect Twitch? The bot will stop collecting.');
}

// ---------- Settings ----------
const splitList = v => v.split(/[\s,;]+/).map(s => s.trim()).filter(Boolean);

// Set on the first edit; a (slow) reload must not overwrite what the user is typing.
let settingsDirty = false;
document.addEventListener('input', e => { if (e.target.closest('#settings-form')) settingsDirty = true; });

async function loadSettings() {
  state.settings = await api('/api/settings');
  if (!settingsDirty) fillSettings(state.settings);
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
  f.lurkEnabled.checked = s.lurk.enabled;
  f.lurkMessage.value = s.lurk.message;
  const repeat = String(s.lurk.repeatMinutes);
  if (![...f.lurkRepeat.options].some(o => o.value === repeat)) f.lurkRepeat.add(new Option(`Every ${repeat} minutes`, repeat));
  f.lurkRepeat.value = repeat;
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
    dropScout: { enabled: f.scoutEnabled.checked, channelsPerGame: Number(f.scoutCount.value), requireLinked: f.scoutLinked.checked, games: s?.dropScout.games || [] },
    lurk: { enabled: f.lurkEnabled.checked, message: f.lurkMessage.value.trim(), repeatMinutes: Number(f.lurkRepeat.value) },
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
    settingsDirty = false;
    fillSettings(state.settings);
    msg.textContent = 'Saved. The bot applies the changes.';
    toast('Settings saved.');
  } catch (e) {
    msg.textContent = e.message;
    toast('Not saved: ' + e.message, true);
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
  es.onerror = () => { label.textContent = 'Connection lost, retrying …'; };
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
  es.addEventListener('campaigns', ev => {
    const c = JSON.parse(ev.data);
    if (state.drops) {
      state.drops.catalogue = c.campaigns;
      state.drops.catalogueAccess = c.access;
      state.drops.catalogueUpdatedAt = c.receivedAt;
      if (currentScreen() === 'drops') {
        renderWatchlist();
        renderCatalogue();
      }
    }
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
  $('#code-copy').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText($('#code-value').textContent);
      toast('Code copied');
    } catch {
      getSelection().selectAllChildren($('#code-value'));
      toast('Press Ctrl+C to copy', true);
    }
  });
  // The code only stays in the page while the dialog is open.
  $('#code-dialog').addEventListener('close', () => { $('#code-value').textContent = ''; });
  $('#drop-search').addEventListener('input', renderCatalogue);
  $('#drop-filter').addEventListener('change', renderCatalogue);
  $('#watch-form').addEventListener('submit', ev => {
    ev.preventDefault();
    const input = $('#watch-input');
    const game = input.value.trim();
    if (!game) return;
    input.value = '';
    if (!isWatched(game)) toggleWatch(game);
  });
  $('#refresh-follows').addEventListener('click', async () => {
    try {
      const r = await api('/api/channels/refresh', { method: 'POST' });
      toast(r.requested ? 'Syncing follows. New channels show up in a moment.' : 'The bot isn’t running.');
      setTimeout(() => loadChannels().catch(() => {}), 8000);
    } catch (e) {
      toast(e.message, true);
    }
  });
  document.addEventListener('click', ev => {
    const code = ev.target.closest('[data-code-campaign]');
    if (code) {
      openCode(code.dataset);
      return;
    }
    const watch = ev.target.closest('[data-watch-game]');
    if (watch && watch.dataset.watchGame) {
      toggleWatch(watch.dataset.watchGame);
      return;
    }
    const unwatch = ev.target.closest('[data-unwatch]');
    if (unwatch) {
      setWatchGames((state.drops?.watchGames || []).filter(g => g !== unwatch.dataset.unwatch));
      return;
    }
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
      $('#catalogue').hidden = tab.dataset.dropTab !== 'catalogue';
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
      toast(`Adding ${input.value.trim()}.`);
      input.value = '';
      state.settings = null;
      renderChannels();
    } catch (e) {
      toast(e.message, true);
    }
  });
  $('#logout-mobile').addEventListener('click', () => $('#logout').click());
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
    $('#me-name').textContent = me.name || 'You';
    $('#me-initial').textContent = (me.name || '?').charAt(0).toUpperCase();
    $('#me-name-mobile').textContent = me.name || 'You';
    $('#me-initial-mobile').textContent = (me.name || '?').charAt(0).toUpperCase();
  } catch (e) {
    return;
  }
  showScreen();
  connectLive();
}

init();
