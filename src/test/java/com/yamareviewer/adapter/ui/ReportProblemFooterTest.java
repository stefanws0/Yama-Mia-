package com.yamareviewer.adapter.ui;

import com.yamareviewer.application.port.ReportRepository;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Test;

public class ReportProblemFooterTest
{
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final FakeReports reports = new FakeReports();
	private final ReportProblemFooter footer = new ReportProblemFooter(executor, reports, "https://github.com/stefanws0/Yama-Mia-");

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private void refreshAndWait() throws Exception
	{
		footer.refresh();
		executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
		SwingUtilities.invokeAndWait(() -> { });
	}

	@Test
	public void theButtonIsEnabledOnlyWhenAReportExists() throws Exception
	{
		assertFalse(footer.isReportAvailable());

		refreshAndWait();
		assertFalse(footer.isReportAvailable());
		assertEquals("No report yet", footer.hintText());

		reports.latest = Optional.of("Yama Reviewer report: x\n");
		refreshAndWait();
		assertTrue(footer.isReportAvailable());
		assertEquals("Latest report ready to send", footer.hintText());
	}

	@Test
	public void aStorageErrorDisablesTheButton() throws Exception
	{
		reports.latest = Optional.of("Yama Reviewer report: x\n");
		refreshAndWait();
		reports.fail = true;

		refreshAndWait();

		assertFalse(footer.isReportAvailable());
	}

	private static final class FakeReports implements ReportRepository
	{
		private volatile Optional<String> latest = Optional.empty();
		private volatile boolean fail;

		@Override
		public void save(String fileName, String text)
		{
		}

		@Override
		public Optional<String> latest() throws IOException
		{
			if (fail)
			{
				throw new IOException("unreadable");
			}
			return latest;
		}
	}
}
