package de.raindancer118.twitchlurker.raffle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TwitchChatClientTest {

    final List<String> sent = new ArrayList<>();
    final List<IrcMessage> received = new ArrayList<>();
    final List<Runnable> timers = new ArrayList<>();
    int connects;
    Consumer<String> onText;
    Consumer<Throwable> onClosed;
    boolean transportClosed;
    TwitchChatClient client;

    @BeforeEach
    void setUp() {
        TwitchChatClient.Connector connector = (text, closed) -> {
            connects++;
            onText = text;
            onClosed = closed;
            transportClosed = false;
            return CompletableFuture.completedFuture(new TwitchChatClient.Transport() {
                @Override
                public void sendText(String line) {
                    sent.add(line);
                }

                @Override
                public void close() {
                    transportClosed = true;
                }
            });
        };
        client = new TwitchChatClient(connector, (task, delay) -> timers.add(task));
    }

    private void drainTimers() {
        var copy = new ArrayList<>(timers);
        timers.clear();
        copy.forEach(Runnable::run);
    }

    @Test
    void logsInJoinsAfterWelcomeAndAnswersPing() {
        client.open("tomlurkt", "tok", received::add);
        assertThat(sent).containsExactly("CAP REQ :twitch.tv/tags twitch.tv/commands", "PASS oauth:tok", "NICK tomlurkt");
        client.setChannels(Set.of("papaplatte"));
        assertThat(sent).hasSize(3);

        onText.accept(":tmi.twitch.tv 001 tomlurkt :Welcome, GLHF!\r\n:tmi.twitch.tv 376 tomlurkt :>\r\n");
        assertThat(client.isConnected()).isTrue();
        drainTimers();
        assertThat(sent).contains("JOIN #papaplatte");

        onText.accept("PING :tmi.twitch.tv\r\n");
        assertThat(sent).contains("PONG :tmi.twitch.tv");

        onText.accept(":a!a@a PRIVMSG #papa");
        onText.accept("platte :hallo\r\n");
        assertThat(received).extracting(IrcMessage::text).contains("hallo");
    }

    @Test
    void partsRemovedChannelsAndThrottlesMessages() {
        client.open("tomlurkt", "tok", received::add);
        onText.accept(":tmi.twitch.tv 001 tomlurkt :Welcome\r\n");
        client.setChannels(Set.of("a1", "b2"));
        drainTimers();
        drainTimers();
        onText.accept(":tomlurkt!tomlurkt@x JOIN #a1\r\n:tomlurkt!tomlurkt@x JOIN #b2\r\n");
        client.setChannels(Set.of("a1"));
        drainTimers();
        assertThat(sent).contains("PART #b2");

        client.send("a1", "!join");
        drainTimers();
        assertThat(sent).contains("PRIVMSG #a1 :!join");
    }

    @Test
    void reconnectsAfterDropAndRejoins() {
        client.open("tomlurkt", "tok", received::add);
        onText.accept(":tmi.twitch.tv 001 tomlurkt :Welcome\r\n");
        client.setChannels(Set.of("a1"));
        drainTimers();
        onClosed.accept(new RuntimeException("reset"));
        assertThat(client.isConnected()).isFalse();
        drainTimers();
        assertThat(connects).isEqualTo(2);
        sent.clear();
        onText.accept(":tmi.twitch.tv 001 tomlurkt :Welcome\r\n");
        drainTimers();
        assertThat(sent).contains("JOIN #a1");
    }

    @Test
    void stopsOnAuthFailure() {
        client.open("tomlurkt", "bad", received::add);
        onText.accept(":tmi.twitch.tv NOTICE * :Login authentication failed\r\n");
        assertThat(client.problem()).contains("rejected");
        assertThat(transportClosed).isTrue();
        onClosed.accept(null);
        drainTimers();
        assertThat(connects).isEqualTo(1);
    }

    @Test
    void closeIsFinal() {
        client.open("tomlurkt", "tok", received::add);
        client.close();
        assertThat(transportClosed).isTrue();
        onClosed.accept(null);
        drainTimers();
        assertThat(connects).isEqualTo(1);
        assertThat(client.channels()).isEmpty();
    }
}
