"""Pure helpers for the runner: no network, no miner threads."""
import os
import pickle
import re
from pathlib import Path

from TwitchChannelPointsMiner.classes.Settings import Priority

DEFAULT_PRIORITY = ["STREAK", "DROPS", "ORDER"]
# Minute-watched events go out every ~20 s per watched stream; anything older than a few cycles is no longer watched.
WATCHING_WINDOW_SECONDS = 90

_POINT_GAIN = re.compile(r"^\+(\d+) → Streamer\(username=([^,)]+).*Reason: ([A-Z_]+)")


def write_cookies(path, token: str, user_id: str) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    cookies = [
        {"name": "auth-token", "value": token},
        {"name": "persistent", "value": f"{user_id}%3A%3A{token[:8]}"},
    ]
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        pickle.dump(cookies, f)
    os.chmod(path, 0o600)


def game_slug(game: dict) -> str:
    if game.get("slug"):
        return game["slug"]
    name = game.get("displayName") or game.get("name") or ""
    slug = re.sub(r"'", "", name.lower().replace("&", " and "))
    slug = re.sub(r"\W+", "-", slug)
    return re.sub(r"-{2,}", "-", slug.strip("-"))


def parse_priority(names) -> list:
    names = [n.strip().upper() for n in (names or [])] or DEFAULT_PRIORITY
    unknown = [n for n in names if n not in Priority.__members__]
    if unknown:
        raise ValueError(f"unknown priority: {', '.join(unknown)}")
    return [Priority[n] for n in names]


def streamer_snapshot(streamer, last_watched: dict, now: float, extra: set) -> dict:
    stream = streamer.stream
    online = bool(streamer.is_online)
    watched_at = last_watched.get(id(stream))
    game = (stream.game or {}) if online else {}
    return {
        "login": streamer.username,
        "channelId": streamer.channel_id,
        "online": online,
        "watching": online and watched_at is not None and now - watched_at <= WATCHING_WINDOW_SECONDS,
        "points": int(streamer.channel_points or 0),
        "game": game.get("displayName") or game.get("name") if game else None,
        "title": stream.title if online else None,
        "viewers": int(stream.viewers_count or 0) if online else 0,
        "onlineSince": streamer.online_at or None if online else None,
        "minutesWatched": round(float(stream.minute_watched or 0), 1) if online else 0,
        "streakPending": bool(online and stream.watch_streak_missing),
        "dropsEligible": bool(online and stream.campaigns_ids),
        "multiplier": bool(streamer.activeMultipliers),
        "source": "extra" if streamer.username in extra else "follow",
    }


def _box_art(url):
    return url.replace("{width}", "285").replace("{height}", "380") if url else None


def inventory_snapshot(inventory: dict) -> dict:
    inventory = inventory or {}
    campaigns = []
    for c in inventory.get("dropCampaignsInProgress") or []:
        drops = []
        for d in c.get("timeBasedDrops") or []:
            benefit = ((d.get("benefitEdges") or [{}])[0] or {}).get("benefit") or {}
            me = d.get("self") or {}
            drops.append({
                "id": d["id"],
                "name": benefit.get("name") or d.get("name"),
                "image": benefit.get("imageAssetURL"),
                "required": int(d.get("requiredMinutesWatched") or 0),
                "watched": int(me.get("currentMinutesWatched") or 0),
                "claimed": bool(me.get("isClaimed")),
            })
        game = c.get("game") or {}
        campaigns.append({
            "id": c["id"],
            "name": c.get("name"),
            "game": game.get("displayName") or game.get("name"),
            "image": _box_art(game.get("boxArtURL")),
            "endsAt": c.get("endAt"),
            "linked": bool((c.get("self") or {}).get("isAccountConnected")),
            "drops": drops,
        })
    claimed = [
        {"id": b.get("id"), "name": b.get("name"), "image": b.get("imageURL"),
         "at": b.get("lastAwardedAt"), "game": (b.get("game") or {}).get("name")}
        for b in inventory.get("gameEventDrops") or []
    ]
    return {"campaigns": campaigns, "claimed": claimed}


def finished_campaign_ids(inventory_snap: dict) -> set:
    return {
        c["id"] for c in inventory_snap["campaigns"]
        if c["drops"] and all(d["claimed"] for d in c["drops"])
    }


def game_watched(game: dict, watch_games) -> bool:
    wanted = {w.strip().lower() for w in watch_games or [] if w and w.strip()}
    if not wanted or not game:
        return False
    name = (game.get("displayName") or game.get("name") or "").lower()
    return name in wanted or game_slug(game) in wanted


def slug_from_lookup(response) -> str | None:
    game = ((response or {}).get("data") or {}).get("game") or {}
    return game.get("slug") or None


def pick_directory_channels(response: dict, exclude: set, limit: int, allowed=None, prefer=None) -> list:
    game = ((response or {}).get("data") or {}).get("game") or {}
    edges = ((game.get("streams") or {}).get("edges")) or []
    nodes = [e["node"] for e in edges if e.get("node") and e["node"].get("broadcaster")]
    prefer = {p.lower() for p in prefer or ()}
    nodes.sort(key=lambda n: (n["broadcaster"]["login"].lower() not in prefer, -(n.get("viewersCount") or 0)))
    picked = []
    for n in nodes:
        login = n["broadcaster"]["login"].lower()
        if allowed and login not in allowed:
            continue
        if login not in exclude and login not in picked:
            picked.append(login)
        if len(picked) >= limit:
            break
    return picked


def parse_point_gain(message: str):
    m = _POINT_GAIN.match(message or "")
    return (m.group(2), int(m.group(1)), m.group(3)) if m else None


def normalize_slots(slots) -> list:
    slots = list(slots or [])[:2]
    slots += [None] * (2 - len(slots))
    return [s.strip().lower() if isinstance(s, str) and s.strip() else None for s in slots]


def apply_order(streamers: list, order) -> None:
    """Sorts in place (the miner's threads hold this list object): ranked logins first, the rest keeps its order."""
    rank = {login: i for i, login in enumerate(order or [])}
    original = {id(s): i for i, s in enumerate(streamers)}
    streamers.sort(key=lambda s: (rank.get(s.username, len(rank)), original[id(s)]))


def choose_watching(streamers: list, priority: list, pinned: list, now: float, max_watch: int = 2, scouted=None) -> list:
    """Indices to watch: online pinned slots first, the rest exactly like TCPM 2.0.7's priority loop."""
    online = [i for i, s in enumerate(streamers)
              if s.is_online and (s.online_at == 0 or now - s.online_at > 30)]
    chosen = []

    def add(indices):
        for i in indices:
            if len(chosen) >= max_watch:
                return
            if i not in chosen:
                chosen.append(i)

    for login in pinned or []:
        if login:
            add([i for i in online if streamers[i].username == login])

    for prior in priority:
        if len(chosen) >= max_watch:
            break
        if prior == Priority.ORDER:
            add(online)
        elif prior in (Priority.POINTS_ASCENDING, Priority.POINTS_DESCENDING):
            add(sorted(online, key=lambda i: streamers[i].channel_points, reverse=prior == Priority.POINTS_DESCENDING))
        elif prior == Priority.STREAK:
            add([i for i in online
                 if streamers[i].settings.watch_streak
                 and streamers[i].stream.watch_streak_missing
                 and (streamers[i].offline_at == 0 or (now - streamers[i].offline_at) // 60 > 30)
                 and streamers[i].stream.minute_watched < 7])
        elif prior == Priority.DROPS:
            scouted = scouted or set()
            add([i for i in online if streamers[i].username in scouted or streamers[i].drops_condition()])
        elif prior == Priority.SUBSCRIBED:
            with_multiplier = [i for i in online if streamers[i].viewer_has_points_multiplier()]
            add(sorted(with_multiplier, key=lambda i: streamers[i].total_points_multiplier(), reverse=True))
    return chosen[:max_watch]


def watch_reason(streamer, pinned, scouted) -> str:
    if streamer.username in (pinned or []):
        return "pinned"
    if streamer.username in (scouted or ()):
        return "drop hunt"
    if streamer.drops_condition():
        return "drops"
    if streamer.stream.watch_streak_missing and streamer.stream.minute_watched < 7:
        return "watch streak"
    return "order"


def follow_changes(followers, current, extra, scouted, blacklist):
    """Diff between Twitch's follow list and what the miner tracks. Extra and drop-scout channels are never removed."""
    followers = [f.lower() for f in followers or []]
    if not followers:
        return [], []
    follow_set, current_set = set(followers), set(current)
    added = [f for f in followers if f not in current_set and f not in blacklist]
    removed = [c for c in current if c not in follow_set and c not in extra and c not in scouted]
    return added, removed


def linked_from_inventory(inventory) -> dict:
    return {c["id"]: bool((c.get("self") or {}).get("isAccountConnected"))
            for c in ((inventory or {}).get("dropCampaignsInProgress") or []) if c.get("id")}


def community_catalogue(data, watch_games, linked) -> list:
    """Active campaigns from the community drops list (Twitch's own campaign objects, grouped by game)."""
    if not isinstance(data, list):
        return []
    out = []
    for group in data:
        game = {"id": group.get("gameId"), "displayName": group.get("gameDisplayName")}
        for c in group.get("rewards") or []:
            if c.get("status", "ACTIVE") not in ("ACTIVE", "UPCOMING"):
                continue
            allow = c.get("allow") or {}
            channels = [ch.get("name", "").lower() for ch in (allow.get("channels") or []) if ch and ch.get("name")] \
                if allow.get("isEnabled") else []
            rewards = []
            for drop in c.get("timeBasedDrops") or []:
                for edge in drop.get("benefitEdges") or []:
                    b = (edge or {}).get("benefit") or {}
                    rewards.append({"name": b.get("name") or drop.get("name"), "image": b.get("imageAssetURL"),
                                    "minutes": int(drop.get("requiredMinutesWatched") or 0)})
            out.append({
                "id": c.get("id"), "name": c.get("name"), "game": game["displayName"], "gameId": game["id"],
                "image": _box_art(group.get("gameBoxArtURL")) or c.get("imageURL"), "status": c.get("status", "ACTIVE"),
                "startAt": c.get("startAt"), "endAt": c.get("endAt"), "linked": (linked or {}).get(c.get("id")),
                "linkUrl": c.get("accountLinkURL"), "channels": channels, "rewards": rewards,
                "watched": game_watched(game, watch_games),
            })
    out.sort(key=lambda x: (not x["watched"], x["status"] != "ACTIVE", x["endAt"] or ""))
    return out


def scout_targets(watch_games, inventory, require_linked: bool) -> list:
    """Games to look for drop streams: watched games (by name, no catalogue needed) first, then running linked campaigns."""
    targets, seen = [], set()
    for name in watch_games or []:
        if name and name.strip() and name.strip().lower() not in seen:
            seen.add(name.strip().lower())
            targets.append({"displayName": name.strip(), "id": None, "watched": True})
    for c in (inventory or {}).get("dropCampaignsInProgress") or []:
        game = c.get("game") or {}
        name = (game.get("displayName") or game.get("name") or "").strip()
        unfinished = any(not ((d.get("self") or {}).get("isClaimed")) for d in c.get("timeBasedDrops") or [])
        linked = bool((c.get("self") or {}).get("isAccountConnected"))
        if name and unfinished and (linked or not require_linked) and name.lower() not in seen:
            seen.add(name.lower())
            targets.append({**game, "displayName": name, "watched": False})
    return targets
