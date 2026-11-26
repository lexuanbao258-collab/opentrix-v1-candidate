// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.records;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Transaction boundary of every Platform write: the record change, the workflow state change and the audit event
 * run inside one database transaction and commit or roll back together.
 *
 * <p>Failure classification (nothing is ever retried here):
 * <ul>
 *   <li>exception while the body runs: the transaction is rolled back; the exception propagates unchanged
 *       (definite outcome: no change);</li>
 *   <li>exception after the body returned (the COMMIT itself failed): the database may or may not have applied the
 *       transaction, so the outcome is reported as {@code COMMIT_OUTCOME_UNKNOWN} and the caller must read to reconcile.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "opentrix.platform.enabled", havingValue = "true")
public class PlatformTransactions {
    private final TransactionTemplate template;

    public PlatformTransactions(PlatformTransactionManager manager) {
        this.template = new TransactionTemplate(manager);
    }

    public <T> T write(Supplier<T> work) {
        boolean[] bodyReturned = {false};
        try {
            return template.execute(status -> {
                T result = work.get();
                bodyReturned[0] = true;
                return result;
            });
        } catch (RuntimeException failure) {
            if (bodyReturned[0]) {
                throw new PlatformException(PlatformException.Code.COMMIT_OUTCOME_UNKNOWN,
                        "The write may or may not have been applied; read the record before deciding to repeat it", Map.of());
            }
            throw failure;
        }
    }
}
