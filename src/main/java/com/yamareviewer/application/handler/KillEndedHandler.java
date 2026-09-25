package com.yamareviewer.application.handler;

import com.yamareviewer.application.command.KillEndedListener;
import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.health.CheckOutcome;
import com.yamareviewer.domain.health.HealthCheck;
import com.yamareviewer.domain.health.HealthCheckRunner;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryProjector;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.KillReviewAssembler;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.ReviewBuilder;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.review.KillReview;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Everything that happens after a kill, on the plugin's own executor (spec 4.3): store the raw log, build the
 * review, run the health checks, write a report when one fails, store the review, rebuild the history and
 * publish if the plugin is still active. Storage failures are logged and the review is still published (spec 11).
 */
@Slf4j
public final class KillEndedHandler implements KillEndedListener
{
	private final ExecutorService executor;
	private final LogRepository logs;
	private final ReviewRepository reviews;
	private final ReviewBuilder builder;
	private final ReviewPublisher publisher;
	private final BooleanSupplier active;
	private final IntSupplier rawLogsKept;
	private final IntSupplier historySize;
	private final Supplier<ReviewSettings> settings;
	private final List<HealthCheck> checks;
	private final ProblemReporter reporter;
	private volatile Future<?> pending = CompletableFuture.completedFuture(null);

	public KillEndedHandler(ExecutorService executor, LogRepository logs, ReviewRepository reviews, ReviewBuilder builder,
		ReviewPublisher publisher, BooleanSupplier active, IntSupplier rawLogsKept, IntSupplier historySize,
		Supplier<ReviewSettings> settings, List<HealthCheck> checks, ProblemReporter reporter)
	{
		this.executor = executor;
		this.logs = logs;
		this.reviews = reviews;
		this.builder = builder;
		this.publisher = publisher;
		this.active = active;
		this.rawLogsKept = rawLogsKept;
		this.historySize = historySize;
		this.settings = settings;
		this.checks = checks;
		this.reporter = reporter;
	}

	@Override
	public void killEnded(KillLog kill)
	{
		try
		{
			pending = executor.submit(() -> handle(kill));
		}
		catch (RejectedExecutionException e)
		{
			log.warn("Plugin stopped before kill {} could be stored", kill.getHeader().getKillId());
		}
	}

	/** The latest submitted work, so client shutdown can wait for it. */
	public Future<?> pending()
	{
		return pending;
	}

	void handle(KillLog kill)
	{
		String killId = kill.getHeader().getKillId();
		try
		{
			logs.save(kill);
			logs.prune(Math.max(1, rawLogsKept.getAsInt()));
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not store raw log {}", killId, e);
		}

		KillReview review;
		try
		{
			ProjectionContext context = builder.run(kill, settings.get());
			List<CheckOutcome> outcomes = HealthCheckRunner.apply(kill, context, checks);
			review = KillReviewAssembler.assemble(kill, context).toBuilder()
				.failedChecks(CheckOutcome.failedNames(outcomes))
				.build()
				.withRecomputedStatus();
			if (CheckOutcome.anyFailed(outcomes))
			{
				Optional<String> report = reporter.reportFailedChecks(kill, context, outcomes);
				log.info("Health checks failed for kill {}: {}; report {}", killId, review.getFailedChecks(), report.orElse("not written"));
			}
		}
		catch (RuntimeException e)
		{
			log.warn("Could not review kill {}", killId, e);
			return;
		}

		int keep = Math.max(1, historySize.getAsInt());
		try
		{
			reviews.save(review);
			reviews.prune(keep);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not store review {}", killId, e);
		}
		HistoryIndex index;
		try
		{
			index = HistoryProjector.index(reviews.loadAll(), keep);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not load the history; showing this kill only", e);
			index = HistoryProjector.index(List.of(review), keep);
		}
		if (active.getAsBoolean())
		{
			try
			{
				publisher.publish(review, index);
			}
			catch (RuntimeException e)
			{
				log.warn("Could not publish review {}", killId, e);
			}
		}
	}
}
