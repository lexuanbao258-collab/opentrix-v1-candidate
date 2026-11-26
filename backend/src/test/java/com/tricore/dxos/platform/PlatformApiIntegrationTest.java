// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tricore.dxos.infrastructure.identity.security.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end through the real Spring stack: production JWT validation chain (local signing key),
 * Spring MVC, RecordService transactions and a freshly migrated PostgreSQL 17 using the
 * least-privilege runtime login. Nothing in this class is skipped when the database is absent; it fails.
 */
@SpringBootTest(properties = {
        "opentrix.platform.enabled=true",
        "springdoc.api-docs.enabled=true",
        "spring.flyway.enabled=false",
        "dxos.workflow.schema=unused_workflow_schema"
})
@AutoConfigureMockMvc
class PlatformApiIntegrationTest {
    private static final PlatformTestDatabase.Database DB = PlatformTestDatabase.fresh();
    private static final String CATEGORIES = "/api/v1/apps/it-helpdesk/entities/category/records";
    private static final String LEAVES = "/api/v1/apps/hr-leave/entities/leaveRequest/records";

    private static final String ADMIN = TestJwt.token("admin-1", "it-helpdesk:admin");
    private static final String REQUESTER = TestJwt.token("requester-1", "it-helpdesk:requester");
    private static final String OUTSIDER = TestJwt.token("outsider-1", "workflow:read");
    private static final String HR_ADMIN_ONLY = TestJwt.token("hr-1", "hr-leave:admin", "hr-leave:manager");
    private static final String EMPLOYEE_A = TestJwt.token("employee-a", "hr-leave:employee");
    private static final String EMPLOYEE_B = TestJwt.token("employee-b", "hr-leave:employee");
    private static final String MANAGER = TestJwt.token("manager-1", "hr-leave:manager");

    @TestBean(name = "jwtDecoder")
    JwtDecoder jwtDecoder;

    static JwtDecoder jwtDecoder() {
        return TestJwt.decoder();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::runtimeUrl);
        registry.add("spring.datasource.username", () -> PlatformTestDatabase.RUNTIME);
        registry.add("spring.datasource.password", () -> PlatformTestDatabase.PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clean() {
        // Owner-only reset between tests; the runtime login cannot delete audit rows.
        DB.ownerSql().execute("TRUNCATE opentrix_platform.records, opentrix_platform.audit_events");
    }

    @Test
    void cleanDatabaseIsMigratedAndBothManifestsAreRegistered() {
        assertThat(DB.registered()).containsExactly("hr-leave", "it-helpdesk");
        assertThat(DB.runtimeSql().queryForObject("""
                SELECT version FROM opentrix_platform.platform_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1""",
                String.class)).isEqualTo("1");
        assertThat(DB.runtimeSql().queryForList(
                "SELECT entity_key FROM opentrix_platform.entity_definitions ORDER BY 1", String.class))
                .containsExactly("category", "leaveRequest");
    }

    @Test
    void crudRoundTripIsPersistedAndAudited() throws Exception {
        JsonNode created = body(call(post(CATEGORIES), ADMIN, "{\"data\":" + category("NET", "Network") + "}")
                .andExpect(status().isCreated()));
        String id = created.path("id").asText();
        assertThat(created.path("version").asLong()).isZero();
        assertThat(created.path("createdBy").asText()).startsWith("oidc:");

        call(get(CATEGORIES + "/" + id), ADMIN, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Network"));
        call(get(CATEGORIES + "?q=netw"), ADMIN, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(id));

        call(put(CATEGORIES + "/" + id), ADMIN, "{\"expectedVersion\":0,\"data\":" + category("NET", "Networking") + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        assertThat(DB.superuserSql().queryForObject(
                "SELECT data->>'name' FROM opentrix_platform.records WHERE record_id = ?::uuid", String.class, id))
                .isEqualTo("Networking");

        call(delete(CATEGORIES + "/" + id + "?expectedVersion=1"), ADMIN, null).andExpect(status().isNoContent());
        call(get(CATEGORIES + "/" + id), ADMIN, null).andExpect(status().isNotFound());

        assertThat(DB.superuserSql().queryForList("""
                SELECT action || ':' || record_version FROM opentrix_platform.audit_events
                 WHERE record_id = ?::uuid ORDER BY event_id""", String.class, id))
                .containsExactly("CREATE:0", "UPDATE:1", "DELETE:1");
    }

    @Test
    void invalidRecordIsRejectedWithFieldErrorsAndNothingIsStored() throws Exception {
        call(post(CATEGORIES), ADMIN, "{\"data\":{\"code\":\"lower case\",\"active\":\"yes\",\"extra\":1}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors['/code']").exists())
                .andExpect(jsonPath("$.errors['/active']").exists());
        call(post(CATEGORIES), ADMIN, "{\"data\":" + category("NET", "Network") + ",\"owner\":\"someone\"}")
                .andExpect(status().isBadRequest());
        call(post(CATEGORIES), ADMIN, "{\"data\":[1,2]}").andExpect(status().isBadRequest());
        assertThat(count("records")).isZero();
        assertThat(count("audit_events")).isZero();
    }

    @Test
    void unauthenticatedAndInvalidTokensAreRejected() throws Exception {
        mvc.perform(get(CATEGORIES)).andExpect(status().isUnauthorized());
        mvc.perform(post(CATEGORIES).contentType(MediaType.APPLICATION_JSON)
                .content("{\"data\":" + category("NET", "Network") + "}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/apps").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        assertThat(count("records")).isZero();
    }

    @Test
    void roleWithoutWriteGrantCanReadButNotWrite() throws Exception {
        String id = createCategory("HW", "Hardware");
        call(get(CATEGORIES), REQUESTER, null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        call(post(CATEGORIES), REQUESTER, "{\"data\":" + category("SW", "Software") + "}")
                .andExpect(status().isForbidden());
        call(put(CATEGORIES + "/" + id), REQUESTER, "{\"expectedVersion\":0,\"data\":" + category("HW", "Hacked") + "}")
                .andExpect(status().isForbidden());
        call(delete(CATEGORIES + "/" + id + "?expectedVersion=0"), REQUESTER, null).andExpect(status().isForbidden());
        call(get("/api/v1/apps/it-helpdesk/entities/category"), REQUESTER, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedOperations.read").value("all"))
                .andExpect(jsonPath("$.allowedOperations.create").doesNotExist());
        assertThat(count("records")).isEqualTo(1);
    }

    @Test
    void callerWithoutAnyAppRoleSeesNothing() throws Exception {
        String id = createCategory("HW", "Hardware");
        call(get("/api/v1/apps"), OUTSIDER, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        call(get("/api/v1/apps/it-helpdesk"), OUTSIDER, null).andExpect(status().isNotFound());
        call(get(CATEGORIES), OUTSIDER, null).andExpect(status().isNotFound());
        call(get(CATEGORIES + "/" + id), OUTSIDER, null).andExpect(status().isNotFound());
        call(delete(CATEGORIES + "/" + id + "?expectedVersion=0"), OUTSIDER, null).andExpect(status().isNotFound());
    }

    @Test
    void crossApplicationAccessIsBlocked() throws Exception {
        String id = createCategory("HW", "Hardware");
        // A role in hr-leave, even one named "admin", grants nothing in it-helpdesk (and the reverse).
        call(get(CATEGORIES), HR_ADMIN_ONLY, null).andExpect(status().isNotFound());
        call(post(CATEGORIES), HR_ADMIN_ONLY, "{\"data\":" + category("X", "X") + "}").andExpect(status().isNotFound());
        call(get(LEAVES), ADMIN, null).andExpect(status().isNotFound());
        // A record id from it-helpdesk is not reachable through another application's or entity's path.
        call(get(LEAVES + "/" + id), MANAGER, null).andExpect(status().isNotFound());
        call(put(LEAVES + "/" + id), MANAGER, "{\"expectedVersion\":0,\"data\":" + leave("x") + "}")
                .andExpect(status().isNotFound());
        call(get("/api/v1/apps"), ADMIN, null).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("it-helpdesk"));
        assertThat(DB.superuserSql().queryForObject(
                "SELECT data->>'name' FROM opentrix_platform.records WHERE record_id = ?::uuid", String.class, id))
                .isEqualTo("Hardware");
    }

    @Test
    void staleVersionConflictsOnUpdateAndDelete() throws Exception {
        String id = createCategory("HW", "Hardware");
        call(put(CATEGORIES + "/" + id), ADMIN, "{\"expectedVersion\":0,\"data\":" + category("HW", "First") + "}")
                .andExpect(status().isOk());
        call(put(CATEGORIES + "/" + id), ADMIN, "{\"expectedVersion\":0,\"data\":" + category("HW", "Second") + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        call(delete(CATEGORIES + "/" + id + "?expectedVersion=0"), ADMIN, null).andExpect(status().isConflict());
        call(get(CATEGORIES + "/" + id), ADMIN, null).andExpect(jsonPath("$.data.name").value("First"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void recordAndAuditCommitOrRollBackTogether() throws Exception {
        String id = createCategory("HW", "Hardware");
        DB.ownerSql().execute("REVOKE INSERT ON opentrix_platform.audit_events FROM " + PlatformTestDatabase.RUNTIME);
        try {
            call(post(CATEGORIES), ADMIN, "{\"data\":" + category("SW", "Software") + "}")
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("Request failed"));
            call(put(CATEGORIES + "/" + id), ADMIN, "{\"expectedVersion\":0,\"data\":" + category("HW", "Lost") + "}")
                    .andExpect(status().isInternalServerError());
            call(delete(CATEGORIES + "/" + id + "?expectedVersion=0"), ADMIN, null)
                    .andExpect(status().isInternalServerError());
        } finally {
            DB.ownerSql().execute("GRANT INSERT ON opentrix_platform.audit_events TO " + PlatformTestDatabase.RUNTIME);
        }
        assertThat(count("records")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
        call(get(CATEGORIES + "/" + id), ADMIN, null).andExpect(jsonPath("$.data.name").value("Hardware"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void searchAndPaginationNeverExposeOtherOwnersRecords() throws Exception {
        for (int i = 0; i < 3; i++) createLeave(EMPLOYEE_A, "alpha trip " + i);
        String secret = createLeave(EMPLOYEE_B, "beta private matter");
        createLeave(EMPLOYEE_B, "beta trip");

        call(get(LEAVES + "?limit=2&offset=0"), EMPLOYEE_A, null).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2));
        call(get(LEAVES + "?limit=2&offset=2"), EMPLOYEE_A, null).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(1));
        call(get(LEAVES + "?q=beta"), EMPLOYEE_A, null).andExpect(jsonPath("$.total").value(0));
        call(get(LEAVES + "?q=trip"), EMPLOYEE_A, null).andExpect(jsonPath("$.total").value(3));
        call(get(LEAVES + "?q=%25"), EMPLOYEE_A, null).andExpect(jsonPath("$.total").value(0));
        call(get(LEAVES + "/" + secret), EMPLOYEE_A, null).andExpect(status().isNotFound());
        call(put(LEAVES + "/" + secret), EMPLOYEE_A, "{\"expectedVersion\":0,\"data\":" + leave("taken over") + "}")
                .andExpect(status().isNotFound());
        call(delete(LEAVES + "/" + secret + "?expectedVersion=0"), EMPLOYEE_A, null).andExpect(status().isNotFound());

        call(get(LEAVES + "?q=trip"), MANAGER, null).andExpect(jsonPath("$.total").value(4));
        call(get(LEAVES), MANAGER, null).andExpect(jsonPath("$.total").value(5));
        // Manager may update any leave request but has no delete grant.
        call(put(LEAVES + "/" + secret), MANAGER, "{\"expectedVersion\":0,\"data\":" + leave("approved note") + "}")
                .andExpect(status().isOk());
        call(delete(LEAVES + "/" + secret + "?expectedVersion=1"), MANAGER, null).andExpect(status().isForbidden());
    }

    @Test
    void invalidPagingParametersAreRejected() throws Exception {
        call(get(CATEGORIES + "?limit=0"), ADMIN, null).andExpect(status().isBadRequest());
        call(get(CATEGORIES + "?limit=101"), ADMIN, null).andExpect(status().isBadRequest());
        call(get(CATEGORIES + "?offset=-1"), ADMIN, null).andExpect(status().isBadRequest());
        call(get(CATEGORIES + "/not-a-uuid"), ADMIN, null).andExpect(status().isBadRequest());
        call(delete(CATEGORIES + "/" + UUID.randomUUID()), ADMIN, null).andExpect(status().isBadRequest());
    }

    @Test
    void openApiIsNotAnonymousAndDescribesTheRegisteredEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        JsonNode document = body(call(get("/v3/api-docs"), REQUESTER, null).andExpect(status().isOk()));
        JsonNode paths = document.path("paths");
        assertThat(paths.path("/api/v1/apps").has("get")).isTrue();
        assertThat(paths.path("/api/v1/apps/{appId}/entities/{entity}").has("get")).isTrue();
        assertThat(paths.path("/api/v1/apps/{appId}/entities/{entity}/records").has("get")).isTrue();
        assertThat(paths.path("/api/v1/apps/{appId}/entities/{entity}/records").has("post")).isTrue();
        JsonNode item = paths.path("/api/v1/apps/{appId}/entities/{entity}/records/{id}");
        assertThat(item.has("get") && item.has("put") && item.has("delete")).isTrue();
        assertThat(paths.fieldNames()).toIterable().allMatch(path -> path.startsWith("/api/v1/apps"));
        assertThat(document.path("components").path("securitySchemes").path("bearer").path("scheme").asText())
                .isEqualTo("bearer");
    }

    @Test
    void openApiIsOffByDefaultInTheShippedConfiguration() throws Exception {
        String yaml = java.nio.file.Files.readString(java.nio.file.Path.of("src", "main", "resources", "application.yml"));
        assertThat(yaml).contains("enabled: ${OPENTRIX_OPENAPI_ENABLED:false}");
    }

    private String createCategory(String code, String name) throws Exception {
        return body(call(post(CATEGORIES), ADMIN, "{\"data\":" + category(code, name) + "}")
                .andExpect(status().isCreated())).path("id").asText();
    }

    private String createLeave(String token, String reason) throws Exception {
        return body(call(post(LEAVES), token, "{\"data\":" + leave(reason) + "}")
                .andExpect(status().isCreated())).path("id").asText();
    }

    private static String category(String code, String name) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"active\":true}";
    }

    private static String leave(String reason) {
        return "{\"kind\":\"ANNUAL\",\"startDate\":\"2026-12-01\",\"days\":2,\"reason\":\"" + reason + "\"}";
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private long count(String table) {
        Long total = DB.superuserSql().queryForObject("SELECT count(*) FROM opentrix_platform." + table, Long.class);
        return total == null ? 0 : total;
    }
}
