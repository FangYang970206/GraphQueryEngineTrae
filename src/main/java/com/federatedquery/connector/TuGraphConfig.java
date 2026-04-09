package com.federatedquery.connector;

public class TuGraphConfig {
    private String uri;
    private String username;
    private String password;
    private String graphName;
    private int maxConnectionPoolSize = 50;
    private long connectionTimeoutMs = 30000;
    private long maxTransactionRetryTimeMs = 30000;

    public TuGraphConfig() {
    }

    public TuGraphConfig(String uri, String username, String password) {
        this.uri = uri;
        this.username = username;
        this.password = password;
    }

    public TuGraphConfig(String uri, String username, String password, String graphName) {
        this.uri = uri;
        this.username = username;
        this.password = password;
        this.graphName = graphName;
    }

    public static TuGraphConfig defaultConfig() {
        return new TuGraphConfig("bolt://127.0.0.1:7687", "admin", "73@TuGraph", "default");
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getGraphName() {
        return graphName;
    }

    public void setGraphName(String graphName) {
        this.graphName = graphName;
    }

    public int getMaxConnectionPoolSize() {
        return maxConnectionPoolSize;
    }

    public void setMaxConnectionPoolSize(int maxConnectionPoolSize) {
        this.maxConnectionPoolSize = maxConnectionPoolSize;
    }

    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(long connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public long getMaxTransactionRetryTimeMs() {
        return maxTransactionRetryTimeMs;
    }

    public void setMaxTransactionRetryTimeMs(long maxTransactionRetryTimeMs) {
        this.maxTransactionRetryTimeMs = maxTransactionRetryTimeMs;
    }
}
