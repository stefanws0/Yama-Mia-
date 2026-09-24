package com.yamareviewer;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.tools.RawLogs;
import com.yamareviewer.tools.Replay;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Assume;
import org.junit.Test;

/**
 * Golden tests (spec 12): every scrubbed logging kill in src/test/resources/fixtures/ must review to the
 * JSON next to it, which was generated with the replay tool and checked by hand. Skips until fixtures exist.
 */
public class GoldenReviewTest
{
	static final Path FIXTURES = Paths.get("src", "test", "resources", "fixtures");
	static final String LOG_SUFFIX = ".jsonl.gz";
	static final String EXPECTED_SUFFIX = ".review.json";

	@Test
	public void everyFixtureReviewsToItsExpectedJson() throws IOException
	{
		List<Path> logs;
		try (Stream<Path> files = Files.list(FIXTURES))
		{
			logs = files.filter(path -> path.getFileName().toString().endsWith(LOG_SUFFIX)).sorted().collect(Collectors.toList());
		}
		Assume.assumeFalse("No golden fixtures yet: do the logging kills (docs/logging-kills.md), then Task 16 steps 7-9", logs.isEmpty());

		for (Path logPath : logs)
		{
			String name = logPath.getFileName().toString();
			Path expectedPath = FIXTURES.resolve(name.substring(0, name.length() - LOG_SUFFIX.length()) + EXPECTED_SUFFIX);
			assertTrue("Missing " + expectedPath + ": generate it with ./gradlew replay --args=\"" + logPath + " " + expectedPath + "\"",
				Files.exists(expectedPath));

			KillLog log = RawLogs.read(logPath);
			KillReview review = Replay.review(log);
			// RuneLite ships Gson 2.8.5, which has no static JsonParser.parseString
			JsonElement expected = new JsonParser().parse(new String(Files.readAllBytes(expectedPath), StandardCharsets.UTF_8));
			JsonElement actual = new JsonParser().parse(Replay.json(review));

			assertEquals(name, Replay.GSON.toJson(expected), Replay.GSON.toJson(actual));
			assertEquals(name + " must be a complete review", ReviewStatus.COMPLETE, review.getStatus());
			for (Section<?> section : review.allSections())
			{
				assertNotEquals(name + " has a section hidden by an error", HiddenReason.ERROR, section.hiddenReason().orElse(null));
			}
			assertTrue(name + ": prayer review hidden", review.getPrayerReview().isOk());
			assertTrue(name + ": tick log hidden", review.getTickLog().isOk());
			assertTrue(name + ": crash lines hidden", review.getCrashes().isOk());
			assertTrue(name + ": waves hidden", review.getWaves().isOk());
			assertTrue(name + ": specs hidden", review.getSpecs().isOk());
		}
	}
}
