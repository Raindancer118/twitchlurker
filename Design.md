# twitchlurker · Design

Layout follows **StoneIntelligence** (sidebar on the left, workspace on the right) with **Twitch colours**. Second pass: **friendlier, rounder, more modern, not a business dashboard**, and properly mobile. The predecessor "Loot Room" is archived under `prototype/`.

## Idea
A cosy hobby room rather than a control centre: soft, rounded tiles on a light background, a dark sidebar as a floating island, Twitch purple as the main colour with friendly companion colours per event. Few lines and tables, more surfaces and air. The two lurked streams sit at the top of the overview.

## Colour
| Token | Light | Dark | Use |
|---|---|---|---|
| `--bg` | `#f4f2fb` | `#0e0e10` | page (slightly purple-tinted) |
| `--surface` | `#ffffff` | `#19181f` | tiles |
| `--surface-soft` | `#f0edfd` | `#221f2e` | inputs, chips, stats |
| `--line` | `#e7e3f5` | `#2d2a3a` | rare lines |
| `--ink` / `--ink-dim` | `#17141f` / `#5f5a70` | `#f2f0f8` / `#b3aec3` | text |
| `--purple` / `--purple-strong` | `#9146ff` / `#772ce8` | `#b38bff` / `#9146ff` | accent, primary buttons |
| `--mint` | `#0f9d6e` | `#4fe0a8` | points, success |
| `--sun` | `#d9820b` | `#ffc15a` | raffles |
| `--sky` | `#2779e0` | `#7cb6ff` | raids, info |
| `--live` | `#eb0400` | `#ff5a5a` | LIVE |
| `--rust` | `#b4432b` | `#ff8a6b` | errors |

Event icons in the feed carry their colour as a soft fill (`color-mix` 14 %).

## Shape & type
- **Nunito** (variable 400–900, OFL, self-hosted) for everything: round and friendly. Headings 800, tracking −0.02em; eyebrows in sentence case, purple, 600 (no letter-spaced caps).
- Radii: tiles 20 px, buttons/inputs 14 px, chips/tags/primary buttons fully round (999 px). Soft shadows instead of lines.
- Touch targets at least 44 px, inputs 16 px on mobile (no iOS zoom).

## Layout
- **Desktop (≥ 901 px):** dark sidebar on the left as a rounded island inset from the edge; sticky, slightly translucent header.
- **Mobile (≤ 900 px):** slim header (brand, live dot, settings, colour scheme), **bottom tab bar** with 5 targets (Overview, Channels, Drops, Raffles, Bot), safe-area padding. Settings via the gear at the top; log out on the Bot page.
- **Overview:** stats as rounded tiles (2 × 3 on mobile), two slots with player/preview, activity, next drop.
- **Channels:** card rows, drag handle (44 px), arrow buttons only with a fine pointer (mouse/keyboard).
- **Settings:** the save bar sticks above the tab bar.

## Motion
Like StoneIntelligence: quiet and short, motion shows where things go. Springs for highlights. Everything behind `prefers-reduced-motion: no-preference`, static otherwise.

- Tokens (`motion.css`): `--ease-out`, `--ease-in-out`, `--ease-spring` (spring curve as `linear()`), 140/260/520 ms, 40 ms stagger, 4–8 px travel.
- Section change: view transition, content glides up 8 px, the active nav highlight moves to the new entry, slim spring bar on the left.
- Entrance: sidebar and header fade in, nav entries staggered; page title revealed by a wipe; content rises staggered (only when opening a section, never on background refreshes).
- Sticky header with a soft scroll edge (`animation-timeline: scroll()`).
- Cards lift 1 px on hover, buttons give way (scale .98), the primary button lifts with a purple shadow.
- Channel list: accent bar on hover, changed point counts flash purple, drop target as a 2 px line.
- Live: new activity springs open and shimmers briefly, numbers count up with "+N", status dots pulse, the LIVE badge breathes, player/preview fade in. Loops keep their phase across re-renders (`--phase`).
- Feedback: toast and dialog spring in, switches spring, a raffle win gets a one-time shine, the device code breathes while waiting.
- **Deliberately not** (rejected, as with Stone): cursor spotlight, sheen across buttons, motion on nav icons.
