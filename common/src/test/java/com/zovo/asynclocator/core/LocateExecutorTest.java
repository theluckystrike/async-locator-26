package com.zovo.asynclocator.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocateExecutorTest {
	/** Stands in for the server thread: a single thread with a known name. */
	private ExecutorService main;
	private final AtomicReference<Thread> mainThread = new AtomicReference<>();

	@BeforeEach
	void setUp() {
		main = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "fake-server-thread");
			mainThread.set(t);
			return t;
		});
		LocateExecutor.start(2, false);
	}

	@AfterEach
	void tearDown() {
		LocateExecutor.stop();
		main.shutdownNow();
	}

	@Test
	void searchRunsOffTheCallerAndResultArrivesOnMain() throws Exception {
		AtomicReference<String> searchThread = new AtomicReference<>();
		AtomicReference<String> callbackThread = new AtomicReference<>();
		AtomicReference<Integer> got = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		LocateExecutor.submit("test", () -> {
			searchThread.set(Thread.currentThread().getName());
			return 42;
		}, main, v -> {
			callbackThread.set(Thread.currentThread().getName());
			got.set(v);
			done.countDown();
		});
		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertEquals(42, got.get());
		assertTrue(searchThread.get().startsWith("AsyncLocator-"), searchThread.get());
		assertEquals("fake-server-thread", callbackThread.get());
	}

	@Test
	void callerIsNotBlockedBySlowSearch() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(1);
		long t0 = System.nanoTime();
		LocateExecutor.submit("slow", () -> {
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "ok";
		}, main, v -> done.countDown());
		long submitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
		assertTrue(submitMs < 200, "submit took " + submitMs + " ms");
		assertEquals(1, done.getCount());
		release.countDown();
		assertTrue(done.await(5, TimeUnit.SECONDS));
	}

	@Test
	void failingSearchCallsOnFailNotOnMain() throws Exception {
		long failedBefore = LocateExecutor.FAILED.get();
		CountDownLatch failed = new CountDownLatch(1);
		AtomicInteger results = new AtomicInteger();
		AtomicReference<String> failThread = new AtomicReference<>();
		LocateExecutor.<Object>submit("boom", () -> {
			throw new IllegalStateException("boom");
		}, main, v -> results.incrementAndGet(), () -> {
			failThread.set(Thread.currentThread().getName());
			failed.countDown();
		});
		assertTrue(failed.await(5, TimeUnit.SECONDS));
		main.submit(() -> {}).get(5, TimeUnit.SECONDS);
		assertEquals(0, results.get(), "a failed search must not look like 'nothing found'");
		assertEquals("fake-server-thread", failThread.get());
		assertEquals(failedBefore + 1, LocateExecutor.FAILED.get());
	}

	@Test
	void nothingFoundDeliversNull() throws Exception {
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Object> got = new AtomicReference<>("unset");
		LocateExecutor.<Object>submit("miss", () -> null, main, v -> {
			got.set(v);
			done.countDown();
		});
		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertNull(got.get());
	}

	@Test
	void stopDropsRunningAndQueuedResults() throws Exception {
		LocateExecutor.start(1, false);
		CountDownLatch started = new CountDownLatch(1);
		AtomicInteger callbacks = new AtomicInteger();
		LocateExecutor.submit("running", () -> {
			started.countDown();
			long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
			while (System.nanoTime() < end) Thread.onSpinWait(); // not interruptible, like a CPU-bound search
			return 1;
		}, main, v -> callbacks.incrementAndGet(), callbacks::incrementAndGet);
		LocateExecutor.submit("queued", () -> 2, main, v -> callbacks.incrementAndGet(), callbacks::incrementAndGet);
		assertTrue(started.await(5, TimeUnit.SECONDS));
		LocateExecutor.stop();
		Thread.sleep(500);
		main.submit(() -> {}).get(5, TimeUnit.SECONDS);
		assertEquals(0, callbacks.get(), "nothing may reach the main thread after stop");
	}

	@Test
	void handOffIsNeverRunInsideSubmit() throws Exception {
		// With an executor that runs tasks inline (a server thread outside a task does that), the callback must still
		// run on the locating thread that finished the search, never inside submit() on the caller's thread.
		String caller = Thread.currentThread().getName();
		List<String> threads = new CopyOnWriteArrayList<>();
		CountDownLatch done = new CountDownLatch(50);
		for (int i = 0; i < 50; i++) {
			LocateExecutor.submit("fast", () -> null, Runnable::run, v -> {
				threads.add(Thread.currentThread().getName());
				done.countDown();
			});
		}
		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertEquals(50, threads.size());
		for (String t : threads) {
			assertNotEquals(caller, t);
			assertTrue(t.startsWith("AsyncLocator-"), t);
		}
	}

	@Test
	void cancelledTaskNeverCallsBack() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		AtomicInteger callbacks = new AtomicInteger();
		AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
		LocateExecutor.Task<String> task = LocateExecutor.submit("cancel me", () -> {
			started.countDown();
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				interrupted.set(true);
			}
			return "late";
		}, main, v -> callbacks.incrementAndGet());
		assertTrue(started.await(5, TimeUnit.SECONDS));
		task.cancel();
		assertTrue(task.isCancelled());
		Thread.sleep(300);
		main.submit(() -> {}).get(5, TimeUnit.SECONDS); // drain the main queue
		assertEquals(0, callbacks.get());
		assertTrue(interrupted.get(), "running search should be interrupted");
	}

	@Test
	void usesConfiguredNumberOfThreads() throws Exception {
		LocateExecutor.start(3, false);
		CountDownLatch allIn = new CountDownLatch(3);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(3);
		List<String> names = new CopyOnWriteArrayList<>();
		for (int i = 0; i < 3; i++) {
			LocateExecutor.submit("p" + i, () -> {
				names.add(Thread.currentThread().getName());
				allIn.countDown();
				try {
					release.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return 1;
			}, main, v -> done.countDown());
		}
		assertTrue(allIn.await(5, TimeUnit.SECONDS), "3 searches should run at the same time");
		release.countDown();
		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertEquals(3, names.stream().distinct().count());
	}

	@Test
	void callbacksKeepSubmissionOrderOnSingleThread() throws Exception {
		LocateExecutor.start(1, false);
		ConcurrentLinkedQueue<Integer> order = new ConcurrentLinkedQueue<>();
		CountDownLatch done = new CountDownLatch(20);
		for (int i = 0; i < 20; i++) {
			int n = i;
			LocateExecutor.submit("o" + i, () -> n, main, v -> {
				order.add(v);
				done.countDown();
			});
		}
		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19), List.copyOf(order));
	}

	@Test
	void stopThenSubmitRestartsLazily() throws Exception {
		LocateExecutor.stop();
		assertFalse(LocateExecutor.running());
		CountDownLatch done = new CountDownLatch(1);
		LocateExecutor.submit("after stop", () -> 1, main, v -> done.countDown());
		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertTrue(LocateExecutor.running());
	}

	@Test
	void knowsItsOwnThreads() throws Exception {
		assertFalse(LocateExecutor.onLocatorThread());
		AtomicReference<Boolean> inside = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		LocateExecutor.submit("who", () -> {
			inside.set(LocateExecutor.onLocatorThread());
			return 1;
		}, main, v -> done.countDown());
		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertTrue(inside.get());
		assertFalse(main.submit(LocateExecutor::onLocatorThread).get(5, TimeUnit.SECONDS));
	}

	@Test
	void locatingThreadsAreDaemons() throws Exception {
		AtomicReference<Boolean> daemon = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		LocateExecutor.submit("daemon", () -> {
			daemon.set(Thread.currentThread().isDaemon());
			return 1;
		}, main, v -> done.countDown());
		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertTrue(daemon.get());
	}
}
