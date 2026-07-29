package com.mawai.wiibsim.service;

import lombok.Getter;

@Getter
public class NewApiRemoteException extends RuntimeException {
    private final int statusCode;
    private final boolean retryable;

    public NewApiRemoteException(int statusCode, String message, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public NewApiRemoteException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.retryable = true;
    }
}
