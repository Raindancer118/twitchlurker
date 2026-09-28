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


from TwitchChannelPointsMiner.classes.Settings import Priority


def _s(name, online=True, points=0, streak=False, drops=False, multiplier=0, online_at=0, minute_watched=0, offline_at=0):
    stream = SimpleNamespace(watch_streak_missing=streak, minute_watched=minute_watched, campaigns_ids=["c"] if drops else [])
    st = SimpleNamespace(username=name, is_online=online, channel_points=points, online_at=online_at, offline_at=offline_at,
                         stream=stream, settings=SimpleNamespace(watch_streak=True, claim_drops=True), activeMultipliers=None)
    st.drops_condition = lambda: st.settings.claim_drops and st.is_online and st.stream.campaigns_ids != []
    st.viewer_has_points_multiplier = lambda: multiplier > 0
    st.total_points_multiplier = lambda: multiplier
    return st


PRIO = [Priority.STREAK, Priority.DROPS, Priority.ORDER]


def names(streamers, idx):
    return [streamers[i].username for i in idx]


def test_choose_watching_follows_tcpm_priorities():
    ss = [_s("a"), _s("b", drops=True), _s("c", streak=True), _s("d", online=False)]
    assert names(ss, core.choose_watching(ss, PRIO, [None, None], now=1000)) == ["c", "b"]
    assert names(ss, core.choose_watching(ss, [Priority.ORDER], [None, None], now=1000)) == ["a", "b"]
    assert names(ss, core.choose_watching(ss, [Priority.POINTS_DESCENDING], [None, None], now=1000)) == ["a", "b"]


def test_choose_watching_pins_first_and_falls_back_when_offline():
    ss = [_s("a"), _s("b", drops=True), _s("c", streak=True), _s("d", online=False)]
    assert names(ss, core.choose_watching(ss, PRIO, ["a", None], now=1000)) == ["a", "c"]
    assert names(ss, core.choose_watching(ss, PRIO, [None, "a"], now=1000)) == ["a", "c"]
    assert names(ss, core.choose_watching(ss, PRIO, ["d", "a"], now=1000)) == ["a", "c"]
    assert names(ss, core.choose_watching(ss, PRIO, ["a", "a"], now=1000)) == ["a", "c"]
    assert names(ss, core.choose_watching(ss, PRIO, ["b", "a"], now=1000)) == ["b", "a"]


def test_choose_watching_skips_streams_that_just_went_live_and_stale_streaks():
    ss = [_s("fresh", online_at=990), _s("old", streak=True, minute_watched=9), _s("x")]
    assert names(ss, core.choose_watching(ss, PRIO, [None, None], now=1000)) == ["old", "x"]
    assert names(ss, core.choose_watching(ss, PRIO, ["fresh", None], now=1000)) == ["old", "x"]


def test_choose_watching_subscribed_prefers_highest_multiplier():
    ss = [_s("a"), _s("b", multiplier=1.2), _s("c", multiplier=2)]
    assert names(ss, core.choose_watching(ss, [Priority.SUBSCRIBED, Priority.ORDER], [None, None], now=1000)) == ["c", "b"]


def test_apply_order_is_stable_and_in_place():
    ss = [_s("a"), _s("b"), _s("c"), _s("d")]
    same = ss
    core.apply_order(ss, ["c", "unknown", "a"])
    assert same is ss
    assert [s.username for s in ss] == ["c", "a", "b", "d"]
    core.apply_order(ss, [])
    assert [s.username for s in ss] == ["c", "a", "b", "d"]


def test_normalize_slots():
    assert core.normalize_slots(None) == [None, None]
    assert core.normalize_slots(["Papaplatte", ""]) == ["papaplatte", None]
    assert core.normalize_slots(["a_b", "c", "d"]) == ["a_b", "c"]


def test_follow_changes():
    added, removed = core.follow_changes(
        followers=["a", "b", "new", "blocked"], current=["a", "b", "gone", "extra_one", "scouted_one"],
        extra={"extra_one"}, scouted={"scouted_one"}, blacklist={"blocked"})
    assert added == ["new"]
    assert removed == ["gone"]


def test_follow_changes_ignores_empty_follow_list():
    # An empty answer from Twitch is far more likely a hiccup than the user unfollowing everyone.
    assert core.follow_changes(followers=[], current=["a"], extra=set(), scouted=set(), blacklist=set()) == ([], [])


def test_game_watch_matching():
    assert core.game_watched({"displayName": "Minecraft"}, ["minecraft"])
    assert core.game_watched({"displayName": "Tom Clancy's Rainbow Six Siege"}, ["rainbow six siege"]) is False
    assert core.game_watched({"displayName": "Tom Clancy's Rainbow Six Siege"}, ["tom-clancys-rainbow-six-siege"])
    assert core.game_watched({"displayName": "VALORANT"}, ["Valorant"])
    assert not core.game_watched({"displayName": "Rust"}, [])


def test_pick_directory_channels_respects_allow_list():
    resp = {"data": {"game": {"streams": {"edges": [
        {"node": {"broadcaster": {"login": "random"}, "viewersCount": 9000}},
        {"node": {"broadcaster": {"login": "gronkh"}, "viewersCount": 100}},
    ]}}}}
    assert core.pick_directory_channels(resp, exclude=set(), limit=2, allowed=["papaplatte", "gronkh"]) == ["gronkh"]
    assert core.pick_directory_channels(resp, exclude=set(), limit=2, allowed=[]) == ["random", "gronkh"]


def test_choose_watching_treats_scouted_drop_channels_as_drop_candidates():
    # TCPM's own campaign detection is blind with the TV client, so scouted channels would never win the DROPS priority.
    ss = [_s("a"), _s("b"), _s("mcstreamer")]
    assert names(ss, core.choose_watching(ss, PRIO, [None, None], now=1000)) == ["a", "b"]
    assert names(ss, core.choose_watching(ss, PRIO, [None, None], now=1000, scouted={"mcstreamer"})) == ["mcstreamer", "a"]


COMMUNITY = [
    {"gameId": "27471", "gameDisplayName": "Minecraft", "gameBoxArtURL": "https://x/mc-120x160.jpg", "rewards": [
        {"id": "mc1", "name": "Minecraft Live", "status": "ACTIVE", "startAt": "2026-09-27T00:00:00Z", "endAt": "2026-10-05T00:00:00Z",
         "accountLinkURL": "https://link/mc", "allow": {"isEnabled": True, "channels": [{"id": "1", "name": "PapaPlatte"}]},
         "game": {"id": "27471", "name": "Minecraft"},
         "timeBasedDrops": [{"name": "Cape", "requiredMinutesWatched": 60,
                             "benefitEdges": [{"benefit": {"name": "Twitch Cape", "imageAssetURL": "https://x/cape.png"}}]}]}]},
    {"gameId": "263490", "gameDisplayName": "Rust", "gameBoxArtURL": None, "rewards": [
        {"id": "rust1", "name": "Rust #40", "status": "ACTIVE", "endAt": "2026-10-02T00:00:00Z", "allow": {"isEnabled": False},
         "timeBasedDrops": []}]},
]


def test_community_catalogue_marks_watched_and_linked_from_inventory():
    cat = core.community_catalogue(COMMUNITY, watch_games=["minecraft"], linked={"rust1": True, "mc1": False})
    assert [c["id"] for c in cat] == ["mc1", "rust1"]
    mc = cat[0]
    assert mc["game"] == "Minecraft" and mc["gameId"] == "27471" and mc["watched"] is True
    assert mc["image"] == "https://x/mc-120x160.jpg" and mc["linkUrl"] == "https://link/mc"
    assert mc["channels"] == ["papaplatte"] and mc["rewards"][0]["minutes"] == 60
    assert mc["linked"] is False and cat[1]["linked"] is True
    # Channel list only counts when the restriction is enabled.
    assert cat[1]["channels"] == []
    assert core.community_catalogue(None, [], {}) == []
    assert core.community_catalogue({"error": "x"}, [], {}) == []


def test_linked_from_inventory():
    inv = {"dropCampaignsInProgress": [{"id": "rust1", "self": {"isAccountConnected": True}}, {"id": "x", "self": {}}]}
    assert core.linked_from_inventory(inv) == {"rust1": True, "x": False}
    assert core.linked_from_inventory(None) == {}


def test_scout_targets_watched_games_by_name_and_linked_inventory_games():
    inv = {"dropCampaignsInProgress": [
        {"id": "rust1", "self": {"isAccountConnected": True}, "game": {"id": "263490", "displayName": "Rust"},
         "timeBasedDrops": [{"self": {"isClaimed": False}}]},
        {"id": "done", "self": {"isAccountConnected": True}, "game": {"id": "9", "displayName": "Done Game"},
         "timeBasedDrops": [{"self": {"isClaimed": True}}]},
        {"id": "nolink", "self": {"isAccountConnected": False}, "game": {"id": "8", "displayName": "Unlinked"},
         "timeBasedDrops": [{"self": {"isClaimed": False}}]},
    ]}
    targets = core.scout_targets(["Minecraft"], inv, require_linked=True)
    assert [(t["displayName"], t["watched"]) for t in targets] == [("Minecraft", True), ("Rust", False)]
    assert core.game_slug(targets[0]) == "minecraft"
    assert [t["displayName"] for t in core.scout_targets([], inv, require_linked=False)] == ["Rust", "Unlinked"]


def test_game_slug_handles_ampersand_like_twitch():
    # Twitch's slug for "Dungeons & Dragons" is dungeons-and-dragons; guessing "dungeons-dragons" found no streams.
    assert core.game_slug({"displayName": "Dungeons & Dragons"}) == "dungeons-and-dragons"


def test_slug_from_game_lookup():
    resp = {"data": {"game": {"id": "509577", "slug": "dungeons-and-dragons", "displayName": "Dungeons & Dragons"}}}
    assert core.slug_from_lookup(resp) == "dungeons-and-dragons"
    assert core.slug_from_lookup({"data": {"game": None}}) is None
    assert core.slug_from_lookup({"errors": [{"message": "x"}]}) is None
    assert core.slug_from_lookup(None) is None


def test_pick_directory_channels_prefers_campaign_channels():
    resp = {"data": {"game": {"streams": {"edges": [
        {"node": {"viewersCount": 900, "broadcaster": {"login": "big"}}},
        {"node": {"viewersCount": 10, "broadcaster": {"login": "wizards_dnd"}}},
        {"node": {"viewersCount": 500, "broadcaster": {"login": "mid"}}},
    ]}}}}
    assert core.pick_directory_channels(resp, exclude=set(), limit=2, prefer={"wizards_dnd"}) == ["wizards_dnd", "big"]
