"""Fake runner with presentable, fictional data for the README screenshots (see readme-shots.spec.cjs)."""
import json, random, sys, threading, time

config = json.load(open(sys.argv[1]))
token = json.load(open(config["tokenFile"]))
lock = threading.Lock()
IMG = "https://static-cdn.jtvnw.net/showcase"


def out(o):
    o.setdefault("ts", time.time())
    with lock:
        sys.stdout.write(json.dumps(o) + "\n")
        sys.stdout.flush()


CHANNELS = [
    # login, online, game, title, viewers, streak, drops
    ("lunaplays", True, "Minecraft", "Wir bauen eine Stadt auf Wolken ☁️ Tag 42", 8400, True, True),
    ("pixelbaer", True, "Stardew Valley", "Gemütlicher Farm-Sonntag mit Chat-Wünschen", 2150, False, False),
    ("nordlicht_tv", True, "Just Chatting", "Kaffee, Kekse und eure Geschichten", 5300, False, False),
    ("retrohannah", True, "Hollow Knight", "Steel Soul Run: diesmal wirklich", 960, False, False),
    ("bytebandit", False, None, None, 0, False, False),
    ("kaffeeklatsch", False, None, None, 0, False, False),
]
points = {"lunaplays": 48210, "pixelbaer": 12880, "nordlicht_tv": 30420, "retrohannah": 7310, "bytebandit": 15990, "kaffeeklatsch": 2240}
order = [o for o in config.get("order", [])]
slots = config.get("slots") or [None, None]
watch = [g.lower() for g in (config.get("dropScout") or {}).get("games", [])]


def catalogue():
    items = [
        {"id": "mc", "name": "Minecraft Live: Himmelsinseln", "game": "Minecraft", "gameId": "27471", "image": f"{IMG}/box-minecraft.png",
         "status": "ACTIVE", "startAt": "2026-09-26T16:00:00Z", "endAt": "2026-10-04T22:00:00Z", "linked": True, "linkUrl": None,
         "channels": [], "rewards": [{"name": "Wolken-Umhang", "image": f"{IMG}/reward-1.png", "minutes": 60},
                                     {"name": "Sternenstaub-Emote", "image": f"{IMG}/reward-2.png", "minutes": 120}]},
        {"id": "sv", "name": "Stardew Valley Erntefest", "game": "Stardew Valley", "gameId": "490744", "image": f"{IMG}/box-stardew.png",
         "status": "ACTIVE", "startAt": "2026-09-25T00:00:00Z", "endAt": "2026-09-30T23:59:00Z", "linked": None, "linkUrl": "https://example.org/link",
         "channels": ["pixelbaer", "kaffeeklatsch"], "rewards": [{"name": "Kürbis-Hut", "image": f"{IMG}/reward-3.png", "minutes": 30}]},
        {"id": "rs", "name": "Rust Garage Door Drop", "game": "Rust", "gameId": "263490", "image": f"{IMG}/box-rust.png",
         "status": "ACTIVE", "startAt": "2026-09-24T00:00:00Z", "endAt": "2026-10-08T00:00:00Z", "linked": None, "linkUrl": "https://example.org/link",
         "channels": [], "rewards": [{"name": "Neon Garage Door", "image": f"{IMG}/reward-4.png", "minutes": 90},
                                     {"name": "Neon Hazmat", "image": f"{IMG}/reward-1.png", "minutes": 180}]},
        {"id": "hk", "name": "Hollow Knight Speedrun Week", "game": "Hollow Knight", "gameId": "490147", "image": f"{IMG}/box-hollow.png",
         "status": "ACTIVE", "startAt": "2026-09-27T00:00:00Z", "endAt": "2026-10-03T00:00:00Z", "linked": False, "linkUrl": "https://example.org/link",
         "channels": [], "rewards": [{"name": "Charm-Profilbild", "image": f"{IMG}/reward-2.png", "minutes": 45}]},
    ]
    for c in items:
        c["watched"] = c["game"].lower() in watch
    return items


def streamer(login, online, game, title, viewers, streak, drops, watching):
    return {"login": login, "channelId": "1", "online": online, "watching": watching, "points": points[login],
            "game": game, "title": title, "viewers": viewers, "onlineSince": time.time() - 7300 if online else None,
            "minutesWatched": 118 if watching else 0, "streakPending": streak, "dropsEligible": drops,
            "multiplier": False, "source": "follow"}


def state_loop():
    while True:
        for login in points:
            points[login] += random.choice([0, 10, 10, 50]) if login in ("lunaplays", "nordlicht_tv") else random.choice([0, 0, 10])
        pinned = [s for s in slots if s]
        auto = [c[0] for c in CHANNELS if c[1] and c[0] not in pinned]
        watching = (pinned + auto)[:2]
        rows = {c[0]: streamer(*c, watching=c[0] in watching) for c in CHANNELS}
        ranked = sorted(rows, key=lambda l: (order.index(l) if l in order else len(order), [c[0] for c in CHANNELS].index(l)))
        out({"t": "state", "user": token["login"], "session": "showcase", "startedAt": None, "slots": slots,
             "streamers": [rows[l] for l in ranked]})
        time.sleep(2)


for login, amount, reason in [("lunaplays", 450, "WATCH_STREAK"), ("nordlicht_tv", 50, "CLAIM"), ("lunaplays", 50, "CLAIM"),
                              ("pixelbaer", 250, "RAID"), ("retrohannah", 50, "CLAIM"), ("lunaplays", 320, "WATCH"), ("nordlicht_tv", 280, "WATCH")]:
    out({"t": "event", "event": "POINTS", "login": login, "amount": amount, "reason": reason, "balance": points[login]})
out({"t": "event", "event": "STREAMER_ONLINE", "msg": "Streamer(username=retrohannah, channel_id=1, channel_points=7k) is Online!"})
out({"t": "event", "event": "RAID", "login": "nordlicht_tv", "target": "pixelbaer"})
out({"t": "event", "event": "BONUS", "login": "lunaplays"})
out({"t": "event", "event": "DROP", "name": "cape", "benefit": "Wolken-Umhang"})
out({"t": "event", "event": "BONUS", "login": "nordlicht_tv"})
out({"t": "drops", "campaigns": [{"id": "mc", "name": "Minecraft Live: Himmelsinseln", "game": "Minecraft", "image": f"{IMG}/box-minecraft.png",
      "endsAt": "2026-10-04T22:00:00Z", "linked": True, "drops": [
          {"id": "d1", "name": "Wolken-Umhang", "image": f"{IMG}/reward-1.png", "required": 60, "watched": 60, "claimed": True},
          {"id": "d2", "name": "Sternenstaub-Emote", "image": f"{IMG}/reward-2.png", "required": 120, "watched": 83, "claimed": False}]}],
     "claimed": [{"id": "b1", "name": "Wolken-Umhang", "image": f"{IMG}/reward-1.png", "at": "2026-09-28T09:12:00Z", "game": "Minecraft"},
                 {"id": "b2", "name": "Neon Garage Door", "image": f"{IMG}/reward-4.png", "at": "2026-09-26T19:40:00Z", "game": "Rust"}]})
out({"t": "campaigns", "campaigns": catalogue(), "access": "community"})
for msg in ["Loading data for 6 streamers. Please wait...", "Drop-Katalog: 4 Kampagnen (community), beobachtet: Minecraft",
            "+450 → Streamer(username=lunaplays) - Reason: WATCH_STREAK.", "Joining raid from nordlicht_tv to pixelbaer!"]:
    out({"t": "log", "level": "INFO", "logger": "runner", "msg": msg})
threading.Thread(target=state_loop, daemon=True).start()
for line in sys.stdin:
    cmd = json.loads(line)
    if cmd.get("cmd") == "order":
        order[:] = cmd["order"]
    elif cmd.get("cmd") == "slots":
        slots[:] = cmd["slots"]
    elif cmd.get("cmd") == "watch-games":
        watch[:] = [g.lower() for g in cmd["games"]]
        out({"t": "campaigns", "campaigns": catalogue(), "access": "community"})
