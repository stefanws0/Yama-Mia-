package com.yamareviewer.adapter.publish;

import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryProjector;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.testing.Reviews;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class CompositeReviewPublisherTest
{
	private final List<String> calls = new ArrayList<>();

	private ReviewPublisher publisher(String name, boolean fail)
	{
		return new ReviewPublisher()
		{
			@Override
			public void publish(KillReview review, HistoryIndex history)
			{
				calls.add(name + " publish " + review.getKillId());
				if (fail)
				{
					throw new IllegalStateException(name);
				}
			}

			@Override
			public void showHistory(HistoryIndex history)
			{
				calls.add(name + " history");
				if (fail)
				{
					throw new IllegalStateException(name);
				}
			}
		};
	}

	@Test
	public void forwardsToEveryPublisherEvenWhenOneFails()
	{
		CompositeReviewPublisher composite = new CompositeReviewPublisher(List.of(publisher("chat", true), publisher("panel", false)));
		HistoryIndex history = HistoryProjector.index(List.of(), 50);

		composite.publish(Reviews.sample(), history);
		composite.showHistory(history);

		assertEquals(List.of("chat publish kill-1", "panel publish kill-1", "chat history", "panel history"), calls);
	}
}
