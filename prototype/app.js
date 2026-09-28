'use strict';
const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const format = value => new Intl.NumberFormat('de-DE').format(value);
const channels = [
 {name:'Gronkh',game:'Minecraft',live:true,points:128450,gain:1850,streak:7,priority:'Streak',chart:[117200,118750,120300,121200,123850,126600,128450]},
 {name:'PietSmiet',game:'Sea of Thieves',live:true,points:86420,gain:1240,streak:4,priority:'Drops',chart:[78000,79300,80200,81800,82900,85180,86420]},
 {name:'Shurjoka',game:'World of Warcraft',live:true,points:42180,gain:610,streak:3,priority:'Platz 3',chart:[36500,37100,38000,40000,41000,41570,42180]},
 {name:'RocketBeansTV',game:'Just Chatting',live:true,points:56820,gain:750,streak:2,priority:'Platz 4',chart:[52000,52600,53500,54300,55200,56070,56820]},
 {name:'PhunkRoyal',game:'Baldur’s Gate 3',live:false,points:24150,gain:400,streak:5,priority:'Platz 5',chart:[20300,21600,21900,22600,23100,23750,24150]},
 {name:'HandOfBlood',game:'League of Legends',live:false,points:19700,gain:0,streak:1,priority:'Platz 6',chart:[15200,16700,17900,18200,19700,19700,19700]},
 {name:'Papaplatte',game:'Minecraft',live:false,points:15200,gain:0,streak:0,priority:'Platz 7',chart:[11000,12500,12500,14200,15200,15200,15200]},
 {name:'Bonjwa',game:'Satisfactory',live:false,points:12000,gain:0,streak:2,priority:'Platz 8',chart:[7000,7800,9200,9800,11300,12000,12000]}
];
let running = true;
let demoState = 'normal';
let toastTimer;
let eventCount = 0;
const labels = {overview:'Übersicht',channels:'Kanäle',drops:'Drops',raffles:'Raffles',bot:'Bot / Login',settings:'Einstellungen'};
function readStored(key, fallback) { try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; } }
function writeStored(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); return true; } catch { return false; } }
const savedTheme = readStored('loot-theme', null);
if (['dark','light'].includes(savedTheme)) document.documentElement.dataset.theme = savedTheme;
function toast(message) { clearTimeout(toastTimer); $('#toast').textContent = message; $('#toast').hidden = false; toastTimer = setTimeout(() => { $('#toast').hidden = true; }, 4500); }
function navigate() {
 const id = Object.hasOwn(labels, location.hash.slice(1)) ? location.hash.slice(1) : 'overview';
 const update = () => {
  $$('.screen').forEach(screen => {screen.hidden = screen.id !== id;});
  $$('nav a').forEach(link => {if(link.hash === `#${id}`) link.setAttribute('aria-current','page'); else link.removeAttribute('aria-current');});
  window.scrollTo(0, 0);
  $('#breadcrumb').textContent = labels[id]; document.title = `${labels[id]} · twitchlurker`;
 };
 if (document.startViewTransition && !matchMedia('(prefers-reduced-motion: reduce)').matches) document.startViewTransition(update); else update();
}
window.addEventListener('hashchange', navigate);
$('#theme-toggle').addEventListener('click', () => {
 const dark = document.documentElement.dataset.theme ? document.documentElement.dataset.theme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
 document.documentElement.dataset.theme = dark ? 'light' : 'dark'; writeStored('loot-theme', dark ? 'light' : 'dark');
});
function sparkline(values) {
 const min = Math.min(...values), max = Math.max(...values);
 return values.map((v,i) => `${i*20},${29-(v-min)/(max-min || 1)*25}`).join(' ');
}
function renderChannels() {
 const query = $('#channel-search').value.toLowerCase().trim(), filter = $('#channel-filter').value;
 $('#channel-list').replaceChildren();
 channels.filter(c => c.name.toLowerCase().includes(query) && (filter==='all' || c.live === (filter==='live'))).forEach(c => {
  // All interpolated values here come from the fixed, local mock dataset.
  const row = document.createElement('article'); row.className='channel-row';
  row.innerHTML=`<div class="channel-name"><span class="avatar">${c.name[0]}</span><div><strong>${c.name}</strong><small>${c.live?'Live':'Offline'} · ${c.game}</small><small>${c.streak ? `${c.streak} Streams in Folge` : 'Noch keine Streak'}</small></div></div><div class="value"><span class="mobile-label">Punktestand</span>${format(c.points)}</div><div class="gain"><span class="mobile-label">Heute</span>+${format(c.gain)}</div><svg class="sparkline" viewBox="0 0 120 34" role="img" aria-label="Punkteverlauf ${c.name}: von ${format(c.chart[0])} auf ${format(c.points)}"><polyline points="${sparkline(c.chart)}"/></svg><span class="channel-priority">${c.priority}</span><button class="icon-button channel-open" data-channel="${c.name}" aria-label="${c.name} öffnen"><svg><use href="#i-arrow"/></svg></button>`;
  $('#channel-list').append(row);
 });
 $('#no-channels').hidden = $('#channel-list').children.length > 0;
}
$('#channel-search').addEventListener('input', renderChannels);
$('#channel-filter').addEventListener('change', renderChannels);
document.addEventListener('click', event => {
 const button = event.target.closest('[data-channel]');
 if (!button) return;
 const c = channels.find(channel => channel.name === button.dataset.channel);
 $('#detail-name').textContent=c.name; $('#detail-info').textContent=`${c.game} · ${c.live?'Gerade live':'Zurzeit offline'} · Priorität: ${c.priority}`;
 $('#detail-points').textContent=format(c.points); $('#detail-gain').textContent=`+${format(c.gain)}`; $('#detail-streak').textContent=`${c.streak} Streams`;
 const min=Math.floor(c.chart[0]/1000)*1000, max=Math.ceil(c.points/1000)*1000;
 const points=c.chart.map((v,i)=>`${64+i*88},${190-(v-min)/(max-min || 1)*155}`).join(' ');
 $('#detail-chart').innerHTML=`<svg class="detail-chart" viewBox="0 0 640 240" role="img" aria-label="Punktestand in sieben Tagen von ${format(c.chart[0])} auf ${format(c.points)}"><path class="gridline" d="M60 35H604M60 112H604M60 190H604"/><text x="0" y="40">${format(max/1000)}k</text><text x="0" y="195">${format(min/1000)}k</text><polyline class="chart-path" points="${points}"/>${c.chart.map((v,i)=>`<text x="${52+i*88}" y="223">${22+i}.09.</text>`).join('')}</svg>`;
 $('#channel-dialog').showModal();
});
const inventory = ['Gilded Phoenix-Hut','Obsidian-Steuerrad','Reittier: Himmelsdrache','Seefahrer-Kompass','Forest Jacket','Obsidian-Fernrohr','Abenteurer-Rucksack','Piratenstiefel','Kleiner Begleiter','Rust Werkzeugkiste','Phoenix-Handschuhe','Goldener Anker'];
function renderInventory() {
 $('#inventory-grid').replaceChildren();
 inventory.forEach((name,i)=>{
  const item=document.createElement('article'); item.className='inventory-item';
  const art=document.createElementNS('http://www.w3.org/2000/svg','svg');art.setAttribute('aria-hidden','true');art.innerHTML='<use href="#chest"/>';
  const title=document.createElement('h3');title.textContent=name;
  const game=document.createElement('p');game.textContent=i===0&&name==='Reisetruhe des Entdeckers'?'World of Warcraft':['Sea of Thieves','Sea of Thieves','World of Warcraft','Sea of Thieves','Rust'][i%5];
  const status=document.createElement('small');status.textContent=`✓ Geclaimt · ${i===0&&name==='Reisetruhe des Entdeckers'?'28':'27'}.09.2026`;
  item.append(art,title,game,status);$('#inventory-grid').append(item);
 }); $('#inventory-count').textContent=inventory.length;
}
$$('[data-drop-tab]').forEach(button=>button.addEventListener('click',()=>{
 $$('[data-drop-tab]').forEach(tab=>{const selected=tab===button;tab.classList.toggle('selected',selected);tab.setAttribute('aria-pressed',String(selected));});
 $('#campaigns').hidden=button.dataset.dropTab!=='campaigns';$('#inventory').hidden=button.dataset.dropTab!=='inventory';
}));
$('#claim-drop').addEventListener('click',()=>{
 inventory.unshift('Reisetruhe des Entdeckers');renderInventory();$('#claim-drop').disabled=true;$('#claim-drop').textContent='✓ Geclaimt · im Inventar';
 $('.stat-extra strong').textContent=`${inventory.length} Drops`;toast('Reisetruhe im Demo-Inventar abgelegt.');
});
$('#link-game').addEventListener('click',()=>{ $('#link-state').textContent='✓ Account verknüpft (Demo)';$('#link-state').className='positive';$('#link-game').textContent='✓ Verknüpft';$('#link-game').disabled=true;toast('Rust-Verknüpfung simuliert. Kampagne wartet auf einen freien Kanalplatz.');});
const raffles=[
 ['20:41','RocketBeansTV','StreamElements','Type !join to enter the giveaway!','!join','Eingetragen · einmal gesendet'],
 ['20:12','Gronkh','Nightbot','Giveaway geöffnet! Mit !raffle bist du dabei.','!raffle','Eingetragen · einmal gesendet'],
 ['19:55','PietSmiet','Moobot','Congratulations Raindancer118, you won!','!ticket','Gewonnen erkannt · Nachricht prüfen'],
 ['19:20','Shurjoka','StreamElements','Type !join to enter!','Keiner','Übersprungen · Kanal deaktiviert'],
 ['18:43','PietSmiet','Moobot','Raffle started! Type !ticket to join.','!ticket','Eingetragen · einmal gesendet']
];
raffles.forEach(([time,channel,bot,message,command,result])=>{
 const row=document.createElement('article');row.className='raffle-entry';row.innerHTML=`<header><strong>${channel}</strong><time>${time} Uhr</time></header><blockquote>„${message}“</blockquote><p>${bot} · Gesendeter Befehl: <strong>${command}</strong></p><span class="result ${command==='Keiner'?'skipped':''}">${result}</span>`;$('#raffle-log').append(row);
});
const toggleState=readStored('loot-raffles',{});
channels.forEach(c=>{
 const label=document.createElement('label');label.className='switch-row';const text=document.createElement('span');text.textContent=c.name;const input=document.createElement('input');input.type='checkbox';input.role='switch';input.checked=typeof toggleState[c.name]==='boolean'?toggleState[c.name]:c.name!=='Shurjoka';
 input.addEventListener('change',()=>{toggleState[c.name]=input.checked;const saved=writeStored('loot-raffles',toggleState);toast(`${c.name}: Raffles ${input.checked?'aktiviert':'deaktiviert'}.${saved?'':' Nur für diese Sitzung.'}`);});
 label.append(text,input);$('#raffle-toggles').append(label);
});
function log(message,level='INFO') {
 const row=document.createElement('li');const time=document.createElement('time');time.textContent=new Date().toLocaleTimeString('de-DE');const badge=document.createElement('span');badge.className='log-level';badge.textContent=level;const text=document.createElement('span');text.textContent=message;row.append(time,badge,text);$('#bot-log').prepend(row);
 while($('#bot-log').children.length>15) $('#bot-log').lastElementChild.remove();
}
function syncBot() {
 const active=running&&demoState==='normal';document.body.classList.toggle('paused',!active);
 $('#bot-state').textContent=demoState==='empty'?'Nicht verbunden':demoState==='expired'?'Anmeldung nötig':active?'Läuft':'Pausiert';
 $('#sidebar-state').textContent=active?'Bot läuft':demoState==='normal'?'Bot pausiert':'Bot nicht verbunden';
 $('#sidebar-uptime').textContent=active?'Seit 3 Tagen, 14 Std.':'Keine aktive Watch-Time';
 $('#bot-toggle').textContent=active?'Bot stoppen':'Bot starten';$('#bot-toggle').disabled=demoState!=='normal';$('#bot-restart').disabled=demoState!=='normal';
 $('#active-count').textContent=active?'2 von 2':'0 von 2';$('#slot-count').textContent=active?'2 / 2':'0 / 2';
 $('.nav-count').textContent=demoState==='empty'?'0':'8';
 $('#channels .page-heading p:last-child').textContent=demoState==='empty'?'Deine Kanäle erscheinen nach dem ersten Twitch-Login.':'8 gefolgte Kanäle. Zwei Plätze zum Punktesammeln.';
 $$('.watch-status').forEach(el=>el.textContent=active?'Wird gelurkt':'Lurken pausiert');
 $('#session-state').replaceChildren();$('#session-state').append(document.createTextNode(active?'Alles läuft entspannt.':'Die Schicht pausiert.'));const small=document.createElement('small');small.textContent=active?'Letzter Check um 20:42 Uhr':'Keine Punkte-Erfassung aktiv';$('#session-state').append(small);
 $('#account-state').textContent=demoState==='empty'?'Noch kein Twitch-Account verbunden':'Account: Raindancer118';
 $('#connection-state').textContent=demoState==='expired'?'Token abgelaufen':demoState==='empty'?'Nicht verbunden':'Twitch verbunden';
 $('#state-banner').hidden=demoState!=='expired';$('#empty-state').hidden=demoState!=='empty';$('#overview-content').hidden=demoState==='empty';
 $('#started-at').textContent=demoState==='empty'?'Noch nicht gestartet':'25.09.2026, 06:12 Uhr';
 $('#mock-event').disabled=!active;$('#log-update').disabled=!active;$('#demo-state').value=demoState;
 document.body.classList.toggle('empty-mode',demoState==='empty');
}
$('#bot-toggle').addEventListener('click',()=>{running=!running;syncBot();log(running?'Bot gestartet. Zwei Kanalplätze belegt.':'Bot pausiert. Watch-Time angehalten.','BOT');toast(running?'Bot in der Demo gestartet.':'Bot in der Demo pausiert.');});
$('#bot-restart').addEventListener('click',()=>{running=true;syncBot();$('#started-at').textContent='Gerade neu gestartet (Demo)';$('#sidebar-uptime').textContent='Gerade neu gestartet';log('Neustart abgeschlossen. Session wiederhergestellt.','BOT');toast('Neustart simuliert. Beide Kanäle sind wieder aktiv.');});
$('#demo-state').addEventListener('change',event=>{demoState=event.target.value;running=demoState==='normal';syncBot();});
$$('[data-connect]').forEach(button=>button.addEventListener('click',()=>{location.hash='bot';$('#login-wait').textContent='Wartet auf Bestätigung · Demo-Code, nicht gültig';$('#simulate-login').disabled=false;$('#simulate-login').textContent='Verbindung simulieren';requestAnimationFrame(()=>$('#simulate-login').focus());}));
$('#simulate-login').addEventListener('click',()=>{demoState='normal';running=true;syncBot();$('#login-wait').textContent='✓ Demo-Verbindung bestätigt. Raindancer118 ist verbunden.';$('#simulate-login').disabled=true;$('#simulate-login').textContent='✓ Verbunden';log('Twitch-Anmeldung simuliert. Raindancer118 verbunden.','LOGIN');toast('Twitch-Verbindung erfolgreich simuliert.');});
$('#log-update').addEventListener('click',()=>{log('Heartbeat bestätigt. Zwei Kanäle aktiv.','WATCH');toast('Neuen Demo-Logeintrag hinzugefügt.');});
$('#mock-event').addEventListener('click',()=>{
 eventCount++;const row=document.createElement('li');row.innerHTML='<span class="event-symbol">+</span><div><strong>Bonus eingesammelt</strong><p>Gronkh · +50 Kanalpunkte (Demo)</p></div><time>Jetzt</time>';$('#feed').prepend(row);if($('#feed').children.length>6)$('#feed').lastElementChild.remove();
 $('.stats-strip>div:nth-child(2)>strong').textContent=format(28640+eventCount*50);
 $('.stats-strip>div:nth-child(3)>strong').textContent=format(384920+eventCount*50);
 $('.stream-card .stream-bottom>strong').innerHTML=`+${format(1850+eventCount*50)} <small>Punkte heute</small>`;
 $('.stats-strip>div:first-child>strong').innerHTML=`${format(4850+eventCount*50)} <small>↗ 18 %</small>`;
 channels[0].points+=50;channels[0].gain+=50;channels[0].chart[6]+=50;renderChannels();log('Gronkh · Demo-Bonus abgeholt, +50 Punkte.','CLAIM');toast('Ein Bonus-Ereignis wurde simuliert.');
});
const form=$('#settings-form');
const settings=readStored('loot-settings',{});
for(const [name,value] of Object.entries(settings)){const input=form.elements.namedItem(name);if(!input)continue;if(input.type==='checkbox')input.checked=Boolean(value);else if(typeof value==='string')input.value=value;}
form.addEventListener('submit',event=>{
 event.preventDefault();const data=Object.fromEntries(new FormData(form));
 const names=/^(?:[a-zA-Z0-9_]{1,25}(?:\s*,\s*[a-zA-Z0-9_]{1,25})*)?$/;
 for(const name of ['order','extra','blacklist']) {const input=form.elements.namedItem(name);input.setCustomValidity(names.test(input.value.trim())?'':'Bitte nur Kanalnamen mit Buchstaben, Zahlen oder Unterstrich, durch Kommas getrennt.');if(!input.reportValidity())return;data[name]=input.value.trim();}
 const keywords=form.elements.namedItem('keywords');keywords.setCustomValidity(/^(?:![a-zA-Z0-9_]+(?:\s*,\s*![a-zA-Z0-9_]+)*)$/.test(keywords.value.trim())?'':'Bitte Befehle wie !join, !raffle eingeben.');if(!keywords.reportValidity())return;
 data.raids=form.elements.namedItem('raids').checked;data.moments=form.elements.namedItem('moments').checked;
 const saved=writeStored('loot-settings',data);$('#save-message').textContent=saved?'Gespeichert. Deine Demo-Einstellungen bleiben in diesem Browser.':'Speichern nicht möglich. Der Browser blockiert lokalen Speicher.';toast(saved?'Deine Spielregeln sind gespeichert.':'Lokaler Speicher nicht verfügbar.');
});
form.addEventListener('input',event=>{event.target.setCustomValidity?.('');$('#save-message').textContent='Ungespeicherte Änderungen';});
for(const id of ['channels','drops','raffles']) {const empty=document.createElement('div');empty.className='account-empty panel';const heading=document.createElement('h2');heading.textContent='Hier startet deine Sammlung.';const p=document.createElement('p');p.textContent='Verbinde zuerst Twitch, damit deine Kanäle und Belohnungen erscheinen.';const link=document.createElement('a');link.href='#bot';link.className='primary';link.textContent='Zum Twitch-Login';empty.append(heading,p,link);$(`#${id}`).append(empty);}
renderChannels();renderInventory();syncBot();navigate();
