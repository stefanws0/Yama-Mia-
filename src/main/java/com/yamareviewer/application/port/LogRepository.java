package com.yamareviewer.application.port;

import com.yamareviewer.domain.model.KillLog;
import java.io.IOException;
import java.util.List;

/** Stores the write model: one raw event log per kill. */
public interface LogRepository
{
	void save(KillLog kill) throws IOException;

	/**
	 * Newest first, current schema version only. Unreadable files are renamed with a .corrupt suffix
	 * and skipped; logs of another schema version are skipped and left in place.
	 */
	List<KillLog> loadAll() throws IOException;

	/** Deletes the oldest raw logs so that at most {@code keep} remain. */
	void prune(int keep) throws IOException;
}
