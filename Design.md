# twitchlurker · Loot Room

## Idee
Ein privater Raum für die Beute aus Streams: ruhige Kohleflächen, frischgrüne Statussignale und zwei großformatige, grafische Spielwelten. Die aktive Session steht im Mittelpunkt. Die Illustrationen erinnern an ausgeschnittene Landschaften und bedruckte Gaming-Sammelkarten. Keine Video-Vorschau, keine vorgetäuschten Streambilder. Eigenständige, lokal gezeichnete SVGs.

Erwogen wurden eine Sport-Anzeigetafel (zu zahlenlastig), ein Inventar-Raster (zu kleinteilig) und der gewählte Loot Room. Die drei im Brief verworfenen Richtungen werden nicht wiederaufgenommen. Funktionale Klarheit aus Fluent/M3 und bewusst großzügige Flächen aus den kuratierten Designreferenzen; keine externe Website kopiert.

## Farben / verbindliche Tokens
| Token | Dark | Light | Zweck |
|---|---|---|---|
| `--bg` | #151714 | #f4f5ee | Arbeitsfläche |
| `--surface` | #1d201b | #ffffff | Module |
| `--raised` | #272b24 | #e9eddf | Eingaben / Hover |
| `--ink` | #f2f4e9 | #20251b | Primärtext |
| `--muted` | #a8af9d | #58634f | Sekundärtext |
| `--line` | #373e30 | #cbd2c0 | Trennlinien |
| `--accent` | #d0ef80 | #d0ef80 | Aktionsfläche, immer dunkle Schrift |
| `--positive` | #d0ef80 | #3d641b | Positiver Text / Charts |
| `--warning` | #ffbf91 | #89400b | Warnung |

Illustrationen behalten in beiden Modi ihre Pigmentfarben. System-Farbschema ist Default; ein expliziter Wechsel ist lokal gespeichert.

Kontrollränder nutzen zusätzlich `--control-line`: Dark #727b66, Light #7e896f. Dekorative Modultrennlinien bleiben zurückhaltender.

## Typografie
Space Grotesk Variable für Wortmarke, Überschriften und Zahlen; Manrope Variable für UI und Lesetext. Beide SIL OFL 1.1, lokale WOFF2 unter `prototype/fonts/`, Lizenzdateien daneben. H1 36–52 px, Tracking −0.055em, Zeilenhöhe 1.05; H2 22 px. Body 14–16 px, 1.6. Zahlen tabellarisch. Kein Monospace.

## Layout und Komponenten
Desktop: 224 px Seitenleiste, Arbeitsbereich max. 1740 px, 40 px Außenabstand. Übersicht: Titelzeile, flache Statistikleiste, zwei asymmetrische Streamkarten (1.15:1), darunter Aktivitätsfeed plus nächste Belohnung. Karten 18 px, kompakte Controls 8 px, Avatare kreisförmig, Listen ohne Schatten. Keine drei Icon-Karten als Statistik.

Unter 1000 px: kompakte horizontale Navigation, Inhalt 24 px. Unter 680 px: 16 px Rand, einspaltige Karten, zweispaltige Statistikleiste, Tabellen werden beschriftete Zeilen. Touchflächen mindestens 48×48 px. Auf 4K bleibt die Inhaltsbreite begrenzt. Container Queries passen Streamdetails an die tatsächlich verfügbare Kartenbreite an.

Navigation: echte Hashlinks, `aria-current=page`, Browser Zurück/Vorwärts. Kanal-Details im nativen Dialog mit Escape und Fokusrückgabe. Formfelder stets beschriftet, sichtbarer Tastaturfokus. Fortschritt mit nativem `progress` und ausgeschriebenen Werten. Zustände nicht nur über Farbe darstellen.

## Motion
180 ms Hintergrund-/Farbwechsel, 220 ms View Transition bei Navigation. Eintritt im Dialog über `@starting-style`. Nur der Lesefortschritt am oberen Rand ist scrollgekoppelt (`animation-timeline: scroll()`); kein unruhiges Pulsieren oder animierte Zahlen. `prefers-reduced-motion: reduce` deaktiviert sämtliche Animationen und Transitions.

## Mock-Verhalten und Backend-Vertrag
Alle Daten sind ein fiktiver Snapshot vom 28.09.2026, 20:42 Uhr. Die Oberfläche benennt den Prototyp dauerhaft. Kein Twitch- oder Authentik-API-Zugriff. Start/Stopp/Neustart, Login-Erfolg, Drops claimen, Raffle-Schalter und Ereignisse sind ausdrücklich lokale Simulationen. Einstellungen und Theme werden in localStorage gespeichert; Loginstatus bleibt in der Sitzung. Erneutes Laden startet den Beispieldatensatz neu.

Authentik schützt später die gesamte App; Twitch-Device-Login ist davon getrennt. Device-Code und Bestätigungsaktion sind nur Demo. Produktion: serverseitige Tokens, Ablauf/Polling und Authentik-Callback implementieren; keine Secrets in localStorage. CSP ist bereits restriktiv über Meta gesetzt (nur eigene Dateien, keine Inline-Skripte/-Styles); frame-ancestors, HSTS, nosniff, Referrer- und Permissions-Policy als HTTP-Header beim Deployment ergänzen.

Zwei aktive Kanäle maximal. Auswahl: Streak vor Drops vor Kanalreihenfolge. Bei Stopp/Tokenfehler keine aktive Watch-Time. Historische Punkte bleiben erhalten. Beim Erstlogin werden historische Module ausgeblendet. Drop-Kampagnen zeigen Spiel, Belohnung, benötigte Minuten, Fortschritt, Ablauf und Accountverknüpfung. Raffles zeigen Erkennung, Befehl und Ergebnis; pro Erkennung einmalige Teilnahme. Settings bestehen aus Priorisierung, Keywords, zusätzlichen Kanälen und Blacklist.
