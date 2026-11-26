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
    private static final String DOCS = "/api/v1/apps/docflow/entities/document/records";

    private static final String ADMIN = TestJwt.token("admin-1", "it-helpdesk:admin");
    private static final String REQUESTER = TestJwt.token("requester-1", "it-helpdesk:requester");
    private static final String OUTSIDER = TestJwt.token("outsider-1", "workflow:read");
    private static final String HR_ADMIN_ONLY = TestJwt.token("hr-1", "docflow:admin", "docflow:reviewer");
    private static final String AUTHOR_A = TestJwt.token("employee-a", "docflow:author");
    private static final String AUTHOR_B = TestJwt.token("employee-b", "docflow:author");
    private static final String REVIEWER = TestJwt.token("manager-1", "docflow:reviewer");

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
        assertThat(DB.registered()).containsExactly("docflow", "it-helpdesk");
        assertThat(DB.runtimeSql().queryForObject("""
                SELECT version FROM opentrix_platform.platform_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1""",
                String.class)).isEqualTo("2");
        assertThat(DB.runtimeSql().queryForList(
                "SELECT entity_key FROM opentrix_platform.entity_definitions ORDER BY 1", String.class))
                .containsExactly("category", "document");
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
        // A role in docflow, even one named "admin", grants nothing in it-helpdesk (and the reverse).
        call(get(CATEGORIES), HR_ADMIN_ONLY, null).andExpect(status().isNotFound());
        call(post(CATEGORIES), HR_ADMIN_ONLY, "{\"data\":" + category("X", "X") + "}").andExpect(status().isNotFound());
        call(get(DOCS), ADMIN, null).andExpect(status().isNotFound());
        // A record id from it-helpdesk is not reachable through another application's or entity's path.
        call(get(DOCS + "/" + id), REVIEWER, null).andExpect(status().isNotFound());
        call(put(DOCS + "/" + id), REVIEWER, "{\"expectedVersion\":0,\"data\":" + doc("x") + "}")
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
        for (int i = 0; i < 3; i++) createDoc(AUTHOR_A, "alpha trip " + i);
        String secret = createDoc(AUTHOR_B, "beta private matter");
        createDoc(AUTHOR_B, "beta trip");

        call(get(DOCS + "?limit=2&offset=0"), AUTHOR_A, null).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2));
        call(get(DOCS + "?limit=2&offset=2"), AUTHOR_A, null).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(1));
        call(get(DOCS + "?q=beta"), AUTHOR_A, null).andExpect(jsonPath("$.total").value(0));
        call(get(DOCS + "?q=trip"), AUTHOR_A, null).andExpect(jsonPath("$.total").value(3));
        call(get(DOCS + "?q=%25"), AUTHOR_A, null).andExpect(jsonPath("$.total").value(0));
        call(get(DOCS + "/" + secret), AUTHOR_A, null).andExpect(status().isNotFound());
        call(put(DOCS + "/" + secret), AUTHOR_A, "{\"expectedVersion\":0,\"data\":" + doc("taken over") + "}")
                .andExpect(status().isNotFound());
        call(delete(DOCS + "/" + secret + "?expectedVersion=0"), AUTHOR_A, null).andExpect(status().isNotFound());

        call(get(DOCS + "?q=trip"), REVIEWER, null).andExpect(jsonPath("$.total").value(4));
        call(get(DOCS), REVIEWER, null).andExpect(jsonPath("$.total").value(5));
        // Reviewer may update any document but has no delete grant.
        call(put(DOCS + "/" + secret), REVIEWER, "{\"expectedVersion\":0,\"data\":" + doc("approved note") + "}")
                .andExpect(status().isOk());
        call(delete(DOCS + "/" + secret + "?expectedVersion=1"), REVIEWER, null).andExpect(status().isForbidden());
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
        assertThat(paths.path("/api/v1/apps/{appId}/entities/{entity}/records")
                .path("post").path("responses").has("201")).isTrue();
        JsonNode item = paths.path("/api/v1/apps/{appId}/entities/{entity}/records/{id}");
        assertThat(item.has("get") && item.has("put") && item.has("delete")).isTrue();
        assertThat(item.path("delete").path("responses").has("204")).isTrue();
        assertThat(paths.fieldNames()).toIterable().allMatch(path -> path.startsWith("/api/v1/apps"));
        assertThat(document.path("components").path("securitySchemes").path("bearer").path("scheme").asText())
                .isEqualTo("bearer");
    }

    /**
     * The reviewed contract for integrators lives in docs/api/openapi-v1.json. This fails when the running
     * application's OpenAPI differs from it; regenerate with OPENAPI_UPDATE=true and review the diff.
     */
    @Test
    void committedOpenApiArtifactMatchesTheRunningApplication() throws Exception {
        JsonNode document = body(call(get("/v3/api-docs"), REQUESTER, null).andExpect(status().isOk()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) document).remove("servers");
        var sorted = json.copy().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
        String canonical = lf(sorted.writeValueAsString(sorted.treeToValue(document, Object.class)));
        java.nio.file.Path artifact = java.nio.file.Path.of("..", "docs", "api", "openapi-v1.json");
        if ("true".equals(System.getenv("OPENAPI_UPDATE"))) {
            java.nio.file.Files.createDirectories(artifact.getParent());
            java.nio.file.Files.writeString(artifact, canonical);
        }
        assertThat(lf(java.nio.file.Files.readString(artifact)))
                .as("docs/api/openapi-v1.json is stale; run with OPENAPI_UPDATE=true").isEqualTo(canonical);
        assertThat(document.path("paths").size()).isEqualTo(9);
    }

    /** Line-ending neutral text with exactly one trailing line feed. */
    private static String lf(String text) {
        return String.join(String.valueOf((char) 10), text.lines().toList()) + (char) 10;
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

    private String createDoc(String token, String summary) throws Exception {
        return body(call(post(DOCS), token, "{\"data\":" + doc(summary) + "}")
                .andExpect(status().isCreated())).path("id").asText();
    }

    private static String category(String code, String name) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"active\":true}";
    }

    private static String doc(String summary) {
        return "{\"kind\":\"MEMO\",\"due\":\"2026-12-01\",\"pages\":2,\"summary\":\"" + summary + "\"}";
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
