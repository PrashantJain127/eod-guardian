package com.caixa.eodguardian.spring;

import com.caixa.eodguardian.common.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots the Spring runtime with an in-memory H2 audit store and exercises the REST API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:h2:mem:eodtest;DB_CLOSE_DELAY=-1"})
class EodGuardianApiIT {

    @Autowired
    TestRestTemplate rest;

    private String login(String user) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.postForEntity("/api/auth/login",
                new HttpEntity<>("{\"username\":\"" + user + "\",\"password\":\"Guardian@2026\"}", h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("HttpOnly");
        return Json.str(Json.parseObject(r.getBody()), "token");
    }

    private HttpHeaders auth(String token, boolean csrf) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        if (csrf) h.add("X-Requested-With", "EODGuardian");
        return h;
    }

    @Test
    void healthIsPublicAndAuditChainValid() {
        ResponseEntity<String> r = rest.getForEntity("/api/health", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"auditChainValid\":true");
    }

    @Test
    void dashboardRequiresAuthentication() {
        assertThat(rest.getForEntity("/api/dashboard", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void operatorCanStartEodButInvestigatorCannot() {
        String inv = login("ravi.investigator");
        ResponseEntity<String> denied = rest.exchange("/api/eod/start", HttpMethod.POST,
                new HttpEntity<>("{\"scenario\":\"SUCCESS\"}", auth(inv, true)), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String op = login("meera.operator");
        ResponseEntity<String> noCsrf = rest.exchange("/api/eod/start", HttpMethod.POST,
                new HttpEntity<>("{\"scenario\":\"SUCCESS\"}", auth(op, false)), String.class);
        assertThat(noCsrf.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> ok = rest.exchange("/api/eod/start", HttpMethod.POST,
                new HttpEntity<>("{\"scenario\":\"SUCCESS\"}", auth(op, true)), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> overview = Json.parseObject(ok.getBody());
        assertThat(overview.get("status")).isEqualTo("RUNNING");
    }

    @Test
    void staticDashboardIsServed() {
        ResponseEntity<String> r = rest.getForEntity("/", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("EOD Guardian");
        assertThat(r.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
    }
}
