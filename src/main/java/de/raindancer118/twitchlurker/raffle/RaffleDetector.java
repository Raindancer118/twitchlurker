package de.raindancer118.twitchlurker.raffle;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RaffleDetector {

    public record Trigger(String command) {}

    private static final Pattern RAFFLE_WORDS = Pattern.compile(
            "\\b(multi-raffle|raffle|giveaway|give-away|verlosung|gewinnspiel|lottery)\\b|(^|\\s)!gw\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CLOSED_WORDS = Pattern.compile(
            "\\b(ended|is over|closed|beendet|vorbei|won|gewonnen|winners? (is|are)|gewinner (ist|sind))\\b|gewinner:",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern COMMAND = Pattern.compile("(?<![\\w!])!([a-z0-9_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRONG_HINT = Pattern.compile(
            "(type|typing|tipp\\w*|schreib\\w*|write|enter by|join by|zum mitmachen|to join|to enter)\\W{0,4}$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern WEAK_HINT = Pattern.compile("\\b(mit|with|use|nutz\\w*|per)\\W{0,4}$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MOD_CONTEXT = Pattern.compile("\\bmod(s|erator\\w*)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WIN_WORDS = Pattern.compile(
            "\\b(won|wins|winner|gewonnen|gewinner|gewinnt|congrats?|congratulations|glückwunsch|gratulation)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final String ownLogin;
    private final Set<String> bots;
    private final List<String> joinCommands;
    private final Pattern ownName;

    public RaffleDetector(String ownLogin, Collection<String> bots, Collection<String> joinCommands) {
        this.ownLogin = ownLogin.toLowerCase(Locale.ROOT);
        this.bots = bots.stream().map(b -> b.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.joinCommands = joinCommands.stream().map(c -> c.replaceFirst("^!", "").toLowerCase(Locale.ROOT)).toList();
        this.ownName = Pattern.compile("(?<![\\w])@?" + Pattern.quote(this.ownLogin) + "(?![\\w])", Pattern.CASE_INSENSITIVE);
    }

    private boolean trustedSender(IrcMessage msg) {
        String sender = msg.sender();
        return sender != null && !sender.equals(ownLogin) && (bots.contains(sender) || msg.isModOrBroadcaster());
    }

    public Optional<Trigger> detectRaffle(IrcMessage msg) {
        String text = msg.text();
        // A message that *is* a command ("!raffle 500") is a mod starting something, not an invitation to viewers.
        if (text == null || text.strip().startsWith("!") || !trustedSender(msg)
                || !RAFFLE_WORDS.matcher(text).find() || CLOSED_WORDS.matcher(text).find()) {
            return Optional.empty();
        }
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        Matcher m = COMMAND.matcher(text);
        while (m.find()) {
            String cmd = m.group(1).toLowerCase(Locale.ROOT);
            if (!joinCommands.contains(cmd)) {
                continue;
            }
            String before = text.substring(Math.max(0, m.start() - 25), m.start());
            int score = STRONG_HINT.matcher(before).find() ? 2 : WEAK_HINT.matcher(before).find() ? 1 : 0;
            if (MOD_CONTEXT.matcher(before).find()) {
                score -= 3;
            }
            if (score > bestScore) {
                bestScore = score;
                best = cmd;
            }
        }
        return best == null || bestScore < 0 ? Optional.empty() : Optional.of(new Trigger("!" + best));
    }

    public boolean isWinFor(IrcMessage msg) {
        String text = msg.text();
        return text != null && trustedSender(msg) && ownName.matcher(text).find() && WIN_WORDS.matcher(text).find();
    }
}
