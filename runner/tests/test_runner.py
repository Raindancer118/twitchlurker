import subprocess
import sys
from pathlib import Path

RUNNER_DIR = Path(__file__).resolve().parents[1]

# lurker_runner rewires stdout at import time, so it is exercised in a separate interpreter.
SCRIPT = r'''
import sys, types
sys.path.insert(0, sys.argv[1])
import lurker_runner as r

class S:
    def __init__(self, name):
        self.username = name

events = []
r.emit = events.append
added = []
r.add_streamer = lambda miner, login, source: added.append((login, source)) or True
miner = types.SimpleNamespace(streamers=[S("a"), S("gone")], twitch=types.SimpleNamespace(get_followers=lambda: ["a", "new"]))
r.sync_follows(miner, {"followers": True, "blacklist": []})
assert added == [("new", "follow")], added
assert [s.username for s in miner.streamers] == ["a"], miner.streamers
assert any(e.get("event") == "STREAMER_REMOVED" for e in events), events
print("OK", file=sys.stderr)
'''


def test_sync_follows_runs_end_to_end():
    result = subprocess.run([sys.executable, "-c", SCRIPT, str(RUNNER_DIR)], capture_output=True, text=True, timeout=60)
    assert result.returncode == 0, result.stderr[-2000:]
    assert "OK" in result.stderr
