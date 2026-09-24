package com.yamareviewer.adapter.ui;

import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ClipboardExport;
import com.yamareviewer.domain.text.ReviewFormatter;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;

/** Loads a past kill's review on the executor and hands its view model to the panel (spec 7.3, "selecting a kill"). */
@Slf4j
public final class KillReviewOpener
{
	private final ExecutorService executor;
	private final ReviewRepository reviews;
	private final ReviewPanel panel;

	public KillReviewOpener(ExecutorService executor, ReviewRepository reviews, ReviewPanel panel)
	{
		this.executor = executor;
		this.reviews = reviews;
		this.panel = panel;
	}

	public void open(String killId)
	{
		try
		{
			executor.execute(() -> openNow(killId));
		}
		catch (RejectedExecutionException e)
		{
			log.warn("Plugin stopped before kill {} could be opened", killId);
		}
	}

	private void openNow(String killId)
	{
		try
		{
			Optional<KillReview> review = reviews.loadAll().stream().filter(candidate -> candidate.getKillId().equals(killId)).findFirst();
			review.ifPresent(found -> panel.showKill(found.getMode(), ReviewFormatter.format(found), ClipboardExport.text(found)));
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not open review {}", killId, e);
		}
	}
}
