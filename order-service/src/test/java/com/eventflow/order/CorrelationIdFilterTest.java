package com.eventflow.order;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void cleanupMdc() {
        org.slf4j.MDC.remove(CorrelationIdFilter.CORRELATION_ID_KEY);
    }

    @Test
    void generatesCorrelationIdWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> assertThat(org.slf4j.MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY))
                .isNotNull();

        filter.init(new MockFilterConfig());
        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isNotNull();
        assertThat(org.slf4j.MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)).isNull();
    }

    @Test
    void preservesExistingCorrelationIdHeader() throws Exception {
        String existing = "cid-123";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, existing);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> assertThat(org.slf4j.MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY))
                .isEqualTo(existing);

        filter.init(new MockFilterConfig());
        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(existing);
        assertThat(org.slf4j.MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)).isNull();
    }

    @Test
    void generatesNewIdWhenHeaderIsBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            String mdc = org.slf4j.MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY);
            assertThat(mdc).isNotNull().isNotBlank();
        };

        filter.init(new MockFilterConfig());
        filter.doFilter(request, response, chain);

        String header = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(header).isNotNull().isNotBlank().isNotEqualTo("   ");
        assertThat(org.slf4j.MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)).isNull();
    }
}
