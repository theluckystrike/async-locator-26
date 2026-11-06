package com.zovo.asynclocator.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs structure searches on a small pool of daemon threads and hands each result back to the main thread.
 * The search itself may still ask the main thread for chunk data; it waits for that, but the tick loop keeps going.
 */
public final class LocateExecutor {
	public static final Logger LOG = LoggerFactory.getLogger("AsyncLocator");
	private static final AtomicInteger POOL_NO = new AtomicInteger();

	private static volatile ExecutorService pool;
	private static volatile int poolThreads;
	private static volatile boolean logSearches = true;

	public static final AtomicLong SUBMITTED = new AtomicLong();
	public static final AtomicLong FINISHED = new AtomicLong();
	public static final AtomicLong FAILED = new AtomicLong();
	public static final AtomicLong CANCELLED = new AtomicLong();

	private LocateExecutor() {}

	public static synchronized void start(int threads, boolean log) {
		logSearches = log;
		if (pool != null && poolThreads == threads && !pool.isShutdown()) return;
		stop();
		int n = Math.max(1, Math.min(Config.MAX_THREADS, threads));
		int poolNo = POOL_NO.incrementAndGet();
		AtomicInteger threadNo = new AtomicInteger();
		ThreadFactory tf = r -> {
			Thread t = new LocatorThread(r, "AsyncLocator-" + poolNo + "-" + threadNo.incrementAndGet());
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);
			return t;
		};
		pool = Executors.newFixedThreadPool(n, tf);
		poolThreads = n;
		LOG.info("Started {} locating thread(s)", n);
	}

	/** Stops the pool and interrupts running searches. Pending results are dropped. */
	public static synchronized void stop() {
		ExecutorService p = pool;
		pool = null;
		if (p != null) {
			p.shutdownNow();
			try {
				p.awaitTermination(2, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			LOG.info("Stopped locating threads");
		}
	}

	/** True on a locating thread. Chunk requests from these threads wait here, not on the server thread. */
	public static boolean onLocatorThread() {
		return Thread.currentThread() instanceof LocatorThread;
	}

	static final class LocatorThread extends Thread {
		LocatorThread(Runnable r, String name) {
			super(r, name);
		}
	}

	public static boolean running() {
		ExecutorService p = pool;
		return p != null && !p.isShutdown();
	}

	/**
	 * Queues {@code search} on a locating thread. When it ends, {@code onMain} runs through {@code mainThread}
	 * with the result, or with null if the search threw. Cancelled tasks never call {@code onMain}.
	 */
	public static <T> Task<T> submit(String what, Supplier<T> search, Executor mainThread, Consumer<T> onMain) {
		ExecutorService p = pool;
		if (p == null || p.isShutdown()) {
			start(1, logSearches);
			p = pool;
		}
		SUBMITTED.incrementAndGet();
		CompletableFuture<T> result = new CompletableFuture<>();
		Task<T> task = new Task<>(result);
		Future<?> job = p.submit(() -> {
			if (task.cancelled) return;
			long t0 = System.nanoTime();
			T value = null;
			boolean failed = false;
			try {
				value = search.get();
			} catch (Throwable t) {
				failed = true;
				FAILED.incrementAndGet();
				LOG.warn("Search for {} failed", what, t);
			}
			long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
			if (!failed) FINISHED.incrementAndGet();
			if (logSearches && !failed) {
				LOG.info("Search for {} took {} ms off the main thread: {}", what, ms, value == null ? "nothing found" : "found");
			}
			result.complete(value);
		});
		task.job = job;
		result.thenAccept(v -> {
			if (task.cancelled) return;
			try {
				mainThread.execute(() -> {
					if (!task.cancelled) onMain.accept(v);
				});
			} catch (RuntimeException e) {
				LOG.debug("Main thread did not accept the result for {}", what, e);
			}
		});
		return task;
	}

	public static final class Task<T> {
		private final CompletableFuture<T> result;
		private volatile Future<?> job;
		private volatile boolean cancelled;

		Task(CompletableFuture<T> result) {
			this.result = result;
		}

		public CompletableFuture<T> result() {
			return result;
		}

		public boolean isCancelled() {
			return cancelled;
		}

		public void cancel() {
			if (cancelled) return;
			cancelled = true;
			CANCELLED.incrementAndGet();
			Future<?> j = job;
			if (j != null) j.cancel(true);
		}
	}
}
