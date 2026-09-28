# twitchlurker · Design

Stand 28.09.2026. Aufbau nach **StoneIntelligence** (Leiste links, Arbeitsfläche rechts), mit **Twitch-Farben**. Zweite Runde auf Toms Wunsch: **freundlicher, runder, moderner, kein Business-Dashboard**, und wirklich mobiltauglich. Vorgänger „Loot Room“ (Astra) archiviert unter `prototype/`.

## Idee
Ein gemütlicher Hobbyraum statt Kontrollzentrum: weiche, runde Kacheln auf hellem Grund, eine dunkle Leiste als schwebende Insel, Twitch-Lila als Hauptfarbe mit freundlichen Begleitfarben pro Ereignis. Wenig Linien und Tabellen, mehr Flächen und Luft. Oben auf der Übersicht laufen die zwei gelurkten Streams.

## Farbe
| Token | Hell | Dunkel | Verwendung |
|---|---|---|---|
| `--bg` | `#f4f2fb` | `#0e0e10` | Seite (leicht lila getönt) |
| `--surface` | `#ffffff` | `#19181f` | Kacheln |
| `--surface-soft` | `#f0edfd` | `#221f2e` | Eingaben, Chips, Kennzahlen |
| `--line` | `#e7e3f5` | `#2d2a3a` | seltene Linien |
| `--ink` / `--ink-dim` | `#17141f` / `#5f5a70` | `#f2f0f8` / `#b3aec3` | Text |
| `--purple` / `--purple-strong` | `#9146ff` / `#772ce8` | `#b38bff` / `#9146ff` | Akzent, Hauptknöpfe |
| `--mint` | `#0f9d6e` | `#4fe0a8` | Punkte, Erfolg |
| `--sun` | `#d9820b` | `#ffc15a` | Raffles |
| `--sky` | `#2779e0` | `#7cb6ff` | Raids, Info |
| `--live` | `#eb0400` | `#ff5a5a` | LIVE |
| `--rust` | `#b4432b` | `#ff8a6b` | Fehler |
Ereignis-Symbole im Feed tragen ihre Farbe als weiche Fläche (`color-mix` 14 %).

## Form & Typografie
- **Nunito** (variabel 400–900, OFL, selbst gehostet) für alles: rund und freundlich. Titel 800, Tracking −0,02em; Überzeilen in normaler Schreibweise, lila, 600 (keine Versal-Sperrung mehr).
- Radien: Kacheln 20 px, Knöpfe/Eingaben 14 px, Chips/Tags/Hauptknöpfe rund (999 px). Weiche Schatten statt Linien.
- Mindestens 44 px Touch-Ziele, Eingaben auf Mobil 16 px (kein iOS-Zoom).

## Aufbau
- **Desktop (≥ 901 px):** dunkle Leiste links als abgerundete Insel mit Abstand zum Rand; Kopfzeile sticky, leicht transparent.
- **Mobil (≤ 900 px):** schlanke Kopfzeile (Marke, Live-Punkt, Einstellungen, Farbschema), **Tab-Leiste unten** mit 5 Zielen (Übersicht, Kanäle, Drops, Raffles, Bot), Safe-Area-Abstand. Einstellungen über das Zahnrad oben; Abmelden auf der Bot-Seite.
- **Übersicht:** Kennzahlen als runde Kacheln (mobil 2 × 3), zwei Slots mit Player/Vorschau, Aktivität, nächster Drop.
- **Kanäle:** Karten-Zeilen, Griff zum Ziehen (44 px), Pfeile nur bei feinem Zeiger (Maus/Tastatur).
- **Einstellungen:** Speichern-Leiste klebt über der Tab-Leiste.

## Motion
Wie StoneIntelligence: leise und kurz, Bewegung zeigt, wohin etwas geht. Federn für Markierungen. Alles hinter `prefers-reduced-motion: no-preference`, sonst statisch.

- Tokens (`motion.css`): `--ease-out`, `--ease-in-out`, `--ease-spring` (Federkurve als `linear()`), 140/260/520 ms, Stagger 40 ms, Wege 4–8 px.
- Bereichswechsel: View Transition, Inhalt gleitet 8 px hoch, die aktive Navigationsfläche wandert zum neuen Eintrag, schmale Federleiste links.
- Auftritt: Leiste und Kopfzeile blenden ein, Navigationseinträge gestaffelt; Seitentitel wird per Wipe enthüllt; Inhalte steigen gestaffelt auf (nur beim Öffnen eines Bereichs, nie beim Hintergrund-Refresh).
- Kopfzeile sticky mit weicher Scroll-Kante (`animation-timeline: scroll()`).
- Karten heben sich beim Überfahren um 1 px, Knöpfe geben nach (scale .98), Hauptknopf hebt sich mit lila Schatten.
- Kanalliste: Akzentleiste bei Überfahren, geänderte Punktzahl blitzt lila, Drag-Ziel als 2-px-Linie.
- Live: neue Aktivität klappt federnd auf und schimmert kurz, Zahlen zählen hoch mit „+N“, Statuspunkte pulsieren, LIVE-Marke atmet, Player/Vorschau blenden ein. Schleifen behalten ihre Phase über Re-Renders (`--phase`).
- Rückmeldung: Toast und Dialog federn herein, Schalter federn, Raffle-Gewinn mit einmaligem Glanz, Device-Code atmet während des Wartens.
- **Bewusst nicht** (wie bei Stone abgelehnt): Lichtfleck am Zeiger, Glanz über Knöpfe, Bewegung an Navigationssymbolen.
