package de.raindancer118.twitchlurker.twitch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RewardCodeServiceTest {

    MockRestServiceServer server;
    RewardCodeService service;
    Optional<TwitchToken> token = Optional.of(new TwitchToken("tok", "tomlurkt", "4711", List.of(), Instant.EPOCH));

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new RewardCodeService(builder, "https://gql.test/gql", () -> token);
    }

    @Test
    void readsTheCodeTwitchShowsInTheDropsInventory() {
        server.expect(requestTo("https://gql.test/gql")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "OAuth tok"))
                .andExpect(header("Client-Id", TwitchAuthService.CLIENT_ID))
                .andExpect(content().string(Matchers.containsString("\"operationName\":\"RewardCodeModal\"")))
                .andExpect(content().string(Matchers.containsString("\"rewardCampaignID\":\"camp-1\"")))
                .andExpect(content().string(Matchers.containsString("\"rewardID\":\"rew-1\"")))
                .andRespond(withSuccess("""
                        {"data":{"currentUser":{"inventory":{"rewardValue":{"value":"K9T6-ABCD","rewardID":"rew-1",
                        "rewardCampaignID":"camp-1","expiresAt":"2026-10-22T06:58:59.999Z"}}}}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(service.code("camp-1", "rew-1"))
                .contains(new RewardCodeService.RewardCode("K9T6-ABCD", Instant.parse("2026-10-22T06:58:59.999Z")));
    }

    @Test
    void rewardsWithoutCodeOrTwitchTroubleGiveNothing() {
        // Badges and unclaimed rewards: Twitch answers "server error" on the field.
        server.expect(requestTo("https://gql.test/gql")).andRespond(withSuccess("""
                {"errors":[{"message":"server error"}],"data":{"currentUser":{"inventory":{"rewardValue":null}}}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gql.test/gql")).andRespond(withServerError());
        assertThat(service.code("camp-1", "rew-1")).isEmpty();
        assertThat(service.code("camp-1", "rew-1")).isEmpty();
    }

    @Test
    void withoutTwitchLoginThereIsNoCall() {
        token = Optional.empty();
        assertThat(service.code("camp-1", "rew-1")).isEmpty();
        server.verify();
    }
}
