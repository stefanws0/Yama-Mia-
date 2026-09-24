package com.yamareviewer.tools;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.PlayerLeft;
import com.yamareviewer.domain.event.PlayerSeen;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FixtureScrubberTest
{
	@Test
	public void namesAreReplacedAndOtherActorsDropped()
	{
		Position here = new Position(3200, 3200, 0);
		KillHeader header = new KillHeader("k", 1L, 2L, "0.1.0", KillLog.SCHEMA_VERSION, "f00d", true);
		KillLog log = KillLog.of(header, List.of(
			new FightStarted(0, "Stefan", here),
			new PlayerSeen(0, "Buddy"),
			new PlayerSeen(3, "Stranger"),
			new AnimationObserved(4, Actor.other("Stranger"), 808),
			new HitsplatObserved(5, Actor.SELF, HitsplatKind.DAMAGE, 3, 1, false),
			new ProjectileObserved(6, 77, Actor.other("Stranger"), 8),
			new TickState(6, Set.of(), 99, 99, 100, 100, -1, Actor.other("Stranger"), here, null),
			new PlayerLeft(7, "Buddy")), 2);

		KillLog scrubbed = FixtureScrubber.scrub(log);

		List<DomainEvent> events = scrubbed.getEvents();
		assertEquals(List.of(
			new FightStarted(0, "Self", here),
			new PlayerSeen(0, "Partner"),
			new PlayerSeen(3, "Other-1"),
			new HitsplatObserved(5, Actor.SELF, HitsplatKind.DAMAGE, 3, 1, false),
			new TickState(6, Set.of(), 99, 99, 100, 100, -1, null, here, null),
			new PlayerLeft(7, "Partner")), events);
		assertEquals(header, scrubbed.getHeader());
		assertEquals(2, scrubbed.getSkippedEvents());
	}
}
