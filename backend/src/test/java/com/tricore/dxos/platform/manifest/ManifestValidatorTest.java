// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.manifest;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestValidatorTest {
    private static final Path HELPDESK = Path.of("..", "apps", "it-helpdesk", "app.json");

    @Test
    void repositoryHelpdeskManifestIsValid() throws Exception {
        AppManifest manifest = ManifestValidator.parse(Files.readAllBytes(HELPDESK));

        assertThat(manifest.id()).isEqualTo("it-helpdesk");
        assertThat(manifest.roles()).containsExactlyInAnyOrder("admin", "agent", "requester");
        assertThat(manifest.entities()).containsOnlyKeys("category");
        assertThat(manifest.entities().get("category").scopeFor(AppManifest.Operation.CREATE,
                java.util.Set.of("requester"))).isEmpty();
        assertThat(manifest.sha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void lineEndingsDoNotChangeTheDigest() throws Exception {
        String text = Files.readString(HELPDESK).replace("\r\n", "\n");
        assertThat(ManifestValidator.parse(text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)).sha256())
                .isEqualTo(ManifestValidator.parse(text.getBytes(StandardCharsets.UTF_8)).sha256());
    }

    @Test
    void missingVersionIsRejected() {
        assertRejected(m -> m.remove("version"), "version");
    }

    @Test
    void unknownTopLevelKeyIsRejected() {
        assertRejected(m -> m.put("workflow", "x"), "workflow");
    }

    @Test
    void remoteReferenceInRecordSchemaIsRejected() {
        assertRejected(m -> field(m).put("$ref", "https://attacker.example/schema.json"), "$ref");
    }

    @Test
    void nonScalarOrUnknownFieldTypeIsRejected() {
        assertRejected(m -> field(m).put("type", "object"), "type");
        assertRejected(m -> field(m).put("type", "text"), "type");
    }

    @Test
    void unsafeRegularExpressionsAreRejected() {
        assertRejected(m -> field(m).put("pattern", "^(a+)+$"), "unsafe regular expression");
        assertRejected(m -> field(m).put("pattern", "^.*$").put("maxLength", 20), "unsafe regular expression");
    }

    @Test
    void patternWithoutBoundedMaxLengthIsRejected() {
        assertRejected(m -> field(m).put("pattern", "^[a-z]{1,10}$").remove("maxLength"), "requires maxLength");
    }

    @Test
    void openRecordSchemaIsRejected() {
        assertRejected(m -> schema(m).put("additionalProperties", true), "additionalProperties");
    }

    @Test
    void permissionForUndeclaredRoleIsRejected() {
        assertRejected(m -> ((ObjectNode) category(m).path("permissions").path("delete")).put("guest", "all"),
                "undeclared role guest");
    }

    @Test
    void unknownPermissionScopeIsRejected() {
        assertRejected(m -> ((ObjectNode) category(m).path("permissions").path("read")).put("agent", "everything"),
                "read");
    }

    @Test
    void searchOnNonStringFieldIsRejected() {
        assertRejected(m -> category(m).putArray("search").add("active"), "active is not a string field");
    }

    @Test
    void requiredUnknownFieldIsRejected() {
        assertRejected(m -> ((com.fasterxml.jackson.databind.node.ArrayNode) schema(m).path("required")).add("ghost"),
                "unknown field ghost");
    }

    @Test
    void duplicateEntityKeyIsRejectedNotMerged() throws Exception {
        String text = Files.readString(HELPDESK);
        String entity = "\"category\": {";
        String duplicated = text.replace("\"entities\": {", "\"entities\": { \"category\": {\"name\":{\"en\":\"x\"}}, ");
        assertThat(duplicated).contains(entity);
        assertThatThrownBy(() -> ManifestValidator.parse(duplicated.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ManifestValidator.ManifestException.class)
                .hasMessageContaining("duplicate keys");
    }

    @Test
    void recordValidationReportsLocations() throws Exception {
        AppManifest manifest = ManifestValidator.parse(Files.readAllBytes(HELPDESK));
        var schema = ManifestValidator.compileRecordSchema(manifest.entities().get("category").recordSchema());
        var bad = ManifestValidator.STRICT_JSON.readTree("{\"code\":\"lower\",\"name\":\"\",\"active\":\"yes\",\"x\":1}");

        assertThat(ManifestValidator.validateRecord(schema, bad)).isNotEmpty()
                .containsKeys("/code", "/name", "/active");
        assertThat(ManifestValidator.validateRecord(schema, ManifestValidator.STRICT_JSON.readTree(
                "{\"code\":\"NET\",\"name\":\"Network\",\"active\":true}"))).isEmpty();
    }

    private static ObjectNode category(ObjectNode manifest) {
        return (ObjectNode) manifest.path("entities").path("category");
    }

    private static ObjectNode schema(ObjectNode manifest) {
        return (ObjectNode) category(manifest).path("schema");
    }

    private static ObjectNode field(ObjectNode manifest) {
        return (ObjectNode) schema(manifest).path("properties").path("name");
    }

    private static void assertRejected(Consumer<ObjectNode> mutation, String expected) {
        try {
            ObjectNode manifest = (ObjectNode) ManifestValidator.STRICT_JSON.readTree(Files.readAllBytes(HELPDESK));
            mutation.accept(manifest);
            byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> ManifestValidator.parse(bytes))
                    .isInstanceOf(ManifestValidator.ManifestException.class)
                    .hasMessageContaining(expected);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
