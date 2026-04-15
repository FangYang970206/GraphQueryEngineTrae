package com.federatedquery.exception;

public enum ErrorCode {

    QUERY_PARSE_ERROR(1001, "Cypher query parse error"),
    QUERY_REWRITE_ERROR(1002, "Query rewrite error"),
    QUERY_EXECUTION_ERROR(1003, "Query execution error"),

    VIRTUAL_EDGE_CONSTRAINT_VIOLATION(2001, "Virtual edge constraint violation"),
    INVALID_PATH_STRUCTURE(2002, "Invalid path structure"),

    DATASOURCE_CONNECTION_ERROR(3001, "Data source connection error"),
    DATASOURCE_QUERY_ERROR(3002, "Data source query error"),
    EXTERNAL_DATASOURCE_ERROR(3003, "External data source error"),

    METADATA_NOT_FOUND(4001, "Metadata not found"),
    INVALID_METADATA(4002, "Invalid metadata configuration"),

    BATCH_EXECUTION_ERROR(5002, "Batch execution error"),
    UNION_EXECUTION_ERROR(5003, "Union execution error"),

    CONFIGURATION_ERROR(6001, "Configuration error"),
    SERIALIZATION_ERROR(6002, "Result serialization error"),

    UNKNOWN_ERROR(9999, "Unknown error");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
