package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SupplyItem;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.SupplyLine;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class KillReviewAssemblerTest
{
	private static final int JUDGE_ID = TestIds.id(Role.JUDGE);

	private static KillLog fullKill()
	{
		return KillLogBuilder.kill().entry(EntryChoice.TRAVEL)
			.supplies(SnapshotKind.START, new SupplyItem(385, "Shark", 5, 800, 90))
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(60).hitsplatOn(Actor.SELF, 12)
			.supplies(SnapshotKind.END, new SupplyItem(385, "Shark", 3, 800, 90))
			.end(EndReason.YAMA_DIED);
	}

	private static KillReview assemble(KillLog kill, IdRegistry ids)
	{
		ReviewBuilder builder = new ReviewBuilder(Projections.standard(), ids, Rules.DEFAULT);
		return KillReviewAssembler.assemble(kill, builder.run(kill, ReviewSettings.DEFAULT));
	}

	@Test
	public void aFullSyntheticKillAssemblesACompleteReview()
	{
		KillLog kill = fullKill();

		KillReview review = assemble(kill, TestIds.registry());

		assertEquals(KillReview.SCHEMA_VERSION, review.getReviewSchemaVersion());
		assertEquals("test-kill", review.getKillId());
		assertEquals(kill.getHeader().getStartEpochMs(), review.getStartEpochMs());
		assertEquals(kill.getHeader().getEndEpochMs(), review.getEndEpochMs());
		assertEquals(EndReason.YAMA_DIED, review.getEndReason());
		assertEquals(Mode.SOLO, review.getMode());
		assertEquals(Contract.NONE, review.getContract());
		assertEquals(ReviewStatus.COMPLETE, review.getStatus());
		assertEquals(0, review.getSkippedEvents());
		assertEquals(5, review.getPhases().value().getSpans().size());
		assertEquals(220, review.getPhases().value().getTotalTicks());
		assertEquals(12, review.getDamage().value().total(Actor.SELF));
		assertEquals(0, review.getFlares().value().spawned());
		assertEquals(List.of(new SupplyLine("Shark", 2, "x", 1_600L, false)), review.getSupplies().value().getLines());
		assertEquals(Section.hidden(HiddenReason.NOT_APPLICABLE), review.getDeathRecap());
		assertTrue(review.allSections().stream().allMatch(section -> section.isOk() || section.hiddenReason().get() == HiddenReason.NOT_APPLICABLE));
	}

	@Test
	public void skippedEventsMakeTheReviewIncomplete()
	{
		KillLog kill = fullKill();
		KillLog withSkipped = KillLog.of(kill.getHeader(), kill.getEvents(), 1);

		KillReview review = assemble(withSkipped, TestIds.registry());

		assertEquals(1, review.getSkippedEvents());
		assertEquals(ReviewStatus.INCOMPLETE, review.getStatus());
	}

	@Test
	public void hiddenSectionsAreCarriedOverWithoutMakingTheReviewIncomplete()
	{
		KillReview review = assemble(fullKill(), TestIds.registryWithout(Role.JUDGE, Role.VOID_FLARE));

		assertEquals(Section.hidden(HiddenReason.IDS_NOT_CAPTURED), review.getPhases());
		assertEquals(Section.hidden(HiddenReason.IDS_NOT_CAPTURED), review.getFlares());
		assertEquals(ReviewStatus.COMPLETE, review.getStatus());
		assertEquals(Mode.SOLO, review.getMode());
	}
}
