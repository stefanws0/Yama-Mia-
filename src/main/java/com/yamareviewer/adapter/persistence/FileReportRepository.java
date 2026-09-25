package com.yamareviewer.adapter.persistence;

import com.yamareviewer.application.port.ReportRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** reports/<timestamp>.txt under the plugin directory; only the newest reports are kept. */
public final class FileReportRepository implements ReportRepository
{
	static final String DIR = "reports";
	static final String SUFFIX = ".txt";
	/** A broken id writes one report per kill; this keeps the folder small. */
	static final int KEPT = 50;

	private final FileStore files;
	private final int kept;

	public FileReportRepository(FileStore files)
	{
		this(files, KEPT);
	}

	FileReportRepository(FileStore files, int kept)
	{
		this.files = files;
		this.kept = kept;
	}

	@Override
	public void save(String fileName, String text) throws IOException
	{
		files.write(DIR + "/" + fileName, text.getBytes(StandardCharsets.UTF_8));
		List<String> names = reportNames();
		for (int i = 0; i < names.size() - kept; i++)
		{
			files.delete(DIR + "/" + names.get(i));
		}
	}

	@Override
	public Optional<String> latest() throws IOException
	{
		List<String> names = reportNames();
		if (names.isEmpty())
		{
			return Optional.empty();
		}
		return Optional.of(new String(files.read(DIR + "/" + names.get(names.size() - 1)), StandardCharsets.UTF_8));
	}

	/** Ascending, which is chronological for the timestamp names ProblemReporter uses. */
	private List<String> reportNames() throws IOException
	{
		return files.list(DIR).stream().filter(name -> name.endsWith(SUFFIX)).collect(Collectors.toList());
	}
}
