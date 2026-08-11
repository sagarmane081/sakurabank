package com.sakurabank.api.exception;

import com.sakurabank.core.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<String> handle(AccountNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<String> handle(InsufficientFundsException e) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(e.getMessage());
    }

    @ExceptionHandler(InvalidAmountException.class)
    public ResponseEntity<String> handle(InvalidAmountException e) {
        return ResponseEntity
                .badRequest()
                .body(e.getMessage());
    }

    @ExceptionHandler(InvalidTransferException.class)
    public ResponseEntity<String> handle(InvalidTransferException e) {
        return ResponseEntity
                .badRequest()
                .body(e.getMessage());
    }

    @ExceptionHandler(InvalidAccountTransitionException.class)
    public ResponseEntity<String> handle(InvalidAccountTransitionException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationException(
            MethodArgumentNotValidException ex) {

        Map<String, String> errors = new LinkedHashMap<>();

        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                        errors.put(error.getField(), error.getDefaultMessage()));

        return ResponseEntity.badRequest().body(errors);
    }

    @ExceptionHandler(AccountOwnershipException.class)
    public ResponseEntity<String> handleAccountOwnershipException(
            AccountOwnershipException ex) {

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ex.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<String> handleInvalidCredentialsException(
            InvalidCredentialsException ex) {

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ex.getMessage());
    }
}