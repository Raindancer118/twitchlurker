package de.raindancer118.twitchlurker.raffle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IrcMessageTest {

    @Test
    void parsesPrivmsgWithTags() {
        var msg = IrcMessage.parse("@badge-info=;badges=moderator/1,partner/1;display-name=StreamElements;mod=1;user-id=100135110 "
                + ":streamelements!streamelements@streamelements.tmi.twitch.tv PRIVMSG #papaplatte :A Raffle has begun! Type !join");
        assertThat(msg.command()).isEqualTo("PRIVMSG");
        assertThat(msg.channel()).isEqualTo("papaplatte");
        assertThat(msg.sender()).isEqualTo("streamelements");
        assertThat(msg.text()).isEqualTo("A Raffle has begun! Type !join");
        assertThat(msg.isModOrBroadcaster()).isTrue();
    }

    @Test
    void detectsBroadcasterBadge() {
        var msg = IrcMessage.parse("@badges=broadcaster/1;mod=0 :zarbex!zarbex@zarbex.tmi.twitch.tv PRIVMSG #zarbex :!gw läuft");
        assertThat(msg.isModOrBroadcaster()).isTrue();
    }

    @Test
    void parsesPingAndNumerics() {
        assertThat(IrcMessage.parse("PING :tmi.twitch.tv").command()).isEqualTo("PING");
        assertThat(IrcMessage.parse("PING :tmi.twitch.tv").text()).isEqualTo("tmi.twitch.tv");
        var notice = IrcMessage.parse(":tmi.twitch.tv NOTICE * :Login authentication failed");
        assertThat(notice.command()).isEqualTo("NOTICE");
        assertThat(notice.text()).isEqualTo("Login authentication failed");
    }

    @Test
    void unescapesTagValues() {
        var msg = IrcMessage.parse("@display-name=A\\sB;system-msg=x\\:y :a!a@a PRIVMSG #c :hi");
        assertThat(msg.tag("display-name")).isEqualTo("A B");
        assertThat(msg.tag("system-msg")).isEqualTo("x;y");
    }

    @Test
    void plainUserIsNotMod() {
        var msg = IrcMessage.parse("@badges=subscriber/12;mod=0 :viewer!viewer@viewer PRIVMSG #c :!join");
        assertThat(msg.isModOrBroadcaster()).isFalse();
    }
}
