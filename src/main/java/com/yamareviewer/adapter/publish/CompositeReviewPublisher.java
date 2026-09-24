package com.yamareviewer.adapter.publish;

import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.review.KillReview;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** Fans out to the chat and panel publishers; one failing publisher never stops the others. */
@Slf4j
public final class CompositeReviewPublisher implements ReviewPublisher
{
	private final List<ReviewPublisher> publishers;

	public CompositeReviewPublisher(List<ReviewPublisher> publishers)
	{
		this.publishers = List.copyOf(publishers);
	}

	@Override
	public void publish(KillReview review, HistoryIndex history)
	{
		for (ReviewPublisher publisher : publishers)
		{
			try
			{
				publisher.publish(review, history);
			}
			catch (RuntimeException e)
			{
				log.warn("Publisher {} failed", publisher.getClass().getSimpleName(), e);
			}
		}
	}

	@Override
	public void showHistory(HistoryIndex history)
	{
		for (ReviewPublisher publisher : publishers)
		{
			try
			{
				publisher.showHistory(history);
			}
			catch (RuntimeException e)
			{
				log.warn("Publisher {} failed", publisher.getClass().getSimpleName(), e);
			}
		}
	}
}
