package com.fangyang.federatedquery.executor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

class ExecutionRuntime {
    private static final long DEFAULT_TIMEOUT_MS = 30000;
    private static final long RETRY_DELAY_MS = 100;
    private static final int CORE_POOL_SIZE = 10;
    private static final int MAX_POOL_SIZE = 20;
    private static final int QUEUE_CAPACITY = 100;
    private static final long KEEP_ALIVE_TIME = 60L;

    private final ExecutorService executorService;
    private long timeoutMs = DEFAULT_TIMEOUT_MS;

    ExecutionRuntime() {
        this.executorService = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    long getTimeoutMs() {
        return timeoutMs;
    }

    void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    Executor executor() {
        return executorService;
    }

    <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executorService);
    }

    <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future) {
        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
    }

    Executor delayedExecutor(int attempt) {
        return CompletableFuture.delayedExecutor(RETRY_DELAY_MS * attempt, TimeUnit.MILLISECONDS);
    }

    void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
