package com.zovo.asynclocator.core;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
	/** False while stopping: no result is handed to the main thread any more. */
	private static volatile boolean accepting = true;
	private static final Set<Task<?>> LIVE = ConcurrentHashMap.newKeySet();

	public static final AtomicLong SUBMITTED = new AtomicLong();
	public static final AtomicLong FINISHED = new AtomicLong();
	public static final AtomicLong FAILED = new AtomicLong();
	public static final AtomicLong CANCELLED = new AtomicLong();

	private LocateExecutor() {}

	public static synchronized void start(int threads, boolean log) {
		logSearches = log;
		if (pool != null && poolThreads == threads && !pool.isShutdown()) {
			accepting = true;
			return;
		}
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
		accepting = true;
		LOG.info("Started {} locating thread(s)", n);
	}

	/** Stops the pool and interrupts running searches. Pending results are dropped. */
	public static synchronized void stop() {
		accepting = false;
		for (Task<?> t : LIVE) t.cancel();
		LIVE.clear();
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

	/** Like {@link #submit(String, Supplier, Executor, Consumer, Runnable)} with no failure callback. */
	public static <T> Task<T> submit(String what, Supplier<T> search, Executor mainThread, Consumer<T> onMain) {
		return submit(what, search, mainThread, onMain, null);
	}

	/**
	 * Queues {@code search} on a locating thread. When it returns, {@code onMain} gets the result (null = nothing
	 * found) through {@code mainThread}. If the search throws, {@code onFail} runs there instead (if given), so a
	 * broken or interrupted search is never mistaken for "nothing found". Nothing runs for cancelled tasks or after
	 * {@link #stop()}. The hand-off is always queued from the locating thread, never run inside this call.
	 */
	public static <T> Task<T> submit(String what, Supplier<T> search, Executor mainThread, Consumer<T> onMain, Runnable onFail) {
		ExecutorService p = pool;
		if (p == null || p.isShutdown()) {
			start(1, logSearches);
			p = pool;
		}
		SUBMITTED.incrementAndGet();
		CompletableFuture<T> result = new CompletableFuture<>();
		Task<T> task = new Task<>(result);
		LIVE.add(task);
		// Registered before the job exists, so it always runs on the locating thread that completes the future.
		result.whenComplete((value, error) -> {
			LIVE.remove(task);
			if (task.cancelled || !accepting) return;
			Runnable step = error == null ? () -> onMain.accept(value) : onFail;
			if (step == null) return;
			try {
				mainThread.execute(() -> {
					if (!task.cancelled && accepting) step.run();
				});
			} catch (RuntimeException e) {
				LOG.debug("Main thread did not accept the result for {}", what, e);
			}
		});
		task.job = p.submit(() -> {
			if (task.cancelled) {
				result.cancel(false);
				return;
			}
			long t0 = System.nanoTime();
			try {
				T value = search.get();
				FINISHED.incrementAndGet();
				if (logSearches) {
					long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
					LOG.info("Search for {} took {} ms off the main thread: {}", what, ms, value == null ? "nothing found" : "found");
				}
				result.complete(value);
			} catch (Throwable t) {
				FAILED.incrementAndGet();
				if (accepting && !task.cancelled) LOG.warn("Search for {} failed", what, t);
				result.completeExceptionally(t);
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
