package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.RecapHit;
import com.yamareviewer.domain.review.RecapTick;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DeathRecapProjectionTest
{
	private static final int MELEE = TestIds.id(Role.YAMA_MELEE);
	private static final int WIDGET = TestIds.id(Role.CONTRACT_NAME_WIDGET);

	private static Section<DeathRecap> section(KillLog kill, IdRegistry ids)
	{
		ReviewBuilder builder = new ReviewBuilder(List.of(new PhasesProjection(), new ContractProjection(),
			new DamageAttributionProjection(), new DeathRecapProjection()), ids, Rules.DEFAULT);
		return builder.run(kill, ReviewSettings.DEFAULT).section(Sections.DEATH_RECAP);
	}

	private static DeathRecap project(KillLog kill)
	{
		return section(kill, TestIds.registry()).value();
	}

	@Test
	public void onlyDeathsHaveARecap()
	{
		assertEquals(Section.hidden(HiddenReason.NOT_APPLICABLE), section(KillLogBuilder.kill().ticks(5).end(EndReason.YAMA_DIED), TestIds.registry()));
		assertEquals(Section.hidden(HiddenReason.NOT_APPLICABLE), section(KillLogBuilder.kill().ticks(5).end(EndReason.LEFT), TestIds.registry()));
	}

	@Test
	public void theLastTenTicksIncludingTheDeathTick()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(5).yamaAnimates(MELEE).ticks(1)
			.hitsplatOn(Actor.SELF, 22).hp(60).inventory(385, "Shark", -1).endTick()
			.hp(80).prayerPoints(40).ticks(5)
			.hitsplatOn(Actor.SELF, 80).hp(0).yamaTarget(Actor.PARTNER)
			.end(EndReason.PLAYER_DIED);

		DeathRecap recap = project(kill);

		assertEquals(10, recap.getTicks().size());
		assertEquals(new RecapTick(3, List.of(), 99, 99, null, "you", List.of()), recap.getTicks().get(0));
		assertEquals(new RecapTick(6, List.of(new RecapHit(DamageSource.MELEE, 22)), 60, 99, null, "you", List.of("Shark")), recap.getTicks().get(3));
		assertEquals(new RecapTick(12, List.of(new RecapHit(DamageSource.OTHER, 80)), 0, 40, null, "partner", List.of()), recap.getTicks().get(9));
		assertFalse(recap.isOutOfRunEnergy());
	}

	@Test
	public void anEarlyDeathHasFewerTicks()
	{
		DeathRecap recap = project(KillLogBuilder.kill().ticks(4).hp(0).end(EndReason.PLAYER_DIED));

		assertEquals(5, recap.getTicks().size());
		assertEquals(0, recap.getTicks().get(0).getTick());
		assertEquals(4, recap.getTicks().get(4).getTick());
		assertEquals(0, recap.getTicks().get(4).getHitpoints());
	}

	@Test
	public void forfeitBreathShowsRunEnergyAndFlagsAnOutOfEnergyDeath()
	{
		KillLog outOfEnergy = KillLogBuilder.kill().widgetText(WIDGET, "Contract of Forfeit Breath").ticks(3).run(0).hp(0).end(EndReason.PLAYER_DIED);
		KillLog withEnergy = KillLogBuilder.kill().widgetText(WIDGET, "Contract of Forfeit Breath").ticks(3).run(5).hp(0).end(EndReason.PLAYER_DIED);
		KillLog noContract = KillLogBuilder.kill().ticks(3).run(0).hp(0).end(EndReason.PLAYER_DIED);

		DeathRecap recap = project(outOfEnergy);
		assertTrue(recap.isOutOfRunEnergy());
		assertEquals(Integer.valueOf(100), recap.getTicks().get(0).getRunEnergy());
		assertEquals(Integer.valueOf(0), recap.getTicks().get(3).getRunEnergy());

		assertFalse(project(withEnergy).isOutOfRunEnergy());
		assertFalse(project(noContract).isOutOfRunEnergy());
		assertNull(project(noContract).getTicks().get(3).getRunEnergy());
	}

	@Test
	public void hitsWithoutDamageAttributionAreOther()
	{
		KillLog kill = KillLogBuilder.kill().ticks(5).yamaAnimates(MELEE).ticks(1).hitsplatOn(Actor.SELF, 22).hp(0).end(EndReason.PLAYER_DIED);

		DeathRecap recap = section(kill, TestIds.registryWithout(Role.YAMA_MELEE)).value();

		assertEquals(List.of(new RecapHit(DamageSource.OTHER, 22)), recap.getTicks().get(recap.getTicks().size() - 1).getHits());
	}

	@Test
	public void keyAndRoles()
	{
		DeathRecapProjection projection = new DeathRecapProjection();

		assertEquals(Sections.DEATH_RECAP, projection.key());
		assertEquals(Set.of(), projection.requiredRoles());
	}
}
