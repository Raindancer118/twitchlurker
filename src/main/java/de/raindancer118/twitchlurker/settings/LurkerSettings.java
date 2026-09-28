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
        List<String> slots) {

    public static final Set<String> PRIORITIES = Set.of("STREAK", "DROPS", "ORDER", "SUBSCRIBED", "POINTS_ASCENDING", "POINTS_DESCENDING");
    static final Pattern LOGIN = Pattern.compile("[a-z0-9_]{3,25}");
    static final Pattern COMMAND = Pattern.compile("[a-z0-9_]{1,25}");

    public record DropScout(Boolean enabled, Integer channelsPerGame, Boolean requireLinked) {
        public DropScout {
            enabled = enabled == null || enabled;
            channelsPerGame = channelsPerGame == null ? 2 : channelsPerGame;
            requireLinked = requireLinked == null || requireLinked;
        }
    }

    public record Raffle(Boolean enabled, List<String> joinCommands, List<String> bots, List<String> disabledChannels,
                         Integer minDelaySeconds, Integer maxDelaySeconds, Integer cooldownSeconds) {
        public Raffle {
            enabled = enabled == null || enabled;
            joinCommands = normalize(joinCommands, List.of("join", "enter", "raffle", "giveaway", "gw"), true);
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
        dropScout = dropScout == null ? new DropScout(null, null, null) : dropScout;
        raffle = raffle == null ? new Raffle(null, null, null, null, null, null, null) : raffle;
        autostart = autostart == null || autostart;
        order = normalize(order, List.of(), false);
        slots = normalizeSlots(slots);
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
        return new LurkerSettings(null, null, null, null, null, null, null, null, null, null, null, null);
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
        checkLogins("Kanäle", streamers, errors);
        checkLogins("Blacklist", blacklist, errors);
        checkLogins("Bots", raffle.bots(), errors);
        checkLogins("Raffle-Ausnahmen", raffle.disabledChannels(), errors);
        checkLogins("Reihenfolge", order, errors);
        checkLogins("Slots", slots.stream().filter(java.util.Objects::nonNull).toList(), errors);
        priority.stream().filter(p -> !PRIORITIES.contains(p)).forEach(p -> errors.add("Unbekannte Priorität: " + p));
        raffle.joinCommands().stream().filter(c -> !COMMAND.matcher(c).matches())
                .forEach(c -> errors.add("Ungültiger Befehl: " + c));
        if (raffle.joinCommands().size() > 20) {
            errors.add("Höchstens 20 Join-Befehle");
        }
        if (raffle.minDelaySeconds() < 0 || raffle.maxDelaySeconds() > 300 || raffle.minDelaySeconds() > raffle.maxDelaySeconds()) {
            errors.add("Verzögerung muss zwischen 0 und 300 s liegen, Minimum ≤ Maximum");
        }
        if (raffle.cooldownSeconds() < 30 || raffle.cooldownSeconds() > 86_400) {
            errors.add("Cooldown muss zwischen 30 s und 24 h liegen");
        }
        if (dropScout.channelsPerGame() < 0 || dropScout.channelsPerGame() > 5) {
            errors.add("Drop-Kanäle pro Spiel: 0 bis 5");
        }
        return errors;
    }

    private static void checkLogins(String label, List<String> logins, List<String> errors) {
        if (logins.size() > 500) {
            errors.add(label + ": höchstens 500 Einträge");
        }
        logins.stream().filter(l -> !LOGIN.matcher(l).matches())
                .forEach(l -> errors.add(label + ": ungültiger Twitch-Name „" + l + "“"));
    }

    public LurkerSettings withStreamers(List<String> v) {
        return new LurkerSettings(followers, v, blacklist, priority, followRaid, claimMoments, watchStreak, dropScout, raffle, autostart, order, slots);
    }

    public LurkerSettings withBlacklist(List<String> v) {
        return new LurkerSettings(followers, streamers, v, priority, followRaid, claimMoments, watchStreak, dropScout, raffle, autostart, order, slots);
    }

    public LurkerSettings withRaffle(Raffle v) {
        return new LurkerSettings(followers, streamers, blacklist, priority, followRaid, claimMoments, watchStreak, dropScout, v, autostart, order, slots);
    }

    public LurkerSettings withAutostart(boolean v) {
        return new LurkerSettings(followers, streamers, blacklist, priority, followRaid, claimMoments, watchStreak, dropScout, raffle, v, order, slots);
    }

    public LurkerSettings withOrder(List<String> v) {
        return new LurkerSettings(followers, streamers, blacklist, priority, followRaid, claimMoments, watchStreak, dropScout, raffle, autostart, v, slots);
    }

    public LurkerSettings withSlots(List<String> v) {
        return new LurkerSettings(followers, streamers, blacklist, priority, followRaid, claimMoments, watchStreak, dropScout, raffle, autostart, order, v);
    }

    /** True if a change requires restarting the miner process (raffle/autostart changes don't). */
    public boolean minerRelevantDiff(LurkerSettings other) {
        return !followers.equals(other.followers) || !streamers.equals(other.streamers) || !blacklist.equals(other.blacklist)
                || !priority.equals(other.priority) || !followRaid.equals(other.followRaid)
                || !claimMoments.equals(other.claimMoments) || !watchStreak.equals(other.watchStreak)
                || !dropScout.equals(other.dropScout);
    }
}
