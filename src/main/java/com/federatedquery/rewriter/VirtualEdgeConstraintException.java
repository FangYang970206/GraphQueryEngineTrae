package com.federatedquery.rewriter;

public class VirtualEdgeConstraintException extends RuntimeException {
    public VirtualEdgeConstraintException(String message) {
        super(message);
    }
    
    public VirtualEdgeConstraintException(String message, Throwable cause) {
        super(message, cause);
    }
}
