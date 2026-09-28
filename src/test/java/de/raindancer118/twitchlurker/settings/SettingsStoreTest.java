package de.raindancer118.twitchlurker.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class SettingsStoreTest {

    @TempDir
    Path dir;

    private SettingsStore store() {
        return new SettingsStore(dir, JsonMapper.builder().build());
    }

    @Test
    void defaultsWhenNoFile() {
        var s = store().get();
        assertThat(s.followers()).isTrue();
        assertThat(s.priority()).containsExactly("STREAK", "DROPS", "ORDER");
        assertThat(s.raffle().enabled()).isTrue();
        assertThat(s.raffle().joinCommands()).contains("join", "enter");
        assertThat(s.raffle().bots()).contains("streamelements", "nightbot");
        assertThat(s.dropScout().enabled()).isTrue();
        assertThat(s.autostart()).isTrue();
    }

    @Test
    void savesNormalizedAndReloads() {
        var st = store();
        var s = st.get();
        var changed = s.withStreamers(List.of(" Papaplatte ", "zarbex", "zarbex")).withBlacklist(List.of("TRYMACS"));
        st.save(changed);
        var reloaded = store().get();
        assertThat(reloaded.streamers()).containsExactly("papaplatte", "zarbex");
        assertThat(reloaded.blacklist()).containsExactly("trymacs");
        assertThat(Files.exists(dir.resolve("settings.json"))).isTrue();
    }

    @Test
    void rejectsInvalidLogins() {
        var st = store();
        assertThatThrownBy(() -> st.save(st.get().withStreamers(List.of("ok_name", "bad name; rm -rf"))))
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("bad name");
    }

    @Test
    void rejectsUnknownPriorityAndBadDelays() {
        var st = store();
        var s = st.get();
        assertThatThrownBy(() -> st.save(new LurkerSettings(s.followers(), s.streamers(), s.blacklist(), List.of("STREAK", "MAGIC"),
                s.followRaid(), s.claimMoments(), s.watchStreak(), s.dropScout(), s.raffle(), s.autostart())))
                .isInstanceOf(InvalidSettingsException.class).hasMessageContaining("MAGIC");
        var badRaffle = new LurkerSettings.Raffle(true, s.raffle().joinCommands(), s.raffle().bots(), List.of(), 30, 5, 180);
        assertThatThrownBy(() -> st.save(s.withRaffle(badRaffle)))
                .isInstanceOf(InvalidSettingsException.class).hasMessageContaining("Verzögerung");
    }

    @Test
    void toleratesUnknownFieldsInStoredFile() throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"followers\":false,\"somethingOld\":1}");
        var s = store().get();
        assertThat(s.followers()).isFalse();
        assertThat(s.priority()).containsExactly("STREAK", "DROPS", "ORDER");
    }
}
