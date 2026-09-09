package com.discoveryhub.holds.client;

import com.discoveryhub.contracts.Message;
import com.discoveryhub.holds.config.HoldProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Scope resolution depends on this client enumerating a custodian's <i>entire</i> mailbox — an
 * early stop silently under-covers a hold, which is the unsafe direction (FR-4.2). The loop must
 * be driven by {@code totalPages}, and any response that does not have the shape it needs must
 * fail loudly rather than return a partial result.
 */
class ArchiveMessageClientTest {

    private ArchiveMessageClient clientFor(MockRestServiceServer server, RestClient.Builder builder) {
        HoldProperties props = new HoldProperties(null, null, Duration.ofSeconds(5), 2);
        return new ArchiveMessageClient(builder.build(), props);
    }

    private String messageJson(String id) {
        return "{\"messageId\":\"" + id + "\",\"custodianId\":\"c1\",\"sentAt\":\""
                + Instant.parse("2024-01-01T00:00:00Z") + "\"}";
    }

    @Test
    void enumeratesEveryPageEvenWhenAnIntermediatePageHasFewerRowsThanPageSize() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://archive.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ArchiveMessageClient client = clientFor(server, builder);

        server.expect(requestTo(org.hamcrest.Matchers.containsString("page=0")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("{\"content\":[" + messageJson("m1") + "],"
                        + "\"totalPages\":2,\"totalElements\":2,\"last\":false}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("page=1")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("{\"content\":[" + messageJson("m2") + "],"
                        + "\"totalPages\":2,\"totalElements\":2,\"last\":true}", MediaType.APPLICATION_JSON));

        List<Message> result = client.listByCustodian("c1");

        assertThat(result).extracting(Message::messageId).containsExactly("m1", "m2");
        server.verify();
    }

    @Test
    void anUnexpectedEmptyPageBeforeTheLastOneFailsRatherThanSilentlyStopping() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://archive.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ArchiveMessageClient client = clientFor(server, builder);

        server.expect(requestTo(org.hamcrest.Matchers.containsString("page=0")))
                .andRespond(withSuccess("{\"content\":[],\"totalPages\":2,\"totalElements\":2,\"last\":false}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.listByCustodian("c1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty page");
    }

    @Test
    void aNullResponseBodyFailsRatherThanReturningPartialResults() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://archive.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ArchiveMessageClient client = clientFor(server, builder);

        server.expect(requestTo(org.hamcrest.Matchers.containsString("page=0")))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.listByCustodian("c1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no body");
    }

    @Test
    void singlePageResultReturnsAllMessages() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://archive.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ArchiveMessageClient client = clientFor(server, builder);

        server.expect(requestTo(org.hamcrest.Matchers.containsString("page=0")))
                .andRespond(withSuccess("{\"content\":[" + messageJson("m1") + "],"
                        + "\"totalPages\":1,\"totalElements\":1,\"last\":true}", MediaType.APPLICATION_JSON));

        List<Message> result = client.listByCustodian("c1");

        assertThat(result).extracting(Message::messageId).containsExactly("m1");
        server.verify();
    }
}
