package com.baseball.score.util;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(String message) { this(HttpStatus.BAD_REQUEST, message); }

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
