package com.yamareviewer.application.port;

import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.review.KillReview;

/** The only way a review reaches the user. Called on the plugin executor, never on the client thread. */
public interface ReviewPublisher
{
	void publish(KillReview review, HistoryIndex history);

	void showHistory(HistoryIndex history);
}
