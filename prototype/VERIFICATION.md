# Browserprüfung · 28.09.2026

Ergebnis: **11 Playwright-Tests bestanden (29,3 Sekunden)**, Chromium Headless 153, Playwright 1.63.0. Statischer Start über Python HTTP-Server. Keine produktiven Dienste aufgerufen. Nach dem letzten Feinschliff an mobilen Diagramm-Labels wurden die fünf betroffenen Sonderzustands-/Viewport-Tests erneut erfolgreich ausgeführt (10,5 Sekunden).

## Abdeckung

- Alle sechs Screens bei 1440 × 1100 und 390 × 844, jeweils Dark und Light per emuliertem Betriebssystem-Farbschema.
- Axe-Prüfung WCAG 2 A/AA und 2.1 AA auf allen sechs Screens sowie Leerzustand, Tokenfehler und Kanaldialog: keine automatisiert gefundenen Verstöße.
- 320, 768, 1024 und 3840 px: alle sechs Screens ohne horizontalen Seitenüberlauf.
- Kanal-Suche, leeres Suchergebnis, Live-/Offline-Filter, Kanaldialog, Tastaturöffnung, Escape und Fokusrückgabe.
- Bot starten/stoppen, Token abgelaufen, erster Login und simulierte Verbindung.
- Drop claimen, Eintrag im Inventar, Schutz vor erneutem Claim.
- Raffle-Schalter und Einstellungen über Neuladen hinweg gespeichert.
- Keine externen Ressourcenanforderungen, keine JavaScript-/CSP-Konsolenfehler im geprüften Interaktionspfad.
- Reduced-Motion-Fallback geprüft.

## Visuelle Prüfung und Korrekturen

Screenshots persönlich visuell geprüft: beide Desktop- und Mobile-Farbschemata der Übersicht, Kanäle, Drops, Raffles, Bot, Einstellungen sowie Zusatzansichten. Korrigiert wurden Letterboxing der SVG-Illustrationen, Hash-Scrollversatz bei feststehenden Elementen, zu kleine Sekundärtexte, Kontrollränder und Abstände unter Fortschrittsbalken. Die abschließenden Screenshots stehen unter `screenshots/` (41 PNGs: 24 Hauptansichten, 16 Sonderansichten, 1 × 4K).

Die Prüfungen sind kein vollständiges manuelles WCAG-Audit. Safari/Firefox und echte Mobilgeräte wurden nicht getestet. Twitch, Authentik, Chat und Claim-APIs werden im reinen Prototyp absichtlich nicht angesprochen. Einstellungen verändern lokale Formularwerte; ein echter Scheduler ist Sache des Backends.

## Wiederholen

```sh
cd prototype
npm ci
PLAYWRIGHT_BROWSERS_PATH="$PWD/.browsers" npx playwright install chromium
PLAYWRIGHT_BROWSERS_PATH="$PWD/.browsers" npm test
```

Vorschau: `cd prototype && npm run preview`, dann http://127.0.0.1:4188.
