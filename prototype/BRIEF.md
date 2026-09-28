# Design-Brief: twitchlurker (twitchlurker.raindancer118.de)

Du bist Designer:in und Frontend-Entwickler:in. Baue einen **reinen HTML/CSS-Prototyp** (Vanilla-JS nur für Kleinkram wie Tabs/Mock-Updates) für ein privates Web-Dashboard. Es soll **wirklich hübsch und modern** sein, ein eigenständiges Kunstwerk und kein generisches SaaS-Admin-Template.

## Was die App macht
Ein Bot lurkt rund um die Uhr auf Twitch mit Toms Account und nimmt alle Belohnungen mit:
- **Kanalpunkte**: Watch-Time, Bonus-Truhen (auto-claim), Watch-Streaks, Raids folgen, Moments claimen.
  Twitch zählt höchstens **2 Kanäle gleichzeitig**. Der Bot wählt aus allen gefolgten Kanälen nach Priorität (Streak > Drops > Reihenfolge).
- **Drops**: aktive Drop-Kampagnen (Spiel, Belohnung, benötigte Minuten, Fortschritt in %, Ablaufdatum, geclaimt ja/nein, Game-Account verknüpft ja/nein).
- **Raffles**: ein Chat-Watcher erkennt Giveaways von StreamElements/Nightbot/Moobot/etc. (z. B. „Type !join to enter“) und schreibt einmalig den Join-Befehl. Log: Zeit, Kanal, erkannte Nachricht, gesendeter Befehl, Status (eingetragen / übersprungen / gewonnen erkannt). Pro Kanal an/aus schaltbar.

Nutzer: nur Tom, Login über Authentik-SSO (auth.volantic.de). Sprache der UI: **Deutsch**, Ton locker und konkret.

## Screens (alle im Prototyp, mit realistischen Mockdaten)
1. **Übersicht**: gerade gelurkte Kanäle (max. 2, mit Spiel/Titel/Zuschauerzahl/Uptime), Punkte heute/Woche/gesamt, letzte Ereignisse (Bonus geclaimt, Streak, Raid, Drop, Raffle) als Live-Feed, Bot-Status (läuft / eingeloggt als / seit).
2. **Kanäle**: alle gefolgten Kanäle, live/offline, Punktestand, Zuwachs, Streak-Status, Priorität, Punkteverlauf als kleiner Chart (inline SVG, kein Chart-Lib), Detailansicht mit großem Verlaufschart.
3. **Drops**: Kampagnen mit Fortschritt, Inventar geclaimter Drops.
4. **Raffles**: Log + Kanal-Toggles + erkannte Muster/Bots.
5. **Bot / Login**: Twitch-Device-Login (großer 8-stelliger Code, Link twitch.tv/activate, Wartezustand), Start/Stopp/Neustart, Live-Log (gestylt, nicht bloß `<pre>`).
6. **Einstellungen**: Prioritäten, Raffle-Keywords, Kanäle zusätzlich zu den Follows, Blacklist.
Plus: leerer Zustand (Bot noch nie eingeloggt), Fehlerzustand (Token abgelaufen).

## Harte Vorgaben
- Dateien: `prototype/index.html` (alle Screens, per Tabs/Anker umschaltbar) + `prototype/styles.css` + optional `prototype/app.js`. Keine Frameworks, kein Tailwind/Bootstrap, **keine CDNs** (strenge CSP). Schriften als woff2 lokal unter `prototype/fonts/` (nur OFL/frei lizenziert), Lizenzdateien mitliefern.
- Modernes natives CSS: Custom Properties, Container Queries, `:has()`, View Transitions, `animation-timeline` wo sinnvoll, `@starting-style`.
- Responsiv (Handy bis 4K), Touch-Targets ≥ 48 px, `prefers-reduced-motion`-Fallback für jede Animation, Kontrast WCAG AA, Dark **und** Light (`prefers-color-scheme`), Tastaturbedienbar.
- **Nicht nach KI aussehen.** Vermeide ohne echten Grund: Lila→Blau-Gradienten (auch naheliegendes Twitch-Lila als Hauptfläche), Cyan auf Dunkel, Glassmorphismus, Gradient-Blobs, Inter/System-Sans als einzige Schrift, Monospace-Labels, kursive Akzentwörter in Headlines, drei Icon-Cards im Grid, „01/02/03“-Labels, Pill-Buttons überall, Cards mit farbigem Linksrand, ein Radius+Schatten für alles, Emoji als Icons, Em-Dash-lastige Copy.
- Tom hat drei Richtungen bereits abgelehnt: „nächtliche Fensterfassade“, „editoriales Papier-Logbuch“, „riesiger kinetischer Punktezähler mit Echo-Stapel“. Such eine eigene, bessere Idee.
- Kerning bei großer Schrift optisch justieren.

## Außerdem liefern
`Design.md` im Projekt-Root (`../Design.md`): Konzept und Begründung, Farben (Tokens), Typografie, Komponenten, Motion-Regeln. Das Backend-Team implementiert danach exakt danach.
Arbeite nur in diesem Projektordner. Kein git commit.
