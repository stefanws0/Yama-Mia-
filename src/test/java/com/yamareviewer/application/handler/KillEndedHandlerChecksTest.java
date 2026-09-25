package com.yamareviewer.application.handler;

import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.application.port.ReportRepository;
import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.diagnosis.Versions;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.health.CheckResult;
import com.yamareviewer.domain.health.HealthCheck;
import com.yamareviewer.domain.health.HealthChecks;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.Projections;
import com.yamareviewer.domain.projection.ReviewBuilder;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.testing.CheckFights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Test;

public class KillEndedHandlerChecksTest
{
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final FakeLogs logs = new FakeLogs();
	private final FakeReviews reviews = new FakeReviews();
	private final FakePublisher publisher = new FakePublisher();
	private final FakeReports reports = new FakeReports();

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private KillEndedHandler handler(List<HealthCheck> checks)
	{
		ReviewBuilder builder = new ReviewBuilder(Projections.standard(), TestIds.registry(), Rules.DEFAULT);
		ProblemReporter reporter = new ProblemReporter(reports, TestIds.registry(), () -> new Versions("0.1.0", "1.11.5", "232"),
			Clock.fixed(Instant.parse("2026-09-24T15:30:12.345Z"), ZoneOffset.UTC));
		return new KillEndedHandler(executor, logs, reviews, builder, publisher, () -> true, () -> 20, () -> 50,
			() -> ReviewSettings.DEFAULT, checks, reporter);
	}

	@Test
	public void aHealthyKillIsCompleteAndWritesNoReport()
	{
		handler(HealthChecks.standard()).handle(CheckFights.healthySolo());

		KillReview review = publisher.published.get(0);
		assertEquals(ReviewStatus.COMPLETE, review.getStatus());
		assertEquals(List.of(), review.getFailedChecks());
		assertTrue(reports.saved.isEmpty());
		assertEquals(1, reviews.saved.size());
	}

	@Test
	public void aFailedCheckHidesItsSectionsNamesItselfAndWritesAReport()
	{
		handler(List.of(new AlwaysFailing())).handle(CheckFights.healthySolo());

		KillReview review = publisher.published.get(0);
		assertEquals(Optional.of(HiddenReason.HEALTH_CHECK_FAILED), review.getPhases().hiddenReason());
		assertEquals(List.of("AlwaysFailing"), review.getFailedChecks());
		assertEquals(ReviewStatus.INCOMPLETE, review.getStatus());
		assertEquals(List.of("AlwaysFailing"), reviews.saved.get(0).getFailedChecks());
		assertEquals(1, reports.saved.size());
		String text = reports.saved.values().iterator().next();
		assertTrue(text, text.contains("health checks failed (AlwaysFailing)"));
		assertTrue(text, text.contains("Plugin 0.1.0, RuneLite 1.11.5, game revision 232"));
	}

	@Test
	public void anEarlyDeathWritesNoReport()
	{
		KillLogBuilder kill = CheckFights.solo().ticks(40);
		CheckFights.judgePhase(kill).ticks(10);

		handler(HealthChecks.standard()).handle(CheckFights.finish(kill, EndReason.PLAYER_DIED));

		assertEquals(List.of(), publisher.published.get(0).getFailedChecks());
		assertEquals(ReviewStatus.COMPLETE, publisher.published.get(0).getStatus());
		assertTrue(reports.saved.isEmpty());
	}

	@Test
	public void aReportWriteFailureStillPublishesTheReview()
	{
		reports.fail = true;

		handler(List.of(new AlwaysFailing())).handle(CheckFights.healthySolo());

		assertEquals(1, publisher.published.size());
		assertEquals(ReviewStatus.INCOMPLETE, publisher.published.get(0).getStatus());
	}

	private static final class AlwaysFailing implements HealthCheck
	{
		@Override
		public String name()
		{
			return "AlwaysFailing";
		}

		@Override
		public Set<Role> reportRoles()
		{
			return Set.of(Role.JUDGE);
		}

		@Override
		public List<SectionKey<?>> hides()
		{
			return List.of(Sections.PHASES);
		}

		@Override
		public boolean applies(ContractRules rules)
		{
			return true;
		}

		@Override
		public CheckResult check(KillLog log, ProjectionContext context)
		{
			return CheckResult.fail("always", 0);
		}
	}

	private static final class FakeLogs implements LogRepository
	{
		@Override
		public void save(KillLog kill)
		{
		}

		@Override
		public List<KillLog> loadAll()
		{
			return List.of();
		}

		@Override
		public void prune(int keep)
		{
		}
	}

	private static final class FakeReviews implements ReviewRepository
	{
		private final List<KillReview> saved = new ArrayList<>();

		@Override
		public void save(KillReview review)
		{
			saved.add(review);
		}

		@Override
		public List<KillReview> loadAll()
		{
			return List.copyOf(saved);
		}

		@Override
		public void prune(int keepPerKey)
		{
		}
	}

	private static final class FakePublisher implements ReviewPublisher
	{
		private final List<KillReview> published = new ArrayList<>();

		@Override
		public void publish(KillReview review, HistoryIndex history)
		{
			published.add(review);
		}

		@Override
		public void showHistory(HistoryIndex history)
		{
		}
	}

	private static final class FakeReports implements ReportRepository
	{
		private final Map<String, String> saved = new LinkedHashMap<>();
		private boolean fail;

		@Override
		public void save(String fileName, String text) throws IOException
		{
			if (fail)
			{
				throw new IOException("disk full");
			}
			saved.put(fileName, text);
		}

		@Override
		public Optional<String> latest()
		{
			return saved.values().stream().reduce((first, second) -> second);
		}
	}
}
