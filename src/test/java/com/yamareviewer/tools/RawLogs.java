package com.yamareviewer.tools;

import com.google.gson.Gson;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.adapter.persistence.GsonLogRepository;
import com.yamareviewer.adapter.persistence.InMemoryFileStore;
import com.yamareviewer.domain.model.KillLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Development tools only: read and write one raw log file through the production codec. */
public final class RawLogs
{
	private RawLogs()
	{
	}

	/** The file name must end with .jsonl.gz, as the recorder names them. */
	public static KillLog read(Path path) throws IOException
	{
		InMemoryFileStore files = new InMemoryFileStore();
		files.write("raw/" + path.getFileName(), Files.readAllBytes(path));
		List<KillLog> logs = new GsonLogRepository(files, new EventCodec(new Gson())).loadAll();
		if (logs.isEmpty())
		{
			throw new IOException("Not a readable raw log of the current schema: " + path);
		}
		return logs.get(0);
	}

	public static void write(KillLog log, Path path) throws IOException
	{
		InMemoryFileStore files = new InMemoryFileStore();
		new GsonLogRepository(files, new EventCodec(new Gson())).save(log);
		String stored = "raw/" + files.list("raw").get(0);
		if (path.getParent() != null)
		{
			Files.createDirectories(path.getParent());
		}
		Files.write(path, files.read(stored));
	}
}
