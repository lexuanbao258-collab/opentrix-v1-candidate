// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tricore.dxos.infrastructure.identity.security.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Generic workflow + audit through the real stack and PostgreSQL, using the test-only docflow fixture. */
@SpringBootTest(properties = {
        "opentrix.platform.enabled=true",
        "spring.flyway.enabled=false",
        "dxos.workflow.schema=unused_workflow_schema"
})
@AutoConfigureMockMvc
class PlatformWorkflowIntegrationTest {
    private static final PlatformTestDatabase.Database DB = PlatformTestDatabase.fresh();
    private static final String DOCS = "/api/v1/apps/docflow/entities/document";
    private static final String SECRET = "TOP-SECRET-SUMMARY-9f3a";

    private static final String AUTHOR = TestJwt.token("author-1", "docflow:author");
    private static final String AUTHOR_2 = TestJwt.token("author-2", "docflow:author");
    private static final String REVIEWER = TestJwt.token("reviewer-1", "docflow:reviewer");
    private static final String AUDITOR = TestJwt.token("auditor-1", "docflow:auditor");
    private static final String HELPDESK_ADMIN = TestJwt.token("hd-admin", "it-helpdesk:admin");
    private static final String BOT = TestJwt.machineToken("review-bot", "docflow:reviewer", "docflow:auditor");

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
        DB.ownerSql().execute("TRUNCATE opentrix_platform.records, opentrix_platform.audit_events");
    }

    @Test
    void successfulTransitionsMoveTheRecordThroughItsDeclaredWorkflowAndAreRecorded() throws Exception {
        String id = create(AUTHOR);
        call(get(DOCS + "/records/" + id + "/workflow"), AUTHOR, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DRAFT")).andExpect(jsonPath("$.terminal").value(false))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.allowedActions[0].id").value("discard")).andExpect(jsonPath("$.allowedActions[1].id").value("submit"));
        call(get(DOCS + "/records/" + id + "/workflow"), REVIEWER, null)
                .andExpect(jsonPath("$.allowedActions.length()").value(0));

        call(post(DOCS + "/records/" + id + "/transitions/submit"), AUTHOR, "{\"expectedVersion\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REVIEW")).andExpect(jsonPath("$.version").value(1));
        call(get(DOCS + "/records/" + id + "/workflow"), REVIEWER, null)
                .andExpect(jsonPath("$.allowedActions[0].id").value("approve")).andExpect(jsonPath("$.allowedActions[1].id").value("reject"))
                .andExpect(jsonPath("$.editable").value(false));
        call(post(DOCS + "/records/" + id + "/transitions/approve"), REVIEWER, "{\"expectedVersion\":1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PUBLISHED")).andExpect(jsonPath("$.version").value(2));
        call(get(DOCS + "/records/" + id + "/workflow"), REVIEWER, null)
                .andExpect(jsonPath("$.terminal").value(true)).andExpect(jsonPath("$.allowedActions.length()").value(0));

        call(get(DOCS + "/records/" + id + "/history"), AUTHOR, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].action").value("CREATE")).andExpect(jsonPath("$[0].toState").value("DRAFT"))
                .andExpect(jsonPath("$[1].transitionId").value("submit")).andExpect(jsonPath("$[1].fromState").value("DRAFT"))
                .andExpect(jsonPath("$[2].transitionId").value("approve")).andExpect(jsonPath("$[2].toState").value("PUBLISHED"))
                .andExpect(jsonPath("$[2].actorType").value("USER"));
        assertThat(DB.superuserSql().queryForObject(
                "SELECT workflow_state || ':' || version FROM opentrix_platform.records WHERE record_id = ?::uuid",
                String.class, id)).isEqualTo("PUBLISHED:2");
        assertThat(DB.superuserSql().queryForObject("""
                SELECT count(*) FROM opentrix_platform.audit_events
                 WHERE record_id = ?::uuid AND action = 'TRANSITION' AND before_data IS NULL AND after_data IS NULL""",
                Long.class, id)).isEqualTo(2);
    }

    @Test
    void invalidAndUnknownTransitionsAreRejectedWithoutChange() throws Exception {
        String id = create(AUTHOR);
        call(post(DOCS + "/records/" + id + "/transitions/approve"), REVIEWER, "{\"expectedVersion\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRANSITION_NOT_ALLOWED"));
        call(post(DOCS + "/records/" + id + "/transitions/publishAll"), AUTHOR, "{\"expectedVersion\":0}")
                .andExpect(status().isNotFound());
        call(post(DOCS + "/records/" + id + "/transitions/submit"), AUTHOR, "{\"expectedVersion\":0,\"state\":\"PUBLISHED\"}")
                .andExpect(status().isBadRequest());
        call(post(DOCS + "/records/" + id + "/transitions/submit"), AUTHOR, "{\"expectedVersion\":0.5}")
                .andExpect(status().isBadRequest());
        call(post(DOCS + "/records/" + id + "/transitions/submit"), AUTHOR, "{}").andExpect(status().isBadRequest());
        call(post(DOCS + "/records/" + id + "/transitions/discard"), AUTHOR, "{\"expectedVersion\":0}").andExpect(status().isOk());
        // terminal state: no further transition
        call(post(DOCS + "/records/" + id + "/transitions/submit"), AUTHOR, "{\"expectedVersion\":1}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRANSITION_NOT_ALLOWED"));
        assertThat(state(id)).isEqualTo("WITHDRAWN:1");
    }

    @Test
    void forbiddenTransitionsAreRefusedByRoleAndOwnership() throws Exception {
        String id = create(AUTHOR);
        call(post(DOCS + "/records/" + id + "/transitions/submit"), REVIEWER, "{\"expectedVersion\":0}")
                .andExpect(status().isForbidden());
        call(post(DOCS + "/records/" + id + "/transitions/submit"), AUTHOR_2, "{\"expectedVersion\":0}")
                .andExpect(status().isNotFound());
        call(post(DOCS + "/records/" + id + "/transitions/submit"), AUTHOR, "{\"expectedVersion\":0}").andExpect(status().isOk());
        call(post(DOCS + "/records/" + id + "/transitions/approve"), AUTHOR, "{\"expectedVersion\":1}")
                .andExpect(status().isForbidden());
        call(post(DOCS + "/records/" + id + "/transitions/approve"), AUDITOR, "{\"expectedVersion\":1}")
                .andExpect(status().isForbidden());
        assertThat(state(id)).isEqualTo("REVIEW:1");
    }

    @Test
    void staleVersionConflictsAndLeavesTheRecordUnchanged() throws Exception {
        String id = create(AUTHOR);
        call(post(DOCS + "/records/" + id + "/transitions/submit"), AUTHOR, "{\"expectedVersion\":5}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        call(put(DOCS + "/records/" + id), AUTHOR, "{\"expectedVersion\":0,\"data\":" + doc("edited") + "}").andExpect(status().isOk());
        // the edit bumped the shared version, so a transition prepared against version 0 is stale
        call(post(DOCS + "/records/" + id + "/transitions/submit"), AUTHOR, "{\"expectedVersion\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        call(post(DOCS + "/records/" + id + "/transitions/submit"), AUTHOR, "{\"expectedVersion\":1}").andExpect(status().isOk());
        assertThat(state(id)).isEqualTo("REVIEW:2");
    }

    @Test
    void concurrentTransitionsHaveExactlyOneWinner() throws Exception {
        String id = create(AUTHOR);
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> submit = () -> {
                barrier.await();
                return call(post(DOCS + "/records/" + id + "/transitions/submit"), AUTHOR, "{\"expectedVersion\":0}")
                        .andReturn().getResponse().getStatus();
            };
            Future<Integer> first = pool.submit(submit);
            Future<Integer> second = pool.submit(submit);
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(state(id)).isEqualTo("REVIEW:1");
        assertThat(DB.superuserSql().queryForObject(
                "SELECT count(*) FROM opentrix_platform.audit_events WHERE record_id = ?::uuid AND action = 'TRANSITION'",
                Long.class, id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hrCreatorCannotApproveOrRejectEvenWithManagerRole(boolean machine) throws Exception {
        String creator = machine ? TestJwt.machineToken("hr-dual-bot", "hr-leave:employee", "hr-leave:manager")
                : TestJwt.token("hr-dual-user", "hr-leave:employee", "hr-leave:manager");
        String manager = machine ? TestJwt.machineToken("hr-manager-bot", "hr-leave:manager")
                : TestJwt.token("hr-manager-user", "hr-leave:manager");
        String records = "/api/v1/apps/hr-leave/entities/leaveRequest/records";
        String id = body(call(post(records), creator, """
                {"data":{"kind":"ANNUAL","startDate":"2026-12-20","days":2,"reason":"family"}}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("DRAFT"))).path("id").asText();
        String record = records + "/" + id;
        call(post(record + "/transitions/submit"), creator, "{\"expectedVersion\":0}").andExpect(status().isOk());
        var creatorActions = body(call(get(record + "/workflow"), creator, null).andExpect(status().isOk()));
        assertThat(creatorActions.path("allowedActions").findValuesAsText("id")).containsExactly("withdraw");
        for (String denied : List.of("approve", "reject")) {
            call(post(record + "/transitions/" + denied), creator, "{\"expectedVersion\":1}").andExpect(status().isForbidden());
        }
        assertThat(state(id)).isEqualTo("PENDING:1");
        String kind = machine ? "MACHINE" : "USER";
        assertThat(DB.ownerSql().queryForList("""
                SELECT action || ':' || actor_type FROM opentrix_platform.audit_events
                WHERE record_id = ?::uuid ORDER BY event_id""", String.class, id)).containsExactly("CREATE:" + kind, "TRANSITION:" + kind);
        var managerActions = body(call(get(record + "/workflow"), manager, null).andExpect(status().isOk()));
        assertThat(managerActions.path("allowedActions").findValuesAsText("id")).containsExactly("approve", "reject");
        call(post(record + "/transitions/approve"), manager, "{\"expectedVersion\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED")).andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void transitionAndAuditRollBackTogetherWhenAuditCannotBeWritten() throws Exception {
        String id = create(AUTHOR);
        DB.ownerSql().execute("REVOKE INSERT ON opentrix_platform.audit_events FROM " + PlatformTestDatabase.RUNTIME);
        try {
            call(post(DOCS + "/records/" + id + "/transitions/submit"), AUTHOR, "{\"expectedVersion\":0}")
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("Request failed"));
        } finally {
            DB.ownerSql().execute("GRANT INSERT ON opentrix_platform.audit_events TO " + PlatformTestDatabase.RUNTIME);
        }
        assertThat(state(id)).isEqualTo("DRAFT:0");
        assertThat(DB.superuserSql().queryForObject(
                "SELECT count(*) FROM opentrix_platform.audit_events WHERE action = 'TRANSITION'", Long.class)).isZero();
    }

    @Test
    void failedCommitIsReportedAsOutcomeUnknownNotAsSuccessOrPlainFailure() throws Exception {
        var owner = DB.ownerSql();
        owner.execute("CREATE FUNCTION opentrix_platform.fail_at_commit() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'deferred failure'; END $$");
        owner.execute("CREATE CONSTRAINT TRIGGER fail_at_commit AFTER INSERT ON opentrix_platform.records "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opentrix_platform.fail_at_commit()");
        try {
            call(post(DOCS + "/records"), AUTHOR, "{\"data\":" + doc("x") + "}")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("COMMIT_OUTCOME_UNKNOWN"));
        } finally {
            owner.execute("DROP TRIGGER fail_at_commit ON opentrix_platform.records");
            owner.execute("DROP FUNCTION opentrix_platform.fail_at_commit()");
        }
        assertThat(DB.superuserSql().queryForObject("SELECT count(*) FROM opentrix_platform.records", Long.class)).isZero();
    }

    @Test
    void genericWritesCannotChangeOrBypassWorkflowState() throws Exception {
        String id = create(AUTHOR);
        call(put(DOCS + "/records/" + id), AUTHOR,
                "{\"expectedVersion\":0,\"data\":" + doc("x").replace("}", ",\"state\":\"PUBLISHED\"}") + "}")
                .andExpect(status().isBadRequest());
        call(post(DOCS + "/records"), AUTHOR, "{\"data\":" + doc("x").replace("}", ",\"workflowState\":\"PUBLISHED\"}") + "}")
                .andExpect(status().isBadRequest());
        call(post(DOCS + "/records"), AUTHOR, "{\"data\":" + doc("x") + ",\"state\":\"PUBLISHED\"}")
                .andExpect(status().isBadRequest());
        call(post(DOCS + "/records/" + id + "/transitions/submit"), AUTHOR, "{\"expectedVersion\":0}").andExpect(status().isOk());
        // in REVIEW the generic routes are locked, even for roles that normally hold update/delete
        call(put(DOCS + "/records/" + id), AUTHOR, "{\"expectedVersion\":1,\"data\":" + doc("late") + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_LOCKED"));
        call(put(DOCS + "/records/" + id), REVIEWER, "{\"expectedVersion\":1,\"data\":" + doc("late") + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_LOCKED"));
        call(delete(DOCS + "/records/" + id + "?expectedVersion=1"), AUTHOR, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_LOCKED"));
        assertThat(state(id)).isEqualTo("REVIEW:1");
        call(get(DOCS + "/records/" + id), AUTHOR, null).andExpect(jsonPath("$.state").value("REVIEW"));
    }

    @Test
    void workflowEndpointsAreNotFoundForEntitiesWithoutAWorkflowAndAcrossApps() throws Exception {
        String id = create(AUTHOR);
        String category = body(call(post("/api/v1/apps/it-helpdesk/entities/category/records"), HELPDESK_ADMIN,
                "{\"data\":{\"code\":\"NET\",\"name\":\"Network\",\"active\":true}}").andExpect(status().isCreated())).path("id").asText();
        call(get("/api/v1/apps/it-helpdesk/entities/category/records/" + category + "/workflow"), HELPDESK_ADMIN, null)
                .andExpect(status().isNotFound());
        call(post("/api/v1/apps/it-helpdesk/entities/category/records/" + category + "/transitions/submit"), HELPDESK_ADMIN,
                "{\"expectedVersion\":0}").andExpect(status().isNotFound());
        // a role in another application reaches nothing of docflow
        call(get(DOCS + "/records/" + id + "/workflow"), HELPDESK_ADMIN, null).andExpect(status().isNotFound());
        call(post(DOCS + "/records/" + id + "/transitions/submit"), HELPDESK_ADMIN, "{\"expectedVersion\":0}").andExpect(status().isNotFound());
        call(get(DOCS + "/records/" + id + "/history"), HELPDESK_ADMIN, null).andExpect(status().isNotFound());
        // a docflow id cannot be driven through the other application's path
        call(post("/api/v1/apps/it-helpdesk/entities/category/records/" + id + "/transitions/submit"), HELPDESK_ADMIN,
                "{\"expectedVersion\":0}").andExpect(status().isNotFound());
        assertThat(state(id)).isEqualTo("DRAFT:0");
    }

    @Test
    void auditRequiresTheAuditGrantIsScopedAndRedactsSensitiveValues() throws Exception {
        String id = create(AUTHOR, SECRET);
        call(put(DOCS + "/records/" + id), AUTHOR, "{\"expectedVersion\":0,\"data\":" + doc(SECRET + "-v2") + "}").andExpect(status().isOk());
        call(post(DOCS + "/records/" + id + "/transitions/submit"), AUTHOR, "{\"expectedVersion\":1}").andExpect(status().isOk());
        call(post(DOCS + "/records/" + id + "/transitions/reject"), REVIEWER, "{\"expectedVersion\":2}").andExpect(status().isOk());
        String other = create(AUTHOR_2, "another");

        var audit = body(call(get(DOCS + "/audit?recordId=" + id), AUDITOR, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4)));
        assertThat(audit.path("items").findValuesAsText("action")).containsExactly("CREATE", "UPDATE", "TRANSITION", "TRANSITION");
        assertThat(audit.toString()).doesNotContain(SECRET).contains("[REDACTED]");
        assertThat(audit.path("items").get(1).path("before").path("summary").asText()).isEqualTo("[REDACTED]");
        assertThat(audit.path("items").get(1).path("after").path("kind").asText()).isEqualTo("MEMO");
        call(get(DOCS + "/audit"), AUDITOR, null).andExpect(jsonPath("$.total").value(5));
        call(get(DOCS + "/audit?limit=2&offset=4"), AUDITOR, null).andExpect(jsonPath("$.items.length()").value(1));
        call(get(DOCS + "/audit?recordId=" + other), AUDITOR, null).andExpect(jsonPath("$.total").value(1));

        // the secret never reaches the database audit table either, nor the record's own read for non-owners without access
        assertThat(DB.superuserSql().queryForObject(
                "SELECT count(*) FROM opentrix_platform.audit_events WHERE before_data::text LIKE ? OR after_data::text LIKE ?",
                Long.class, "%" + SECRET + "%", "%" + SECRET + "%")).isZero();

        for (String token : List.of(AUTHOR, REVIEWER)) {
            call(get(DOCS + "/audit"), token, null).andExpect(status().isForbidden());
        }
        call(get(DOCS + "/audit"), HELPDESK_ADMIN, null).andExpect(status().isNotFound());
        call(get("/api/v1/apps/it-helpdesk/entities/category/audit?recordId=" + id), HELPDESK_ADMIN, null)
                .andExpect(status().isForbidden());
        call(get(DOCS + "/audit?limit=0"), AUDITOR, null).andExpect(status().isBadRequest());
        mvc.perform(get(DOCS + "/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    void machineIdentityUsesOnlyItsGrantedRolesAndIsLabelledInAudit() throws Exception {
        String id = create(AUTHOR);
        call(post(DOCS + "/records/" + id + "/transitions/submit"), AUTHOR, "{\"expectedVersion\":0}").andExpect(status().isOk());
        // the bot holds reviewer + auditor only: it may approve but not create or submit, and not touch other apps
        call(post(DOCS + "/records/" + id + "/transitions/approve"), BOT, "{\"expectedVersion\":1}").andExpect(status().isOk());
        call(post(DOCS + "/records"), BOT, "{\"data\":" + doc("x") + "}").andExpect(status().isForbidden());
        call(get("/api/v1/apps/it-helpdesk/entities/category/records"), BOT, null).andExpect(status().isNotFound());
        assertThat(DB.superuserSql().queryForList(
                "SELECT actor_type FROM opentrix_platform.audit_events WHERE record_id = ?::uuid ORDER BY event_id",
                String.class, id)).containsExactly("USER", "USER", "MACHINE");
        call(get(DOCS + "/audit?recordId=" + id), BOT, null).andExpect(jsonPath("$.items[2].actorType").value("MACHINE"));
    }

    private String create(String token) throws Exception {
        return create(token, "plain summary");
    }

    private String create(String token, String summary) throws Exception {
        return body(call(post(DOCS + "/records"), token, "{\"data\":" + doc(summary) + "}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("DRAFT"))).path("id").asText();
    }

    private String state(String id) {
        return DB.superuserSql().queryForObject(
                "SELECT workflow_state || ':' || version FROM opentrix_platform.records WHERE record_id = ?::uuid", String.class, id);
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
}
