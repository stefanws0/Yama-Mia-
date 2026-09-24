package com.yamareviewer.adapter.publish;

import com.yamareviewer.adapter.ui.ReviewPanel;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryProjector;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ClipboardExport;
import com.yamareviewer.domain.text.ReviewFormatter;
import com.yamareviewer.testing.Reviews;
import java.util.List;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.ArgumentMatchers.eq;

public class PanelReviewPublisherTest
{
	private final ReviewPanel panel = mock(ReviewPanel.class);
	private final PanelReviewPublisher publisher = new PanelReviewPublisher(panel);

	@Test
	public void handsViewModelsToThePanel()
	{
		KillReview review = Reviews.sample();
		HistoryIndex history = HistoryProjector.index(List.of(review), 50);

		publisher.publish(review, history);
		publisher.showHistory(history);

		verify(panel).show(eq(ReviewFormatter.format(review)), eq(ClipboardExport.text(review)), same(history));
		verify(panel).showHistory(same(history));
		verifyNoMoreInteractions(panel);
	}
}
