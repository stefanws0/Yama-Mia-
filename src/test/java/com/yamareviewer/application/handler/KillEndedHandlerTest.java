package com.yamareviewer.application.handler;

import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Test;

public class KillEndedHandlerTest
{
	private final FakeLogs logs = new FakeLogs();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "yama-reviewer-test"));

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private static KillLog kill()
	{
		return KillLog.of(new KillHeader("k", 1L, 2L, "0.1.0", KillLog.SCHEMA_VERSION, "f00d", false), List.of(), 0);
	}

	@Test
	public void savesThenPrunesOnTheExecutor() throws Exception
	{
		KillEndedHandler handler = new KillEndedHandler(executor, logs, () -> 20);

		handler.killEnded(kill());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertEquals(List.of("save k on yama-reviewer-test", "prune 20"), logs.calls);
	}

	@Test
	public void storageFailureDoesNotEscape() throws Exception
	{
		logs.fail = true;
		KillEndedHandler handler = new KillEndedHandler(executor, logs, () -> 20);

		handler.killEnded(kill());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertTrue(logs.calls.isEmpty());
	}

	@Test
	public void aStoppedExecutorIsLoggedNotThrown()
	{
		executor.shutdownNow();
		KillEndedHandler handler = new KillEndedHandler(executor, logs, () -> 20);

		handler.killEnded(kill());

		assertTrue(handler.pending().isDone());
	}

	private static final class FakeLogs implements LogRepository
	{
		private final List<String> calls = new CopyOnWriteArrayList<>();
		private volatile boolean fail;

		@Override
		public void save(KillLog kill) throws IOException
		{
			if (fail)
			{
				throw new IOException("disk full");
			}
			calls.add("save " + kill.getHeader().getKillId() + " on " + Thread.currentThread().getName());
		}

		@Override
		public List<KillLog> loadAll()
		{
			return List.of();
		}

		@Override
		public void prune(int keep)
		{
			calls.add("prune " + keep);
		}
	}
}
