// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.records;

import com.fasterxml.jackson.databind.JsonNode;
import com.tricore.dxos.platform.manifest.AppManifest;
import com.tricore.dxos.platform.manifest.AppManifest.Operation;
import com.tricore.dxos.platform.manifest.AppManifest.Scope;
import com.tricore.dxos.platform.manifest.ManifestValidator;
import com.tricore.dxos.platform.registry.PlatformRegistry;
import com.tricore.dxos.platform.security.PlatformCaller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Generic record use cases. Order of checks for every call:
 * 1. verified caller; 2. application exists AND caller holds a role in it, else 404 (no existence leak);
 * 3. entity exists, else 404; 4. role grants the operation, else 403; 5. record visibility
 * ('own' scope = created by caller), else 404; 6. schema validation; 7. write + audit in one transaction.
 */
@Service
@ConditionalOnProperty(name = "opentrix.platform.enabled", havingValue = "true")
public class RecordService {
    public record Page(List<RecordRepository.StoredRecord> items, long total, int limit, int offset) {}

    public record EntityView(AppManifest app, AppManifest.Entity entity, Set<String> callerRoles,
                             Map<Operation, Scope> allowed) {}

    private final PlatformRegistry registry;
    private final PlatformCaller callers;
    private final RecordRepository records;
    private final String tenantId;

    public RecordService(PlatformRegistry registry, PlatformCaller callers, RecordRepository records,
                         @Value("${opentrix.platform.tenant-id:default}") String tenantId) {
        if (!tenantId.matches("[a-z][a-z0-9-]{0,62}")) throw new IllegalArgumentException("Invalid tenant id");
        this.registry = registry;
        this.callers = callers;
        this.records = records;
        this.tenantId = tenantId;
    }

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

    public record AppView(AppManifest app, Set<String> callerRoles) {}

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
        Scope scope = require(view, Operation.READ);
        String owner = scope == Scope.OWN ? callers.require().subjectId() : null;
        return records.find(scope(view), id, owner).orElseThrow(PlatformException::notFound);
    }

    @Transactional
    public RecordRepository.StoredRecord create(String appId, String entityKey, JsonNode data) {
        var view = resolve(appId, entityKey);
        require(view, Operation.CREATE);
        validate(view, data);
        String actor = callers.require().subjectId();
        var created = records.insert(scope(view), UUID.randomUUID(), data, actor);
        records.audit(scope(view), created.id(), "CREATE", actor, created.version(), null, created.data());
        return created;
    }

    @Transactional
    public RecordRepository.StoredRecord update(String appId, String entityKey, UUID id, long expectedVersion,
                                                JsonNode data) {
        var view = resolve(appId, entityKey);
        var current = lockWritable(view, id, Operation.UPDATE);
        validate(view, data);
        if (current.version() != expectedVersion) throw conflict();
        String actor = callers.require().subjectId();
        var updated = records.update(scope(view), id, data, actor);
        records.audit(scope(view), id, "UPDATE", actor, updated.version(), current.data(), updated.data());
        return updated;
    }

    @Transactional
    public void delete(String appId, String entityKey, UUID id, long expectedVersion) {
        var view = resolve(appId, entityKey);
        var current = lockWritable(view, id, Operation.DELETE);
        if (current.version() != expectedVersion) throw conflict();
        String actor = callers.require().subjectId();
        records.delete(scope(view), id);
        records.audit(scope(view), id, "DELETE", actor, current.version(), current.data(), null);
    }

    /** Write access: caller must be able to see the record (else 404) and hold the write scope (else 403). */
    private RecordRepository.StoredRecord lockWritable(EntityView view, UUID id, Operation operation) {
        String subject = callers.require().subjectId();
        Scope write = view.allowed().get(operation);
        Scope read = view.allowed().get(Operation.READ);
        var current = records.findForUpdate(scope(view), id).orElseThrow(PlatformException::notFound);
        boolean owner = current.createdBy().equals(subject);
        boolean visible = read == Scope.ALL || (read == Scope.OWN && owner);
        if (!visible) throw PlatformException.notFound();
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
