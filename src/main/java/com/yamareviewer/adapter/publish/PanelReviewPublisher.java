package com.yamareviewer.adapter.publish;

import com.yamareviewer.adapter.ui.ReviewPanel;
import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ClipboardExport;
import com.yamareviewer.domain.text.ReviewFormatter;

/** Turns the review into immutable view models on the executor; the panel hops to the Swing thread itself. */
public final class PanelReviewPublisher implements ReviewPublisher
{
	private final ReviewPanel panel;

	public PanelReviewPublisher(ReviewPanel panel)
	{
		this.panel = panel;
	}

	@Override
	public void publish(KillReview review, HistoryIndex history)
	{
		panel.show(ReviewFormatter.format(review), ClipboardExport.text(review), history);
	}

	@Override
	public void showHistory(HistoryIndex history)
	{
		panel.showHistory(history);
	}
}
