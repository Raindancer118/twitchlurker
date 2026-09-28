package de.raindancer118.twitchlurker.raffle;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

class FakeChat implements ChatConnection {

    String login;
    String token;
    Consumer<IrcMessage> handler;
    Set<String> channels = Set.of();
    boolean connected;
    final List<String> sent = new ArrayList<>();

    @Override
    public void open(String login, String token, Consumer<IrcMessage> onMessage) {
        this.login = login;
        this.token = token;
        this.handler = onMessage;
        this.connected = true;
    }

    @Override
    public void close() {
        connected = false;
        channels = Set.of();
    }

    @Override
    public void setChannels(Set<String> channels) {
        this.channels = Set.copyOf(channels);
    }

    @Override
    public Set<String> channels() {
        return channels;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String problem() {
        return null;
    }

    @Override
    public void send(String channel, String text) {
        sent.add("#" + channel + " " + text);
    }

    void receive(String line) {
        handler.accept(IrcMessage.parse(line));
    }
}
