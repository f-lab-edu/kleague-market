package com.flab.kleaguemarket.order;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 주문 API가 docs/api/openapi.yaml 계약대로 응답하는지 검증한다.
 *
 * <p>이 테스트는 DB와 무관한 고정 응답 계약 테스트다. classpath에 JDBC·Flyway가 올라온 뒤로는
 * DataSource 설정이 없으면 컨텍스트 기동 자체가 실패하므로, 두 자동 설정을 여기서만 제외한다.
 * 실제 DB 검증은 OrderSchemaPostgresTest가 담당하며 그쪽과 전역 설정에는 이 제외를 적용하지 않는다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
@AutoConfigureMockMvc
class OrderApiContractTest {

    @Autowired
    MockMvc mockMvc;

    private static final String BEARER = "Bearer dummy-token";
    private static final UUID UNKNOWN_ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final String ISO_UTC_PATTERN =
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";
    private static final String VALID_ORDER = """
            {"playerId": 1, "side": "BUY", "quantity": 10, "limitPrice": 15000}
            """;

    @Test
    void 반대_호가가_없으면_전량이_OPEN으로_접수된다() throws Exception {
        mockMvc.perform(post("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_ORDER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.playerId").value(1))
                .andExpect(jsonPath("$.side").value("BUY"))
                .andExpect(jsonPath("$.quantity").value(10))
                .andExpect(jsonPath("$.limitPrice").value(15000L))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern(ISO_UTC_PATTERN)))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.filledQuantity").value(0))
                .andExpect(jsonPath("$.remainingQuantity").value(10))
                .andExpect(jsonPath("$.fills").isArray())
                .andExpect(jsonPath("$.avgFillPrice").value(nullValue()));
    }

    @Test
    void 주문_생성_시_수량이_0이면_400을_반환한다() throws Exception {
        String body = """
                {"playerId": 1, "side": "BUY", "quantity": 0, "limitPrice": 15000}
                """;

        mockMvc.perform(post("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(notNullValue()))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(ISO_UTC_PATTERN)))
                .andExpect(jsonPath("$.path").value("/api/orders"));
    }

    @Test
    void 주문_생성_시_인증_헤더가_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_ORDER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value(notNullValue()))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(ISO_UTC_PATTERN)))
                .andExpect(jsonPath("$.path").value("/api/orders"));
    }

    @Test
    void 내_주문_목록은_페이지_형태로_반환한다() throws Exception {
        주문을_생성한다();  // content[0]을 단언하려면 목록이 비어 있지 않아야 한다

        mockMvc.perform(get("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, BEARER)
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderId").value(matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.content[0].side").isString())
                .andExpect(jsonPath("$.content[0].quantity").isNumber())
                .andExpect(jsonPath("$.content[0].limitPrice").isNumber())
                .andExpect(jsonPath("$.content[0].createdAt").value(matchesPattern(ISO_UTC_PATTERN)))
                .andExpect(jsonPath("$.content[0].status").isString())
                .andExpect(jsonPath("$.content[0].filledQuantity").isNumber())
                .andExpect(jsonPath("$.content[0].remainingQuantity").isNumber())
                .andExpect(jsonPath("$.content[0].fills").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber())
                .andExpect(jsonPath("$.pageable").doesNotExist())
                .andExpect(jsonPath("$.last").doesNotExist())
                .andExpect(jsonPath("$.number").doesNotExist());
    }

    @Test
    void 목록_조회_시_size가_100을_넘으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, BEARER)
                .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(notNullValue()))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(ISO_UTC_PATTERN)))
                .andExpect(jsonPath("$.path").value("/api/orders"));
    }

    @Test
    void 주문_단건_조회는_주문_상태를_반환한다() throws Exception {
        UUID orderId = 주문을_생성한다();

        mockMvc.perform(get("/api/orders/{id}", orderId)
                .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.playerId").isNumber())
                .andExpect(jsonPath("$.side").isString())
                .andExpect(jsonPath("$.status").isString())
                .andExpect(jsonPath("$.quantity").isNumber())
                .andExpect(jsonPath("$.filledQuantity").isNumber())
                .andExpect(jsonPath("$.remainingQuantity").isNumber())
                .andExpect(jsonPath("$.limitPrice").isNumber())
                .andExpect(jsonPath("$.fills").isArray())
                .andExpect(jsonPath("$.createdAt").value(matchesPattern(ISO_UTC_PATTERN)));
    }

    @Test
    void 없는_주문을_조회하면_404를_반환한다() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", UNKNOWN_ORDER_ID)
                .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(notNullValue()))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(ISO_UTC_PATTERN)))
                .andExpect(jsonPath("$.path").value("/api/orders/" + UNKNOWN_ORDER_ID));
    }

    @Test
    void 주문_취소는_취소된_주문을_반환한다() throws Exception {
        UUID orderId = 주문을_생성한다();

        mockMvc.perform(delete("/api/orders/{id}", orderId)
                .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.playerId").isNumber())
                .andExpect(jsonPath("$.side").isString())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.quantity").isNumber())
                .andExpect(jsonPath("$.filledQuantity").isNumber())
                .andExpect(jsonPath("$.remainingQuantity").value(0))
                .andExpect(jsonPath("$.limitPrice").isNumber())
                .andExpect(jsonPath("$.fills").isArray())
                .andExpect(jsonPath("$.createdAt").value(matchesPattern(ISO_UTC_PATTERN)));
    }

    @Test
    void 없는_주문을_취소하면_404를_반환한다() throws Exception {
        mockMvc.perform(delete("/api/orders/{id}", UNKNOWN_ORDER_ID)
                .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(notNullValue()))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(ISO_UTC_PATTERN)))
                .andExpect(jsonPath("$.path").value("/api/orders/" + UNKNOWN_ORDER_ID));
    }

    @Test
    void 주문_생성_시_side가_enum_밖의_값이면_400을_반환한다() throws Exception {
        String body = """
                {"playerId": 1, "side": "HOLD", "quantity": 10, "limitPrice": 15000}
                """;

        mockMvc.perform(post("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(notNullValue()))
                // 봉투만이 아니라 "예외 원문이 새지 않는다"까지 지킨다
                .andExpect(jsonPath("$.message", not(containsString("Exception"))))
                .andExpect(jsonPath("$.message", not(containsString("com."))))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(ISO_UTC_PATTERN)))
                .andExpect(jsonPath("$.path").value("/api/orders"));
    }

    @Test
    void 같은_주문을_두_번_조회해도_생성_시각은_같다() throws Exception {
        UUID orderId = 주문을_생성한다();

        assertEquals(조회한_생성시각(orderId), 조회한_생성시각(orderId));
    }

    @Test
    void 주문_id가_uuid_형식이_아니면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", "not-a-uuid")
                .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(notNullValue()))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(ISO_UTC_PATTERN)))
                .andExpect(jsonPath("$.path").value("/api/orders/not-a-uuid"));
    }

    @Test
    void 목록_조회_시_status가_enum_밖의_값이면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, BEARER)
                .param("status", "WEIRD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(notNullValue()))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(ISO_UTC_PATTERN)))
                .andExpect(jsonPath("$.path").value("/api/orders"));
    }

    private String 조회한_생성시각(UUID orderId) throws Exception {
        String response = mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.createdAt");
    }

    /**
     * 조회·취소 대상 주문을 계약으로만 만든다 — 컨트롤러 내부 상수에 기대지 않으므로
     * 더미가 실제 서비스로 바뀌어도 이 테스트는 그대로 산다.
     */
    private UUID 주문을_생성한다() throws Exception {
        String response = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_ORDER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.orderId"));
    }
}
