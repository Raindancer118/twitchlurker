import json, os, sys, time
config = json.load(open(sys.argv[1]))
token = json.load(open(config["tokenFile"]))
def out(o):
    sys.stdout.write(json.dumps(o) + "\n"); sys.stdout.flush()
out({"t": "log", "level": "INFO", "logger": "fake", "msg": "config streamers=" + ",".join(config["streamers"]) + " login=" + token["login"]})
if os.path.exists(os.path.join(config["workDir"], "crash")):
    sys.stderr.write("boom\n"); sys.stderr.flush()
    sys.exit(3)
out({"t": "state", "user": token["login"], "session": "s", "startedAt": None, "streamers": [
    {"login": "papaplatte", "channelId": "1", "online": True, "watching": True, "points": 100, "game": "x", "title": "t",
     "viewers": 1, "onlineSince": None, "minutesWatched": 1, "streakPending": False, "dropsEligible": False, "multiplier": False, "source": "follow"}]})
for line in sys.stdin:
    cmd = json.loads(line)
    arg = cmd.get("login") or ("" if cmd["cmd"] == "refresh-follows" else None) or ",".join(x or "" for x in cmd.get("slots") or cmd.get("order") or cmd.get("games") or [])
    out({"t": "log", "level": "INFO", "logger": "fake", "msg": "cmd " + cmd["cmd"] + " " + arg})
out({"t": "log", "level": "INFO", "logger": "fake", "msg": "bye"})
