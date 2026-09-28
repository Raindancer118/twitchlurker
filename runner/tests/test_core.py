import pickle
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import lurker_core as core


def test_write_cookies_matches_miner_format(tmp_path):
    path = tmp_path / "cookies" / "tomlurkt.pkl"
    core.write_cookies(path, "abc123", "4711")
    cookies = pickle.loads(path.read_bytes())
    assert {"name": "auth-token", "value": "abc123"} in cookies
    persistent = next(c["value"] for c in cookies if c["name"] == "persistent")
    # the miner parses the user id as persistent.split("%")[0]
    assert int(persistent.split("%")[0]) == 4711
    assert oct(path.stat().st_mode & 0o777) == "0o600"


def test_slug_from_game_name():
    assert core.game_slug({"displayName": "Tom Clancy's Rainbow Six Siege"}) == "tom-clancys-rainbow-six-siege"
    assert core.game_slug({"displayName": "Rust", "slug": "rust"}) == "rust"
    assert core.game_slug({"name": "Call of Duty: Black Ops 7"}) == "call-of-duty-black-ops-7"


def test_parse_priority_rejects_unknown():
    assert [p.name for p in core.parse_priority(["STREAK", "drops", "ORDER"])] == ["STREAK", "DROPS", "ORDER"]
    with pytest.raises(ValueError):
        core.parse_priority(["STREAK", "EVERYTHING"])
    assert [p.name for p in core.parse_priority([])] == ["STREAK", "DROPS", "ORDER"]


def _streamer(name, online=True, points=1200, game="Rust"):
    stream = SimpleNamespace(
        title="chill", game={"id": "1", "displayName": game} if game else {},
        viewers_count=321, watch_streak_missing=False, minute_watched=12.0,
        campaigns_ids=["c1"], campaigns=[],
    )
    return SimpleNamespace(
        username=name, channel_id="99", is_online=online, channel_points=points,
        online_at=1000.0, offline_at=0, stream=stream, activeMultipliers=None,
    )


def test_streamer_snapshot_marks_recently_watched():
    s = _streamer("papaplatte")
    snap = core.streamer_snapshot(s, last_watched={id(s.stream): 5000.0}, now=5050.0, extra=set())
    assert snap["login"] == "papaplatte"
    assert snap["online"] is True
    assert snap["watching"] is True
    assert snap["points"] == 1200
    assert snap["game"] == "Rust"
    assert snap["viewers"] == 321
    assert snap["dropsEligible"] is True
    assert snap["source"] == "follow"

    stale = core.streamer_snapshot(s, last_watched={id(s.stream): 5000.0}, now=5200.0, extra={"papaplatte"})
    assert stale["watching"] is False
    assert stale["source"] == "extra"


def test_streamer_snapshot_offline_has_no_stream_fields():
    s = _streamer("trymacs", online=False, game=None)
    snap = core.streamer_snapshot(s, last_watched={}, now=1.0, extra=set())
    assert snap["online"] is False and snap["game"] is None and snap["viewers"] == 0


INVENTORY = {
    "dropCampaignsInProgress": [{
        "id": "camp1", "name": "Rust Twitch Drops #40", "endAt": "2026-10-02T18:00:00Z",
        "game": {"id": "263490", "displayName": "Rust", "boxArtURL": "https://x/rust-{width}x{height}.jpg"},
        "self": {"isAccountConnected": True},
        "timeBasedDrops": [
            {"id": "d1", "name": "Hazmat", "requiredMinutesWatched": 120,
             "benefitEdges": [{"benefit": {"id": "b1", "name": "Hazmat Suit", "imageAssetURL": "https://x/h.png"}}],
             "self": {"currentMinutesWatched": 75, "isClaimed": False, "dropInstanceID": None}},
            {"id": "d2", "name": "Door", "requiredMinutesWatched": 60,
             "benefitEdges": [{"benefit": {"id": "b2", "name": "Door Skin", "imageAssetURL": "https://x/d.png"}}],
             "self": {"currentMinutesWatched": 60, "isClaimed": True, "dropInstanceID": "inst"}},
        ],
    }],
    "gameEventDrops": [
        {"id": "b2", "name": "Door Skin", "imageURL": "https://x/d.png", "lastAwardedAt": "2026-09-27T14:02:00Z", "game": {"name": "Rust"}},
    ],
}


def test_inventory_snapshot():
    snap = core.inventory_snapshot(INVENTORY)
    camp = snap["campaigns"][0]
    assert camp["id"] == "camp1" and camp["game"] == "Rust" and camp["linked"] is True
    assert camp["image"] == "https://x/rust-285x380.jpg"
    first = camp["drops"][0]
    assert first == {"id": "d1", "name": "Hazmat Suit", "image": "https://x/h.png",
                     "required": 120, "watched": 75, "claimed": False}
    assert camp["drops"][1]["claimed"] is True
    assert snap["claimed"][0]["name"] == "Door Skin"
    assert snap["claimed"][0]["at"] == "2026-09-27T14:02:00Z"


def test_inventory_snapshot_tolerates_empty():
    assert core.inventory_snapshot({}) == {"campaigns": [], "claimed": []}
    assert core.inventory_snapshot({"dropCampaignsInProgress": None, "gameEventDrops": None}) == {"campaigns": [], "claimed": []}


def test_campaigns_needing_channels_only_linked_and_unfinished():
    dashboard = [
        {"id": "a", "status": "ACTIVE", "game": {"id": "1", "displayName": "Rust"}, "self": {"isAccountConnected": True}},
        {"id": "b", "status": "ACTIVE", "game": {"id": "2", "displayName": "Valorant"}, "self": {"isAccountConnected": False}},
        {"id": "c", "status": "EXPIRED", "game": {"id": "3", "displayName": "Dota 2"}, "self": {"isAccountConnected": True}},
        {"id": "d", "status": "ACTIVE", "game": {"id": "1", "displayName": "Rust"}, "self": {"isAccountConnected": True}},
    ]
    finished = {"d"}
    games = core.games_to_scout(dashboard, finished_campaign_ids=finished, require_linked=True)
    assert games == [{"id": "1", "displayName": "Rust"}]
    games_all = core.games_to_scout(dashboard, finished_campaign_ids=set(), require_linked=False)
    assert [g["displayName"] for g in games_all] == ["Rust", "Valorant"]


def test_finished_campaigns_from_inventory():
    inv = core.inventory_snapshot(INVENTORY)
    assert core.finished_campaign_ids(inv) == set()
    for d in inv["campaigns"][0]["drops"]:
        d["claimed"] = True
    assert core.finished_campaign_ids(inv) == {"camp1"}


def test_pick_directory_channels():
    resp = {"data": {"game": {"streams": {"edges": [
        {"node": {"broadcaster": {"login": "big"}, "viewersCount": 9000}},
        {"node": {"broadcaster": None, "viewersCount": 5}},
        {"node": {"broadcaster": {"login": "known"}, "viewersCount": 800}},
        {"node": {"broadcaster": {"login": "blocked"}, "viewersCount": 700}},
        {"node": {"broadcaster": {"login": "mid"}, "viewersCount": 400}},
    ]}}}}
    picked = core.pick_directory_channels(resp, exclude={"known", "blocked"}, limit=2)
    assert picked == ["big", "mid"]
    assert core.pick_directory_channels({"data": {"game": None}}, exclude=set(), limit=3) == []
    assert core.pick_directory_channels({"errors": [{"message": "x"}]}, exclude=set(), limit=3) == []


def test_parse_point_gain():
    assert core.parse_point_gain("+12 → Streamer(username=papaplatte, channel_id=1, channel_points=2.1k) - Reason: WATCH.") == ("papaplatte", 12, "WATCH")
    assert core.parse_point_gain("irgendwas") is None
