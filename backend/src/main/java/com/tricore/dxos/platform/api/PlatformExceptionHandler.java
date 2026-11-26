// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.api;

import com.tricore.dxos.common.error.ApiError;
import com.tricore.dxos.platform.records.PlatformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;
import java.util.TreeMap;

/** Maps Platform failures to the shared {@link ApiError} shape. Unexpected errors never expose details. */
@RestControllerAdvice(assignableTypes = PlatformController.class)
@ConditionalOnProperty(name = "opentrix.platform.enabled", havingValue = "true")
public class PlatformExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(PlatformExceptionHandler.class);

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ApiError> platform(PlatformException exception) {
        return error(exception.code().status, exception.code().name(), exception.getMessage(), exception.errors());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, jakarta.validation.ConstraintViolationException.class})
    public ResponseEntity<ApiError> badRequest() {
        return error(400, "VALIDATION_FAILED", "Request is malformed", Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new TreeMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(field -> errors.putIfAbsent(field.getField(), field.getDefaultMessage()));
        return error(400, "VALIDATION_FAILED", "Request is invalid", errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> invalidParameter() {
        return error(400, "VALIDATION_FAILED", "Request parameter is invalid", Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> mediaType() {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "Request body must be application/json", Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception) {
        LOG.error("Platform request failed", exception);
        return error(500, "INTERNAL_ERROR", "Request failed", Map.of());
    }

    private static ResponseEntity<ApiError> error(int status, String code, String message, Map<String, String> errors) {
        return ResponseEntity.status(status).body(new ApiError(status, code, message, errors));
    }
}
