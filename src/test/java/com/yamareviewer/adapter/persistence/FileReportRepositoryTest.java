package com.yamareviewer.adapter.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FileReportRepositoryTest
{
	private final InMemoryFileStore files = new InMemoryFileStore();
	private final FileReportRepository repository = new FileReportRepository(files);

	@Test
	public void savesUnderReportsAndReadsTheNewest() throws IOException
	{
		repository.save("20260924-153012-000.txt", "first");
		repository.save("20260924-160000-000.txt", "second");

		assertEquals(Optional.of("second"), repository.latest());
		assertEquals(List.of("20260924-153012-000.txt", "20260924-160000-000.txt"), files.list("reports"));
		assertEquals("first", new String(files.read("reports/20260924-153012-000.txt"), StandardCharsets.UTF_8));
	}

	@Test
	public void emptyWhenThereAreNoReports() throws IOException
	{
		assertEquals(Optional.empty(), repository.latest());
	}

	@Test
	public void keepsOnlyTheNewestReports() throws IOException
	{
		FileReportRepository small = new FileReportRepository(files, 2);

		small.save("20260924-100000-000.txt", "a");
		small.save("20260924-110000-000.txt", "b");
		small.save("20260924-120000-000.txt", "c");

		assertEquals(List.of("20260924-110000-000.txt", "20260924-120000-000.txt"), files.list("reports"));
		assertEquals(Optional.of("c"), small.latest());
	}

	@Test
	public void ignoresFilesThatAreNotReports() throws IOException
	{
		files.write("reports/notes.md", "x".getBytes(StandardCharsets.UTF_8));
		repository.save("20260924-100000-000.txt", "a");
		files.write("reports/zzz.tmp", "y".getBytes(StandardCharsets.UTF_8));

		assertEquals(Optional.of("a"), repository.latest());
	}
}
