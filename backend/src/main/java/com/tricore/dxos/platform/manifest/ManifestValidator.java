// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.manifest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Validates manifests (frame + restricted record schemas) and record data. Manifests are data only:
 * nothing in a manifest is executed, interpolated into SQL or fetched from the network.
 */
public final class ManifestValidator {
    /** Strict parser: duplicate keys (e.g. two entities with the same name) are rejected, not merged. */
    public static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder
                    .schemaRegistryConfig(SchemaRegistryConfig.builder()
                            .formatAssertionsEnabled(true).locale(Locale.ENGLISH).build())
                    .schemaLoader(loader -> loader.fetchRemoteResources(false)));
    private static final Schema MANIFEST_SCHEMA = loadManifestSchema();

    private ManifestValidator() {}

    public static AppManifest parse(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
        JsonNode document;
        try {
            document = STRICT_JSON.readTree(text);
        } catch (JsonProcessingException invalid) {
            throw new ManifestException(List.of("manifest is not valid JSON or has duplicate keys"));
        }
        if (document == null || !document.isObject()) {
            throw new ManifestException(List.of("manifest must be a JSON object"));
        }
        Map<String, String> frameErrors = errors(MANIFEST_SCHEMA, document);
        if (!frameErrors.isEmpty()) {
            throw new ManifestException(frameErrors.entrySet().stream()
                    .map(error -> error.getKey() + ": " + error.getValue()).toList());
        }
        return semantic(document, sha256(text));
    }

    /** Compiles a record schema that already passed the manifest frame validation. */
    public static Schema compileRecordSchema(JsonNode recordSchema) {
        return REGISTRY.getSchema(recordSchema);
    }

    /** Record validation errors keyed by JSON Pointer-like instance location. Empty means valid. */
    public static Map<String, String> validateRecord(Schema schema, JsonNode data) {
        Map<String, String> oversized = new TreeMap<>();
        for (var field : schema.getSchemaNode().path("properties").properties()) {
            JsonNode value = data.path(field.getKey());
            if (field.getValue().has("pattern") && value.isTextual()) {
                String text = value.textValue();
                int max = field.getValue().path("maxLength").asInt();
                if (text.codePointCount(0, text.length()) > max) {
                    oversized.put("/" + field.getKey(), "must have at most " + max + " characters");
                }
            }
        }
        if (!oversized.isEmpty()) return oversized;
        return errors(schema, data);
    }

    private static AppManifest semantic(JsonNode document, String sha256) {
        List<String> problems = new ArrayList<>();
        Set<String> roles = new LinkedHashSet<>();
        document.path("roles").fieldNames().forEachRemaining(roles::add);
        Map<String, AppManifest.Entity> entities = new LinkedHashMap<>();
        for (var entry : document.path("entities").properties()) {
            String key = entry.getKey();
            JsonNode entity = entry.getValue();
            JsonNode schema = entity.path("schema");
            JsonNode properties = schema.path("properties");
            for (JsonNode required : schema.path("required")) {
                if (!properties.has(required.asText())) {
                    problems.add("/entities/" + key + "/schema/required: unknown field " + required.asText());
                }
            }
            for (var field : properties.properties()) {
                JsonNode pattern = field.getValue().path("pattern");
                if (pattern.isTextual()) {
                    String where = "/entities/" + key + "/schema/properties/" + field.getKey();
                    SafePatterns.violation(pattern.asText())
                            .ifPresent(reason -> problems.add(where + "/pattern: unsafe regular expression (" + reason + ")"));
                    if (!field.getValue().path("maxLength").isInt() || field.getValue().path("maxLength").asInt() > 1000) {
                        problems.add(where + ": a pattern requires maxLength <= 1000");
                    }
                }
            }
            List<String> search = new ArrayList<>();
            for (JsonNode field : entity.path("search")) {
                if (!"string".equals(properties.path(field.asText()).path("type").asText())) {
                    problems.add("/entities/" + key + "/search: " + field.asText() + " is not a string field");
                }
                search.add(field.asText());
            }
            Map<AppManifest.Operation, Map<String, AppManifest.Scope>> permissions =
                    new EnumMap<>(AppManifest.Operation.class);
            for (AppManifest.Operation operation : AppManifest.Operation.values()) {
                String name = operation.name().toLowerCase(Locale.ROOT);
                Map<String, AppManifest.Scope> grants = new LinkedHashMap<>();
                for (var grant : entity.path("permissions").path(name).properties()) {
                    if (!roles.contains(grant.getKey())) {
                        problems.add("/entities/" + key + "/permissions/" + name + ": undeclared role " + grant.getKey());
                    }
                    grants.put(grant.getKey(), AppManifest.Scope.valueOf(grant.getValue().asText().toUpperCase(Locale.ROOT)));
                }
                permissions.put(operation, Map.copyOf(grants));
            }
            try {
                compileRecordSchema(schema);
            } catch (RuntimeException invalid) {
                problems.add("/entities/" + key + "/schema: cannot be compiled");
            }
            Set<String> sensitive = new LinkedHashSet<>();
            for (var field : properties.properties()) {
                if (field.getValue().path("sensitive").asBoolean(false)) sensitive.add(field.getKey());
            }
            AppManifest.Workflow workflow = workflow(app(document), key, entity.path("workflow"), roles, problems);
            entities.put(key, new AppManifest.Entity(key, schema.deepCopy(), List.copyOf(search),
                    Set.copyOf(sensitive), Map.copyOf(permissions), workflow));
        }
        if (!problems.isEmpty()) throw new ManifestException(problems);
        return new AppManifest(document.path("id").asText(), document.path("version").asText(), sha256,
                document.deepCopy(), Set.copyOf(roles), java.util.Collections.unmodifiableMap(entities));
    }

    private static String app(JsonNode document) {
        return document.path("id").asText();
    }

    /** Builds the reused core definition (which re-validates structure) plus Platform grants; null when absent. */
    private static AppManifest.Workflow workflow(String appId, String entityKey, JsonNode node, Set<String> roles,
                                                 List<String> problems) {
        if (node.isMissingNode()) return null;
        String where = "/entities/" + entityKey + "/workflow";
        Set<String> states = new LinkedHashSet<>();
        Map<String, Boolean> editable = new LinkedHashMap<>();
        for (var state : node.path("states").properties()) {
            states.add(state.getKey());
            editable.put(state.getKey(), state.getValue().path("editable").asBoolean(true));
        }
        Set<com.tricore.dxos.core.workflow.WorkflowTransition> transitions = new LinkedHashSet<>();
        Set<String> notByCreator = new LinkedHashSet<>();
        Map<String, Map<String, AppManifest.Scope>> grants = new LinkedHashMap<>();
        for (var transition : node.path("transitions").properties()) {
            String from = transition.getValue().path("from").asText();
            String to = transition.getValue().path("to").asText();
            if (!states.contains(from) || !states.contains(to)) {
                problems.add(where + "/transitions/" + transition.getKey() + ": unknown state");
                continue;
            }
            if (from.equals(to)) problems.add(where + "/transitions/" + transition.getKey() + ": from and to must differ");
            Map<String, AppManifest.Scope> roleScopes = new LinkedHashMap<>();
            for (var grant : transition.getValue().path("roles").properties()) {
                if (!roles.contains(grant.getKey())) {
                    problems.add(where + "/transitions/" + transition.getKey() + "/roles: undeclared role " + grant.getKey());
                }
                roleScopes.put(grant.getKey(), AppManifest.Scope.valueOf(grant.getValue().asText().toUpperCase(Locale.ROOT)));
            }
            if (roleScopes.isEmpty()) problems.add(where + "/transitions/" + transition.getKey() + "/roles: at least one role is required");
            grants.put(transition.getKey(), Map.copyOf(roleScopes));
            if (transition.getValue().path("notByCreator").asBoolean()) {
                notByCreator.add(transition.getKey());
            }
            transitions.add(new com.tricore.dxos.core.workflow.WorkflowTransition(transition.getKey(), from, to));
        }
        String initial = node.path("initialState").asText();
        if (!states.contains(initial)) {
            problems.add(where + "/initialState: unknown state");
            return null;
        }
        com.tricore.dxos.core.workflow.WorkflowDefinition definition;
        try {
            definition = new com.tricore.dxos.core.workflow.WorkflowDefinition(appId + "/" + entityKey, 1, initial, states, transitions);
        } catch (RuntimeException invalid) {
            problems.add(where + ": invalid state machine");
            return null;
        }
        if (definition.isTerminal(initial)) problems.add(where + "/initialState: must have an outgoing transition");
        Set<String> reachable = new LinkedHashSet<>(Set.of(initial));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (var transition : transitions) {
                if (reachable.contains(transition.sourceState()) && reachable.add(transition.targetState())) grew = true;
            }
        }
        for (String state : states) {
            if (!reachable.contains(state)) problems.add(where + "/states/" + state + ": unreachable from the initial state");
        }
        return new AppManifest.Workflow(definition, Map.copyOf(editable), Map.copyOf(grants), Set.copyOf(notByCreator));
    }

    private static Map<String, String> errors(Schema schema, JsonNode instance) {
        Map<String, String> result = new TreeMap<>();
        for (var error : schema.validate(instance)) {
            String location = error.getInstanceLocation().toString();
            result.merge(location.isEmpty() || location.equals("$") ? "/" : location, error.getMessage(),
                    (first, second) -> first);
        }
        return result;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Schema loadManifestSchema() {
        try (InputStream input = ManifestValidator.class.getResourceAsStream("/platform/app-manifest.schema.json")) {
            if (input == null) throw new IllegalStateException("Manifest schema resource missing");
            return REGISTRY.getSchema(STRICT_JSON.readTree(input));
        } catch (IOException failure) {
            throw new IllegalStateException("Manifest schema unreadable", failure);
        }
    }

    /** Safe rejection: messages carry JSON locations and rule names, never file system paths or SQL. */
    public static final class ManifestException extends RuntimeException {
        private final List<String> problems;

        public ManifestException(List<String> problems) {
            super("Invalid application manifest: " + String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }
}
