package de.raindancer118.twitchlurker.raffle;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Twitch chat over the official IRC WebSocket endpoint, with reconnects and conservative rate limits. */
public class TwitchChatClient implements ChatConnection {

    public interface Transport {
        void sendText(String line);

        void close();
    }

    @FunctionalInterface
    public interface Connector {
        CompletableFuture<Transport> connect(Consumer<String> onText, Consumer<Throwable> onClosed);
    }

    private static final Logger log = LoggerFactory.getLogger(TwitchChatClient.class);
    // Twitch allows 20 joins / 10 s and 20 messages / 30 s for regular accounts; stay well below both.
    private static final Duration JOIN_SPACING = Duration.ofMillis(700);
    private static final Duration SEND_SPACING = Duration.ofMillis(1600);

    private final Connector connector;
    private final RaffleService.Delayer timer;

    private String login;
    private String token;
    private Consumer<IrcMessage> handler;
    private Transport transport;
    private boolean connected;
    private boolean closed = true;
    private String problem;
    private int attempt;
    private long generation;
    private Set<String> desired = Set.of();
    private final Set<String> joined = new HashSet<>();
    private final Set<String> joinRequested = new HashSet<>();
    private final ArrayDeque<String> outgoing = new ArrayDeque<>();
    private boolean joinPumpScheduled;
    private boolean sendPumpScheduled;
    private final StringBuilder partial = new StringBuilder();

    public TwitchChatClient(Connector connector, RaffleService.Delayer timer) {
        this.connector = connector;
        this.timer = timer;
    }

    public static Connector webSocketConnector(HttpClient http) {
        return (onText, onClosed) -> http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create("wss://irc-ws.chat.twitch.tv:443"), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            onText.accept(buf.toString());
                            buf.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        onClosed.accept(null);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        onClosed.accept(error);
                    }
                })
                .thenApply(ws -> new Transport() {
                    @Override
                    public synchronized void sendText(String line) {
                        ws.sendText(line + "\r\n", true).join();
                    }

                    @Override
                    public void close() {
                        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
                    }
                });
    }

    @Override
    public synchronized void open(String login, String token, Consumer<IrcMessage> onMessage) {
        this.login = login.toLowerCase(Locale.ROOT);
        this.token = token;
        this.handler = onMessage;
        this.closed = false;
        this.problem = null;
        this.attempt = 0;
        connect();
    }

    private synchronized void connect() {
        if (closed) {
            return;
        }
        long gen = ++generation;
        connector.connect(text -> onText(gen, text), error -> onClosed(gen, error))
                .whenComplete((t, error) -> {
                    if (error != null) {
                        onClosed(gen, error);
                        return;
                    }
                    synchronized (this) {
                        if (gen != generation || closed) {
                            t.close();
                            return;
                        }
                        transport = t;
                        t.sendText("CAP REQ :twitch.tv/tags twitch.tv/commands");
                        t.sendText("PASS oauth:" + token);
                        t.sendText("NICK " + login);
                    }
                });
    }

    private void onText(long gen, String chunk) {
        String[] lines;
        synchronized (this) {
            if (gen != generation) {
                return;
            }
            partial.append(chunk);
            int end = partial.lastIndexOf("\r\n");
            if (end < 0) {
                return;
            }
            lines = partial.substring(0, end).split("\r\n");
            partial.delete(0, end + 2);
        }
        for (String line : lines) {
            if (!line.isBlank()) {
                handleLine(line);
            }
        }
    }

    private void handleLine(String line) {
        IrcMessage msg;
        try {
            msg = IrcMessage.parse(line);
        } catch (RuntimeException e) {
            log.debug("Unparseable IRC line: {}", line);
            return;
        }
        Consumer<IrcMessage> forward = null;
        synchronized (this) {
            switch (msg.command()) {
                case "PING" -> raw("PONG :" + msg.text());
                case "001" -> {
                    connected = true;
                    attempt = 0;
                    joined.clear();
                    joinRequested.clear();
                    scheduleJoinPump();
                }
                case "RECONNECT" -> {
                    if (transport != null) {
                        transport.close();
                    }
                    onClosedLocked();
                }
                case "JOIN" -> {
                    if (login.equals(msg.sender())) {
                        joined.add(msg.channel());
                    }
                }
                case "PART" -> {
                    if (login.equals(msg.sender())) {
                        joined.remove(msg.channel());
                    }
                }
                case "NOTICE" -> {
                    String text = msg.text() == null ? "" : msg.text();
                    if (text.contains("Login authentication failed") || text.contains("Improperly formatted auth")) {
                        problem = "Twitch hat den Chat-Login abgelehnt. Bitte Twitch neu verbinden.";
                        closed = true;
                        connected = false;
                        if (transport != null) {
                            transport.close();
                        }
                    } else {
                        forward = handler;
                    }
                }
                default -> forward = handler;
            }
        }
        if (forward != null) {
            forward.accept(msg);
        }
    }

    private void onClosed(long gen, Throwable error) {
        synchronized (this) {
            if (gen != generation) {
                return;
            }
            if (error != null) {
                log.info("Twitch chat connection lost: {}", error.toString());
            }
            onClosedLocked();
        }
    }

    private void onClosedLocked() {
        connected = false;
        transport = null;
        joined.clear();
        joinRequested.clear();
        partial.setLength(0);
        generation++;
        if (closed) {
            return;
        }
        long seconds = Math.min(60, 1L << Math.min(attempt, 6));
        attempt++;
        timer.schedule(this::connect, Duration.ofSeconds(seconds));
    }

    @Override
    public synchronized void close() {
        closed = true;
        connected = false;
        desired = Set.of();
        outgoing.clear();
        generation++;
        if (transport != null) {
            transport.close();
            transport = null;
        }
    }

    @Override
    public synchronized void setChannels(Set<String> channels) {
        desired = Set.copyOf(channels);
        if (connected) {
            scheduleJoinPump();
        }
    }

    @Override
    public synchronized Set<String> channels() {
        return desired;
    }

    @Override
    public synchronized boolean isConnected() {
        return connected;
    }

    @Override
    public synchronized String problem() {
        return problem;
    }

    @Override
    public synchronized void send(String channel, String text) {
        outgoing.add("PRIVMSG #" + channel + " :" + text.replace("\r", " ").replace("\n", " "));
        if (!sendPumpScheduled) {
            sendPumpScheduled = true;
            timer.schedule(this::sendPump, Duration.ZERO);
        }
    }

    private void sendPump() {
        synchronized (this) {
            sendPumpScheduled = false;
            if (!connected || outgoing.isEmpty()) {
                return;
            }
            raw(outgoing.poll());
            if (!outgoing.isEmpty()) {
                sendPumpScheduled = true;
                timer.schedule(this::sendPump, SEND_SPACING);
            }
        }
    }

    private void scheduleJoinPump() {
        if (!joinPumpScheduled) {
            joinPumpScheduled = true;
            timer.schedule(this::joinPump, Duration.ZERO);
        }
    }

    private void joinPump() {
        synchronized (this) {
            joinPumpScheduled = false;
            if (!connected) {
                return;
            }
            for (String ch : Set.copyOf(joined)) {
                if (!desired.contains(ch)) {
                    raw("PART #" + ch);
                    joined.remove(ch);
                }
            }
            joinRequested.retainAll(desired);
            var next = desired.stream().filter(ch -> !joined.contains(ch) && !joinRequested.contains(ch)).sorted().findFirst();
            if (next.isPresent()) {
                raw("JOIN #" + next.get());
                joinRequested.add(next.get());
                joinPumpScheduled = true;
                timer.schedule(this::joinPump, JOIN_SPACING);
            }
        }
    }

    private void raw(String line) {
        if (transport != null) {
            transport.sendText(line);
        }
    }
}
