package com.yamareviewer.application.handler;

import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryKey;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.testing.Reviews;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Test;

public class HistoryLoaderTest
{
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "yama-reviewer-test"));
	private final FakeReviews reviews = new FakeReviews();
	private final FakePublisher publisher = new FakePublisher();

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private void drain() throws Exception
	{
		executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
	}

	@Test
	public void publishesTheHistoryOnTheExecutor() throws Exception
	{
		reviews.stored = List.of(
			Reviews.builder("b", 2_000L, Mode.SOLO, Contract.NONE).build(),
			Reviews.builder("a", 1_000L, Mode.SOLO, Contract.NONE).build());

		new HistoryLoader(executor, reviews, publisher, () -> 50).load();
		drain();

		assertEquals(1, publisher.shown.size());
		assertEquals(2, publisher.shown.get(0).view(new HistoryKey(Mode.SOLO, Contract.NONE)).get().getKills().size());
		assertEquals("yama-reviewer-test", publisher.thread);
	}

	@Test
	public void aStorageFailureIsLoggedNotThrown() throws Exception
	{
		reviews.fail = true;

		new HistoryLoader(executor, reviews, publisher, () -> 50).load();
		drain();

		assertTrue(publisher.shown.isEmpty());
	}

	@Test
	public void aStoppedExecutorIsLoggedNotThrown()
	{
		executor.shutdownNow();

		new HistoryLoader(executor, reviews, publisher, () -> 50).load();

		assertTrue(publisher.shown.isEmpty());
	}

	private static final class FakeReviews implements ReviewRepository
	{
		private volatile List<KillReview> stored = List.of();
		private volatile boolean fail;

		@Override
		public void save(KillReview review)
		{
		}

		@Override
		public List<KillReview> loadAll() throws IOException
		{
			if (fail)
			{
				throw new IOException("disk gone");
			}
			return stored;
		}

		@Override
		public void prune(int keepPerKey)
		{
		}
	}

	private static final class FakePublisher implements ReviewPublisher
	{
		private final List<HistoryIndex> shown = new CopyOnWriteArrayList<>();
		private volatile String thread;

		@Override
		public void publish(KillReview review, HistoryIndex history)
		{
			throw new AssertionError("the loader never publishes a review");
		}

		@Override
		public void showHistory(HistoryIndex history)
		{
			thread = Thread.currentThread().getName();
			shown.add(history);
		}
	}
}
