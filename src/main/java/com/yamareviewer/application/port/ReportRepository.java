package com.yamareviewer.application.port;

import java.io.IOException;
import java.util.Optional;

/** Stores problem reports (spec 9). File names sort chronologically. */
public interface ReportRepository
{
	void save(String fileName, String text) throws IOException;

	/** The text of the newest report, empty when there is none. */
	Optional<String> latest() throws IOException;
}
