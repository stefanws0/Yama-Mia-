package com.yamareviewer.application.handler;

import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.application.port.ReportRepository;
import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.diagnosis.Versions;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryKey;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.projection.ReviewBuilder;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Test;

public class KillEndedHandlerTest
{
	private final FakeLogs logs = new FakeLogs();
	private final FakeReviews reviews = new FakeReviews();
	private final FakePublisher publisher = new FakePublisher();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "yama-reviewer-test"));

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private KillEndedHandler handler(boolean active)
	{
		ReviewBuilder builder = new ReviewBuilder(List.of(), TestIds.registry(), Rules.DEFAULT);
		return new KillEndedHandler(executor, logs, reviews, builder, publisher, () -> active, () -> 20, () -> 50, () -> ReviewSettings.DEFAULT,
			List.of(), new ProblemReporter(NO_REPORTS, TestIds.registry(), () -> new Versions("0.1.0", "test", "0"), Clock.systemUTC()));
	}

	private static KillLog kill()
	{
		return KillLogBuilder.kill().ticks(2).end(EndReason.YAMA_DIED);
	}

	private static final ReportRepository NO_REPORTS = new ReportRepository()
	{
		@Override
		public void save(String fileName, String text)
		{
		}

		@Override
		public Optional<String> latest()
		{
			return Optional.empty();
		}
	};

	@Test
	public void storesTheLogAndTheReviewThenPublishesOnTheExecutor() throws Exception
	{
		KillEndedHandler handler = handler(true);

		handler.killEnded(kill());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertEquals(List.of("save test-kill on yama-reviewer-test", "prune 20"), logs.calls);
		assertEquals(List.of("save test-kill", "prune 50", "loadAll"), reviews.calls);
		assertEquals(1, publisher.published.size());
		KillReview review = publisher.published.get(0);
		assertEquals("test-kill", review.getKillId());
		assertEquals(Mode.SOLO, review.getMode());
		assertEquals(ReviewStatus.COMPLETE, review.getStatus());
		assertEquals(1, publisher.histories.get(0).view(new HistoryKey(Mode.SOLO, Contract.NONE)).get().getKills().size());
		assertEquals("yama-reviewer-test", publisher.thread);
	}

	@Test
	public void nothingIsPublishedWhenThePluginIsInactive() throws Exception
	{
		KillEndedHandler handler = handler(false);

		handler.killEnded(kill());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertTrue(publisher.published.isEmpty());
		assertEquals(1, reviews.saved.size());
	}

	@Test
	public void storageFailuresStillPublishTheReview() throws Exception
	{
		logs.fail = true;
		reviews.failSave = true;
		KillEndedHandler handler = handler(true);

		handler.killEnded(kill());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertEquals(1, publisher.published.size());
	}

	@Test
	public void aHistoryLoadFailureFallsBackToThisKill() throws Exception
	{
		reviews.failLoad = true;
		KillEndedHandler handler = handler(true);

		handler.killEnded(kill());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertEquals(1, publisher.histories.get(0).view(new HistoryKey(Mode.SOLO, Contract.NONE)).get().getKills().size());
	}

	@Test
	public void aPublisherFailureDoesNotEscape() throws Exception
	{
		publisher.fail = true;
		KillEndedHandler handler = handler(true);

		handler.killEnded(kill());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertEquals(1, reviews.saved.size());
	}

	@Test
	public void aStoppedExecutorIsLoggedNotThrown()
	{
		executor.shutdownNow();
		KillEndedHandler handler = handler(true);

		handler.killEnded(kill());

		assertTrue(handler.pending().isDone());
		assertTrue(publisher.published.isEmpty());
	}

	private static final class FakeLogs implements LogRepository
	{
		private final List<String> calls = new CopyOnWriteArrayList<>();
		private volatile boolean fail;

		@Override
		public void save(KillLog kill) throws IOException
		{
			if (fail)
			{
				throw new IOException("disk full");
			}
			calls.add("save " + kill.getHeader().getKillId() + " on " + Thread.currentThread().getName());
		}

		@Override
		public List<KillLog> loadAll()
		{
			return List.of();
		}

		@Override
		public void prune(int keep)
		{
			calls.add("prune " + keep);
		}
	}

	private static final class FakeReviews implements ReviewRepository
	{
		private final List<String> calls = new CopyOnWriteArrayList<>();
		private final List<KillReview> saved = new CopyOnWriteArrayList<>();
		private volatile boolean failSave;
		private volatile boolean failLoad;

		@Override
		public void save(KillReview review) throws IOException
		{
			if (failSave)
			{
				throw new IOException("disk full");
			}
			calls.add("save " + review.getKillId());
			saved.add(review);
		}

		@Override
		public List<KillReview> loadAll() throws IOException
		{
			calls.add("loadAll");
			if (failLoad)
			{
				throw new IOException("disk gone");
			}
			return List.copyOf(saved);
		}

		@Override
		public void prune(int keepPerKey)
		{
			calls.add("prune " + keepPerKey);
		}
	}

	private static final class FakePublisher implements ReviewPublisher
	{
		private final List<KillReview> published = new CopyOnWriteArrayList<>();
		private final List<HistoryIndex> histories = new CopyOnWriteArrayList<>();
		private volatile String thread;
		private volatile boolean fail;

		@Override
		public void publish(KillReview review, HistoryIndex history)
		{
			if (fail)
			{
				throw new IllegalStateException("chat is gone");
			}
			thread = Thread.currentThread().getName();
			published.add(review);
			histories.add(history);
		}

		@Override
		public void showHistory(HistoryIndex history)
		{
			histories.add(history);
		}
	}
}
