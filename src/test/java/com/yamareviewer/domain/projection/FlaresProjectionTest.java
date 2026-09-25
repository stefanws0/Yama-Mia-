package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.GameStateKind;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.FlareCause;
import com.yamareviewer.domain.review.FlareFate;
import com.yamareviewer.domain.review.FlareResult;
import com.yamareviewer.domain.review.FlareSummary;
import com.yamareviewer.domain.review.FlareWave;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class FlaresProjectionTest
{
	private static final int FLARE_ID = TestIds.id(Role.VOID_FLARE);
	private static final int SUMMON = TestIds.id(Role.YAMA_FLARE_SUMMON);
	private static final int MELEE = TestIds.id(Role.YAMA_MELEE);
	private static final int EXPLODE = TestIds.id(Role.FLARE_EXPLODE);
	private static final int DEATH = TestIds.id(Role.FLARE_DEATH);
	private static final int HEAL_GRAPHIC = TestIds.id(Role.FLARE_HEAL);
	private static final int PURGING_SPEC = TestIds.id(Role.SPEC_PURGING_STAFF);

	private static FlareSummary project(KillLog kill)
	{
		return project(kill, TestIds.registry());
	}

	private static FlareSummary project(KillLog kill, IdRegistry ids)
	{
		return new FlaresProjection().project(kill, new ProjectionContext(ids, Rules.DEFAULT, ReviewSettings.DEFAULT)).value();
	}

	private static Actor flare(int index)
	{
		return Actor.flare(index);
	}

	@Test
	public void aSummonedWaveWithAKillAndAnExplosion()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(20).yamaAnimates(SUMMON)
			.ticks(2).npcSpawns(flare(1), FLARE_ID).npcSpawns(flare(2), FLARE_ID)
			.ticks(10).myHitOn(flare(1), 40).animates(flare(1), DEATH).npcDespawns(flare(1), FLARE_ID, true)
			.ticks(13).animates(flare(2), EXPLODE)
			.ticks(1).yamaHealed(30).npcDespawns(flare(2), FLARE_ID, false)
			.ticks(10).end(EndReason.YAMA_DIED);

		FlareSummary summary = project(kill);

		assertEquals(2, summary.spawned());
		assertEquals(1, summary.killed());
		assertEquals(1, summary.exploded());
		assertEquals(30, summary.getYamaHealing());
		assertEquals(List.of(new FlareWave(22, 46, List.of(
			new FlareResult(1, 22, 32, FlareCause.SUMMONED, FlareFate.KILLED, false),
			new FlareResult(2, 22, 46, FlareCause.SUMMONED, FlareFate.EXPLODED, false)))), summary.getWaves());
	}

	@Test
	public void aFlareAfterMeleeIsItsOwnWaveAndAliveFlaresEndWithTheFight()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(20).yamaAnimates(SUMMON)
			.ticks(1).npcSpawns(flare(1), FLARE_ID)
			.ticks(10).yamaAnimates(MELEE)
			.ticks(1).npcSpawns(flare(2), FLARE_ID)
			.ticks(5).end(EndReason.LEFT);

		FlareSummary summary = project(kill);

		assertEquals(List.of(
			new FlareWave(21, 37, List.of(new FlareResult(1, 21, null, FlareCause.SUMMONED, FlareFate.UNKNOWN, false))),
			new FlareWave(32, 37, List.of(new FlareResult(2, 32, null, FlareCause.MELEE, FlareFate.UNKNOWN, false)))),
			summary.getWaves());
		assertEquals(FlareCause.MELEE, summary.getWaves().get(1).cause());
		assertEquals(0, summary.killed());
		assertEquals(0, summary.exploded());
	}

	@Test
	public void aHealOrHealGraphicNextToTheDespawnMeansExploded()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(10).npcSpawns(flare(1), FLARE_ID).npcSpawns(flare(2), FLARE_ID).npcSpawns(flare(3), FLARE_ID)
			.ticks(30).npcDespawns(flare(1), FLARE_ID, false)
			.ticks(1).yamaHealed(20)
			.ticks(18).graphicOn(Actor.YAMA, HEAL_GRAPHIC)
			.ticks(1).npcDespawns(flare(2), FLARE_ID, false)
			.ticks(10).npcDespawns(flare(3), FLARE_ID, false)
			.ticks(5).end(EndReason.YAMA_DIED);

		List<FlareResult> flares = project(kill).getWaves().get(0).getFlares();

		assertEquals(FlareFate.EXPLODED, flares.get(0).getFate());
		assertEquals(FlareFate.EXPLODED, flares.get(1).getFate());
		assertEquals(FlareFate.UNKNOWN, flares.get(2).getFate());
		assertEquals(Integer.valueOf(70), flares.get(2).getDespawnTick());
		assertEquals(20, project(kill).getYamaHealing());
	}

	@Test
	public void aDyingDespawnWithoutAnExplosionIsAKill()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(10).npcSpawns(flare(1), FLARE_ID)
			.ticks(5).npcDespawns(flare(1), FLARE_ID, true)
			.ticks(2).end(EndReason.YAMA_DIED);

		FlareResult flare = project(kill).getWaves().get(0).getFlares().get(0);

		assertEquals(FlareFate.KILLED, flare.getFate());
		assertEquals(FlareCause.UNKNOWN, flare.getCause());
		assertFalse(flare.isKilledByPurgingStaff());
	}

	@Test
	public void purgingStaffKillsAreCountedWhenTheRoleIsCaptured()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(10).npcSpawns(flare(1), FLARE_ID)
			.ticks(5).animates(Actor.SELF, PURGING_SPEC)
			.ticks(1).myHitOn(flare(1), 71)
			.ticks(1).animates(flare(1), DEATH).npcDespawns(flare(1), FLARE_ID, true)
			.ticks(3).end(EndReason.YAMA_DIED);

		assertTrue(project(kill).getWaves().get(0).getFlares().get(0).isKilledByPurgingStaff());
		assertEquals(1, project(kill).purgingStaffKills());
		assertFalse(project(kill, TestIds.registryWithout(Role.SPEC_PURGING_STAFF)).getWaves().get(0).getFlares().get(0).isKilledByPurgingStaff());
	}

	@Test
	public void aHitOutsideTheSpecWindowIsNotAPurgingStaffKill()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(10).npcSpawns(flare(1), FLARE_ID)
			.ticks(5).animates(Actor.SELF, PURGING_SPEC)
			.ticks(8).myHitOn(flare(1), 71).animates(flare(1), DEATH).npcDespawns(flare(1), FLARE_ID, true)
			.ticks(2).end(EndReason.YAMA_DIED);

		assertFalse(project(kill).getWaves().get(0).getFlares().get(0).isKilledByPurgingStaff());
	}

	@Test
	public void aSceneReloadDoesNotRespawnAFlare()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(10).npcSpawns(flare(1), FLARE_ID)
			.ticks(5).gameState(GameStateKind.LOADING).npcDespawns(flare(1), FLARE_ID, false).npcSpawns(flare(1), FLARE_ID)
			.ticks(1).gameState(GameStateKind.LOGGED_IN)
			.ticks(10).animates(flare(1), DEATH).npcDespawns(flare(1), FLARE_ID, true)
			.ticks(2).end(EndReason.YAMA_DIED);

		FlareSummary summary = project(kill);

		assertEquals(1, summary.spawned());
		assertEquals(1, summary.killed());
		assertEquals(Integer.valueOf(26), summary.getWaves().get(0).getFlares().get(0).getDespawnTick());
	}

	@Test
	public void aReusedIndexAfterTheDespawnIsANewFlare()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(20).yamaAnimates(SUMMON)
			.ticks(2).npcSpawns(flare(1), FLARE_ID)
			.ticks(10).animates(flare(1), DEATH).npcDespawns(flare(1), FLARE_ID, true)
			.ticks(18).yamaAnimates(SUMMON)
			.ticks(2).npcSpawns(flare(1), FLARE_ID)
			.ticks(8).animates(flare(1), EXPLODE)
			.ticks(1).npcDespawns(flare(1), FLARE_ID, false)
			.ticks(5).end(EndReason.YAMA_DIED);

		FlareSummary summary = project(kill);

		assertEquals(2, summary.spawned());
		assertEquals(1, summary.killed());
		assertEquals(1, summary.exploded());
		assertEquals(List.of(
			new FlareWave(22, 32, List.of(new FlareResult(1, 22, 32, FlareCause.SUMMONED, FlareFate.KILLED, false))),
			new FlareWave(52, 61, List.of(new FlareResult(1, 52, 61, FlareCause.SUMMONED, FlareFate.EXPLODED, false)))),
			summary.getWaves());
	}

	@Test
	public void needsTheFlareRole()
	{
		FlaresProjection projection = new FlaresProjection();

		assertEquals(Sections.FLARES, projection.key());
		assertEquals(Set.of(Role.VOID_FLARE), projection.requiredRoles());
	}
}
