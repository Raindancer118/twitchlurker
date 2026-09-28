# twitchlurker · Design

Stand 28.09.2026. Auf Toms Wunsch im Stil von **StoneIntelligence** (kb.tstieh.de, `StoneSync/webapp/Design.md`) mit **Twitch-Farben**. Vorgänger „Loot Room“ (Astra, Kohle/Limette) liegt archiviert unter `prototype/`.

## Idee
Ein ruhiges Instrumentenpult neben einem hellen Schreibtisch: dunkle Navigationsleiste links, helle Arbeitsfläche rechts, redaktionelle Georgia-Überschriften, kleine Versal-Überzeilen. Twitch-Lila ist der eine Akzent (Hauptknöpfe, aktive Einträge, Links, Live-Signale), Twitch-Rot nur für LIVE, Fehler in Rost. Oben auf der Übersicht laufen die zwei gelurkten Streams als echte Player.

## Farbe
| Token | Hell | Dunkel | Verwendung |
|---|---|---|---|
| `--bg` | `#f7f7f8` | `#0e0e10` | Seite |
| `--surface` | `#ffffff` | `#18181b` | Panels, Kopfzeile |
| `--surface-raised` | `#ffffff` | `#1f1f23` | Eingaben, Knöpfe |
| `--line` | `#e5e5ea` | `#2f2f35` | Linien |
| `--ink` / `--ink-dim` | `#0e0e10` / `#53535f` | `#efeff1` / `#adadb8` | Text |
| `--purple` | `#9146ff` | `#a970ff` | Akzent, Links, Fokus |
| `--purple-strong` | `#772ce8` | `#9146ff` | Hover/gedrückt, Hauptknopf-Fläche im Dunkeln |
| `--ice` | `#f0f0ff` | `#26213a` | Hintergrund aktiver Einträge |
| `--live` | `#eb0400` | `#ff4a4a` | LIVE-Marke |
| `--rust` | `#a04429` | `#ff8a6b` | Fehler, Warnungen |
| Navigation | `#0e0e10` | `#18181b` | Leiste links, eigene Token |

## Typografie
Public Sans 400–700 (selbst gehostet, OFL) für UI und Fließtext, Georgia für Seitentitel und Kanalnamen in den Stream-Karten. Überzeilen: Versalien, 0,7rem, Tracking 0,15em. Zahlen tabellarisch.

## Aufbau
- **Leiste links** (238 px, dunkel): Marke, Überzeile „Bereiche“, Einträge mit schmaler Federleiste am linken Rand und heller Ice-Fläche für den aktiven Bereich. Unten Bot-Status und Konto. Mobil wird daraus eine horizontale Leiste oben.
- **Kopfzeile** 76 px, sticky mit weicher Scroll-Kante: Brotkrumen, Live-Status, Farbschema.
- **Übersicht:** Titel, Kennzahlen-Zeile, darunter **zwei Slots** nebeneinander: Twitch-Player (stumm, 16:9) mit Kopf „Platz 1 · Automatisch/Fest“ und Auswahlmenü, darunter Kanal, Titel, Punkte heute. Handy/Datensparmodus: Vorschaubild mit Abspielknopf. Darunter Aktivität und nächster Drop.
- **Kanäle:** Liste in der tatsächlichen Reihenfolge des Bots. Ziehen am Griff sortiert um (Maus/Touch), Pfeilknöpfe für Tastatur. Rang, Slot-Marke, Status.
- Panels: weiß, 1 px Linie, 6 px Radius, heben sich beim Überfahren um 1 px. Knöpfe 5 px Radius, mind. 44 px hoch (Hauptaktionen 48 px).

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
