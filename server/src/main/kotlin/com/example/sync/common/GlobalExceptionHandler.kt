package com.example.sync.common

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handle(e: ApiException): ResponseEntity<ApiError> {
        val builder = ResponseEntity.status(e.error.status)
        if (e.error.status == 401) builder.header("WWW-Authenticate", "Bearer")
        return builder.body(ApiError(e.error.code, e.message ?: e.error.code))
    }

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        MissingRequestHeaderException::class,
        HttpMediaTypeNotSupportedException::class,
    )
    fun handleBadRequest(e: Exception): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(ApiError(ErrorCode.INVALID_REQUEST.code, "Malformed request"))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethod(e: HttpRequestMethodNotSupportedException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(ApiError(ErrorCode.INVALID_REQUEST.code, "Method not allowed"))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ApiError> {
        log.error("Unexpected error", e)
        return ResponseEntity.status(500).body(ApiError(ErrorCode.INTERNAL_ERROR.code, "Internal error"))
    }
}
