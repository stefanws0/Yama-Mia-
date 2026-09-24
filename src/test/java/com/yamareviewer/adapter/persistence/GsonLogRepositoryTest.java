package com.yamareviewer.adapter.persistence;

import com.google.gson.Gson;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GsonLogRepositoryTest
{
	private final InMemoryFileStore files = new InMemoryFileStore();
	private final EventCodec codec = new EventCodec(new Gson());
	private final GsonLogRepository repository = new GsonLogRepository(files, codec);

	private static KillLog kill(String id, long start, int schemaVersion)
	{
		KillHeader header = new KillHeader(id, start, start + 60_000, "0.1.0", schemaVersion, "f00d", false);
		return KillLog.of(header, List.of(new AnimationObserved(0, Actor.YAMA, 1), new FightEnded(5, EndReason.YAMA_DIED)), 0);
	}

	private static KillLog kill(String id, long start)
	{
		return kill(id, start, KillLog.SCHEMA_VERSION);
	}

	private List<String> loadedIds() throws IOException
	{
		return repository.loadAll().stream().map(k -> k.getHeader().getKillId()).collect(toList());
	}

	@Test
	public void savedLogLoadsBackEqual() throws IOException
	{
		KillLog kill = kill("a", 1_000);

		repository.save(kill);

		assertEquals(List.of(kill), repository.loadAll());
	}

	@Test
	public void loadsNewestFirst() throws IOException
	{
		repository.save(kill("old", 1_000));
		repository.save(kill("new", 2_000));

		assertEquals(List.of("new", "old"), loadedIds());
	}

	@Test
	public void pruneKeepsTheNewest() throws IOException
	{
		repository.save(kill("1", 1_000));
		repository.save(kill("2", 2_000));
		repository.save(kill("3", 3_000));

		repository.prune(2);

		assertEquals(List.of("3", "2"), loadedIds());
	}

	@Test
	public void corruptFileIsRenamedAndSkipped() throws IOException
	{
		repository.save(kill("good", 2_000));
		files.write("raw/0000000001000-bad.jsonl.gz", new byte[]{1, 2, 3});

		assertEquals(List.of("good"), loadedIds());
		assertTrue(files.paths().contains("raw/0000000001000-bad.jsonl.gz.corrupt"));
		assertFalse(files.paths().contains("raw/0000000001000-bad.jsonl.gz"));
	}

	@Test
	public void otherSchemaVersionsAreSkippedButKept() throws IOException
	{
		repository.save(kill("current", 2_000));
		repository.save(kill("future", 3_000, KillLog.SCHEMA_VERSION + 1));

		assertEquals(List.of("current"), loadedIds());
		assertEquals(2, files.list("raw").size());
	}

	@Test
	public void unknownEventTypesAreCountedNotFatal() throws IOException
	{
		KillHeader header = kill("x", 1_000).getHeader();
		String text = codec.encodeHeader(header) + "\n"
			+ "{\"type\":\"from-the-future\",\"data\":{}}\n"
			+ codec.encode(new FightEnded(3, EndReason.LEFT)) + "\n";
		files.write("raw/" + GsonLogRepository.fileName(header), GsonLogRepository.gzip(text.getBytes(StandardCharsets.UTF_8)));

		KillLog loaded = repository.loadAll().get(0);

		assertEquals(1, loaded.getSkippedEvents());
		assertEquals(1, loaded.getEvents().size());
	}
}
