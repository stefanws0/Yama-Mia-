package com.yamareviewer.domain.diagnosis;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.testing.CheckFights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CandidateFinderTest
{
	private static final int UNKNOWN_CAST = 900_001;
	private static final int DISTRACTOR_GRAPHIC = 900_002;
	private static final int UNKNOWN_FIREBALL = 900_010;
	private static final int DISTRACTOR_GROUND = 900_011;
	private static final int UNKNOWN_SPEC = 900_020;
	private static final int DISTRACTOR_ANIMATION = 900_021;

	private static List<Candidate> find(KillLog log, Set<Role> roles)
	{
		return CandidateFinder.find(log, TestIds.registry(), roles);
	}

	@Test
	public void aRenumberedCastGraphicRanksFirst()
	{
		KillLogBuilder kill = KillLogBuilder.kill();
		for (int i = 0; i < 10; i++)
		{
			kill.yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).graphicOn(Actor.YAMA, UNKNOWN_CAST)
				.ticks(2).hitsplatOn(Actor.SELF, 5).ticks(5);
		}
		kill.graphicOn(Actor.YAMA, DISTRACTOR_GRAPHIC).ticks(3).graphicOn(Actor.YAMA, DISTRACTOR_GRAPHIC).ticks(1);

		List<Candidate> candidates = find(kill.end(EndReason.YAMA_DIED), Set.of(Role.YAMA_CAST_MAGIC));

		assertEquals(2, candidates.size());
		Candidate best = candidates.get(0);
		assertEquals(UNKNOWN_CAST, best.getId());
		assertEquals(Role.YAMA_CAST_MAGIC, best.getRole());
		assertEquals("YAMA", best.getWhere());
		assertEquals(10, best.getCount());
		assertEquals(0, best.getFirstTick());
		assertEquals(63, best.getLastTick());
		assertTrue(best.getScore() > candidates.get(1).getScore());
		assertTrue(best.getEvidence(), best.getEvidence().contains("gaps of 7 or 8 ticks: 9 of 9"));
		assertTrue(best.getEvidence(), best.getEvidence().contains("player hitsplat 1-3 ticks after: 10 of 10"));
		assertEquals(DISTRACTOR_GRAPHIC, candidates.get(1).getId());
	}

	@Test
	public void knownIdsAreNeverCandidates()
	{
		assertEquals(List.of(), find(CheckFights.healthySolo(), Set.of(Role.YAMA_STANDARD_ATTACK, Role.YAMA_CAST_MAGIC, Role.IMPACT_MAGIC,
			Role.CRASH_FIREBALL, Role.SPEC_EMBERLIGHT, Role.JUDGE)));
	}

	@Test
	public void crashFireballTripletsRankFirst()
	{
		KillLogBuilder kill = KillLogBuilder.kill();
		for (int set = 0; set < 3; set++)
		{
			kill.groundGraphic(UNKNOWN_FIREBALL, 3199, 3200).groundGraphic(UNKNOWN_FIREBALL, 3200, 3200)
				.groundGraphic(UNKNOWN_FIREBALL, 3201, 3200).ticks(2);
			kill.groundGraphic(DISTRACTOR_GROUND, 3205, 3205).ticks(3);
		}

		List<Candidate> candidates = find(kill.end(EndReason.YAMA_DIED), Set.of(Role.CRASH_FIREBALL));

		assertEquals(UNKNOWN_FIREBALL, candidates.get(0).getId());
		assertEquals("ground", candidates.get(0).getWhere());
		assertEquals(9, candidates.get(0).getCount());
		assertTrue(candidates.get(0).getEvidence(), candidates.get(0).getEvidence().contains("same-tick groups of three: 3 of 3 groups"));
		assertEquals(DISTRACTOR_GROUND, candidates.get(1).getId());
		assertEquals(0.0, candidates.get(1).getScore(), 0.0);
	}

	@Test
	public void aSpecAnimationAtTheUnmatchedEnergyDropRanksFirst()
	{
		KillLogBuilder kill = KillLogBuilder.kill().weapon(TestIds.id(Role.WEAPON_EMBERLIGHT)).spec(100);
		kill.animates(Actor.SELF, DISTRACTOR_ANIMATION).ticks(3);
		kill.animates(Actor.SELF, UNKNOWN_SPEC).spec(75).ticks(4);
		kill.animates(Actor.SELF, DISTRACTOR_ANIMATION).ticks(3);
		kill.animates(Actor.SELF, UNKNOWN_SPEC).spec(50).ticks(4);
		kill.animates(Actor.SELF, DISTRACTOR_ANIMATION).ticks(1);

		List<Candidate> candidates = find(kill.end(EndReason.YAMA_DIED), Set.of(Role.SPEC_EMBERLIGHT));

		assertEquals(UNKNOWN_SPEC, candidates.get(0).getId());
		assertEquals("SELF", candidates.get(0).getWhere());
		assertTrue(candidates.get(0).getEvidence(), candidates.get(0).getEvidence().contains("at an unmatched spec-energy drop: 2 of 2"));
		assertEquals(DISTRACTOR_ANIMATION, candidates.get(1).getId());
	}

	@Test
	public void unknownNpcSpawnsAreCandidatesForNpcRoles()
	{
		KillLog log = KillLogBuilder.kill().npcSpawns(Actor.other("npc 900030"), 900_030).ticks(2)
			.npcSpawns(Actor.other("npc 900030"), 900_030).end(EndReason.LEFT);

		List<Candidate> candidates = find(log, Set.of(Role.JUDGE));

		assertEquals(1, candidates.size());
		assertEquals(900_030, candidates.get(0).getId());
		assertEquals(2, candidates.get(0).getCount());
		assertEquals("OTHER", candidates.get(0).getWhere());
	}

	@Test
	public void unknownOverheadTextsFromYamaAreCandidates()
	{
		KillLog log = KillLogBuilder.kill().overhead(Actor.YAMA, "Kneel.").ticks(1).overhead(Actor.YAMA, "Enough.").end(EndReason.LEFT);

		List<Candidate> candidates = find(log, Set.of(Role.PHASE_TRANSITION_TEXT));

		assertEquals(1, candidates.size());
		assertEquals("Kneel.", candidates.get(0).getText());
		assertEquals(0, candidates.get(0).getId());
		assertEquals("YAMA", candidates.get(0).getWhere());
	}

	@Test
	public void candidatesAreGroupedByRoleInRoleOrder()
	{
		KillLog log = KillLogBuilder.kill().graphicOn(Actor.YAMA, UNKNOWN_CAST).graphicOn(Actor.SELF, DISTRACTOR_GRAPHIC).end(EndReason.LEFT);

		List<Candidate> candidates = find(log, Set.of(Role.IMPACT_MAGIC, Role.YAMA_CAST_MAGIC));

		assertEquals(List.of(Role.YAMA_CAST_MAGIC, Role.IMPACT_MAGIC),
			List.of(candidates.get(0).getRole(), candidates.get(1).getRole()));
		assertEquals(UNKNOWN_CAST, candidates.get(0).getId());
		assertEquals(DISTRACTOR_GRAPHIC, candidates.get(1).getId());
		assertNull(candidates.get(0).getText());
	}

	@Test
	public void kindsThatAreNotRecordedWithoutARoleGiveNothing()
	{
		KillLog log = KillLogBuilder.kill().varbit(900_040, 1).end(EndReason.LEFT);

		assertEquals(List.of(), find(log, Set.of(Role.PHASE_VARBIT)));
		assertEquals(List.of(), find(log, Set.of()));
	}
}
