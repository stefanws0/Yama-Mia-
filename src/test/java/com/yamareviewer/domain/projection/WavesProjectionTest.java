package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.WaveHit;
import com.yamareviewer.domain.review.WaveSummary;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class WavesProjectionTest
{
	private final WavesProjection projection = new WavesProjection();

	private WaveSummary waves(KillLog log, ProjectionContext context)
	{
		context.put(Sections.ATTACKS, new AttacksProjection().project(log, context));
		return projection.project(log, context).value();
	}

	@Test
	public void keyAndRoles()
	{
		assertEquals(Sections.WAVES, projection.key());
		assertEquals(Set.of(Role.SHADOW_WAVE), projection.requiredRoles());
	}

	@Test
	public void aWaveOnYouIsHitByTheHitsplatInTheWindowAndFlagsDisabledPrayers()
	{
		KillLog log = Fights.throughToP3().ticks(3).waveOn(Actor.SELF).ticks(1).hitsplatOn(Actor.SELF, 15).prayersDisabledMessage().ticks(3)
			.end(EndReason.YAMA_DIED);

		WaveSummary summary = waves(log, TestContext.withPhases(47));

		assertEquals(List.of(new WaveHit(43, Phase.P3, Actor.SELF, true, 15, true)), summary.getWaves());
		assertEquals(1, summary.prayersDisabled(Actor.SELF));
	}

	@Test
	public void aGroundWaveThatDidNotHitIsDodged()
	{
		KillLog log = Fights.throughToP3().ticks(3).waveAt(3200, 3203).ticks(4).end(EndReason.YAMA_DIED);

		WaveSummary summary = waves(log, TestContext.withPhases(47));

		assertEquals(List.of(new WaveHit(43, Phase.P3, Actor.SELF, false, 0, false)), summary.getWaves());
		assertEquals(1, summary.dodged(Actor.SELF));
	}

	@Test
	public void graphicsWithinTwoTicksAreOneWaveAndFurtherApartAnother()
	{
		KillLogBuilder kill = Fights.throughToP3().ticks(3);
		kill.waveAt(3190, 3203).ticks(1).waveAt(3195, 3203).ticks(1).waveAt(3200, 3203).ticks(5);
		kill.waveAt(3200, 3203).ticks(2);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		WaveSummary summary = waves(log, TestContext.withPhases(52));

		assertEquals(2, summary.total(Actor.SELF));
		assertEquals(45, summary.getWaves().get(0).getTick());
		assertEquals(50, summary.getWaves().get(1).getTick());
	}

	@Test
	public void inDuoEachPlayerGetsTheirOwnResult()
	{
		KillLog log = Fights.throughToP3().partnerAt(3210, 3200).ticks(3).waveOn(Actor.SELF).ticks(1).waveOn(Actor.PARTNER).ticks(1)
			.hitsplatOn(Actor.PARTNER, 10).ticks(3).end(EndReason.YAMA_DIED);

		WaveSummary summary = waves(log, TestContext.duo(48));

		assertEquals(List.of(new WaveHit(43, Phase.P3, Actor.SELF, false, 0, false), new WaveHit(44, Phase.P3, Actor.PARTNER, true, 10, false)),
			summary.getWaves());
	}

	@Test
	public void aStandardAttacksLandingIsNotAWaveHit()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(2).impactOn(Actor.SELF, Style.MAGIC).hitsplatOn(Actor.SELF, 3)
			.waveOn(Actor.SELF).ticks(5).end(EndReason.YAMA_DIED);

		assertFalse(waves(log, TestContext.withPhases(47)).getWaves().get(0).isHit());
	}

	@Test
	public void hiddenAttacksHideTheWavesWithTheSameReason()
	{
		KillLog log = Fights.throughToP3().ticks(3).waveOn(Actor.SELF).ticks(3).end(EndReason.YAMA_DIED);
		ProjectionContext context = TestContext.withPhases(46);
		context.put(Sections.ATTACKS, Section.<AttackTimeline>hidden(HiddenReason.ERROR));

		assertEquals(Optional.of(HiddenReason.ERROR), projection.project(log, context).hiddenReason());
		assertTrue(projection.project(log, TestContext.empty()).hiddenReason().isPresent());
	}
}
