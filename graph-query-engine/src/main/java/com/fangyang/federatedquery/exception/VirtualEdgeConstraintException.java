package com.fangyang.federatedquery.exception;

public class VirtualEdgeConstraintException extends GraphQueryException {
    public VirtualEdgeConstraintException(String message) {
        super(ErrorCode.VIRTUAL_EDGE_CONSTRAINT_VIOLATION, message);
    }

    public VirtualEdgeConstraintException(String message, Throwable cause) {
        super(ErrorCode.VIRTUAL_EDGE_CONSTRAINT_VIOLATION, message, cause);
    }
}
