package ru.ops.console.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyLimitTest {

    @Test
    void extraRequestsFailInsteadOfPilingUp() throws Exception {
        ConcurrencyLimit limit = new ConcurrencyLimit("test", 2, 100);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(pool.submit(() -> {
                try {
                    return limit.run(() -> {
                        maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        running.decrementAndGet();
                        return "ok";
                    });
                } catch (ConcurrencyLimit.BusyException e) {
                    return "busy";
                }
            }));
        }
        Thread.sleep(500);   // the 4 extra requests have given up by now
        release.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) outcomes.add(f.get(5, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(maxRunning.get()).isEqualTo(2);
        assertThat(outcomes).containsOnly("ok", "busy");
        assertThat(outcomes.stream().filter("ok"::equals)).hasSize(2);
    }

    @Test
    void permitIsReleasedOnFailure() {
        ConcurrencyLimit limit = new ConcurrencyLimit("test", 1, 50);
        for (int i = 0; i < 3; i++) {
            try {
                limit.run(() -> {
                    throw new IllegalStateException("boom");
                });
            } catch (IllegalStateException expected) {
                // next iteration must still get the permit
            }
        }
        assertThat(limit.run(() -> "ok")).isEqualTo("ok");
    }
}
