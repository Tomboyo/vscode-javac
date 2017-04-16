package org.javacs;

import com.google.common.util.concurrent.RateLimiter;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

/**
 * Executor that runs one task at a time and queues one task at a time.
 * When a new task is submitted, the queued task is evicted.
 */
class EvictingExecutor {
    private final Optional<RateLimiter> rateLimiter;
    private volatile Optional<Runnable> queued = Optional.empty();

    EvictingExecutor() {
        this.rateLimiter = Optional.empty();
    }

    /**
     * Limit the frequency of execution
     */
    EvictingExecutor(RateLimiter limit) {
        this.rateLimiter = Optional.of(limit);
    }

    public Future<?> submit(Runnable task) {
        queued = Optional.of(task);

        // Don't use your own thread for this
        // We would like to be able to run MANY EvictingExecutors so they need to be lightweight
        return ForkJoinPool.commonPool().submit(this::take);
    }

    private synchronized void take() {
        Optional<Runnable> todo = queued;

        queued = Optional.empty();

        todo.ifPresent(task -> {
            rateLimiter.ifPresent(limit -> limit.acquire(1));

            task.run();
        });
    }
}