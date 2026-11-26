// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.tricore.dxos.platform.manifest.AppManifest;
import com.tricore.dxos.platform.records.RecordRepository.StoredRecord;
import com.tricore.dxos.platform.records.RecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Public Platform API v1: application discovery and generic records. Thin HTTP adapter over {@link RecordService}. */
@RestController
@RequestMapping("/api/v1/apps")
@Tag(name = "Platform")
@SecurityRequirement(name = "bearer")
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@ConditionalOnProperty(name = "opentrix.platform.enabled", havingValue = "true")
public class PlatformController {
    private final RecordService service;

    public PlatformController(RecordService service) {
        this.service = service;
    }

    public record EntitySummary(String key, Map<String, String> name) {}

    public record AppResponse(String id, String version, Map<String, String> name, Map<String, String> description,
                              List<String> callerRoles, List<EntitySummary> entities) {}

    public record EntityResponse(String app, String key, Map<String, String> name, JsonNode schema,
                                 List<String> searchFields, Map<String, String> allowedOperations) {}

    public record RecordResponse(UUID id, String app, String entity, JsonNode data, long version,
                                 String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {}

    public record PageResponse(List<RecordResponse> items, long total, int limit, int offset) {}

    public record CreateRecordRequest(JsonNode data) {
        @JsonAnySetter public void unknown(String field, Object value) { throw new IllegalArgumentException("Unknown field"); }
    }

    public record UpdateRecordRequest(@NotNull @PositiveOrZero Long expectedVersion, JsonNode data) {
        @JsonAnySetter public void unknown(String field, Object value) { throw new IllegalArgumentException("Unknown field"); }
    }

    @Operation(summary = "List applications in which the caller holds a role")
    @GetMapping
    public List<AppResponse> applications() {
        return service.applications().stream().map(PlatformController::app).toList();
    }

    @Operation(summary = "Read one application (404 when the caller holds no role in it)")
    @GetMapping("/{appId}")
    public AppResponse application(@PathVariable String appId) {
        return app(service.application(appId));
    }

    @Operation(summary = "Read an entity's record schema and the caller's allowed operations")
    @GetMapping("/{appId}/entities/{entity}")
    public EntityResponse entity(@PathVariable String appId, @PathVariable String entity) {
        var view = service.entity(appId, entity);
        Map<String, String> allowed = new TreeMap<>();
        view.allowed().forEach((operation, scope) ->
                allowed.put(operation.name().toLowerCase(Locale.ROOT), scope.name().toLowerCase(Locale.ROOT)));
        return new EntityResponse(view.app().id(), view.entity().key(),
                text(view.app().document().path("entities").path(view.entity().key()).path("name")),
                view.entity().recordSchema(), view.entity().searchFields(), allowed);
    }

    @Operation(summary = "Search and page records the caller may read")
    @GetMapping("/{appId}/entities/{entity}/records")
    public PageResponse search(@PathVariable String appId, @PathVariable String entity,
                               @RequestParam(required = false) @Size(min = 1, max = 100) String q,
                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                               @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        var page = service.search(appId, entity, q, limit, offset);
        return new PageResponse(page.items().stream().map(item -> record(appId, entity, item)).toList(),
                page.total(), page.limit(), page.offset());
    }

    @Operation(summary = "Create a record; validated against the entity schema; audited")
    @PostMapping("/{appId}/entities/{entity}/records")
    public ResponseEntity<RecordResponse> create(@PathVariable String appId, @PathVariable String entity,
                                                 @RequestBody CreateRecordRequest input) {
        var created = record(appId, entity, service.create(appId, entity, input.data()));
        return ResponseEntity.created(URI.create("/api/v1/apps/" + appId + "/entities/" + entity
                + "/records/" + created.id())).body(created);
    }

    @Operation(summary = "Read one record")
    @GetMapping("/{appId}/entities/{entity}/records/{id}")
    public RecordResponse get(@PathVariable String appId, @PathVariable String entity, @PathVariable UUID id) {
        return record(appId, entity, service.get(appId, entity, id));
    }

    @Operation(summary = "Replace a record's data; expectedVersion must match (409 otherwise); audited")
    @PutMapping("/{appId}/entities/{entity}/records/{id}")
    public RecordResponse update(@PathVariable String appId, @PathVariable String entity, @PathVariable UUID id,
                                 @RequestBody @Valid UpdateRecordRequest input) {
        return record(appId, entity, service.update(appId, entity, id, input.expectedVersion(), input.data()));
    }

    @Operation(summary = "Delete a record; expectedVersion must match (409 otherwise); audited")
    @DeleteMapping("/{appId}/entities/{entity}/records/{id}")
    public ResponseEntity<Void> delete(@PathVariable String appId, @PathVariable String entity, @PathVariable UUID id,
                                       @RequestParam @PositiveOrZero long expectedVersion) {
        service.delete(appId, entity, id, expectedVersion);
        return ResponseEntity.noContent().build();
    }

    private static AppResponse app(RecordService.AppView view) {
        AppManifest app = view.app();
        List<EntitySummary> entities = app.entities().keySet().stream()
                .map(key -> new EntitySummary(key, text(app.document().path("entities").path(key).path("name"))))
                .toList();
        return new AppResponse(app.id(), app.version(), text(app.document().path("name")),
                text(app.document().path("description")), view.callerRoles().stream().sorted().toList(), entities);
    }

    private static RecordResponse record(String appId, String entity, StoredRecord stored) {
        return new RecordResponse(stored.id(), appId, entity, stored.data(), stored.version(), stored.createdBy(),
                stored.createdAt(), stored.updatedBy(), stored.updatedAt());
    }

    private static Map<String, String> text(JsonNode localized) {
        Map<String, String> result = new TreeMap<>();
        localized.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        return result;
    }
}
