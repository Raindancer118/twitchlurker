# twitchlurker

Lurkt rund um die Uhr auf Twitch und nimmt mit, was es gibt: Kanalpunkte, Bonus-Truhen, Watch-Streaks, Raids, Moments, Drops und Chat-Verlosungen. Dashboard unter https://twitchlurker.raindancer118.de (Login über Authentik).

## Aufbau

- **Spring Boot (Java 25)**: Web-UI, REST-API, Authentik-OIDC, Twitch-Device-Login, SQLite (Flyway), Raffle-Chatwatcher (Twitch-IRC über WebSocket).
- **`runner/lurker_runner.py`**: startet [Twitch-Channel-Points-Miner-v2](https://github.com/rdavydov/Twitch-Channel-Points-Miner-v2) (gepinnt auf 2.0.7) als Subprozess und meldet Zustand/Ereignisse als JSON-Zeilen. Findet zusätzlich Kanäle für laufende Drop-Kampagnen.
- `runner/lurker_watch.py` ersetzt TCPMs Minute-Watched-Schleife: feste Slots aus dem Dashboard gehen vor, danach TCPMs Prioritäten (`lurker_core.choose_watching`). Slots und Reihenfolge wirken live per stdin-Befehl.
- Frontend: statisches HTML/CSS/JS unter `src/main/resources/static`, Stil wie StoneIntelligence mit Twitch-Farben, Design in `Design.md`. Die beiden Slots zeigen den Twitch-Player (`frame-src https://player.twitch.tv`).

## Lokal

```sh
./mvnw verify                                  # Java-Tests inkl. Startup-Test
python -m pytest runner/tests                  # braucht runner/requirements.txt
cd e2e && npm ci && npx playwright test        # E2E gegen das echte Jar mit Fake-Miner
```

Dev-Start ohne Authentik: `LURKER_DEV_PASSWORD=… java -jar target/twitchlurker-*.jar --spring.profiles.active=dev` (Login `dev`).

## Betrieb

Push auf `main` → GitHub Actions testet und deployt auf glaedr (`/home/oromis/twitchlurker`, `docker compose`). Konfiguration in `.env` auf dem Server, siehe `.env.example`. Daten im Volume `twitchlurker_lurker-data` (`/data`: SQLite, Settings, Twitch-Token mit 0600).

Releases: `git tag X.Y.Z && git push origin X.Y.Z`.
