// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.manifest;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowManifestTest {
    private static final Path DOCFLOW = Path.of("src", "test", "resources", "platform", "manifests", "docflow", "app.json");

    @Test
    void declaredWorkflowIsBuiltOnTheReusedCoreDefinition() throws Exception {
        var workflow = ManifestValidator.parse(Files.readAllBytes(DOCFLOW)).entities().get("document").workflow();

        assertThat(workflow.definition().initialState()).isEqualTo("DRAFT");
        assertThat(workflow.definition().states()).containsExactlyInAnyOrder("DRAFT", "REVIEW", "PUBLISHED", "REJECTED", "WITHDRAWN");
        assertThat(workflow.definition().isTerminal("PUBLISHED")).isTrue();
        assertThat(workflow.definition().isTerminal("REVIEW")).isFalse();
        assertThat(workflow.isEditable("DRAFT")).isTrue();
        assertThat(workflow.isEditable("REVIEW")).isFalse();
        assertThat(workflow.scopeFor("submit", Set.of("author"))).contains(AppManifest.Scope.OWN);
        assertThat(workflow.scopeFor("approve", Set.of("author"))).isEmpty();
        assertThat(ManifestValidator.parse(Files.readAllBytes(DOCFLOW)).entities().get("document").sensitiveFields())
                .containsExactly("summary");
    }

    @Test
    void invalidWorkflowDefinitionsAreRejected() {
        assertRejected(w -> w.put("initialState", "NOWHERE"), "unknown state");
        assertRejected(w -> transition(w, "submit").put("to", "GHOST"), "unknown state");
        assertRejected(w -> transition(w, "submit").put("to", "DRAFT"), "must differ");
        assertRejected(w -> ((ObjectNode) transition(w, "approve").path("roles")).put("ghost", "all"), "undeclared role ghost");
        assertRejected(w -> ((ObjectNode) transition(w, "approve")).set("roles", w.objectNode()), "at least one role");
        assertRejected(w -> ((ObjectNode) w.path("transitions")).remove("approve").size(), "unreachable");
        assertRejected(w -> ((ObjectNode) w.path("states")).putObject("ORPHAN"), "unreachable");
        assertRejected(w -> w.put("initialState", "PUBLISHED"), "outgoing transition");
        assertRejected(w -> ((ObjectNode) w.path("states")).putObject("lower"), "workflow");
        assertRejected(w -> w.put("extra", true), "workflow");
        assertRejected(w -> ((ObjectNode) transition(w, "submit")).put("script", "rm -rf /"), "workflow");
    }

    @Test
    void auditGrantMustBeAllAndForDeclaredRoles() {
        assertRejectedEntity(e -> ((ObjectNode) e.path("permissions").path("audit")).put("author", "own"), "audit");
        assertRejectedEntity(e -> ((ObjectNode) e.path("permissions").path("audit")).put("ghost", "all"), "undeclared role ghost");
    }

    private static ObjectNode transition(ObjectNode workflow, String id) {
        return (ObjectNode) workflow.path("transitions").path(id);
    }

    private static void assertRejected(Consumer<ObjectNode> mutation, String expected) {
        assertRejectedEntity(entity -> mutation.accept((ObjectNode) entity.path("workflow")), expected);
    }

    private static void assertRejectedEntity(Consumer<ObjectNode> mutation, String expected) {
        try {
            ObjectNode manifest = (ObjectNode) ManifestValidator.STRICT_JSON.readTree(Files.readAllBytes(DOCFLOW));
            mutation.accept((ObjectNode) manifest.path("entities").path("document"));
            byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> ManifestValidator.parse(bytes))
                    .isInstanceOf(ManifestValidator.ManifestException.class)
                    .hasMessageContaining(expected);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
