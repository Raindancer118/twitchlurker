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
        if (text == null || !trustedSender(msg) || !RAFFLE_WORDS.matcher(text).find() || CLOSED_WORDS.matcher(text).find()) {
            return Optional.empty();
        }
        Matcher m = COMMAND.matcher(text);
        while (m.find()) {
            String cmd = m.group(1).toLowerCase(Locale.ROOT);
            if (joinCommands.contains(cmd)) {
                return Optional.of(new Trigger("!" + cmd));
            }
        }
        return Optional.empty();
    }

    public boolean isWinFor(IrcMessage msg) {
        String text = msg.text();
        return text != null && trustedSender(msg) && ownName.matcher(text).find() && WIN_WORDS.matcher(text).find();
    }
}
