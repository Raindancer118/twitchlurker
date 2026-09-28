package de.raindancer118.twitchlurker.raffle;

import java.util.Set;
import java.util.function.Consumer;

/** Twitch chat as seen by the raffle logic. */
public interface ChatConnection {

    void open(String login, String token, Consumer<IrcMessage> onMessage);

    void close();

    /** Channels we want to be in; the connection joins/parts and re-joins after reconnects. */
    void setChannels(Set<String> channels);

    Set<String> channels();

    boolean isConnected();

    /** Last fatal problem, e.g. rejected login. */
    String problem();

    void send(String channel, String text);
}
