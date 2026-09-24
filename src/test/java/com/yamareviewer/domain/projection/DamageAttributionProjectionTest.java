package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.DamageHit;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class DamageAttributionProjectionTest
{
	private static final int MELEE = TestIds.id(Role.YAMA_MELEE);
	private static final int FLARE_HIT = TestIds.id(Role.FLARE_HIT);
	private static final int FLARE_EXPLODE = TestIds.id(Role.FLARE_EXPLODE);
	private static final int FIRE_STREAK = TestIds.id(Role.FIRE_STREAK);
	private static final int SHADOW_STOMP = TestIds.id(Role.SHADOW_STOMP);
	private static final int METEOR_STRIKE = TestIds.id(Role.METEOR_STRIKE);
	private static final int FIRE_ATTACK = TestIds.id(Role.FIRE_ATTACK);
	private static final int GLYPH_PROTECTION = TestIds.id(Role.GLYPH_PROTECTION);
	private static final int SHADOW_POOL = TestIds.id(Role.SHADOW_POOL);
	private static final int WIDGET = TestIds.id(Role.CONTRACT_NAME_WIDGET);
	private static final int JUDGE_ID = TestIds.id(Role.JUDGE);

	private static Section<DamageSummary> section(KillLog kill, IdRegistry ids)
	{
		ReviewBuilder builder = new ReviewBuilder(
			List.of(new PhasesProjection(), new ContractProjection(), new DamageAttributionProjection()), ids, Rules.DEFAULT);
		return builder.run(kill, ReviewSettings.DEFAULT).section(Sections.DAMAGE);
	}

	private static DamageSummary project(KillLog kill)
	{
		return section(kill, TestIds.registry()).value();
	}

	private static List<DamageSource> sources(DamageSummary summary)
	{
		return summary.getHits().stream().map(DamageHit::getSource).collect(toList());
	}

	@Test
	public void meleeWithinTwoTicksAndSplashOnTheOtherPlayer()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(10).yamaAnimates(MELEE).yamaTarget(Actor.SELF).endTick()
			.hitsplatOn(Actor.SELF, 20).hitsplatOn(Actor.PARTNER, 15)
			.ticks(2).hitsplatOn(Actor.SELF, 9)
			.end(EndReason.YAMA_DIED);

		DamageSummary summary = project(kill);

		assertEquals(List.of(DamageSource.MELEE, DamageSource.MELEE_SPLASH, DamageSource.OTHER), sources(summary));
		assertEquals(29, summary.total(Actor.SELF));
		assertEquals(15, summary.total(Actor.PARTNER));
		assertEquals(15, summary.shared());
	}

	@Test
	public void flareHitsAndExplosionsWithinOneTick()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(20).graphicOn(Actor.SELF, FLARE_HIT)
			.ticks(1).hitsplatOn(Actor.SELF, 40)
			.ticks(9).animates(Actor.flare(3), FLARE_EXPLODE).hitsplatOn(Actor.PARTNER, 42)
			.ticks(2).hitsplatOn(Actor.SELF, 5)
			.end(EndReason.YAMA_DIED);

		assertEquals(List.of(DamageSource.FLARE, DamageSource.FLARE, DamageSource.OTHER), sources(project(kill)));
	}

	@Test
	public void fireStreaksWithinTwoTicks()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(40).groundGraphic(FIRE_STREAK, 3210, 3200)
			.ticks(2).hitsplatOn(Actor.SELF, 30)
			.ticks(1).hitsplatOn(Actor.SELF, 30)
			.end(EndReason.YAMA_DIED);

		assertEquals(List.of(DamageSource.FIRE_STREAK, DamageSource.OTHER), sources(project(kill)));
	}

	@Test
	public void specialsFromStompMeteorFireAttackAndGlyphProtection()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).yamaAnimates(SHADOW_STOMP).ticks(1).hitsplatOn(Actor.SELF, 25)
			.ticks(9).graphicOn(Actor.SELF, METEOR_STRIKE).hitsplatOn(Actor.SELF, 30)
			.ticks(10).groundGraphic(FIRE_ATTACK, 3200, 3200).ticks(2).hitsplatOn(Actor.PARTNER, 12)
			.ticks(8).graphicOn(Actor.PARTNER, GLYPH_PROTECTION).ticks(1).hitsplatOn(Actor.SELF, 20)
			.end(EndReason.YAMA_DIED);

		DamageSummary summary = project(kill);

		assertEquals(List.of(DamageSource.SPECIAL, DamageSource.SPECIAL, DamageSource.SPECIAL, DamageSource.SPECIAL), sources(summary));
		assertEquals(87, summary.shared());
	}

	@Test
	public void shadowPoolsOnlyUnderAPoolContract()
	{
		KillLog oathplate = KillLogBuilder.kill().widgetText(WIDGET, "Contract of Oathplate Acquisition")
			.ticks(90).groundGraphic(SHADOW_POOL, 3205, 3200)
			.ticks(1).hitsplatOn(Actor.SELF, 13)
			.ticks(4).selfAt(3205, 3200).hitsplatOn(Actor.SELF, 12).endTick()
			.selfAt(3200, 3200).hitsplatOn(Actor.SELF, 12)
			.end(EndReason.YAMA_DIED);
		KillLog bloodied = KillLogBuilder.kill().widgetText(WIDGET, "Contract of Bloodied Blows")
			.ticks(90).groundGraphic(SHADOW_POOL, 3205, 3200)
			.ticks(1).hitsplatOn(Actor.SELF, 13)
			.ticks(4).selfAt(3205, 3200).hitsplatOn(Actor.SELF, 12).endTick()
			.selfAt(3200, 3200).hitsplatOn(Actor.SELF, 12)
			.end(EndReason.YAMA_DIED);

		assertEquals(List.of(DamageSource.SHADOW_POOL, DamageSource.SHADOW_POOL, DamageSource.OTHER), sources(project(oathplate)));
		assertEquals(List.of(DamageSource.OTHER, DamageSource.OTHER, DamageSource.OTHER), sources(project(bloodied)));
		assertEquals(Optional.of(HiddenReason.IDS_NOT_CAPTURED), section(oathplate, TestIds.registryWithout(Role.SHADOW_POOL)).hiddenReason());
		assertEquals(Optional.empty(), section(bloodied, TestIds.registryWithout(Role.SHADOW_POOL)).hiddenReason());
	}

	@Test
	public void damageDuringAJudgePhaseIsTheJudges()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(5).hitsplatOn(Actor.SELF, 10)
			.ticks(25).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(5).hitsplatOn(Actor.SELF, 10)
			.end(EndReason.YAMA_DIED);

		DamageSummary summary = project(kill);

		assertEquals(List.of(DamageSource.JUDGE, DamageSource.OTHER), sources(summary));
		assertEquals(Phase.JUDGE_1, summary.getHits().get(0).getPhase());
		assertEquals(Phase.P2, summary.getHits().get(1).getPhase());
		assertEquals(10, summary.total(Actor.SELF, Phase.JUDGE_1));
	}

	@Test
	public void earlierRulesWin()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(10).yamaAnimates(MELEE).yamaTarget(Actor.SELF).endTick()
			.graphicOn(Actor.SELF, FLARE_HIT).hitsplatOn(Actor.SELF, 20)
			.end(EndReason.YAMA_DIED);

		assertEquals(List.of(DamageSource.MELEE), sources(project(kill)));
	}

	@Test
	public void onlyDamageOnPlayersCounts()
	{
		KillLog kill = KillLogBuilder.kill()
			.hitsplatOn(Actor.YAMA, 30).myHitOn(Actor.YAMA, 40).hitsplatOn(Actor.SELF, 0).yamaHealed(5).hitsplatOn(Actor.SELF, 7)
			.end(EndReason.YAMA_DIED);

		assertEquals(List.of(new DamageHit(0, Phase.P1, Actor.SELF, DamageSource.OTHER, 7)), project(kill).getHits());
	}

	@Test
	public void hitsWithoutKnownPhasesHaveNoPhase()
	{
		KillLog kill = KillLogBuilder.kill().ticks(5).hitsplatOn(Actor.SELF, 7).end(EndReason.YAMA_DIED);

		DamageSummary summary = section(kill, TestIds.registryWithout(Role.JUDGE)).value();

		assertNull(summary.getHits().get(0).getPhase());
		assertEquals(7, summary.total(Actor.SELF));
	}

	@Test
	public void keyAndRoles()
	{
		DamageAttributionProjection projection = new DamageAttributionProjection();

		assertEquals(Sections.DAMAGE, projection.key());
		assertEquals(EnumSet.of(Role.YAMA_MELEE, Role.FLARE_HIT, Role.FLARE_EXPLODE, Role.FIRE_STREAK, Role.METEOR_STRIKE,
			Role.SHADOW_STOMP, Role.FIRE_ATTACK, Role.GLYPH_PROTECTION), projection.requiredRoles());
	}
}
