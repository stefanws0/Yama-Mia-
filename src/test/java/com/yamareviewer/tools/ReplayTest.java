package com.yamareviewer.tools;

import com.google.gson.Gson;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ReviewFormatter;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ReplayTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static KillLog kill()
	{
		return KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, TestIds.id(Role.JUDGE))
			.ticks(30).npcDespawns(Actor.JUDGE, TestIds.id(Role.JUDGE), true)
			.ticks(20).hitsplatOn(Actor.SELF, 9).end(EndReason.YAMA_DIED);
	}

	@Test
	public void reviewsAKillAndRendersIt()
	{
		KillReview review = Replay.review(kill(), TestIds.registry());
		String text = Replay.text(ReviewFormatter.format(review));

		assertEquals(Mode.SOLO, review.getMode());
		assertTrue(text, text.startsWith("Yama (solo) 1:00"));
		assertTrue(text, text.contains("Complete"));
		assertTrue(text, text.contains("Phases"));
		assertTrue(text, text.contains("You: 9"));
	}

	@Test
	public void readsARawLogFile() throws IOException
	{
		KillLog kill = kill();
		EventCodec codec = new EventCodec(new Gson());
		StringBuilder lines = new StringBuilder(codec.encodeHeader(kill.getHeader())).append('\n');
		for (DomainEvent event : kill.getEvents())
		{
			lines.append(codec.encode(event)).append('\n');
		}
		lines.append("{\"type\":\"from-the-future\",\"data\":{}}\n");
		Path file = folder.newFile("0000000000001-test-kill.jsonl.gz").toPath();
		try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file)))
		{
			out.write(lines.toString().getBytes(StandardCharsets.UTF_8));
		}

		KillLog read = Replay.read(file);

		assertEquals(kill.getHeader(), read.getHeader());
		assertEquals(kill.getEvents(), read.getEvents());
		assertEquals(1, read.getSkippedEvents());
	}
}
