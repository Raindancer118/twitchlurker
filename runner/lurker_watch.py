# SPDX-License-Identifier: GPL-3.0-or-later
# Derived from Twitch-Channel-Points-Miner-v2 (https://github.com/rdavydov/Twitch-Channel-Points-Miner-v2), GPL-3.0-or-later.
"""Replacement for Twitch.send_minute_watched_events (TCPM 2.0.7).

Identical sending logic, but the channel choice goes through lurker_core.choose_watching so the
dashboard can pin slots. Keep in sync with the pinned TCPM commit when upgrading.
"""
import copy
import logging
import time

import requests
import validators
from TwitchChannelPointsMiner.classes.Settings import Events
from TwitchChannelPointsMiner.classes.Twitch import Twitch
from TwitchChannelPointsMiner.constants import GQLOperations

import lurker_core as core

logger = logging.getLogger("TwitchChannelPointsMiner.classes.Twitch")

# Mutated by the runner's command loop; read on every iteration.
control = {"pinned": [None, None], "scouted": set()}


def _send_minute(twitch, streamer):
    json_data = copy.deepcopy(GQLOperations.PlaybackAccessToken)
    json_data["variables"] = {"login": streamer.username, "isLive": True, "isVod": False, "vodID": "",
                              "playerType": "site", "platform": "web"}
    try:
        token_response = twitch.post_gql_request(json_data)
    except Exception as e:
        logger.error(f"Error fetching PlaybackAccessToken for {streamer}: {e}")
        return
    if "data" not in token_response:
        logger.error(f"Invalid response from Twitch: {token_response}")
        return
    access = token_response["data"].get("streamPlaybackAccessToken", {}) or {}
    signature, value = access.get("signature"), access.get("value")
    if not signature or not value:
        logger.error(f"Missing signature or value in Twitch response: {token_response}")
        return

    headers = {"User-Agent": twitch.user_agent}
    qualities = requests.get(f"https://usher.ttvnw.net/api/channel/hls/{streamer.username}.m3u8?sig={signature}&token={value}",
                             headers=headers, timeout=20)
    if qualities.status_code != 200:
        return
    lowest_quality = qualities.text.split("\n")[-1]
    if not validators.url(lowest_quality):
        return
    segments = requests.get(lowest_quality, headers=headers, timeout=20)
    if segments.status_code != 200:
        return
    segment = segments.text.split("\n")[-2]
    if not validators.url(segment):
        return
    if requests.head(segment, headers=headers, timeout=20).status_code != 200:
        return

    response = requests.post(streamer.stream.spade_url, data=streamer.stream.encode_payload(), headers=headers, timeout=20)
    logger.debug(f"Send minute watched request for {streamer} - Status code: {response.status_code}")
    if response.status_code != 204:
        return
    streamer.stream.update_minute_watched()
    for campaign in streamer.stream.campaigns:
        for drop in campaign.drops:
            if drop.has_preconditions_met is not False and drop.is_printable is True:
                for line in (f"{streamer} is streaming {streamer.stream}", f"Campaign: {campaign}", f"Drop: {drop}",
                             f"{drop.progress_bar()}"):
                    logger.info(line, extra={"event": Events.DROP_STATUS, "skip_telegram": True, "skip_discord": True,
                                             "skip_webhook": True, "skip_matrix": True, "skip_gotify": True})


def send_minute_watched_events(self, streamers, priority, chunk_size=3):
    while self.running:
        try:
            now = time.time()
            for s in streamers:
                if s.is_online and (s.online_at == 0 or now - s.online_at > 30) and s.stream.update_elapsed() / 60 > 10:
                    self.check_streamer_online(s)

            watching = [streamers[i] for i in core.choose_watching(streamers, priority, control["pinned"], time.time(), scouted=control.get("scouted"))]
            for streamer in watching:
                next_iteration = time.time() + 20 / len(watching)
                try:
                    _send_minute(self, streamer)
                except requests.exceptions.ConnectionError as e:
                    logger.error(f"Error while trying to send minute watched: {e}")
                    self._Twitch__check_connection_handler(chunk_size)
                except requests.exceptions.Timeout as e:
                    logger.error(f"Error while trying to send minute watched: {e}")
                self._Twitch__chuncked_sleep(next_iteration - time.time(), chunk_size=chunk_size)

            if not watching:
                self._Twitch__chuncked_sleep(20, chunk_size=chunk_size)
        except Exception:
            logger.error("Exception raised in send minute watched", exc_info=True)


def install():
    Twitch.send_minute_watched_events = send_minute_watched_events
