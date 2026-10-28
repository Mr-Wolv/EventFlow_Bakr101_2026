package com.eventflow.fulfillment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eventflow.admin.enabled=false")
class AdminControllerDisabledByDefaultTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    private URI uri(String path, Object... uriVariables) {
        return UriComponentsBuilder.fromHttpUrl("http://localhost:" + port)
                .path(path)
                .buildAndExpand(uriVariables)
                .toUri();
    }

    @Test
    void adminStatsReturns404WhenDisabled() {
        ResponseEntity<Map> response = rest.getForEntity(uri("/__admin/stats"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void adminFailureEndpointsReturn404WhenDisabled() {
        ResponseEntity<Map> get = rest.getForEntity(uri("/__admin/failure"), Map.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> post = rest.postForEntity(
                uri("/__admin/failure"),
                Map.of("fault", "NONE"),
                Map.class);
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> clear = rest.postForEntity(
                uri("/__admin/failure/clear"),
                Map.of(),
                Map.class);
        assertThat(clear.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void adminOrderStatusReturns404WhenDisabled() {
        ResponseEntity<Map> response = rest.getForEntity(
                uri("/__admin/orders/{id}", UUID.randomUUID()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
