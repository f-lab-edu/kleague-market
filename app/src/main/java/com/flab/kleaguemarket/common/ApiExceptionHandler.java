package com.flab.kleaguemarket.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException e, HttpServletRequest request) {
        return build(e.getStatus(), e.getCode(), e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e,
                                                          HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("요청 검증에 실패했습니다");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    /** @Validated가 붙은 쿼리 파라미터(page·size 등) 검증 실패. @Valid 바디와 예외 타입이 다르다. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e,
                                                                   HttpServletRequest request) {
        String message = e.getConstraintViolations().stream()
                .map(violation -> parameterName(violation.getPropertyPath().toString())
                        + ": " + violation.getMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("요청 검증에 실패했습니다");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    /**
     * 본문을 역직렬화조차 못 한 경우 — 깨진 JSON, enum 밖의 값({"side": "HOLD"}) 등.
     * 예외 원문에는 파서 위치·내부 클래스명이 들어 있으므로 응답에 싣지 않는다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e,
                                                              HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "요청 본문을 읽을 수 없습니다. JSON 형식과 필드 값을 확인하세요", request);
    }

    /** propertyPath는 "list.size" 형태라 메서드명이 응답에 새지 않게 마지막 마디만 쓴다. */
    private String parameterName(String propertyPath) {
        return propertyPath.substring(propertyPath.lastIndexOf('.') + 1);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(code, message, Instant.now(), request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
