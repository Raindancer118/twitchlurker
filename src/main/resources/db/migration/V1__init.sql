CREATE TABLE events (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    ts      INTEGER NOT NULL,
    type    TEXT    NOT NULL,
    login   TEXT,
    amount  INTEGER,
    detail  TEXT
);
CREATE INDEX events_ts ON events (ts);
CREATE INDEX events_login_ts ON events (login, ts);

CREATE TABLE points_snapshots (
    ts      INTEGER NOT NULL,
    login   TEXT    NOT NULL,
    points  INTEGER NOT NULL,
    PRIMARY KEY (login, ts)
);

CREATE TABLE raffles (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    ts              INTEGER NOT NULL,
    channel         TEXT    NOT NULL,
    trigger_user    TEXT    NOT NULL,
    trigger_message TEXT    NOT NULL,
    command         TEXT    NOT NULL,
    status          TEXT    NOT NULL,
    detail          TEXT,
    sent_at         INTEGER
);
CREATE INDEX raffles_ts ON raffles (ts);
CREATE INDEX raffles_channel_ts ON raffles (channel, ts);
