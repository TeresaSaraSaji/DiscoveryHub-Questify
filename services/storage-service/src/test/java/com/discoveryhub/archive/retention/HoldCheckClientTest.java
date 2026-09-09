package com.discoveryhub.archive.retention;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The disposition guard: never answer "not held" except on an explicit {@code held: false}. A
 * network failure, a non-2xx status, and — the bug this pins — a 2xx response with an empty or
 * unparseable body must all fail closed (treated as held), because any of them defaulting to
 * "not held" would let a held message be deleted.
 */
class HoldCheckClientTest {

    private HoldCheckClient clientBackedBy(MockRestServiceServer[] serverOut) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://holds.test");
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        return new HoldCheckClient(builder.build());
    }

    @Test
    void explicitHeldTrueIsHeld() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        HoldCheckClient client = clientBackedBy(serverOut);
        serverOut[0].expect(requestTo(org.hamcrest.Matchers.containsString("/holds/check")))
                .andRespond(withSuccess("{\"held\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.isHeld("m-1")).isTrue();
    }

    @Test
    void explicitHeldFalseIsNotHeld() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        HoldCheckClient client = clientBackedBy(serverOut);
        serverOut[0].expect(requestTo(org.hamcrest.Matchers.containsString("/holds/check")))
                .andRespond(withSuccess("{\"held\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.isHeld("m-1")).isFalse();
    }

    @Test
    void anEmptyTwoHundredBodyFailsClosedAsHeld() {
        // This is the bug: retrieve().body(Class) returns null for an empty 2xx body, and the old
        // "response != null && response.held()" read that as false (not held) rather than as "no
        // answer, so assume the worst".
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        HoldCheckClient client = clientBackedBy(serverOut);
        serverOut[0].expect(requestTo(org.hamcrest.Matchers.containsString("/holds/check")))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThat(client.isHeld("m-1")).isTrue();
    }

    @Test
    void aServerErrorFailsClosedAsHeld() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        HoldCheckClient client = clientBackedBy(serverOut);
        serverOut[0].expect(requestTo(org.hamcrest.Matchers.containsString("/holds/check")))
                .andRespond(withServerError());

        assertThat(client.isHeld("m-1")).isTrue();
    }

    @Test
    void aNotFoundStatusFailsClosedAsHeld() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        HoldCheckClient client = clientBackedBy(serverOut);
        serverOut[0].expect(requestTo(org.hamcrest.Matchers.containsString("/holds/check")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.isHeld("m-1")).isTrue();
    }
}
