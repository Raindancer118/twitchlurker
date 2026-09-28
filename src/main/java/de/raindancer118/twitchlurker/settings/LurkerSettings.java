package de.raindancer118.twitchlurker.settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** User-editable bot configuration. Missing values in stored JSON fall back to defaults. */
public record LurkerSettings(
        Boolean followers,
        List<String> streamers,
        List<String> blacklist,
        List<String> priority,
        Boolean followRaid,
        Boolean claimMoments,
        Boolean watchStreak,
        DropScout dropScout,
        Raffle raffle,
        Boolean autostart,
        List<String> order,
        List<String> slots,
        Lurk lurk) {

    public static final Set<String> PRIORITIES = Set.of("STREAK", "DROPS", "ORDER", "SUBSCRIBED", "POINTS_ASCENDING", "POINTS_DESCENDING");
    static final Pattern LOGIN = Pattern.compile("[a-z0-9_]{3,25}");
    static final Pattern COMMAND = Pattern.compile("[a-z0-9_]{1,25}");

    public record DropScout(Boolean enabled, Integer channelsPerGame, Boolean requireLinked, List<String> games) {
        public DropScout {
            enabled = enabled == null || enabled;
            channelsPerGame = channelsPerGame == null ? 2 : channelsPerGame;
            requireLinked = requireLinked == null || requireLinked;
            games = normalizeGames(games);
        }

        /** Same scouting behaviour apart from the watchlist (which is applied live). */
        boolean sameBehaviour(DropScout o) {
            return enabled.equals(o.enabled) && channelsPerGame.equals(o.channelsPerGame) && requireLinked.equals(o.requireLinked);
        }
    }

    private static List<String> normalizeGames(List<String> values) {
        var out = new ArrayList<String>();
        var seen = new java.util.HashSet<String>();
        for (String v : values == null ? List.<String>of() : values) {
            if (v == null || v.isBlank()) {
                continue;
            }
            String g = v.strip().replaceAll("\\s+", " ");
            if (seen.add(g.toLowerCase(Locale.ROOT))) {
                out.add(g);
            }
        }
        return List.copyOf(out);
    }

    /** What to say when the bot starts lurking a channel; repeatMinutes 0 = once per stream. */
    public record Lurk(Boolean enabled, String message, Integer repeatMinutes) {
        public Lurk {
            enabled = enabled == null || enabled;
            message = message == null || message.isBlank() ? "!lurk" : message.strip();
            repeatMinutes = repeatMinutes == null ? 0 : repeatMinutes;
        }
    }

    static final List<String> DEFAULT_JOIN_COMMANDS = List.of("join", "enter", "gw");

    public record Raffle(Boolean enabled, List<String> joinCommands, List<String> bots, List<String> disabledChannels,
                         Integer minDelaySeconds, Integer maxDelaySeconds, Integer cooldownSeconds) {
        public Raffle {
            enabled = enabled == null || enabled;
            joinCommands = normalize(joinCommands, DEFAULT_JOIN_COMMANDS, true);
            // Up to 0.4.0 the default also contained !raffle and !giveaway, which are mostly mod commands.
            if (joinCommands.equals(List.of("join", "enter", "raffle", "giveaway", "gw"))) {
                joinCommands = DEFAULT_JOIN_COMMANDS;
            }
            bots = normalize(bots, List.of("streamelements", "nightbot", "moobot", "fossabot", "wizebot", "streamlabs", "botrixoficial", "sery_bot", "deepbot", "coebot"), false);
            disabledChannels = normalize(disabledChannels, List.of(), false);
            minDelaySeconds = minDelaySeconds == null ? 4 : minDelaySeconds;
            maxDelaySeconds = maxDelaySeconds == null ? 25 : maxDelaySeconds;
            cooldownSeconds = cooldownSeconds == null ? 180 : cooldownSeconds;
        }
    }

    public LurkerSettings {
        followers = followers == null || followers;
        streamers = normalize(streamers, List.of(), false);
        blacklist = normalize(blacklist, List.of(), false);
        priority = priority == null || priority.isEmpty()
                ? List.of("STREAK", "DROPS", "ORDER")
                : List.copyOf(new LinkedHashSet<>(priority.stream().map(p -> p.strip().toUpperCase(Locale.ROOT)).toList()));
        followRaid = followRaid == null || followRaid;
        claimMoments = claimMoments == null || claimMoments;
        watchStreak = watchStreak == null || watchStreak;
        dropScout = dropScout == null ? new DropScout(null, null, null, null) : dropScout;
        raffle = raffle == null ? new Raffle(null, null, null, null, null, null, null) : raffle;
        autostart = autostart == null || autostart;
        order = normalize(order, List.of(), false);
        slots = normalizeSlots(slots);
        lurk = lurk == null ? new Lurk(null, null, null) : lurk;
    }

    /** Exactly two entries; null means the slot is filled automatically. */
    private static List<String> normalizeSlots(List<String> values) {
        var out = new ArrayList<String>(2);
        for (int i = 0; i < 2; i++) {
            String v = values != null && i < values.size() ? values.get(i) : null;
            v = v == null ? null : v.strip().toLowerCase(Locale.ROOT);
            out.add(v == null || v.isEmpty() ? null : v);
        }
        return java.util.Collections.unmodifiableList(out);
    }

    public static LurkerSettings defaults() {
        return new LurkerSettings(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static List<String> normalize(List<String> values, List<String> fallback, boolean stripBang) {
        if (values == null) {
            return fallback;
        }
        var out = new LinkedHashSet<String>();
        for (String v : values) {
            if (v == null) {
                continue;
            }
            String n = v.strip().toLowerCase(Locale.ROOT);
            if (stripBang && n.startsWith("!")) {
                n = n.substring(1);
            }
            if (!n.isEmpty()) {
                out.add(n);
            }
        }
        return List.copyOf(out);
    }

    public List<String> validate() {
        var errors = new ArrayList<String>();
        checkLogins("Channels", streamers, errors);
        checkLogins("Blacklist", blacklist, errors);
        checkLogins("Bots", raffle.bots(), errors);
        checkLogins("Raffle exceptions", raffle.disabledChannels(), errors);
        checkLogins("Order", order, errors);
        checkLogins("Slots", slots.stream().filter(java.util.Objects::nonNull).toList(), errors);
        priority.stream().filter(p -> !PRIORITIES.contains(p)).forEach(p -> errors.add("Unknown priority: " + p));
        raffle.joinCommands().stream().filter(c -> !COMMAND.matcher(c).matches())
                .forEach(c -> errors.add("Invalid command: " + c));
        if (raffle.joinCommands().size() > 20) {
            errors.add("At most 20 join commands");
        }
        if (raffle.minDelaySeconds() < 0 || raffle.maxDelaySeconds() > 300 || raffle.minDelaySeconds() > raffle.maxDelaySeconds()) {
            errors.add("Delay must be between 0 and 300 s, minimum ≤ maximum");
        }
        if (raffle.cooldownSeconds() < 30 || raffle.cooldownSeconds() > 86_400) {
            errors.add("Cooldown must be between 30 s and 24 h");
        }
        if (lurk.message().length() > 100 || lurk.message().chars().anyMatch(Character::isISOControl)) {
            errors.add("Lurk message: at most 100 characters, one line");
        }
        if (lurk.repeatMinutes() != 0 && (lurk.repeatMinutes() < 30 || lurk.repeatMinutes() > 1440)) {
            errors.add("Repeat: 0 (once per stream) or 30 to 1440 minutes");
        }
        if (dropScout.games().size() > 40) {
            errors.add("At most 40 watched games");
        }
        dropScout.games().stream().filter(g -> g.length() > 80 || g.chars().anyMatch(Character::isISOControl))
                .forEach(g -> errors.add("Invalid game name: “" + (g.length() > 30 ? g.substring(0, 30) + "…" : g) + "”"));
        if (dropScout.channelsPerGame() < 0 || dropScout.channelsPerGame() > 5) {
            errors.add("Drop channels per game: 0 to 5");
        }
        return errors;
    }

    private static void checkLogins(String label, List<String> logins, List<String> errors) {
        if (logins.size() > 500) {
            errors.add(label + ": at most 500 entries");
        }
        logins.stream().filter(l -> !LOGIN.matcher(l).matches())
                .forEach(l -> errors.add(label + ": invalid Twitch name “" + l + "”"));
    }

    public LurkerSettings withStreamers(List<String> v) {
        return new LurkerSettings(followers, v, blacklist, priority, followRaid, claimMoments, watchStreak, dropScout, raffle, autostart, order, slots, lurk);
    }

    public LurkerSettings withBlacklist(List<String> v) {
        return new LurkerSettings(followers, streamers, v, priority, followRaid, claimMoments, watchStreak, dropScout, raffle, autostart, order, slots, lurk);
    }

    public LurkerSettings withRaffle(Raffle v) {
        return new LurkerSettings(followers, streamers, blacklist, priority, followRaid, claimMoments, watchStreak, dropScout, v, autostart, order, slots, lurk);
    }

    public LurkerSettings withAutostart(boolean v) {
        return new LurkerSettings(followers, streamers, blacklist, priority, followRaid, claimMoments, watchStreak, dropScout, raffle, v, order, slots, lurk);
    }

    public LurkerSettings withLurk(Lurk v) {
        return new LurkerSettings(followers, streamers, blacklist, priority, followRaid, claimMoments, watchStreak, dropScout, raffle, autostart, order, slots, v);
    }

    public LurkerSettings withWatchGames(List<String> v) {
        var d = new DropScout(dropScout.enabled(), dropScout.channelsPerGame(), dropScout.requireLinked(), v);
        return new LurkerSettings(followers, streamers, blacklist, priority, followRaid, claimMoments, watchStreak, d, raffle, autostart, order, slots, lurk);
    }

    public LurkerSettings withOrder(List<String> v) {
        return new LurkerSettings(followers, streamers, blacklist, priority, followRaid, claimMoments, watchStreak, dropScout, raffle, autostart, v, slots, lurk);
    }

    public LurkerSettings withSlots(List<String> v) {
        return new LurkerSettings(followers, streamers, blacklist, priority, followRaid, claimMoments, watchStreak, dropScout, raffle, autostart, order, v, lurk);
    }

    /** True if a change requires restarting the miner process (raffle/autostart changes don't). */
    public boolean minerRelevantDiff(LurkerSettings other) {
        return !followers.equals(other.followers) || !streamers.equals(other.streamers) || !blacklist.equals(other.blacklist)
                || !priority.equals(other.priority) || !followRaid.equals(other.followRaid)
                || !claimMoments.equals(other.claimMoments) || !watchStreak.equals(other.watchStreak)
                || !dropScout.sameBehaviour(other.dropScout);
    }
}
