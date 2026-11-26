// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.tricore.dxos.common.error.ApiError;
import com.tricore.dxos.platform.records.PlatformException;
import com.tricore.dxos.platform.manifest.AppManifest;
import com.tricore.dxos.platform.records.RecordRepository.StoredRecord;
import com.tricore.dxos.platform.records.RecordService;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
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
@OpenAPIDefinition(info = @Info(title = "OpenTrix Platform API", version = "v1"))
@SecurityRequirement(name = "bearer")
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
@ConditionalOnProperty(name = "opentrix.platform.enabled", havingValue = "true")
public class PlatformController {
    private final RecordService service;

    public PlatformController(RecordService service) {
        this.service = service;
    }

    public record EntitySummary(String key, Map<String, String> name) {}

    public record AppResponse(String id, String version, Map<String, String> name, Map<String, String> description,
                              List<String> callerRoles, List<EntitySummary> entities) {}

    public record StateSummary(String id, Map<String, String> name, boolean editable, boolean terminal) {}

    public record TransitionSummary(String id, String from, String to, Map<String, String> name, boolean allowedForCaller) {}

    public record WorkflowSummary(String initialState, List<StateSummary> states, List<TransitionSummary> transitions) {}

    public record EntityResponse(String app, String key, Map<String, String> name, JsonNode schema,
                                 List<String> searchFields, Map<String, String> allowedOperations,
                                 WorkflowSummary workflow) {}

    public record RecordResponse(UUID id, String app, String entity, JsonNode data, long version, String state,
                                 String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {}

    public record ActionResponse(String id, String to) {}

    public record WorkflowResponse(String state, long version, boolean terminal, boolean editable,
                                   List<ActionResponse> allowedActions) {}

    public record HistoryItem(String action, String transitionId, String fromState, String toState, String actorId,
                              String actorType, long recordVersion, Instant occurredAt) {}

    public record AuditItem(long eventId, Instant occurredAt, UUID recordId, String action, String actorId,
                            String actorType, long recordVersion, String transitionId, String fromState, String toState,
                            JsonNode before, JsonNode after) {}

    public record AuditPageResponse(List<AuditItem> items, long total, int limit, int offset) {}

    public record TransitionRequest(
            @Schema(implementation = Long.class, minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
            JsonNode expectedVersion) {
        @JsonAnySetter public void unknown(String field, Object value) { throw new IllegalArgumentException("Unknown field"); }
    }

    public record PageResponse(List<RecordResponse> items, long total, int limit, int offset) {}

    public record CreateRecordRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) JsonNode data) {
        @JsonAnySetter public void unknown(String field, Object value) { throw new IllegalArgumentException("Unknown field"); }
    }

    public record UpdateRecordRequest(
            @Schema(implementation = Long.class, minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
            JsonNode expectedVersion, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) JsonNode data) {
        @JsonAnySetter public void unknown(String field, Object value) { throw new IllegalArgumentException("Unknown field"); }
    }

    @Operation(summary = "List applications in which the caller holds a role")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<AppResponse> applications() {
        return service.applications().stream().map(PlatformController::app).toList();
    }

    @Operation(summary = "Read one application (404 when the caller holds no role in it)")
    @GetMapping("/{appId}")
    @ResponseStatus(HttpStatus.OK)
    public AppResponse application(@PathVariable String appId) {
        return app(service.application(appId));
    }

    @Operation(summary = "Read an entity's record schema and the caller's allowed operations")
    @GetMapping("/{appId}/entities/{entity}")
    @ResponseStatus(HttpStatus.OK)
    public EntityResponse entity(@PathVariable String appId, @PathVariable String entity) {
        var view = service.entity(appId, entity);
        Map<String, String> allowed = new TreeMap<>();
        view.allowed().forEach((operation, scope) ->
                allowed.put(operation.name().toLowerCase(Locale.ROOT), scope.name().toLowerCase(Locale.ROOT)));
        return new EntityResponse(view.app().id(), view.entity().key(),
                text(view.app().document().path("entities").path(view.entity().key()).path("name")),
                view.entity().recordSchema(), view.entity().searchFields(), allowed, workflow(view));
    }

    @Operation(summary = "Search and page records the caller may read")
    @GetMapping("/{appId}/entities/{entity}/records")
    @ResponseStatus(HttpStatus.OK)
    public PageResponse search(@PathVariable String appId, @PathVariable String entity,
                               @RequestParam(required = false) @Size(min = 1, max = 100) String q,
                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                               @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        var page = service.search(appId, entity, q, limit, offset);
        return new PageResponse(page.items().stream().map(item -> record(appId, entity, item)).toList(),
                page.total(), page.limit(), page.offset());
    }

    @Operation(summary = "Create a record; validated against the entity schema; audited",
            description = "Invalid data returns 400. If a commit cannot be confirmed, 503 COMMIT_OUTCOME_UNKNOWN"
                    + " requires reconciliation before another write.")
    @PostMapping("/{appId}/entities/{entity}/records")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<RecordResponse> create(@PathVariable String appId, @PathVariable String entity,
                                                 @RequestBody CreateRecordRequest input) {
        var created = record(appId, entity, service.create(appId, entity, input.data()));
        return ResponseEntity.created(URI.create("/api/v1/apps/" + appId + "/entities/" + entity
                + "/records/" + created.id())).body(created);
    }

    @Operation(summary = "Read one record")
    @GetMapping("/{appId}/entities/{entity}/records/{id}")
    @ResponseStatus(HttpStatus.OK)
    public RecordResponse get(@PathVariable String appId, @PathVariable String entity, @PathVariable UUID id) {
        return record(appId, entity, service.get(appId, entity, id));
    }

    @Operation(summary = "Replace a record's data; expectedVersion must match (409 otherwise); audited")
    @ApiResponse(responseCode = "409", description = "VERSION_CONFLICT or STATE_LOCKED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/{appId}/entities/{entity}/records/{id}")
    @ResponseStatus(HttpStatus.OK)
    public RecordResponse update(@PathVariable String appId, @PathVariable String entity, @PathVariable UUID id,
                                 @RequestBody UpdateRecordRequest input) {
        return record(appId, entity, service.update(appId, entity, id, exactVersion(input.expectedVersion()), input.data()));
    }

    @Operation(summary = "Delete a record; expectedVersion must match (409 otherwise); audited")
    @ApiResponse(responseCode = "409", description = "VERSION_CONFLICT or STATE_LOCKED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @DeleteMapping("/{appId}/entities/{entity}/records/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> delete(@PathVariable String appId, @PathVariable String entity, @PathVariable UUID id,
                                       @RequestParam @PositiveOrZero long expectedVersion) {
        service.delete(appId, entity, id, expectedVersion);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Workflow state of a record and the transitions the caller may run now")
    @GetMapping("/{appId}/entities/{entity}/records/{id}/workflow")
    @ResponseStatus(HttpStatus.OK)
    public WorkflowResponse workflowOf(@PathVariable String appId, @PathVariable String entity, @PathVariable UUID id) {
        var view = service.workflow(appId, entity, id);
        return new WorkflowResponse(view.state(), view.version(), view.terminal(), view.editable(),
                view.allowedActions().stream().map(action -> new ActionResponse(action.id(), action.to())).toList());
    }

    @Operation(summary = "Run a declared workflow transition and audit it in the same transaction",
            description = "Visibility and transition role scopes apply. An unknown transition or hidden record returns 404."
                    + " A caller lacking the transition grant for a visible record receives 403.")
    @ApiResponse(responseCode = "409", description = "VERSION_CONFLICT or TRANSITION_NOT_ALLOWED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "STORE_UNAVAILABLE or COMMIT_OUTCOME_UNKNOWN"
            + " (read to reconcile before retrying)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{appId}/entities/{entity}/records/{id}/transitions/{transition}")
    @ResponseStatus(HttpStatus.OK)
    public RecordResponse transition(@PathVariable String appId, @PathVariable String entity, @PathVariable UUID id,
                                     @PathVariable String transition, @RequestBody TransitionRequest input) {
        return record(appId, entity, service.transition(appId, entity, id, transition, exactVersion(input.expectedVersion())));
    }

    private static long exactVersion(JsonNode version) {
        if (version == null || !version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0) {
            throw new PlatformException(PlatformException.Code.VALIDATION_FAILED, "Request is invalid",
                    Map.of("expectedVersion", "must be a non-negative 64-bit integer"));
        }
        return version.longValue();
    }

    @Operation(summary = "Workflow history of a record (creation state and transitions); needs read access to the record")
    @GetMapping("/{appId}/entities/{entity}/records/{id}/history")
    @ResponseStatus(HttpStatus.OK)
    public List<HistoryItem> history(@PathVariable String appId, @PathVariable String entity, @PathVariable UUID id) {
        return service.history(appId, entity, id).stream()
                .map(row -> new HistoryItem(row.action(), row.transitionId(), row.fromState(), row.toState(), row.actorId(),
                        row.actorType(), row.recordVersion(), row.occurredAt())).toList();
    }

    @Operation(summary = "Entity audit trail (needs the manifest audit grant); values of sensitive fields are redacted")
    @GetMapping("/{appId}/entities/{entity}/audit")
    @ResponseStatus(HttpStatus.OK)
    public AuditPageResponse audit(@PathVariable String appId, @PathVariable String entity,
                                   @RequestParam(required = false) UUID recordId,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                                   @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset) {
        var page = service.audit(appId, entity, recordId, limit, offset);
        return new AuditPageResponse(page.items().stream().map(row -> new AuditItem(row.eventId(), row.occurredAt(),
                row.recordId(), row.action(), row.actorId(), row.actorType(), row.recordVersion(), row.transitionId(),
                row.fromState(), row.toState(), row.before(), row.after())).toList(), page.total(), page.limit(), page.offset());
    }

    private static WorkflowSummary workflow(RecordService.EntityView view) {
        var workflow = view.entity().workflow();
        if (workflow == null) return null;
        JsonNode node = view.app().document().path("entities").path(view.entity().key()).path("workflow");
        List<StateSummary> states = workflow.definition().states().stream().sorted()
                .map(state -> new StateSummary(state, text(node.path("states").path(state).path("name")),
                        workflow.isEditable(state), workflow.definition().isTerminal(state))).toList();
        List<TransitionSummary> transitions = workflow.definition().transitions().stream()
                .sorted(java.util.Comparator.comparing(com.tricore.dxos.core.workflow.WorkflowTransition::transitionId))
                .map(transition -> new TransitionSummary(transition.transitionId(), transition.sourceState(),
                        transition.targetState(), text(node.path("transitions").path(transition.transitionId()).path("name")),
                        workflow.scopeFor(transition.transitionId(), view.callerRoles()).isPresent())).toList();
        return new WorkflowSummary(workflow.definition().initialState(), states, transitions);
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
        return new RecordResponse(stored.id(), appId, entity, stored.data(), stored.version(), stored.state(),
                stored.createdBy(), stored.createdAt(), stored.updatedBy(), stored.updatedAt());
    }

    private static Map<String, String> text(JsonNode localized) {
        Map<String, String> result = new TreeMap<>();
        localized.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        return result;
    }
}
