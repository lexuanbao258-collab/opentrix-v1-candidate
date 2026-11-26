// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.records;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tricore.dxos.core.workflow.ActorReference;
import com.tricore.dxos.core.workflow.ResourceReference;
import com.tricore.dxos.core.workflow.TransitionCommand;
import com.tricore.dxos.core.workflow.WorkflowException;
import com.tricore.dxos.core.workflow.WorkflowInstance;
import com.tricore.dxos.core.workflow.WorkflowLifecycle;
import com.tricore.dxos.platform.manifest.AppManifest;
import com.tricore.dxos.platform.manifest.AppManifest.Operation;
import com.tricore.dxos.platform.manifest.AppManifest.Scope;
import com.tricore.dxos.platform.manifest.ManifestValidator;
import com.tricore.dxos.platform.registry.PlatformRegistry;
import com.tricore.dxos.platform.records.RecordRepository.AuditEvent;
import com.tricore.dxos.platform.security.PlatformCaller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Generic record and workflow use cases.
 *
 * <p>Order of checks for every call: verified caller; application exists AND caller holds a declared role in it,
 * else 404 (no existence leak); entity exists, else 404; operation granted, else 403; record visible
 * ('own' scope = created by the caller), else 404; state/transition rules; schema validation; then the write and
 * its audit event in ONE database transaction ({@link PlatformTransactions}: any exception rolls both back).
 *
 * <p>Single source of truth: the workflow state is a column of the record row and shares the record's version
 * counter. Only {@link #transition} writes it; PUT/DELETE never touch it. The state machine itself is the reused
 * core {@link WorkflowInstance}; this class never retries a write.
 */
@Service
@ConditionalOnProperty(name = "opentrix.platform.enabled", havingValue = "true")
public class RecordService {
    public record Page(List<RecordRepository.StoredRecord> items, long total, int limit, int offset) {}

    public record EntityView(AppManifest app, AppManifest.Entity entity, Set<String> callerRoles,
                             Map<Operation, Scope> allowed) {}

    public record AppView(AppManifest app, Set<String> callerRoles) {}

    public record Action(String id, String to) {}

    public record WorkflowView(String state, long version, boolean terminal, boolean editable, List<Action> allowedActions) {}

    public record AuditPage(List<RecordRepository.AuditRow> items, long total, int limit, int offset) {}

    static final String REDACTED = "[REDACTED]";

    private final PlatformRegistry registry;
    private final PlatformTransactions tx;
    private final PlatformCaller callers;
    private final RecordRepository records;
    private final String tenantId;
    private final Clock clock = Clock.systemUTC();

    public RecordService(PlatformRegistry registry, PlatformTransactions tx, PlatformCaller callers, RecordRepository records,
                         @Value("${opentrix.platform.tenant-id:default}") String tenantId) {
        if (!tenantId.matches("[a-z][a-z0-9-]{0,62}")) throw new IllegalArgumentException("Invalid tenant id");
        this.registry = registry;
        this.tx = tx;
        this.callers = callers;
        this.records = records;
        this.tenantId = tenantId;
    }

    // ---- discovery ----------------------------------------------------------------------------------------

    /** Applications in which the caller holds at least one declared role. */
    @Transactional(readOnly = true)
    public List<AppView> applications() {
        var caller = callers.require();
        return registry.applicationIds().stream()
                .map(registry::application).flatMap(java.util.Optional::stream)
                .map(app -> new AppView(app, declaredRoles(app, caller)))
                .filter(view -> !view.callerRoles().isEmpty())
                .toList();
    }

    @Transactional(readOnly = true)
    public AppView application(String appId) {
        var caller = callers.require();
        AppManifest app = registry.application(appId).orElseThrow(PlatformException::notFound);
        Set<String> roles = declaredRoles(app, caller);
        if (roles.isEmpty()) throw PlatformException.notFound();
        return new AppView(app, roles);
    }

    @Transactional(readOnly = true)
    public EntityView entity(String appId, String entityKey) {
        return resolve(appId, entityKey);
    }

    // ---- records ------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page search(String appId, String entityKey, String query, int limit, int offset) {
        var view = resolve(appId, entityKey);
        Scope scope = require(view, Operation.READ);
        if (query != null && view.entity().searchFields().isEmpty()) {
            throw validation("q", "this entity declares no search fields");
        }
        String owner = scope == Scope.OWN ? callers.require().subjectId() : null;
        var where = scope(view);
        List<String> fields = view.entity().searchFields();
        return new Page(records.search(where, owner, fields, query, limit, offset),
                records.count(where, owner, fields, query), limit, offset);
    }

    @Transactional(readOnly = true)
    public RecordRepository.StoredRecord get(String appId, String entityKey, UUID id) {
        var view = resolve(appId, entityKey);
        return visible(view, id);
    }

    public RecordRepository.StoredRecord create(String appId, String entityKey, JsonNode data) {
        return tx.write(() -> doCreate(appId, entityKey, data));
    }

    private RecordRepository.StoredRecord doCreate(String appId, String entityKey, JsonNode data) {
        var view = resolve(appId, entityKey);
        require(view, Operation.CREATE);
        validate(view, data);
        var caller = callers.require();
        String initial = view.entity().workflow() == null ? null : view.entity().workflow().definition().initialState();
        var created = records.insert(scope(view), UUID.randomUUID(), data, initial, caller.subjectId());
        records.audit(scope(view), created.id(), new AuditEvent("CREATE", caller.subjectId(), caller.machine(),
                created.version(), null, null, initial, null, redact(view.entity(), created.data())));
        return created;
    }

    public RecordRepository.StoredRecord update(String appId, String entityKey, UUID id, long expectedVersion,
                                                JsonNode data) {
        return tx.write(() -> doUpdate(appId, entityKey, id, expectedVersion, data));
    }

    private RecordRepository.StoredRecord doUpdate(String appId, String entityKey, UUID id, long expectedVersion,
                                                   JsonNode data) {
        var view = resolve(appId, entityKey);
        var current = lockWritable(view, id, Operation.UPDATE);
        requireEditable(view, current);
        validate(view, data);
        if (current.version() != expectedVersion) throw conflict();
        var caller = callers.require();
        var updated = records.update(scope(view), id, data, caller.subjectId());
        records.audit(scope(view), id, new AuditEvent("UPDATE", caller.subjectId(), caller.machine(), updated.version(),
                null, null, null, redact(view.entity(), current.data()), redact(view.entity(), updated.data())));
        return updated;
    }

    public void delete(String appId, String entityKey, UUID id, long expectedVersion) {
        tx.write(() -> {
            doDelete(appId, entityKey, id, expectedVersion);
            return null;
        });
    }

    private void doDelete(String appId, String entityKey, UUID id, long expectedVersion) {
        var view = resolve(appId, entityKey);
        var current = lockWritable(view, id, Operation.DELETE);
        requireEditable(view, current);
        if (current.version() != expectedVersion) throw conflict();
        var caller = callers.require();
        records.delete(scope(view), id);
        records.audit(scope(view), id, new AuditEvent("DELETE", caller.subjectId(), caller.machine(), current.version(),
                null, null, null, redact(view.entity(), current.data()), null));
    }

    // ---- workflow -----------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public WorkflowView workflow(String appId, String entityKey, UUID id) {
        var view = resolve(appId, entityKey);
        var workflow = requireWorkflow(view);
        var current = visible(view, id);
        return workflowView(view, workflow, current);
    }

    public RecordRepository.StoredRecord transition(String appId, String entityKey, UUID id, String transitionId,
                                                    long expectedVersion) {
        return tx.write(() -> doTransition(appId, entityKey, id, transitionId, expectedVersion));
    }

    private RecordRepository.StoredRecord doTransition(String appId, String entityKey, UUID id, String transitionId,
                                                       long expectedVersion) {
        var view = resolve(appId, entityKey);
        var workflow = requireWorkflow(view);
        var caller = callers.require();
        var current = lockVisible(view, id);
        if (!workflow.transitionRoles().containsKey(transitionId)) {
            throw new PlatformException(PlatformException.Code.NOT_FOUND, "Unknown transition", Map.of());
        }
        Scope granted = workflow.scopeFor(transitionId, view.callerRoles()).orElseThrow(PlatformException::forbidden);
        if (granted == Scope.OWN && !current.createdBy().equals(caller.subjectId())) throw PlatformException.forbidden();

        // The reused domain decides version, source state and terminal-state rules; it owns no persistence here.
        var definition = workflow.definition();
        var instance = WorkflowInstance.restore(id.toString(), definition,
                new ResourceReference(view.app().id() + "/" + view.entity().key(), id.toString()), current.state(),
                current.version(), definition.isTerminal(current.state()) ? WorkflowLifecycle.COMPLETED : WorkflowLifecycle.ACTIVE,
                Instant.EPOCH, Instant.EPOCH);
        var result = tryTransition(instance, definition, new TransitionCommand(id.toString(), transitionId,
                new ActorReference(caller.subjectId()), expectedVersion));

        var updated = records.updateState(scope(view), id, result.instance().currentState(), caller.subjectId());
        if (updated.version() != result.instance().runtimeVersion()) {
            throw new IllegalStateException("Workflow version diverged from the record version");
        }
        records.audit(scope(view), id, new AuditEvent("TRANSITION", caller.subjectId(), caller.machine(),
                updated.version(), transitionId, result.record().sourceState(), result.record().targetState(), null, null));
        return updated;
    }

    @Transactional(readOnly = true)
    public List<RecordRepository.AuditRow> history(String appId, String entityKey, UUID id) {
        var view = resolve(appId, entityKey);
        requireWorkflow(view);
        visible(view, id);
        return records.workflowHistory(scope(view), id);
    }

    // ---- audit --------------------------------------------------------------------------------------------

    /** Entity-wide audit. Needs the manifest's 'audit' grant; other apps and entities are unreachable by construction. */
    @Transactional(readOnly = true)
    public AuditPage audit(String appId, String entityKey, UUID recordIdOrNull, int limit, int offset) {
        var view = resolve(appId, entityKey);
        require(view, Operation.AUDIT);
        var where = scope(view);
        return new AuditPage(records.audit(where, recordIdOrNull, limit, offset),
                records.auditCount(where, recordIdOrNull), limit, offset);
    }

    // ---- internals ----------------------------------------------------------------------------------------

    private static com.tricore.dxos.core.workflow.TransitionResult tryTransition(
            WorkflowInstance instance, com.tricore.dxos.core.workflow.WorkflowDefinition definition, TransitionCommand command) {
        try {
            return instance.transition(definition, command, Instant.now());
        } catch (WorkflowException failure) {
            switch (failure.code()) {
                case VERSION_CONFLICT -> throw conflict();
                case TRANSITION_NOT_ALLOWED, INSTANCE_COMPLETED -> throw new PlatformException(
                        PlatformException.Code.TRANSITION_NOT_ALLOWED,
                        "Transition is not allowed in the record's current state", Map.of());
                default -> throw new IllegalStateException("Workflow rejected the transition: " + failure.code());
            }
        }
    }

    private WorkflowView workflowView(EntityView view, AppManifest.Workflow workflow, RecordRepository.StoredRecord current) {
        String subject = callers.require().subjectId();
        boolean owner = current.createdBy().equals(subject);
        List<Action> actions = new ArrayList<>();
        for (var transition : workflow.definition().transitions()) {
            if (!transition.sourceState().equals(current.state())) continue;
            var scope = workflow.scopeFor(transition.transitionId(), view.callerRoles());
            if (scope.isPresent() && (scope.get() == Scope.ALL || owner)) {
                actions.add(new Action(transition.transitionId(), transition.targetState()));
            }
        }
        actions.sort(java.util.Comparator.comparing(Action::id));
        return new WorkflowView(current.state(), current.version(), workflow.definition().isTerminal(current.state()),
                workflow.isEditable(current.state()), List.copyOf(actions));
    }

    private static AppManifest.Workflow requireWorkflow(EntityView view) {
        if (view.entity().workflow() == null) throw PlatformException.notFound();
        return view.entity().workflow();
    }

    private static void requireEditable(EntityView view, RecordRepository.StoredRecord current) {
        var workflow = view.entity().workflow();
        if (workflow != null && !workflow.isEditable(current.state())) {
            throw new PlatformException(PlatformException.Code.STATE_LOCKED,
                    "Record cannot be changed or deleted in state " + current.state(), Map.of());
        }
    }

    /** Record readable by the caller (404 otherwise). */
    private RecordRepository.StoredRecord visible(EntityView view, UUID id) {
        Scope scope = require(view, Operation.READ);
        String owner = scope == Scope.OWN ? callers.require().subjectId() : null;
        return records.find(scope(view), id, owner).orElseThrow(PlatformException::notFound);
    }

    /** Row-locked, readable record: serializes concurrent writers. */
    private RecordRepository.StoredRecord lockVisible(EntityView view, UUID id) {
        Scope read = view.allowed().get(Operation.READ);
        if (read == null) throw PlatformException.notFound();
        String owner = read == Scope.OWN ? callers.require().subjectId() : null;
        return records.findForUpdate(scope(view), id, owner).orElseThrow(PlatformException::notFound);
    }

    /** Write access: caller must be able to see the record (else 404) and hold the write scope (else 403). */
    private RecordRepository.StoredRecord lockWritable(EntityView view, UUID id, Operation operation) {
        String subject = callers.require().subjectId();
        Scope write = view.allowed().get(operation);
        var current = lockVisible(view, id);
        boolean owner = current.createdBy().equals(subject);
        if (write == null || (write == Scope.OWN && !owner)) throw PlatformException.forbidden();
        return current;
    }

    private EntityView resolve(String appId, String entityKey) {
        var caller = callers.require();
        AppManifest app = registry.application(appId).orElseThrow(PlatformException::notFound);
        Set<String> roles = declaredRoles(app, caller);
        if (roles.isEmpty()) throw PlatformException.notFound();
        AppManifest.Entity entity = app.entities().get(entityKey);
        if (entity == null) throw PlatformException.notFound();
        Map<Operation, Scope> allowed = new java.util.EnumMap<>(Operation.class);
        for (Operation operation : Operation.values()) {
            entity.scopeFor(operation, roles).ifPresent(scope -> allowed.put(operation, scope));
        }
        return new EntityView(app, entity, roles, Map.copyOf(allowed));
    }

    private static Set<String> declaredRoles(AppManifest app, PlatformCaller.Caller caller) {
        Set<String> roles = new java.util.HashSet<>(caller.rolesIn(app.id()));
        roles.retainAll(app.roles());
        return Set.copyOf(roles);
    }

    private static Scope require(EntityView view, Operation operation) {
        Scope scope = view.allowed().get(operation);
        if (scope == null) throw PlatformException.forbidden();
        return scope;
    }

    private void validate(EntityView view, JsonNode data) {
        if (data == null || !data.isObject()) throw validation("data", "must be a JSON object");
        var errors = ManifestValidator.validateRecord(registry.recordSchema(view.app(), view.entity()), data);
        if (!errors.isEmpty()) {
            throw new PlatformException(PlatformException.Code.VALIDATION_FAILED, "Record is invalid", errors);
        }
    }

    /** Audit never stores values of fields the manifest marks sensitive. */
    static JsonNode redact(AppManifest.Entity entity, JsonNode data) {
        if (data == null || entity.sensitiveFields().isEmpty()) return data;
        ObjectNode copy = data.deepCopy();
        for (String field : entity.sensitiveFields()) {
            if (copy.has(field)) copy.put(field, REDACTED);
        }
        return copy;
    }

    private RecordRepository.Scope scope(EntityView view) {
        return new RecordRepository.Scope(tenantId, view.app().id(), view.entity().key());
    }

    private static PlatformException conflict() {
        return new PlatformException(PlatformException.Code.VERSION_CONFLICT,
                "Record was changed by another request; reload and retry", Map.of());
    }

    private static PlatformException validation(String field, String message) {
        return new PlatformException(PlatformException.Code.VALIDATION_FAILED, "Request is invalid", Map.of(field, message));
    }
}
