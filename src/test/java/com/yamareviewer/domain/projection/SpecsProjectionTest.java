package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.drain.SpecWeapon;
import com.yamareviewer.domain.drain.YamaStats;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.DrainStep;
import com.yamareviewer.domain.review.SpecOutcome;
import com.yamareviewer.domain.review.SpecResult;
import com.yamareviewer.domain.review.SpecSummary;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SpecsProjectionTest
{
	private static final int MAUL = TestIds.id(Role.WEAPON_ELDER_MAUL);

	private final SpecsProjection projection = new SpecsProjection();

	private SpecSummary specs(KillLog log, ProjectionContext context)
	{
		return projection.project(log, context).value();
	}

	private static KillLogBuilder maulSpecAtTickTwo()
	{
		return KillLogBuilder.kill().weapon(MAUL).ticks(2).specAnimation(Actor.SELF, Role.SPEC_ELDER_MAUL).spec(50).endTick();
	}

	@Test
	public void keyAndRoles()
	{
		assertEquals(Sections.SPECS, projection.key());
		assertEquals(Set.of(), projection.requiredRoles());
	}

	@Test
	public void ownSpecWithAnimationAndLandedHitDrainsDefence()
	{
		KillLog log = maulSpecAtTickTwo().myHitOn(Actor.YAMA, 60).ticks(4).end(EndReason.YAMA_DIED);

		SpecSummary summary = specs(log, TestContext.withPhases(7));

		assertEquals(List.of(new SpecResult(2, Phase.P1, Actor.SELF, SpecWeapon.ELDER_MAUL, true, Actor.YAMA, SpecOutcome.LANDED, 60, 50, null, null)),
			summary.getSpecs());
		assertEquals(List.of(new DrainStep(2, Phase.P1, "Elder maul", new YamaStats(225, 250, 0), new YamaStats(147, 250, 0))), summary.getDrains());
		assertEquals(147, summary.getLowestDefence());
		assertEquals(Phase.P1, summary.getLowestDefencePhase());
		assertEquals(78, summary.defenceDrained());
		assertEquals(new YamaStats(147, 250, 0), summary.getFinalStats());
		assertEquals(Optional.of(1.0), summary.landedShare());
	}

	@Test
	public void animationOnTheTickBeforeTheDropStillMatches()
	{
		KillLog log = KillLogBuilder.kill().weapon(MAUL).ticks(2).specAnimation(Actor.SELF, Role.SPEC_ELDER_MAUL).endTick()
			.spec(50).myHitOn(Actor.YAMA, 60).ticks(4).end(EndReason.YAMA_DIED);

		SpecResult spec = specs(log, TestContext.withPhases(7)).getSpecs().get(0);

		assertEquals(2, spec.getTick());
		assertEquals(50, spec.getEnergyUsed());
		assertTrue(spec.isAnimationSeen());
		assertEquals(SpecOutcome.LANDED, spec.getOutcome());
	}

	@Test
	public void otherWeaponDropIsRecordedWithoutDrain()
	{
		KillLog log = KillLogBuilder.kill().weapon(4151).ticks(2).spec(50).endTick().myHitOn(Actor.YAMA, 30).ticks(4).end(EndReason.YAMA_DIED);

		SpecSummary summary = specs(log, TestContext.withPhases(7));

		assertEquals(List.of(new SpecResult(2, Phase.P1, Actor.SELF, null, false, Actor.YAMA, SpecOutcome.LANDED, 30, 50, null, null)), summary.getSpecs());
		assertTrue(summary.getDrains().isEmpty());
		assertEquals(1, summary.otherWeaponSpecs());
		assertEquals(0, summary.defenceDrained());
	}

	@Test
	public void knownWeaponWithoutAnimationIsRecordedUnmatched()
	{
		KillLog log = KillLogBuilder.kill().weapon(MAUL).ticks(2).spec(50).endTick().myHitOn(Actor.YAMA, 60).ticks(4).end(EndReason.YAMA_DIED);

		SpecSummary summary = specs(log, TestContext.withPhases(7));

		SpecResult spec = summary.getSpecs().get(0);
		assertEquals(SpecWeapon.ELDER_MAUL, spec.getWeapon());
		assertFalse(spec.isAnimationSeen());
		assertEquals(147, summary.getLowestDefence());
	}

	@Test
	public void missedAndUnknownResultsDoNotDrain()
	{
		KillLogBuilder kill = maulSpecAtTickTwo().myHitOn(Actor.YAMA, 0).ticks(5);
		kill.specAnimation(Actor.SELF, Role.SPEC_ELDER_MAUL).spec(0).endTick().ticks(8);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		SpecSummary summary = specs(log, TestContext.withPhases(17));

		assertEquals(List.of(SpecOutcome.MISSED, SpecOutcome.UNKNOWN), summary.getSpecs().stream().map(SpecResult::getOutcome).collect(Collectors.toList()));
		assertEquals(Integer.valueOf(0), summary.getSpecs().get(0).getDamage());
		assertNull(summary.getSpecs().get(1).getDamage());
		assertTrue(summary.getDrains().isEmpty());
		assertEquals(Optional.of(0.0), summary.landedShare());
		assertEquals(2, summary.used(SpecWeapon.ELDER_MAUL));
		assertEquals(0, summary.landed(SpecWeapon.ELDER_MAUL));
	}

	@Test
	public void purgingStaffIsDetectedByItsAnimationAloneAndTargetsTheFlare()
	{
		KillLog log = KillLogBuilder.kill().ticks(2).specAnimation(Actor.SELF, Role.SPEC_PURGING_STAFF).endTick()
			.myHitOn(Actor.flare(7), 40).ticks(3).end(EndReason.YAMA_DIED);

		SpecSummary summary = specs(log, TestContext.withPhases(6));

		assertEquals(List.of(new SpecResult(2, Phase.P1, Actor.SELF, SpecWeapon.PURGING_STAFF, true, Actor.flare(7), SpecOutcome.LANDED, 40, 0, null, null)),
			summary.getSpecs());
		assertTrue(summary.getDrains().isEmpty());
	}

	@Test
	public void partnerSpecsAreDetectedByAnimationAndResolvedByTheirHitsplat()
	{
		KillLog log = KillLogBuilder.kill().ticks(2).animates(Actor.PARTNER, TestIds.id(Role.SPEC_DRAGON_WARHAMMER)).endTick()
			.hitsplatOn(Actor.YAMA, 50).ticks(3).end(EndReason.YAMA_DIED);

		SpecSummary summary = specs(log, TestContext.duo(6));

		assertEquals(List.of(new SpecResult(2, Phase.P1, Actor.PARTNER, SpecWeapon.DRAGON_WARHAMMER, true, Actor.YAMA, SpecOutcome.LANDED, 50, 0, null, null)),
			summary.getSpecs());
		assertEquals(158, summary.getLowestDefence());
		assertTrue(summary.ownSpecs().isEmpty());
		assertEquals(Optional.empty(), summary.landedShare());
	}

	@Test
	public void specsAlwaysLandUnderFamiliarAcquisition()
	{
		KillLog log = maulSpecAtTickTwo().myHitOn(Actor.YAMA, 0).ticks(4).end(EndReason.YAMA_DIED);

		SpecSummary summary = specs(log, TestContext.under(Contract.FAMILIAR_ACQUISITION, 7));

		assertEquals(SpecOutcome.LANDED, summary.getSpecs().get(0).getOutcome());
		assertEquals(new YamaStats(247, 275, 0), summary.getBase());
		assertEquals(161, summary.getLowestDefence());
	}

	@Test
	public void hornAssistsTheNextMeleeSpecWithinTenTicksOrIsUnused()
	{
		KillLogBuilder kill = KillLogBuilder.kill().weapon(TestIds.id(Role.WEAPON_SOULFLAME_HORN)).ticks(2);
		kill.specAnimation(Actor.SELF, Role.SPEC_SOULFLAME_HORN).spec(75).endTick();
		kill.weapon(MAUL).ticks(5);
		kill.specAnimation(Actor.SELF, Role.SPEC_ELDER_MAUL).spec(25).endTick();
		kill.myHitOn(Actor.YAMA, 60).ticks(6);
		kill.weapon(TestIds.id(Role.WEAPON_SOULFLAME_HORN)).spec(100).ticks(5);
		kill.specAnimation(Actor.SELF, Role.SPEC_SOULFLAME_HORN).spec(75).endTick();
		kill.ticks(3);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		SpecSummary summary = specs(log, TestContext.withPhases(24));

		assertEquals(2, summary.getHorns().size());
		assertEquals(8, summary.getHorns().get(0).getAssistedSpec().getTick());
		assertEquals(SpecOutcome.LANDED, summary.getHorns().get(0).getAssistedSpec().getOutcome());
		assertTrue(summary.getHorns().get(1).isUnused());
		assertEquals(20, summary.getHorns().get(1).getTick());
		assertEquals(SpecOutcome.UNKNOWN, summary.getSpecs().get(0).getOutcome());
		assertEquals(25, summary.getSpecs().get(0).getEnergyUsed());
		assertEquals(50, summary.ownEnergyUsedOnDrains());
	}

	@Test
	public void saradominGodswordRestoresHpAndPrayerFromTheTickStates()
	{
		KillLog log = KillLogBuilder.kill().weapon(TestIds.id(Role.WEAPON_SARADOMIN_GODSWORD)).hp(50).prayerPoints(30).ticks(2)
			.specAnimation(Actor.SELF, Role.SPEC_SARADOMIN_GODSWORD).spec(50).endTick()
			.hp(70).prayerPoints(40).myHitOn(Actor.YAMA, 40).ticks(3).end(EndReason.YAMA_DIED);

		SpecResult spec = specs(log, TestContext.withPhases(6)).getSpecs().get(0);

		assertEquals(Integer.valueOf(20), spec.getHpRestored());
		assertEquals(Integer.valueOf(10), spec.getPrayerRestored());
		assertEquals(SpecOutcome.LANDED, spec.getOutcome());
	}

	@Test
	public void statRestoreStepsAreRecordedAndTheFinalStatsIncludeThem()
	{
		KillLogBuilder kill = maulSpecAtTickTwo().myHitOn(Actor.YAMA, 60).spec(100).ticks(147);
		kill.specAnimation(Actor.SELF, Role.SPEC_ELDER_MAUL).spec(50).endTick();
		kill.myHitOn(Actor.YAMA, 60).ticks(59);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		SpecSummary summary = specs(log, TestContext.withPhases(210));

		assertEquals(List.of("Elder maul", "Stat restore", "Elder maul", "Stat restore"),
			summary.getDrains().stream().map(DrainStep::getCause).collect(Collectors.toList()));
		assertEquals(List.of(147, 148, 145, 146), summary.getDrains().stream().map(step -> step.getAfter().getDefence()).collect(Collectors.toList()));
		assertEquals(List.of(2, 150, 150, 210), summary.getDrains().stream().map(DrainStep::getTick).collect(Collectors.toList()));
		assertEquals(146, summary.getFinalStats().getDefence());
		assertEquals(145, summary.getLowestDefence());
		assertEquals(Phase.P3, summary.getLowestDefencePhase());
	}

	@Test
	public void hiddenPhasesLeaveThePhaseNullButStillReview()
	{
		KillLog log = maulSpecAtTickTwo().myHitOn(Actor.YAMA, 60).ticks(4).end(EndReason.YAMA_DIED);

		SpecSummary summary = specs(log, TestContext.empty());

		assertNull(summary.getSpecs().get(0).getPhase());
		assertEquals(147, summary.getLowestDefence());
		assertNull(summary.getLowestDefencePhase());
	}
}
