package com.yamareviewer.adapter.persistence;

import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import lombok.extern.slf4j.Slf4j;

/** raw/<startEpochMs, 13 digits>-<killId>.jsonl.gz: line 1 the header, then one event per line. */
@Slf4j
public final class GsonLogRepository implements LogRepository
{
	private static final String RAW = "raw";
	private static final String SUFFIX = ".jsonl.gz";

	private final FileStore files;
	private final EventCodec codec;

	public GsonLogRepository(FileStore files, EventCodec codec)
	{
		this.files = files;
		this.codec = codec;
	}

	@Override
	public void save(KillLog kill) throws IOException
	{
		files.write(RAW + "/" + fileName(kill.getHeader()), gzip(lines(kill).getBytes(StandardCharsets.UTF_8)));
	}

	@Override
	public List<KillLog> loadAll() throws IOException
	{
		List<String> names = logFileNames();
		List<KillLog> kills = new ArrayList<>();
		for (int i = names.size() - 1; i >= 0; i--)
		{
			String path = RAW + "/" + names.get(i);
			try
			{
				parse(new String(gunzip(files.read(path)), StandardCharsets.UTF_8)).ifPresent(kills::add);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Unreadable raw log {}, renaming it to .corrupt", path, e);
				files.rename(path, path + ".corrupt");
			}
		}
		return kills;
	}

	@Override
	public void prune(int keep) throws IOException
	{
		List<String> names = logFileNames();
		for (int i = 0; i < names.size() - keep; i++)
		{
			files.delete(RAW + "/" + names.get(i));
		}
	}

	static String fileName(KillHeader header)
	{
		return String.format("%013d-%s%s", header.getStartEpochMs(), header.getKillId(), SUFFIX);
	}

	static byte[] gzip(byte[] data) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (OutputStream out = new GZIPOutputStream(bytes))
		{
			out.write(data);
		}
		return bytes.toByteArray();
	}

	private static byte[] gunzip(byte[] data) throws IOException
	{
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data)))
		{
			return in.readAllBytes();
		}
	}

	/** Empty for a log of another schema version. */
	private Optional<KillLog> parse(String text)
	{
		String[] lines = text.split("\n");
		KillHeader header = codec.decodeHeader(lines[0]);
		if (header.getSchemaVersion() != KillLog.SCHEMA_VERSION)
		{
			return Optional.empty();
		}
		List<DomainEvent> events = new ArrayList<>();
		int skipped = 0;
		for (int i = 1; i < lines.length; i++)
		{
			if (lines[i].isBlank())
			{
				continue;
			}
			Optional<DomainEvent> event = codec.decode(lines[i]);
			if (event.isPresent())
			{
				events.add(event.get());
			}
			else
			{
				skipped++;
			}
		}
		return Optional.of(KillLog.of(header, events, skipped));
	}

	private String lines(KillLog kill)
	{
		StringBuilder text = new StringBuilder(codec.encodeHeader(kill.getHeader())).append('\n');
		for (DomainEvent event : kill.getEvents())
		{
			text.append(codec.encode(event)).append('\n');
		}
		return text.toString();
	}

	private List<String> logFileNames() throws IOException
	{
		return files.list(RAW).stream().filter(name -> name.endsWith(SUFFIX)).collect(Collectors.toList());
	}
}
