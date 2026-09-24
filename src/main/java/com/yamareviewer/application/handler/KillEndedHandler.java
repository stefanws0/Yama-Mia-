package com.yamareviewer.application.handler;

import com.yamareviewer.application.command.KillEndedListener;
import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.domain.model.KillLog;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;

/** Everything that happens after a kill, on the plugin's own executor. Part 1: persist the raw log. */
@Slf4j
public final class KillEndedHandler implements KillEndedListener
{
	private final ExecutorService executor;
	private final LogRepository logs;
	private final IntSupplier rawLogsKept;
	private volatile Future<?> pending = CompletableFuture.completedFuture(null);

	public KillEndedHandler(ExecutorService executor, LogRepository logs, IntSupplier rawLogsKept)
	{
		this.executor = executor;
		this.logs = logs;
		this.rawLogsKept = rawLogsKept;
	}

	@Override
	public void killEnded(KillLog kill)
	{
		try
		{
			pending = executor.submit(() -> handle(kill));
		}
		catch (RejectedExecutionException e)
		{
			log.warn("Plugin stopped before kill {} could be stored", kill.getHeader().getKillId());
		}
	}

	/** The latest submitted work, so client shutdown can wait for it. */
	public Future<?> pending()
	{
		return pending;
	}

	void handle(KillLog kill)
	{
		try
		{
			logs.save(kill);
			logs.prune(Math.max(1, rawLogsKept.getAsInt()));
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not store raw log {}", kill.getHeader().getKillId(), e);
		}
	}
}
