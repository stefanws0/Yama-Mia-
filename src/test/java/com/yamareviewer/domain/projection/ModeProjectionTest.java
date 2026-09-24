package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.PriceMode;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ModeProjectionTest
{
	private static final int JUDGE_ID = TestIds.id(Role.JUDGE);

	private static Mode project(KillLog kill)
	{
		return project(kill, ReviewSettings.DEFAULT);
	}

	private static Mode project(KillLog kill, ReviewSettings settings)
	{
		ReviewBuilder builder = new ReviewBuilder(List.of(new PhasesProjection(), new ModeProjection()), TestIds.registry(), Rules.DEFAULT);
		return builder.run(kill, settings).value(Sections.MODE).get();
	}

	/** Continues a builder with a Judge phase from tick 50 to 80 and ends at 100. */
	private static KillLog withJudge(KillLogBuilder kill)
	{
		return kill.ticks(50 - kill.tick()).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(20).end(EndReason.LEFT);
	}

	@Test
	public void theConfigOverrideWins()
	{
		KillLog kill = KillLogBuilder.kill().entry(EntryChoice.JOIN).playerSeen("Buddy").ticks(5).end(EndReason.LEFT);

		assertEquals(Mode.SOLO, project(kill, new ReviewSettings(PriceMode.GRAND_EXCHANGE, Mode.SOLO)));
	}

	@Test
	public void joinMeansDuoJoiner()
	{
		assertEquals(Mode.DUO_JOINER, project(KillLogBuilder.kill().entry(EntryChoice.JOIN).ticks(5).end(EndReason.LEFT)));
	}

	@Test
	public void travelWithAPartnerMeansDuoHost()
	{
		assertEquals(Mode.DUO_HOST, project(KillLogBuilder.kill().entry(EntryChoice.TRAVEL).playerSeen("Buddy").ticks(5).end(EndReason.LEFT)));
		assertEquals(Mode.DUO_HOST, project(withJudge(KillLogBuilder.kill().entry(EntryChoice.TRAVEL).ticks(70).playerSeen("Buddy"))));
	}

	@Test
	public void travelAloneMeansSolo()
	{
		assertEquals(Mode.SOLO, project(KillLogBuilder.kill().entry(EntryChoice.TRAVEL).ticks(5).end(EndReason.LEFT)));
	}

	@Test
	public void aPlayerSeenAfterTheFirstJudgeEndedIsNotAPartner()
	{
		assertEquals(Mode.SOLO, project(withJudge(KillLogBuilder.kill().entry(EntryChoice.TRAVEL).ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true).ticks(10).playerSeen("Spectator"))));
		assertEquals(Mode.SOLO, project(withJudge(KillLogBuilder.kill().ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true).ticks(1).playerSeen("Spectator"))));
	}

	@Test
	public void withoutAnEntryChoiceThePositionAtTheFirstJudgeDecides()
	{
		KillLog east = withJudge(KillLogBuilder.kill().playerSeen("Buddy").ticks(55).selfAt(3220, 3200).partnerAt(3200, 3200));
		KillLog west = withJudge(KillLogBuilder.kill().playerSeen("Buddy").ticks(55).selfAt(3200, 3200).partnerAt(3220, 3200));

		assertEquals(Mode.DUO_JOINER, project(east));
		assertEquals(Mode.DUO_HOST, project(west));
	}

	@Test
	public void withoutAnEntryChoiceAndNoPositionsAPartnerMeansDuoHost()
	{
		assertEquals(Mode.DUO_HOST, project(withJudge(KillLogBuilder.kill().playerSeen("Buddy"))));
		assertEquals(Mode.DUO_HOST, project(KillLogBuilder.kill().playerSeen("Buddy").ticks(5).end(EndReason.LEFT)));
	}

	@Test
	public void withoutAnEntryChoiceAndNobodySeenMeansSolo()
	{
		assertEquals(Mode.SOLO, project(withJudge(KillLogBuilder.kill())));
	}

	@Test
	public void keyAndRoles()
	{
		ModeProjection projection = new ModeProjection();

		assertEquals(Sections.MODE, projection.key());
		assertEquals(Set.of(), projection.requiredRoles());
	}
}
