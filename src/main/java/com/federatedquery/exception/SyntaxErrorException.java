package com.federatedquery.exception;

public class SyntaxErrorException extends GraphQueryException {
    public SyntaxErrorException(String message) {
        super(ErrorCode.QUERY_PARSE_ERROR, message);
    }

    public SyntaxErrorException(String message, Throwable cause) {
        super(ErrorCode.QUERY_PARSE_ERROR, message, cause);
    }
}
