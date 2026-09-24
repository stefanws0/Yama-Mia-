package com.yamareviewer.application.port;

import com.yamareviewer.domain.review.KillReview;
import java.io.IOException;
import java.util.List;

/** Stores the read model: one review per kill. */
public interface ReviewRepository
{
	void save(KillReview review) throws IOException;

	/** Newest first; files with another reviewSchemaVersion are skipped, unreadable files renamed .corrupt. */
	List<KillReview> loadAll() throws IOException;

	/** Keeps the newest keepPerKey reviews per HistoryKey (mode and contract). */
	void prune(int keepPerKey) throws IOException;
}
