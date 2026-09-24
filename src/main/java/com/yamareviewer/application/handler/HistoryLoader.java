package com.yamareviewer.application.handler;

import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.history.HistoryProjector;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;

/** Builds the history from the stored reviews on the executor at start-up and shows it in the panel (spec 7.5). */
@Slf4j
public final class HistoryLoader
{
	private final ExecutorService executor;
	private final ReviewRepository reviews;
	private final ReviewPublisher publisher;
	private final IntSupplier historySize;

	public HistoryLoader(ExecutorService executor, ReviewRepository reviews, ReviewPublisher publisher, IntSupplier historySize)
	{
		this.executor = executor;
		this.reviews = reviews;
		this.publisher = publisher;
		this.historySize = historySize;
	}

	public void load()
	{
		try
		{
			executor.execute(this::loadNow);
		}
		catch (RejectedExecutionException e)
		{
			log.warn("Plugin stopped before the history could be loaded");
		}
	}

	private void loadNow()
	{
		try
		{
			publisher.showHistory(HistoryProjector.index(reviews.loadAll(), Math.max(1, historySize.getAsInt())));
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not load the review history", e);
		}
	}
}
