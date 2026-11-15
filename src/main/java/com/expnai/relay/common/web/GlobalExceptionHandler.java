package com.expnai.relay.common.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.exception.InvalidSessionException;
import com.expnai.relay.common.exception.ResourceConflictException;
import com.expnai.relay.common.exception.ResourceNotFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ResourceNotFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(404, ex.getMessage(), null));
	}

	@ExceptionHandler(ResourceConflictException.class)
	public ResponseEntity<ApiResponse<Void>> handleConflict(ResourceConflictException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(409, ex.getMessage(), ex.getMeta()));
	}

	@ExceptionHandler(BusinessRuleException.class)
	public ResponseEntity<ApiResponse<Void>> handleBusinessRule(BusinessRuleException ex) {
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
				.body(ApiResponse.fail(422, ex.getMessage(), null));
	}

	@ExceptionHandler(InvalidSessionException.class)
	public ResponseEntity<ApiResponse<Void>> handleInvalidSession(InvalidSessionException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(401, ex.getMessage(), null));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
			fields.put(fe.getField(), fe.getDefaultMessage());
		}
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(400, "Validation failed", fields));
	}

	@ExceptionHandler({ BadCredentialsException.class, DisabledException.class, LockedException.class })
	public ResponseEntity<ApiResponse<Void>> handleAuthFailure(AuthenticationException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(ApiResponse.fail(401, "Invalid email or password", null));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(400, ex.getMessage(), null));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiResponse.fail(500, "Unexpected server error", null));
	}
}
