package com.yamareviewer.adapter.ui;

import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ClipboardExport;
import com.yamareviewer.domain.text.ReviewFormatter;
import com.yamareviewer.testing.Reviews;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class KillReviewOpenerTest
{
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "yama-reviewer-test"));
	private final ReviewPanel panel = mock(ReviewPanel.class);
	private final KillReview a = Reviews.builder("a", 1_000L, Mode.SOLO, Contract.NONE).build();
	private final KillReview b = Reviews.builder("b", 2_000L, Mode.DUO_HOST, Contract.NONE).build();
	private final ReviewRepository reviews = new ReviewRepository()
	{
		@Override
		public void save(KillReview review)
		{
		}

		@Override
		public List<KillReview> loadAll() throws IOException
		{
			return List.of(b, a);
		}

		@Override
		public void prune(int keepPerKey)
		{
		}
	};
	private final KillReviewOpener opener = new KillReviewOpener(executor, reviews, panel);

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private void drain() throws Exception
	{
		executor.submit(() ->
		{
		}).get(5, TimeUnit.SECONDS);
	}

	@Test
	public void loadsTheReviewOnTheExecutorAndShowsItInItsModeTab() throws Exception
	{
		opener.open("b");
		drain();

		verify(panel).showKill(Mode.DUO_HOST, ReviewFormatter.format(b), ClipboardExport.text(b));
	}

	@Test
	public void anUnknownKillShowsNothing() throws Exception
	{
		opener.open("zzz");
		drain();

		verifyNoInteractions(panel);
	}

	@Test
	public void aStoppedExecutorIsLoggedNotThrown()
	{
		executor.shutdownNow();

		opener.open("a");

		verifyNoInteractions(panel);
	}
}
