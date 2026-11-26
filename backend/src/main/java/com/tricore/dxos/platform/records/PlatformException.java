// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.records;

import java.util.Map;

/** Expected Platform outcomes mapped to HTTP by the API layer; messages are safe to return. */
public final class PlatformException extends RuntimeException {
    public enum Code {
        NOT_FOUND(404), FORBIDDEN(403), VERSION_CONFLICT(409), VALIDATION_FAILED(400),
        TRANSITION_NOT_ALLOWED(409), STATE_LOCKED(409), COMMIT_OUTCOME_UNKNOWN(503);

        public final int status;

        Code(int status) {
            this.status = status;
        }
    }

    private final Code code;
    private final Map<String, String> errors;

    public PlatformException(Code code, String message, Map<String, String> errors) {
        super(message);
        this.code = code;
        this.errors = Map.copyOf(errors);
    }

    public static PlatformException notFound() {
        return new PlatformException(Code.NOT_FOUND, "Resource not found", Map.of());
    }

    public static PlatformException forbidden() {
        return new PlatformException(Code.FORBIDDEN, "Operation is not permitted", Map.of());
    }

    public Code code() {
        return code;
    }

    public Map<String, String> errors() {
        return errors;
    }
}
