package de.raindancer118.twitchlurker.raffle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal IRCv3 line parser for Twitch chat. */
public record IrcMessage(Map<String, String> tags, String prefix, String command, List<String> params, String text) {

    public static IrcMessage parse(String line) {
        String rest = line.strip();
        Map<String, String> tags = new HashMap<>();
        if (rest.startsWith("@")) {
            int sp = rest.indexOf(' ');
            for (String kv : rest.substring(1, sp).split(";")) {
                int eq = kv.indexOf('=');
                if (eq < 0) {
                    tags.put(kv, "");
                } else {
                    tags.put(kv.substring(0, eq), unescape(kv.substring(eq + 1)));
                }
            }
            rest = rest.substring(sp + 1);
        }
        String prefix = null;
        if (rest.startsWith(":")) {
            int sp = rest.indexOf(' ');
            prefix = rest.substring(1, sp);
            rest = rest.substring(sp + 1);
        }
        String text = null;
        int trailing = rest.indexOf(" :");
        if (trailing >= 0) {
            text = rest.substring(trailing + 2);
            rest = rest.substring(0, trailing);
        }
        List<String> parts = new ArrayList<>(List.of(rest.split(" ")));
        String command = parts.removeFirst();
        return new IrcMessage(tags, prefix, command, List.copyOf(parts), text);
    }

    private static String unescape(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                sb.append(switch (n) {
                    case 's' -> ' ';
                    case ':' -> ';';
                    case 'r' -> '\r';
                    case 'n' -> '\n';
                    default -> n;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public String tag(String name) {
        return tags.get(name);
    }

    public String sender() {
        if (prefix == null) {
            return null;
        }
        int bang = prefix.indexOf('!');
        return (bang < 0 ? prefix : prefix.substring(0, bang)).toLowerCase();
    }

    public String channel() {
        return params.stream().filter(p -> p.startsWith("#")).findFirst().map(p -> p.substring(1)).orElse(null);
    }

    public boolean isModOrBroadcaster() {
        if ("1".equals(tags.get("mod"))) {
            return true;
        }
        String badges = tags.getOrDefault("badges", "");
        return badges.contains("broadcaster/") || badges.contains("moderator/");
    }
}
