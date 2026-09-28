package de.raindancer118.twitchlurker.raffle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RaffleDetectorTest {

    private final RaffleDetector detector = new RaffleDetector("tomlurkt",
            Set.of("streamelements", "nightbot", "moobot", "fossabot"),
            List.of("join", "enter", "raffle", "giveaway", "gw"));

    private IrcMessage msg(String sender, String badges, String text) {
        return IrcMessage.parse("@badges=" + badges + " :" + sender + "!" + sender + "@x PRIVMSG #papaplatte :" + text);
    }

    @Test
    void streamElementsRaffle() {
        var t = detector.detectRaffle(msg("streamelements", "", "A Raffle has begun for 500 Points it will end in 60 Seconds. Enter by typing !join"));
        assertThat(t).hasValueSatisfying(r -> assertThat(r.command()).isEqualTo("!join"));
    }

    @Test
    void streamElementsMultiRaffle() {
        var t = detector.detectRaffle(msg("streamelements", "moderator/1",
                "A multi-raffle has begun, 1000 points will be split among the winners. type !join to join the raffle!"));
        assertThat(t).map(RaffleDetector.Trigger::command).hasValue("!join");
    }

    @Test
    void germanModGiveaway() {
        var t = detector.detectRaffle(msg("somemod", "moderator/1", "Verlosung gestartet! Schreibt !gw in den Chat, um mitzumachen"));
        assertThat(t).map(RaffleDetector.Trigger::command).hasValue("!gw");
    }

    @Test
    void moobotGiveaway() {
        assertThat(detector.detectRaffle(msg("moobot", "", "Giveaway started! Type !enter to join.")))
                .map(RaffleDetector.Trigger::command).hasValue("!enter");
    }

    @Test
    void ignoresRegularViewers() {
        assertThat(detector.detectRaffle(msg("randomviewer", "subscriber/3", "giveaway? type !join lol"))).isEmpty();
    }

    @Test
    void ignoresBotMessagesWithoutRaffleWording() {
        assertThat(detector.detectRaffle(msg("nightbot", "moderator/1", "Folgt auf Insta! Type !join für nix"))).isEmpty();
        assertThat(detector.detectRaffle(msg("streamelements", "", "The raffle has ended, thanks!"))).isEmpty();
    }

    @Test
    void ignoresPaidTicketCommands() {
        assertThat(detector.detectRaffle(msg("streamelements", "", "A raffle has begun! Buy tickets with !ticket <amount>"))).isEmpty();
    }

    @Test
    void ignoresOwnMessages() {
        assertThat(detector.detectRaffle(msg("tomlurkt", "moderator/1", "giveaway !join"))).isEmpty();
    }

    @Test
    void modCommandInvocationsAreNotAnnouncements() {
        // Real case (28.09.2026): a mod typed "!raffle" to start a raffle; the bot answered "!raffle", which only mods may use.
        assertThat(detector.detectRaffle(msg("kangatato", "moderator/1", "!raffle"))).isEmpty();
        assertThat(detector.detectRaffle(msg("kangatato", "moderator/1", "!raffle 500 60"))).isEmpty();
        assertThat(detector.detectRaffle(msg("papaplatte", "broadcaster/1", "!giveaway start Skins"))).isEmpty();
    }

    @Test
    void prefersTheCommandViewersAreToldToType() {
        var mentionsBoth = msg("streamelements", "", "Raffle gestartet (Mods nutzen !raffle stop). Type !join to enter!");
        assertThat(detector.detectRaffle(mentionsBoth)).map(RaffleDetector.Trigger::command).hasValue("!join");
        var quoted = msg("streamelements", "", "A Raffle has begun for 1000 Points it will end in 120 Seconds. Enter by typing \"!join\"");
        assertThat(detector.detectRaffle(quoted)).map(RaffleDetector.Trigger::command).hasValue("!join");
        var german = msg("nightbot", "moderator/1", "Gewinnspiel läuft! Mit !gw seid ihr dabei");
        assertThat(detector.detectRaffle(german)).map(RaffleDetector.Trigger::command).hasValue("!gw");
    }

    @Test
    void detectsWin() {
        assertThat(detector.isWinFor(msg("streamelements", "", "The raffle has ended and tomlurkt won 500 points"))).isTrue();
        assertThat(detector.isWinFor(msg("streamelements", "", "Gewinner der Verlosung: @TomLurkt, Glückwunsch!"))).isTrue();
        assertThat(detector.isWinFor(msg("streamelements", "", "tomlurkt has 1200 points"))).isFalse();
        assertThat(detector.isWinFor(msg("viewer", "", "tomlurkt won lol"))).isFalse();
        assertThat(detector.isWinFor(msg("streamelements", "", "tomlurktfan won 500 points"))).isFalse();
    }
}
