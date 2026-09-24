package com.yamareviewer.domain.model;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.VarbitObserved;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class KillLogTest
{
	private static KillHeader header()
	{
		return new KillHeader("kill-1", 1_000L, 2_000L, "0.1.0", KillLog.SCHEMA_VERSION, "f00d", false);
	}

	@Test
	public void eventsOfFiltersByTypeKeepingOrder()
	{
		KillLog log = KillLog.of(header(), List.of(
			new AnimationObserved(1, Actor.YAMA, 100),
			new HitsplatObserved(2, Actor.SELF, HitsplatKind.DAMAGE, 16, 3, false),
			new AnimationObserved(3, Actor.SELF, 200)), 0);

		List<AnimationObserved> animations = log.eventsOf(AnimationObserved.class);

		assertEquals(2, animations.size());
		assertEquals(100, animations.get(0).getAnimationId());
		assertEquals(200, animations.get(1).getAnimationId());
	}

	@Test
	public void endReasonAndLastTickComeFromFightEnded()
	{
		KillLog log = KillLog.of(header(), List.of(new FightEnded(40, EndReason.YAMA_DIED)), 0);

		assertEquals(EndReason.YAMA_DIED, log.endReason());
		assertEquals(40, log.lastTick());
	}

	@Test
	public void missingFightEndedCountsAsLeft()
	{
		KillLog log = KillLog.of(header(), List.of(new AnimationObserved(5, Actor.YAMA, 1)), 0);

		assertEquals(EndReason.LEFT, log.endReason());
	}

	@Test(expected = UnsupportedOperationException.class)
	public void eventsCannotBeModified()
	{
		KillLog log = KillLog.of(header(), List.of(new VarbitObserved(0, 1, 1)), 0);

		log.getEvents().add(new VarbitObserved(1, 1, 2));
	}

	@Test
	public void laterChangesToTheSourceListDoNotLeakIn()
	{
		List<DomainEvent> source = new ArrayList<>();
		KillLog log = KillLog.of(header(), source, 0);

		source.add(new VarbitObserved(1, 1, 1));

		assertTrue(log.getEvents().isEmpty());
	}
}
