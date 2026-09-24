package com.yamareviewer.adapter.persistence;

import com.google.gson.Gson;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.RecapHit;
import com.yamareviewer.domain.review.RecapTick;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Reviews;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GsonReviewRepositoryTest
{
	private final InMemoryFileStore files = new InMemoryFileStore();
	private final GsonReviewRepository repository = new GsonReviewRepository(files, new Gson());

	private static KillReview review(String id, long start, Mode mode, Contract contract)
	{
		return Reviews.builder(id, start, mode, contract).build();
	}

	private List<String> loadedIds() throws IOException
	{
		return repository.loadAll().stream().map(KillReview::getKillId).collect(toList());
	}

	@Test
	public void savedReviewLoadsBackEqual() throws IOException
	{
		KillReview review = Reviews.builder("a", 1_000L, Mode.DUO_JOINER, Contract.BLOODIED_BLOWS)
			.endReason(EndReason.PLAYER_DIED)
			.flares(Section.hidden(HiddenReason.IDS_NOT_CAPTURED))
			.deathRecap(Section.ok(new DeathRecap(List.of(
				new RecapTick(419, List.of(new RecapHit(DamageSource.MELEE, 22)), 12, 5, null, "you", List.of("Shark")),
				new RecapTick(420, List.of(), 0, 5, 7, "partner", List.of())), false)))
			.build();

		repository.save(review);

		assertEquals(List.of(review), repository.loadAll());
		assertTrue(files.paths().contains("reviews/DUO_JOINER/0000000001000-a.json"));
		assertEquals("reviews/DUO_JOINER/0000000001000-a.json", GsonReviewRepository.path(review));
	}

	@Test
	public void loadsNewestFirstAcrossModes() throws IOException
	{
		repository.save(review("a", 1_000L, Mode.SOLO, Contract.NONE));
		repository.save(review("c", 3_000L, Mode.DUO_HOST, Contract.NONE));
		repository.save(review("b", 2_000L, Mode.SOLO, Contract.NONE));

		assertEquals(List.of("c", "b", "a"), loadedIds());
	}

	@Test
	public void pruneKeepsTheNewestPerModeAndContract() throws IOException
	{
		repository.save(review("s1", 1_000L, Mode.SOLO, Contract.NONE));
		repository.save(review("s2", 2_000L, Mode.SOLO, Contract.NONE));
		repository.save(review("s3", 3_000L, Mode.SOLO, Contract.NONE));
		repository.save(review("b1", 1_500L, Mode.SOLO, Contract.BLOODIED_BLOWS));
		repository.save(review("b2", 2_500L, Mode.SOLO, Contract.BLOODIED_BLOWS));
		repository.save(review("h1", 100L, Mode.DUO_HOST, Contract.NONE));

		repository.prune(2);

		assertEquals(List.of("s3", "b2", "s2", "b1", "h1"), loadedIds());
	}

	@Test
	public void otherSchemaVersionsAreSkippedButKept() throws IOException
	{
		repository.save(review("current", 2_000L, Mode.SOLO, Contract.NONE));
		repository.save(Reviews.builder("future", 3_000L, Mode.SOLO, Contract.NONE).reviewSchemaVersion(KillReview.SCHEMA_VERSION + 1).build());

		assertEquals(List.of("current"), loadedIds());
		assertEquals(2, files.list("reviews/SOLO").size());
	}

	@Test
	public void corruptFileIsRenamedAndSkipped() throws IOException
	{
		repository.save(review("good", 2_000L, Mode.SOLO, Contract.NONE));
		files.write("reviews/SOLO/0000000000500-bad.json", "{not json".getBytes(StandardCharsets.UTF_8));

		assertEquals(List.of("good"), loadedIds());
		assertTrue(files.paths().contains("reviews/SOLO/0000000000500-bad.json.corrupt"));
		assertFalse(files.paths().contains("reviews/SOLO/0000000000500-bad.json"));
	}

	@Test
	public void anEmptyStoreLoadsNothing() throws IOException
	{
		assertEquals(List.of(), repository.loadAll());
		repository.prune(5);
	}
}
