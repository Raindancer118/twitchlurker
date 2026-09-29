"""Runs Twitch-Channel-Points-Miner-v2 under the Java supervisor.

Protocol: one JSON object per line on stdout (t = log | event | state | drops | status).
Commands arrive as JSON lines on stdin ({"cmd": "add", "login": "..."}).
Anything the miner prints directly ends up on stderr.
"""
import copy
import json
import logging
import os
import random
import sys
import threading
import time
import traceback

import requests

import lurker_core as core
import lurker_watch

_out = os.fdopen(os.dup(1), "w", encoding="utf-8", buffering=1)
os.dup2(2, 1)
sys.stdout = sys.stderr
_emit_lock = threading.Lock()


def emit(obj):
    obj.setdefault("ts", time.time())
    line = json.dumps(obj, ensure_ascii=False, default=str)
    with _emit_lock:
        _out.write(line + "\n")
        _out.flush()


from TwitchChannelPointsMiner import TwitchChannelPointsMiner  # noqa: E402
from TwitchChannelPointsMiner.classes.Chat import ChatPresence  # noqa: E402
from TwitchChannelPointsMiner.classes.entities.PubsubTopic import PubsubTopic  # noqa: E402
from TwitchChannelPointsMiner.classes.entities.Stream import Stream  # noqa: E402
from TwitchChannelPointsMiner.classes.entities.Streamer import Streamer, StreamerSettings  # noqa: E402
from TwitchChannelPointsMiner.classes.Exceptions import StreamerDoesNotExistException  # noqa: E402
from TwitchChannelPointsMiner.classes.Settings import Settings  # noqa: E402
from TwitchChannelPointsMiner.classes.Twitch import Twitch  # noqa: E402
from TwitchChannelPointsMiner.constants import GQLOperations  # noqa: E402
from TwitchChannelPointsMiner.logger import LoggerSettings  # noqa: E402
from TwitchChannelPointsMiner.utils import set_default_settings  # noqa: E402

GAME_DIRECTORY = {
    "operationName": "DirectoryPage_Game",
    "extensions": {"persistedQuery": {"version": 1, "sha256Hash": "86bcceb4e8b1a51256ff8eed8bd8aae4acacf80d737efe904f84f3aeadf8cafd"}},
    "variables": {
        "limit": 30, "slug": None, "imageWidth": 50, "includeCostreaming": False,
        "options": {
            "broadcasterLanguages": [], "freeformTags": None, "includeRestricted": ["SUB_ONLY_LIVE"],
            "recommendationsContext": {"platform": "web"}, "sort": "VIEWER_COUNT",
            "systemFilters": ["DROPS_ENABLED"], "tags": [], "requestID": "JIRA-VXP-2397",
        },
        "sortTypeIsRecency": False,
    },
}

last_watched = {}
order = []
scouted = set()
extra_logins = set()


class JsonLogHandler(logging.Handler):
    def emit(self, record):
        try:
            if record.levelno < logging.INFO:
                return
            msg = record.getMessage()
            event = getattr(record, "event", None)
            if event is not None:
                emit({"t": "event", "event": str(event), "msg": msg})
            emit({"t": "log", "level": record.levelname, "logger": "runner" if getattr(record, "runner", False) else record.name, "msg": msg})
        except Exception:
            pass


def install_hooks():
    lurker_watch.install()
    orig_minute = Stream.update_minute_watched

    def update_minute_watched(self):
        last_watched[id(self)] = time.time()
        return orig_minute(self)

    Stream.update_minute_watched = update_minute_watched

    orig_history = Streamer.update_history

    def update_history(self, reason_code, earned, counter=1):
        if reason_code != "Spent":
            emit({"t": "event", "event": "POINTS", "login": self.username, "amount": earned,
                  "reason": reason_code, "balance": self.channel_points})
        return orig_history(self, reason_code, earned, counter)

    Streamer.update_history = update_history

    orig_bonus = Twitch.claim_bonus

    def claim_bonus(self, streamer, claim_id):
        result = orig_bonus(self, streamer, claim_id)
        emit({"t": "event", "event": "BONUS", "login": streamer.username})
        return result

    Twitch.claim_bonus = claim_bonus

    orig_moment = Twitch.claim_moment

    def claim_moment(self, streamer, moment_id):
        result = orig_moment(self, streamer, moment_id)
        emit({"t": "event", "event": "MOMENT", "login": streamer.username})
        return result

    Twitch.claim_moment = claim_moment

    orig_raid = Twitch.update_raid

    def update_raid(self, streamer, raid):
        joining = streamer.raid != raid
        result = orig_raid(self, streamer, raid)
        if joining:
            emit({"t": "event", "event": "RAID", "login": streamer.username,
                  "target": getattr(raid, "target_login", None)})
        return result

    Twitch.update_raid = update_raid

    orig_claim_drop = Twitch.claim_drop

    def claim_drop(self, drop):
        ok = orig_claim_drop(self, drop)
        if ok:
            emit({"t": "event", "event": "DROP", "name": getattr(drop, "name", str(drop)),
                  "benefit": getattr(drop, "benefit", None)})
        return ok

    Twitch.claim_drop = claim_drop


def wait_until_running(miner):
    while miner.ws_pool is None or not miner.streamers:
        time.sleep(2)


def add_streamer(miner, login, source):
    login = login.lower().strip()
    if any(s.username == login for s in miner.streamers):
        return False
    twitch = miner.twitch
    streamer = Streamer(login)
    try:
        streamer.channel_id = twitch.get_channel_id(login)
    except StreamerDoesNotExistException:
        emit({"t": "log", "level": "WARNING", "logger": "runner", "msg": f"Channel {login} does not exist"})
        return False
    streamer.settings = set_default_settings(streamer.settings, Settings.streamer_settings)
    streamer.settings.bet = set_default_settings(streamer.settings.bet, Settings.streamer_settings.bet)
    twitch.load_channel_points_context(streamer)
    twitch.check_streamer_online(streamer)
    miner.streamers.append(streamer)
    miner.ws_pool.submit(PubsubTopic("video-playback-by-id", streamer=streamer))
    if streamer.settings.follow_raid:
        miner.ws_pool.submit(PubsubTopic("raid", streamer=streamer))
    if streamer.settings.claim_moments:
        miner.ws_pool.submit(PubsubTopic("community-moments-channel-v1", streamer=streamer))
    if source == "drops":
        scouted.add(login)
    core.apply_order(miner.streamers, order)
    emit({"t": "event", "event": "STREAMER_ADDED", "login": login, "source": source})
    return True


def drop_scout(miner, login):
    """Stop lurking a scouted channel that no longer earns drops (follows and manual channels are never touched)."""
    if login not in scouted:
        return
    scouted.discard(login)
    for s in list(miner.streamers):
        if s.username == login:
            miner.streamers.remove(s)
    emit({"t": "event", "event": "STREAMER_REMOVED", "login": login})
    emit({"t": "log", "level": "INFO", "logger": "runner", "msg": f"Drop hunt: {login} no longer earns drops, stopped lurking"})


stream_start_cache = {}
STREAM_START_QUERY = "query StreamStart($logins: [String!]) { users(logins: $logins) { login stream { id createdAt } } }"


def refresh_stream_starts(miner):
    """Twitch's start time per broadcast, looked up once per broadcast (the !lurk announcer keys on it)."""
    missing = core.logins_missing_start(list(miner.streamers), stream_start_cache)
    for i in range(0, len(missing), 50):
        try:
            response = miner.twitch.post_gql_request({"operationName": "StreamStart", "query": STREAM_START_QUERY,
                                                      "variables": {"logins": missing[i:i + 50]}})
            stream_start_cache.update(core.stream_starts(response))
        except Exception as e:
            emit({"t": "log", "level": "WARNING", "logger": "runner", "msg": f"Could not read stream start times: {e}"})


def state_loop(miner, config):
    wait_until_running(miner)
    while miner.running:
        refresh_stream_starts(miner)
        now = time.time()
        streamers = []
        for s in list(miner.streamers):
            snap = core.streamer_snapshot(s, last_watched, now, extra_logins, stream_start_cache)
            if s.username in scouted:
                snap["source"] = "drops"
            streamers.append(snap)
        emit({"t": "state", "user": config["login"], "session": miner.session_id, "slots": lurker_watch.control["pinned"],
              "startedAt": miner.start_datetime.isoformat() if miner.start_datetime else None,
              "streamers": streamers})
        time.sleep(15)


QUEST_IN_PROGRESS = ("query QuestProgress { currentUser { id inventory { viewerRewardDropCampaignsInProgress "
                     "{ id rewardGroups { id self { status currentMinutesWatched grantCount } } } } } }")
QUEST_GROUP_FIELDS = ("id name startAt endAt imageURL game { id displayName boxArtURL } rewardGroups { id name "
                      "progressCriteria { requirementType requirements { minutesWatched subs } repeatableConfig { repeatableTimes } } "
                      "rewards { id name thumbnailURL } }")


def fetch_quest_progress(twitch):
    """(user id, in-progress list, details by campaign id) for Twitch's reward-drop campaigns (quests, channel badges).
    Neither shows up in the classic inventory; plain GQL queries still work for the TV client."""
    data = (twitch.post_gql_request({"operationName": "QuestProgress", "query": QUEST_IN_PROGRESS}) or {}).get("data") or {}
    user = data.get("currentUser") or {}
    in_progress = ((user.get("inventory") or {}).get("viewerRewardDropCampaignsInProgress")) or []
    ids = [c["id"] for c in in_progress if c.get("id")]
    details = {}
    if ids:
        params = ", ".join(f"$id{i}: ID!" for i in range(len(ids)))
        fields = " ".join(f"c{i}: dropsCampaign(id: $id{i}) {{ {QUEST_GROUP_FIELDS} }}" for i in range(len(ids)))
        response = twitch.post_gql_request({"operationName": "QuestDetails", "query": f"query QuestDetails({params}) {{ {fields} }}",
                                            "variables": {f"id{i}": cid for i, cid in enumerate(ids)}})
        found = (response or {}).get("data") or {}
        details = {cid: found.get(f"c{i}") for i, cid in enumerate(ids) if found.get(f"c{i}")}
    return user.get("id"), in_progress, details


def claim_reward_drop(twitch, instance_id):
    query = copy.deepcopy(GQLOperations.DropsPage_ClaimDropRewards)
    query["variables"] = {"input": {"dropInstanceID": instance_id}}
    response = twitch.post_gql_request(query) or {}
    return not response.get("errors") and ((response.get("data") or {}).get("claimDropRewards") is not None)


claim_attempts = {}


def inventory_loop(miner):
    wait_until_running(miner)
    quests = None
    while miner.running:
        try:
            twitch = miner.twitch
            inventory = twitch._Twitch__get_inventory()
            snap = core.inventory_snapshot(inventory)
            claimed_now = set()
            try:
                user_id, in_progress, details = fetch_quest_progress(twitch)
                # TCPM only claims classic drops; earned quest tiers would otherwise expire unclaimed.
                tried = core.claims_due(core.claimable_instances(in_progress, details, user_id), claim_attempts, time.time())
                for instance_id, _ in tried:
                    claim_attempts[instance_id] = time.time()
                    claim_reward_drop(twitch, instance_id)
                if tried:
                    # Twitch can answer a claim with success and still leave it unclaimed, so trust only a re-read.
                    user_id, in_progress, details = fetch_quest_progress(twitch)
                    still = {i for i, _ in core.claimable_instances(in_progress, details, user_id)}
                    for instance_id, name in tried:
                        if instance_id in still:
                            emit({"t": "log", "level": "INFO", "logger": "runner",
                                  "msg": f"Twitch did not accept the claim for {name}; claim it at twitch.tv/drops/inventory"})
                        else:
                            claimed_now.add(instance_id.split("#")[1])
                            emit({"t": "event", "event": "DROP", "name": name})
                completed = (inventory or {}).get("completedRewardCampaigns") or []
                snap["campaigns"] += core.quest_progress(in_progress, details, completed, time.time())
            except Exception as e:
                emit({"t": "log", "level": "WARNING", "logger": "runner", "msg": f"Could not read quest progress: {e}"})
            emit({"t": "drops", **snap})
            # Quest rewards are granted by Twitch itself, so no claim_drop hook ever sees them.
            if inventory:
                for name in core.new_quest_rewards(None if quests is None else quests | claimed_now, inventory):
                    emit({"t": "event", "event": "DROP", "name": name})
                quests = core.completed_quest_ids(inventory) | claimed_now | (quests or set())
        except Exception as e:
            emit({"t": "log", "level": "WARNING", "logger": "runner", "msg": f"Could not read inventory: {e}"})
        time.sleep(300)


drops_wake = threading.Event()
watch_games = []


COMMUNITY_DROPS_URL = "https://twitch-drops-api.sunkwi.com/drops"


def fetch_community_drops(url):
    # Twitch only lists campaigns to integrity-protected web/app clients; this public community mirror has the same objects.
    r = requests.get(url, timeout=30, headers={"User-Agent": "twitchlurker (+https://github.com/Raindancer118)"})
    r.raise_for_status()
    data = r.json()
    return data if isinstance(data, list) else None


def fetch_quests(twitch):
    """Reward campaigns ("quests") the user can earn; unlike dropCampaigns, Twitch still answers this for the TV client."""
    try:
        response = twitch.post_gql_request(GQLOperations.ViewerDropsDashboard)
        return ((response or {}).get("data") or {}).get("rewardCampaignsAvailableToUser") or []
    except Exception as e:
        emit({"t": "log", "level": "WARNING", "logger": "runner", "msg": f"Could not read quests: {e}"})
        return []


slug_cache = {}


def resolve_slug(twitch, game):
    """Twitch's own slug for a game name; guessing it breaks on names like 'Dungeons & Dragons'."""
    if game.get("slug"):
        return game["slug"]
    name = game["displayName"]
    if name.lower() not in slug_cache:
        try:
            response = twitch.post_gql_request({"operationName": "GameSlug", "query": "query GameSlug($name:String!){game(name:$name){slug}}", "variables": {"name": name}})
            slug = core.slug_from_lookup(response)
        except Exception:
            slug = None
        if not slug:
            return core.game_slug(game)
        slug_cache[name.lower()] = slug
    return slug_cache[name.lower()]


def drops_loop(miner, config):
    """Every 10 min (or when the watchlist changes): publish the campaign catalogue, then scout drop streams."""
    scout = config.get("dropScout") or {}
    per_game = int(scout.get("channelsPerGame", 2))
    require_linked = bool(scout.get("requireLinked", True))
    scouting = scout.get("enabled", True)
    blacklist = {b.lower() for b in config.get("blacklist", [])}
    url = config.get("communityDropsUrl") or COMMUNITY_DROPS_URL
    query = copy.deepcopy(GAME_DIRECTORY)
    if config.get("gameDirectoryHash"):
        query["extensions"]["persistedQuery"]["sha256Hash"] = config["gameDirectoryHash"]
    community, fetched_at = None, 0.0
    wait_until_running(miner)
    drops_wake.wait(45)
    while miner.running:
        drops_wake.clear()
        try:
            twitch = miner.twitch
            if community is None or time.time() - fetched_at > 1800:
                try:
                    community = fetch_community_drops(url) or community
                    fetched_at = time.time()
                except Exception as e:
                    emit({"t": "log", "level": "WARNING", "logger": "runner", "msg": f"Drop list unreachable: {e}"})
            inventory = twitch._Twitch__get_inventory() or {}
            catalogue = core.sort_catalogue(
                core.quest_catalogue(fetch_quests(twitch), core.completed_quest_ids(inventory), watch_games, time.time())
                + core.community_catalogue(community, watch_games, core.linked_from_inventory(inventory)))
            access = "community" if community is not None else "unavailable"
            emit({"t": "campaigns", "campaigns": catalogue, "access": access})
            emit({"t": "log", "level": "INFO", "logger": "runner",
                  "msg": f"Drop catalogue: {len(catalogue)} campaigns ({access}), watching: {', '.join(watch_games) or '–'}"})

            if scouting:
                known = {s.username for s in miner.streamers} | blacklist
                targets = core.scout_targets(watch_games, inventory, require_linked)
                plans = {t["displayName"].lower(): core.drop_channel_plan(catalogue, t["displayName"]) for t in targets}
                games = {s.username: ((s.stream.game or {}).get("displayName") if s.is_online else None)
                         for s in miner.streamers if s.username in scouted}
                for login in core.stale_scouts(games, plans):
                    drop_scout(miner, login)
                for target in targets:
                    name = target["displayName"].lower()
                    plan = plans[name]
                    if plan["skip"]:
                        emit({"t": "log", "level": "INFO", "logger": "runner", "msg": f"Drops for {target['displayName']}: {plan['skip']}"})
                        continue
                    allowed = plan["allowed"]
                    live_for_game = [s for s in miner.streamers
                                     if s.is_online and ((s.stream.game or {}).get("displayName") or "").lower() == name
                                     and (allowed is None or s.username in allowed)]
                    missing = per_game - len(live_for_game)
                    if missing <= 0:
                        continue
                    q = copy.deepcopy(query)
                    q["variables"]["slug"] = resolve_slug(twitch, target)
                    response = twitch.post_gql_request(q)
                    if not ((response or {}).get("data") or {}).get("game"):
                        emit({"t": "log", "level": "WARNING", "logger": "runner",
                              "msg": f"Drops for {target['displayName']}: Twitch has no category '{q['variables']['slug']}'"})
                        continue
                    picked = core.pick_directory_channels(response, known, missing, allowed=allowed, prefer=plan["prefer"])
                    for login in picked:
                        if add_streamer(miner, login, "drops"):
                            known.add(login)
                    emit({"t": "log", "level": "INFO", "logger": "runner",
                          "msg": f"Drops for {target['displayName']}: "
                                 + (f"lurking {', '.join(picked)}" if picked else
                                    f"waiting for {', '.join(sorted(allowed))} to go live" if allowed else "no live drop streams right now")})
                    time.sleep(random.uniform(1, 3))
        except Exception as e:
            emit({"t": "log", "level": "WARNING", "logger": "runner", "msg": f"Drop hunt failed: {e}"})
        drops_wake.wait(int(scout.get("intervalSeconds", 600)))

follow_lock = threading.Lock()


def sync_follows(miner, config):
    if not config.get("followers", True):
        return
    with follow_lock:
        followers = miner.twitch.get_followers()
        blacklist = {b.lower() for b in config.get("blacklist", [])}
        current = [s.username for s in miner.streamers]
        added, removed = core.follow_changes(followers, current, extra_logins, scouted, blacklist)
        for login in removed:
            for s in list(miner.streamers):
                if s.username == login:
                    miner.streamers.remove(s)
            emit({"t": "event", "event": "STREAMER_REMOVED", "login": login})
        for login in added:
            add_streamer(miner, login, "follow")
        if added or removed:
            emit({"t": "log", "level": "INFO", "logger": "runner", "msg": f"Follows synced: +{len(added)} / -{len(removed)}"})


def follow_loop(miner, config):
    wait_until_running(miner)
    while miner.running:
        time.sleep(300)
        try:
            sync_follows(miner, config)
        except Exception as e:
            emit({"t": "log", "level": "WARNING", "logger": "runner", "msg": f"Could not read follows: {e}"})


def command_loop(miner, config):
    for line in sys.stdin:
        try:
            cmd = json.loads(line)
        except ValueError:
            continue
        if cmd.get("cmd") == "add" and cmd.get("login"):
            wait_until_running(miner)
            extra_logins.add(cmd["login"].lower())
            add_streamer(miner, cmd["login"], "extra")
        elif cmd.get("cmd") == "refresh-follows":
            wait_until_running(miner)
            start_thread(sync_follows, "follow-refresh", miner, config)
        elif cmd.get("cmd") == "watch-games":
            watch_games[:] = [str(g) for g in cmd.get("games") or []]
            drops_wake.set()
        elif cmd.get("cmd") == "slots":
            lurker_watch.control["pinned"] = core.normalize_slots(cmd.get("slots"))
            emit({"t": "log", "level": "INFO", "logger": "runner", "msg": f"Slots: {lurker_watch.control['pinned']}"})
        elif cmd.get("cmd") == "order":
            order[:] = [str(o).lower() for o in cmd.get("order") or []]
            core.apply_order(miner.streamers, order)
            emit({"t": "log", "level": "INFO", "logger": "runner", "msg": f"Order updated ({len(order)} channels)"})
    # stdin closed: supervisor is gone, shut down cleanly.
    miner.end(0, 0)


def start_thread(target, name, *args):
    def run():
        try:
            target(*args)
        except SystemExit:
            pass
        except Exception:
            emit({"t": "log", "level": "ERROR", "logger": "runner", "msg": f"{name}: {traceback.format_exc()}"})

    t = threading.Thread(target=run, name=name, daemon=True)
    t.start()
    return t


def main():
    config = json.load(open(sys.argv[1], encoding="utf-8"))
    os.makedirs(config["workDir"], exist_ok=True)
    os.chdir(config["workDir"])
    token = json.load(open(config["tokenFile"], encoding="utf-8"))
    config["login"] = token["login"]
    core.write_cookies(os.path.join("cookies", f"{token['login']}.pkl"), token["accessToken"], token["userId"])
    extra_logins.update(s.lower() for s in config.get("streamers", []))
    lurker_watch.control["pinned"] = core.normalize_slots(config.get("slots"))
    lurker_watch.control["scouted"] = scouted
    watch_games[:] = [str(g) for g in (config.get("dropScout") or {}).get("games", [])]
    order[:] = [o.lower() for o in config.get("order", [])]

    install_hooks()
    miner = TwitchChannelPointsMiner(
        username=token["login"],
        claim_drops_startup=True,
        priority=core.parse_priority(config.get("priority")),
        logger_settings=LoggerSettings(save=False, console_level=logging.CRITICAL + 10, emoji=False, less=True),
        streamer_settings=StreamerSettings(
            make_predictions=False,
            follow_raid=config.get("followRaid", True),
            claim_drops=True,
            claim_moments=config.get("claimMoments", True),
            watch_streak=config.get("watchStreak", True),
            community_goals=False,
            chat=ChatPresence.NEVER,
        ),
    )
    logging.getLogger().addHandler(JsonLogHandler())
    emit({"t": "status", "status": "STARTING", "login": token["login"]})

    start_thread(state_loop, "state", miner, config)
    start_thread(inventory_loop, "inventory", miner)
    start_thread(drops_loop, "drops", miner, config)
    start_thread(command_loop, "commands", miner, config)
    start_thread(follow_loop, "follows", miner, config)
    start_thread(lambda: (wait_until_running(miner), core.apply_order(miner.streamers, order)), "initial-order")

    miner.mine(
        streamers=config.get("streamers", []),
        blacklist=[b.lower() for b in config.get("blacklist", [])],
        followers=config.get("followers", True),
    )


if __name__ == "__main__":
    main()
