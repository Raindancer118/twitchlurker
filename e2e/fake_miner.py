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
            "papaplatte": streamer("papaplatte", True, "papaplatte" in watching, "Just Chatting", '<img src=x onerror="window.__xss=1"> Chillen & Quatschen', 21000, streak=True),
            "zarbex": streamer("zarbex", True, "zarbex" in watching, "Rust", "Rust Wipe Day #drops", 5400, drops=True),
            "trymacs": streamer("trymacs", True, "trymacs" in watching, "Minecraft", "Hardcore Tag 12", 9000),
            "gronkh": streamer("gronkh", False, False),
        }
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
                {"id": "d2", "name": "Garage Door Skin", "image": None, "required": 60, "watched": 60, "claimed": True}]}],
     "claimed": [{"id": "b2", "name": "Garage Door Skin", "image": None, "at": "2026-09-27T14:02:00Z", "game": "Rust"}]})
threading.Thread(target=state_loop, daemon=True).start()
for line in sys.stdin:
    cmd = json.loads(line)
    if cmd.get("cmd") == "order":
        order[:] = cmd["order"]
    elif cmd.get("cmd") == "slots":
        slots[:] = cmd["slots"]
    out({"t": "log", "level": "INFO", "logger": "fake", "msg": "cmd " + line.strip()})
