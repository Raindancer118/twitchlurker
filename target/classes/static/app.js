'use strict';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const nf = new Intl.NumberFormat('de-DE');
const fmt = n => nf.format(Math.round(n || 0));
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const time = iso => iso ? new Date(iso).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' }) : '';
const dateTime = iso => iso ? new Date(iso).toLocaleString('de-DE', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) + ' Uhr' : '–';
const SCREENS = { overview: 'Übersicht', channels: 'Kanäle', drops: 'Drops', raffles: 'Raffles', bot: 'Bot / Login', settings: 'Einstellungen' };

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
  };
  if (document.startViewTransition && !matchMedia('(prefers-reduced-motion: reduce)').matches) document.startViewTransition(apply);
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

function artClass(game) {
  let h = 0;
  for (const ch of String(game || '')) h = (h * 31 + ch.codePointAt(0)) >>> 0;
  return { symbol: h % 2 ? 'sea' : 'forest', variant: `art-${(h >> 1) % 4}` };
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

function streamCard(s) {
  const art = artClass(s.game);
  const tag = s.streakPending ? 'Streak sichern' : s.dropsEligible ? 'Drops aktiv' : s.source === 'drops' ? 'Drop-Suche' : 'Wird gelurkt';
  const gained = state.channels.find(c => c.login === s.login)?.gainedToday;
  return `<article class="stream-card"><div class="stream-art ${art.variant}"><svg class="landscape" aria-hidden="true"><use href="#${art.symbol}"/></svg><div class="art-top"><span class="live-tag">LIVE</span><span class="watch-tag">${tag}</span></div><span class="game-title">${esc(s.game || 'Live')}</span><span class="art-caption">${esc(fmt(s.viewers))} ZUSCHAUER</span></div>
<div class="stream-body"><div class="stream-identity"><span class="avatar">${initial(s.login)}</span><div><h3>${esc(s.login)}</h3><p>${esc(s.title || '')}</p></div><button class="icon-button" data-channel="${esc(s.login)}" aria-label="${esc(s.login)} öffnen"><svg><use href="#i-arrow"/></svg></button></div>
<div class="stream-meta"><span>${esc(s.game || '–')}</span><span>${fmt(s.viewers)} Zuschauer</span><span>${fmt(s.minutesWatched)} Min. gelurkt</span></div>
<div class="stream-bottom"><span><span class="status-dot"></span> <span class="watch-status">Wird gelurkt</span></span><strong>${gained != null ? '+' + fmt(gained) : fmt(s.points)} <small>${gained != null ? 'Punkte heute' : 'Punkte'}</small></strong></div></div></article>`;
}

function placeholderCard(text) {
  return `<article class="stream-card placeholder"><div class="stream-art art-0"><svg class="landscape" aria-hidden="true"><use href="#forest"/></svg><span class="game-title">Freier Platz</span></div><div class="stream-body"><div class="stream-identity"><div><h3>Gerade niemand</h3><p>${esc(text)}</p></div></div></div></article>`;
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
  if (!best) {
    box.innerHTML = `<div class="eyebrow">DEIN NÄCHSTER DROP</div><svg class="chest-art" aria-hidden="true"><use href="#chest"/></svg><h2>Gerade keine<br>Kampagne offen.</h2><p class="muted">Sobald Twitch Drops für deine Spiele verteilt, taucht der Fortschritt hier auf.</p><a class="text-link" href="#drops">Zu deinen Drops <svg><use href="#i-arrow"/></svg></a>`;
    return;
  }
  const pct = Math.round(best.pct * 100);
  box.innerHTML = `<div class="eyebrow">DEIN NÄCHSTER DROP</div><svg class="chest-art" aria-hidden="true"><use href="#chest"/></svg><p class="game-label">${esc((best.c.game || '').toUpperCase())}</p><h2>Ein bisschen näher<br>am nächsten Schatz.</h2><div class="progress-label"><span>${esc(best.d.name)}</span><strong>${pct} %</strong></div><progress value="${pct}" max="100" aria-label="${esc(best.d.name)}, ${pct} Prozent"></progress><p class="muted">Noch ${fmt(best.d.required - best.d.watched)} von ${fmt(best.d.required)} Minuten Watch-Time.</p><a class="text-link" href="#drops">Zu deinen Drops <svg><use href="#i-arrow"/></svg></a>`;
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

  $('#today-label').textContent = new Date().toLocaleDateString('de-DE', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).toUpperCase();
  const hour = new Date().getHours();
  const dayPart = hour < 11 ? 'Morgen' : hour < 17 ? 'Tag' : 'Abend';
  $('#overview-sub').textContent = o.bot.status === 'RUNNING'
    ? `Ein guter ${dayPart}, um nicht überall dabei zu sein. ${o.online} von ${o.tracked} Kanälen sind live.`
    : 'Der Bot macht gerade Pause. Unter „Bot / Login“ geht’s weiter.';
  $('#session-state').innerHTML = `${esc(BOT_TEXT[o.bot.status]?.[0] || '')}<small>${o.bot.user ? 'als ' + esc(o.bot.user) : ''}</small>`;

  $('#stat-today').textContent = fmt(o.stats.pointsToday);
  $('#stat-week').textContent = fmt(o.stats.pointsWeek);
  $('#stat-total').textContent = fmt(o.stats.pointsTotal);
  $('#stat-drops').textContent = `${fmt(o.stats.dropsTotal)} Drops`;
  $('#stat-extra-sub').textContent = `${fmt(o.stats.rafflesToday)} Raffles heute · ${fmt(o.stats.bonusesToday)} Truhen`;

  $('#slot-count').textContent = `${o.watching.length} / 2`;
  const cards = o.watching.map(streamCard);
  const why = o.bot.status !== 'RUNNING' ? 'Der Bot läuft gerade nicht.' : o.online === 0 ? 'Keiner deiner Kanäle ist live.' : 'Der Bot wählt gleich aus.';
  while (cards.length < 2) cards.push(placeholderCard(why));
  $('#active-streams').innerHTML = cards.join('');

  const feed = o.feed.filter(e => !FEED_HIDDEN(e)).slice(0, 12);
  $('#feed').innerHTML = feed.length ? feed.map(feedItem).join('') : '<li class="feed-empty">Noch ruhig hier. Die ersten Punkte kommen, sobald jemand live ist.</li>';
  $('#nav-channels').textContent = o.tracked || '';
  $('#nav-drops').hidden = !(state.drops?.campaigns?.length);
  renderNextDrop();
}

// ---------- Channels ----------
function sparkline(values, w = 120, h = 34) {
  if (!values || values.length < 2) return '';
  const min = Math.min(...values), max = Math.max(...values), span = max - min || 1;
  return values.map((v, i) => `${(i / (values.length - 1) * (w - 4) + 2).toFixed(1)},${(h - 3 - (v - min) / span * (h - 6)).toFixed(1)}`).join(' ');
}

function channelRow(c) {
  const status = c.watching ? 'Wird gelurkt' : c.online ? `Live · ${c.game || ''}` : 'Offline';
  const tags = [
    c.streakPending ? '<span class="tag hot">Streak</span>' : '',
    c.dropsEligible ? '<span class="tag hot">Drops</span>' : '',
    c.source === 'drops' ? '<span class="tag">Drop-Suche</span>' : c.source === 'extra' ? '<span class="tag">Extra</span>' : '',
    c.raffles ? '' : '<span class="tag">Raffles aus</span>',
  ].join('');
  const spark = sparkline(c.spark);
  return `<article class="channel-row" data-live="${c.online}"><div class="channel-name"><span class="avatar">${initial(c.login)}</span><div><strong>${esc(c.login)}</strong><small>${esc(status)}</small><small class="channel-tags">${tags}</small></div></div>
<div class="value"><span class="mobile-label">Punktestand</span>${fmt(c.points)}</div><div class="gain"><span class="mobile-label">Heute</span>+${fmt(c.gainedToday)}</div>
${spark ? `<svg class="sparkline" viewBox="0 0 120 34" role="img" aria-label="Punkteverlauf ${esc(c.login)}, 7 Tage"><polyline points="${spark}"/></svg>` : '<span class="muted">Noch kein Verlauf</span>'}
<span class="channel-priority">${c.watching ? 'Aktiv' : c.online ? 'Live' : '–'}</span><button class="icon-button channel-open" data-channel="${esc(c.login)}" aria-label="${esc(c.login)} öffnen"><svg><use href="#i-arrow"/></svg></button></article>`;
}

async function loadChannels() {
  const [channels, settings] = await Promise.all([api('/api/channels'), state.settings ? state.settings : api('/api/settings')]);
  state.channels = channels;
  state.settings = settings;
  renderChannels();
}

function renderChannels() {
  const q = $('#channel-search').value.trim().toLowerCase();
  const filter = $('#channel-filter').value;
  const list = state.channels.filter(c => (!q || c.login.includes(q)) && (filter === 'all' || (filter === 'live') === c.online));
  $('#channel-list').innerHTML = list.map(channelRow).join('');
  $('#no-channels').hidden = list.length > 0;
  const online = state.channels.filter(c => c.online).length;
  $('#channels-sub').textContent = `${state.channels.length} Kanäle, ${online} davon live. Zwei Plätze zum Punktesammeln.`;
  if (state.settings) $('#priority-label').textContent = state.settings.priority.map(p => ({ STREAK: 'Streak', DROPS: 'Drops', ORDER: 'Reihenfolge', SUBSCRIBED: 'Abo-Bonus', POINTS_ASCENDING: 'wenigste Punkte', POINTS_DESCENDING: 'meiste Punkte' }[p] || p)).join(' → ');
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
  return `<svg class="detail-chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="Punktestand von ${fmt(vals[0])} auf ${fmt(vals.at(-1))}"><path class="gridline" d="M${L} ${T}H${R}M${L} ${(T + B) / 2}H${R}M${L} ${B}H${R}"/><text class="axis" x="0" y="${T + 5}">${fmt(max)}</text><text class="axis" x="0" y="${B + 5}">${fmt(min)}</text><polyline class="chart-path" points="${line}"/><text class="axis" x="${L}" y="223">${d(points[0].ts)}</text><text class="axis" x="${R - 40}" y="223">${d(points.at(-1).ts)}</text></svg>`;
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
    const art = artClass(c.game);
    const ends = c.endsAt ? new Date(c.endsAt).toLocaleString('de-DE', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : '';
    const drops = c.drops.map(dr => {
      const pct = dr.required ? Math.min(100, Math.round(dr.watched / dr.required * 100)) : 0;
      return `<li><div class="progress-label"><span>${esc(dr.name)}</span><strong>${dr.claimed ? '<span class="claimed">Geclaimt</span>' : pct + ' %'}</strong></div><progress value="${dr.claimed ? 100 : pct}" max="100" aria-label="${esc(dr.name)} Fortschritt"></progress><small class="muted">${fmt(dr.watched)} / ${fmt(dr.required)} Minuten</small></li>`;
    }).join('');
    return `<article class="campaign panel"><div class="campaign-art ${art.variant}"><svg class="landscape" aria-hidden="true"><use href="#${art.symbol}"/></svg><span class="art-caption">${esc((c.game || '').toUpperCase())}</span>${c.image ? `<img class="box-art" src="${esc(c.image)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : ''}</div>
<div class="campaign-body"><div class="row-between"><span class="${c.linked ? 'positive' : 'warning-text'}">${c.linked ? '✓ Account verknüpft' : 'Account nicht verknüpft'}</span><span class="muted">${ends ? 'Bis ' + esc(ends) : ''}</span></div><h2>${esc(c.name)}</h2><ul class="drop-list">${drops}</ul></div></article>`;
  }).join('') : `<div class="account-empty panel"><h2>Gerade keine laufende Kampagne.</h2><p class="muted">Kampagnen erscheinen, sobald du bei einem Drop-Kanal Watch-Time sammelst.${d.updatedAt ? ' Zuletzt geprüft ' + esc(dateTime(d.updatedAt)) + '.' : ''}</p></div>`;

  $('#inventory-grid').innerHTML = d.claimed.length ? d.claimed.map(i => `<article class="inventory-item">${i.image ? `<img src="${esc(i.image)}" alt="" loading="lazy" referrerpolicy="no-referrer" width="64" height="64">` : '<svg aria-hidden="true"><use href="#chest"/></svg>'}<h3>${esc(i.name)}</h3><p>${esc(i.game || '')}</p><small>${i.at ? esc(dateTime(i.at)) : ''}</small></article>`).join('') : '<p class="muted">Noch nichts im Inventar.</p>';

  const scouted = d.scouted || [];
  $('#scouted').innerHTML = `<h2>Drop-Suche</h2><p class="muted">${d.scoutEnabled ? (scouted.length ? 'Diese Kanäle hat der Bot für laufende Kampagnen dazugeholt.' : 'Aktiv. Noch keine zusätzlichen Kanäle nötig.') : 'Ausgeschaltet. Nur deine eigenen Kanäle sammeln Drops.'}</p>${scouted.length ? `<ul>${scouted.map(s => `<li class="${s.online ? 'live' : ''}">${esc(s.login)} · ${esc(s.game || 'offline')}</li>`).join('')}</ul>` : ''}`;
  $('#nav-drops').hidden = !d.campaigns.length;
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
    box.innerHTML = `<div class="device-code" aria-label="Code ${esc(t.userCode)}">${esc(t.userCode)}</div><a class="primary external-link" href="${esc(t.verificationUri)}" target="_blank" rel="noopener noreferrer">twitch.tv/activate <span aria-hidden="true">↗</span></a><p class="waiting">Wartet auf Bestätigung · gültig bis ${esc(time(t.codeExpiresAt))} Uhr</p><button class="secondary" data-twitch="cancel">Abbrechen</button>`;
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
    $('#login-text').textContent = 'Verbunden. Der Token bleibt verschlüsselt auf dem Server, nicht im Browser.';
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
  $('#channel-search').addEventListener('input', renderChannels);
  $('#channel-filter').addEventListener('change', renderChannels);
  $('#log-filter').addEventListener('change', renderLogs);
  document.addEventListener('click', ev => {
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
