package com.fangyang.federatedquery.exception;

public class GraphQueryException extends RuntimeException {
    private final ErrorCode errorCode;

    public GraphQueryException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public GraphQueryException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public GraphQueryException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public GraphQueryException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getCode() {
        return errorCode.getCode();
    }

    @Override
    public String toString() {
        return "GraphQueryException{" +
                "code=" + errorCode.getCode() +
                ", errorCode=" + errorCode +
                ", message='" + getMessage() + '\'' +
                '}';
    }
}
