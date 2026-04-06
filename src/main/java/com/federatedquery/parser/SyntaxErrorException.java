package com.federatedquery.parser;

public class SyntaxErrorException extends RuntimeException {
    public SyntaxErrorException(String message) {
        super(message);
    }
    
    public SyntaxErrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
