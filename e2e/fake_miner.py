"""Stands in for lurker_runner.py in end-to-end tests: speaks the same JSON-lines protocol."""
import json, sys, threading, time

config = json.load(open(sys.argv[1]))
token = json.load(open(config["tokenFile"]))
lock = threading.Lock()

def out(o):
    o.setdefault("ts", time.time())
    with lock:
        sys.stdout.write(json.dumps(o) + "\n")
        sys.stdout.flush()

points = {"papaplatte": 12040, "zarbex": 3310, "trymacs": 870, "gronkh": 45100}
order = [o for o in config.get("order", [])]
slots = config.get("slots") or [None, None]
BASE = ["papaplatte", "zarbex", "trymacs", "gronkh"]
watch = [g.lower() for g in (config.get("dropScout") or {}).get("games", [])]

def catalogue():
    items = [
        {"id": "mc1", "name": "Minecraft Live 2026", "game": "Minecraft", "gameId": "27471", "image": None, "status": "ACTIVE",
         "startAt": "2026-09-27T00:00:00Z", "endAt": "2026-10-05T00:00:00Z", "linked": False, "linkUrl": "https://www.minecraft.net/link",
         "channels": ["papaplatte", "gronkh"], "rewards": [{"name": "Twitch Cape", "image": None, "minutes": 60}]},
        {"id": "rust1", "name": "Rust Twitch Drops #40", "game": "Rust", "gameId": "263490", "image": None, "status": "ACTIVE",
         "startAt": "2026-09-20T00:00:00Z", "endAt": "2026-10-02T18:00:00Z", "linked": True, "linkUrl": None, "channels": [],
         "rewards": [{"name": "Hazmat Suit", "image": None, "minutes": 120}, {"name": "Garage Door Skin", "image": None, "minutes": 60}]},
        {"id": "val1", "name": "VCT Champions", "game": "VALORANT", "gameId": "516575", "image": None, "status": "UPCOMING",
         "startAt": "2026-10-10T00:00:00Z", "endAt": "2026-10-20T00:00:00Z", "linked": None, "linkUrl": None, "channels": [],
         "rewards": [{"name": "Gun Buddy", "image": None, "minutes": 240}]},
        {"id": "dnd-amp", "name": "D&D Ampersand Badge", "game": "Dungeons & Dragons", "gameId": "509577", "image": None, "status": "ACTIVE",
         "startAt": "2026-09-24T00:00:00Z", "endAt": "2026-10-21T00:00:00Z", "linked": None, "linkUrl": None, "channels": [],
         "watchable": False, "rewards": [{"name": "Ampersand Badge", "image": None, "minutes": 0, "subs": 1}]},
        {"id": "aurora", "name": "Aurora Cape", "game": "Minecraft", "gameId": "27471", "image": None, "status": "ACTIVE",
         "startAt": "2026-09-29T07:00:00Z", "endAt": "2026-10-15T06:58:59Z", "linked": None, "linkUrl": None, "channels": [],
         "watchable": True, "quest": True, "completed": True, "rewards": [{"name": "Aurora Cape", "image": None, "minutes": 15, "subs": 0}]},
    ]
    for c in items:
        c["watched"] = c["game"].lower() in watch
    return items

def streamer(login, online, watching, game=None, title=None, viewers=0, drops=False, streak=False, source="follow"):
    return {"login": login, "channelId": "1", "online": online, "watching": watching, "points": points[login], "game": game,
            "title": title, "viewers": viewers, "onlineSince": time.time() - 5000 if online else None, "minutesWatched": 42 if watching else 0,
            "streakPending": streak, "dropsEligible": drops, "multiplier": False, "source": source}

def state_loop():
    while True:
        points["papaplatte"] += 10
        points["zarbex"] += 10
        pinned = [s for s in slots if s and s in ("papaplatte", "zarbex", "trymacs")]
        watching = (pinned + [x for x in ("papaplatte", "zarbex") if x not in pinned])[:2]
        rows = {
            "papaplatte": streamer("papaplatte", True, "papaplatte" in watching, "Just Chatting", '<img src=x onerror="window.__xss=1"> Chilling & chatting', 21000, streak=True),
            "zarbex": streamer("zarbex", True, "zarbex" in watching, "Rust", "Rust Wipe Day #drops", 5400, drops=True),
            "trymacs": streamer("trymacs", True, "trymacs" in watching, "Minecraft", "Hardcore day 12", 9000),
            "gronkh": streamer("gronkh", False, False),
        }
        if "newfollow" in BASE:
            rows["newfollow"] = streamer("newfollow", False, False)
        ranked = sorted(BASE, key=lambda l: (order.index(l) if l in order else len(order), BASE.index(l)))
        out({"t": "state", "user": token["login"], "session": "e2e", "startedAt": None, "slots": slots,
             "streamers": [rows[l] for l in ranked]})
        time.sleep(2)

out({"t": "log", "level": "INFO", "logger": "fake", "msg": "Loading data for 4 streamers. Please wait..."})
out({"t": "event", "event": "STREAMER_ONLINE", "msg": "Streamer(username=zarbex, channel_id=1, channel_points=3k) is Online!"})
out({"t": "event", "event": "POINTS", "login": "papaplatte", "amount": 50, "reason": "CLAIM", "balance": 12040})
out({"t": "event", "event": "BONUS", "login": "papaplatte"})
out({"t": "event", "event": "POINTS", "login": "zarbex", "amount": 450, "reason": "WATCH_STREAK", "balance": 3310})
out({"t": "event", "event": "RAID", "login": "trymacs", "target": "gronkh"})
out({"t": "event", "event": "DROP", "name": "Door", "benefit": "Garage Door Skin"})
out({"t": "drops", "campaigns": [{"id": "c1", "name": "Rust Twitch Drops #40", "game": "Rust", "image": None, "endsAt": "2026-10-02T18:00:00Z", "linked": True,
      "drops": [{"id": "d1", "name": "Hazmat Suit", "image": None, "required": 120, "watched": 75, "claimed": False},
                {"id": "d2", "name": "Garage Door Skin", "image": None, "required": 60, "watched": 60, "claimed": True}]},
                   {"id": "aurora", "name": "Aurora Cape", "game": "Minecraft", "image": None, "endsAt": "2026-10-15T06:58:59Z", "linked": True,
                    "quest": True, "drops": [{"id": "r-aurora", "name": "Aurora Cape", "image": None, "required": 15, "watched": 15, "claimed": True,
                                              "claimable": False, "rewardId": "r-aurora", "redeemUrl": "https://www.minecraft.net/redeem"}]},
                   {"id": "creeper", "name": "Corrupted Creeper Cape", "game": "Minecraft", "image": None, "endsAt": "2026-09-27T06:58:59Z", "linked": True,
                    "quest": True, "drops": [{"id": "g-creeper", "name": "Corrupted Creeper Cape", "image": None, "required": 15, "watched": 15,
                                              "claimed": False, "claimable": True, "rewardId": "r-creeper", "redeemUrl": None}]},
                   {"id": "s0ph", "name": "s0phtember Gifter", "game": None, "image": None, "endsAt": "2026-10-11T05:30:00Z", "linked": True,
                    "quest": True, "drops": [{"id": "g-watch", "name": "s0phtember Watcher", "image": None, "required": 1440, "watched": 137,
                                              "claimed": False, "claimable": False, "rewardId": "r-watcher", "redeemUrl": None}]}],
     "claimed": [{"id": "r-aurora", "name": "Aurora Cape", "image": None, "at": None, "game": "Minecraft",
                  "campaignId": "aurora", "redeemUrl": "https://www.minecraft.net/redeem"},
                 {"id": "pichu", "name": "Pichu", "image": None, "at": None, "game": None, "campaignId": "poke", "redeemUrl": None},
                 {"id": "b2", "name": "Garage Door Skin", "image": None, "at": "2026-09-27T14:02:00Z", "game": "Rust",
                  "campaignId": None, "redeemUrl": None}]})
out({"t": "campaigns", "campaigns": catalogue()})
threading.Thread(target=state_loop, daemon=True).start()
for line in sys.stdin:
    cmd = json.loads(line)
    if cmd.get("cmd") == "order":
        order[:] = cmd["order"]
    elif cmd.get("cmd") == "slots":
        slots[:] = cmd["slots"]
    elif cmd.get("cmd") == "watch-games":
        watch[:] = [g.lower() for g in cmd["games"]]
        out({"t": "campaigns", "campaigns": catalogue()})
    elif cmd.get("cmd") == "refresh-follows" and "newfollow" not in BASE:
        BASE.append("newfollow")
        points["newfollow"] = 5
        out({"t": "event", "event": "STREAMER_ADDED", "login": "newfollow", "source": "follow"})
    out({"t": "log", "level": "INFO", "logger": "fake", "msg": "cmd " + line.strip()})
