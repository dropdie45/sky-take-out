package com.sky.service;

import com.sky.entity.AddressBook;
import com.sky.exception.OrderBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DeliveryRangeServiceTest {
    private DeliveryRangeService service;
    private MockRestServiceServer server;
    private AddressBook address;

    @BeforeEach
    void setUp() {
        service = new DeliveryRangeService("北京市海淀区北太平庄地铁站", "test-ak");
        RestTemplate client = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        server = MockRestServiceServer.bindTo(client).build();
        address = AddressBook.builder().provinceName("北京市").cityName("北京市")
                .districtName("海淀区").detail("测试路1号").build();
    }

    private void expectGeocodes() {
        for (int i = 0; i < 2; i++) {
            server.expect(queryParam("address", UriUtils.encodeQueryParam(
                            i == 0 ? "北京市海淀区北太平庄地铁站"
                                    : "北京市北京市海淀区测试路1号", "UTF-8")))
                    .andExpect(queryParam("ak", "test-ak"))
                    .andRespond(withSuccess("{\"status\":0,\"result\":{\"location\":"
                            + "{\"lat\":39.1234567,\"lng\":116.1234567}}}", MediaType.APPLICATION_JSON));
        }
    }

    private void expectRoute(String routes) {
        expectGeocodes();
        server.expect(queryParam("origin", "39.123457,116.123457"))
                .andExpect(queryParam("destination", "39.123457,116.123457"))
                .andRespond(withSuccess("{\"status\":0,\"result\":{\"routes\":"
                        + routes + "}}", MediaType.APPLICATION_JSON));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4999, 5000})
    void acceptsDistanceWithinFiveKilometers(int distance) {
        expectRoute("[{\"distance\":" + distance + "}]");
        assertDoesNotThrow(() -> service.check(address));
        server.verify();
    }

    @Test
    void rejectsDistanceJustOverFiveKilometers() {
        expectRoute("[{\"distance\":5000.1}]");
        assertTrue(assertThrows(OrderBusinessException.class,
                () -> service.check(address)).getMessage().contains("超出配送范围"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[{}]", "[{\"distance\":-1}]", "[{\"distance\":\"bad\"}]"})
    void rejectsMissingOrInvalidRouteDistance(String routes) {
        expectRoute(routes);
        assertThrows(OrderBusinessException.class, () -> service.check(address));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\":302}", "not-json"})
    void rejectsMapFailureAndMalformedResponse(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThrows(OrderBusinessException.class, () -> service.check(address));
        server.verify();
    }

    @Test
    void rejectsUnavailableMapService() {
        server.expect(anything()).andRespond(withServerError());
        assertThrows(OrderBusinessException.class, () -> service.check(address));
        server.verify();
    }

    @Test
    void rejectsMissingConfigurationOrAddressWithoutNetworkRequests() {
        assertThrows(OrderBusinessException.class,
                () -> new DeliveryRangeService("", "").check(address));
        address.setDetail(" ");
        assertThrows(OrderBusinessException.class, () -> service.check(address));
        server.verify();
    }
}
