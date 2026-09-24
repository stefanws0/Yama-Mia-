package com.yamareviewer.application.command;

import com.yamareviewer.application.port.SnapshotSource;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.EntryChosen;
import com.yamareviewer.domain.event.EventType;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.PlayerSeen;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.model.KillLog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class KillSessionTest
{
	private final List<KillLog> ended = new ArrayList<>();
	private final FakeSnapshots snapshots = new FakeSnapshots();
	private final AtomicInteger killCounter = new AtomicInteger();
	private boolean capture;
	private KillSession session;

	@Before
	public void setUp()
	{
		Clock clock = Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC);
		session = new KillSession(ended::add, snapshots, clock, () -> "kill-" + killCounter.incrementAndGet(),
			"0.1.0", "f00d", () -> capture);
	}

	private static FightStart solo()
	{
		return new FightStart("Me", new Position(1, 2, 0), List.of());
	}

	private static TickState state(int tick)
	{
		return new TickState(tick, Set.of(), 99, 99, 100, 100, -1, null, null, null);
	}

	private static List<String> types(KillLog kill)
	{
		return kill.getEvents().stream().map(e -> EventType.of(e).typeName()).collect(toList());
	}

	private void armAndStart(FightStart start)
	{
		session.updateRegion(true);
		session.yamaSpawned(start);
	}

	@Test
	public void recordsNothingOutsideTheRegion()
	{
		session.yamaSpawned(solo());
		session.record(new VarbitObserved(0, 1, 1));
		session.yamaDied();
		session.endTick();

		assertEquals(SessionState.IDLE, session.state());
		assertTrue(ended.isEmpty());
	}

	@Test
	public void fullKillProducesAnOrderedLog()
	{
		session.chooseEntry(EntryChoice.TRAVEL);
		armAndStart(solo());
		session.record(new AnimationObserved(0, Actor.YAMA, 10));
		session.record(state(0));
		session.endTick();
		session.record(new HitsplatObserved(1, Actor.SELF, HitsplatKind.DAMAGE, 5, 1, false));
		session.yamaDied();
		session.record(state(1));
		session.endTick();

		assertEquals(1, ended.size());
		KillLog kill = ended.get(0);
		assertEquals(List.of("entry", "fight-start", "supplies", "animation", "tick", "hitsplat", "supplies", "tick", "fight-end"),
			types(kill));
		assertEquals("kill-1", kill.getHeader().getKillId());
		assertEquals("f00d", kill.getHeader().getIdsFingerprint());
		assertEquals(5_000L, kill.getHeader().getStartEpochMs());
		assertEquals(EndReason.YAMA_DIED, kill.endReason());
		assertEquals(1, kill.lastTick());
		assertEquals(SessionState.ARMED, session.state());
	}

	@Test
	public void deathKeepsTheDeathTicksState()
	{
		armAndStart(solo());
		session.endTick();
		session.playerDied();

		assertTrue(ended.isEmpty());
		session.record(state(1));
		session.endTick();

		List<DomainEvent> events = ended.get(0).getEvents();
		assertTrue(events.get(events.size() - 2) instanceof TickState);
		assertEquals(new FightEnded(1, EndReason.PLAYER_DIED), events.get(events.size() - 1));
	}

	@Test
	public void theEndSnapshotIsTakenWhenTheEndIsSeen()
	{
		armAndStart(solo());
		session.playerDied();
		session.record(state(0));
		session.endTick();

		List<DomainEvent> events = ended.get(0).getEvents();
		SuppliesSnapshot end = (SuppliesSnapshot) events.get(events.size() - 3);
		assertEquals(SnapshotKind.END, end.getKind());
	}

	@Test
	public void leavingMidFightEndsAsLeftAtOnceAndForgetsTheEntryChoice()
	{
		session.chooseEntry(EntryChoice.JOIN);
		armAndStart(solo());
		session.updateRegion(false);

		assertEquals(EndReason.LEFT, ended.get(0).endReason());
		assertEquals(EntryChoice.JOIN, ended.get(0).eventsOf(EntryChosen.class).get(0).getChoice());
		assertEquals(SessionState.IDLE, session.state());

		armAndStart(solo());
		session.leave();
		assertTrue(ended.get(1).eventsOf(EntryChosen.class).isEmpty());
	}

	@Test
	public void leaveAfterDeathKeepsTheDeathReason()
	{
		armAndStart(solo());
		session.playerDied();
		session.leave();

		assertEquals(EndReason.PLAYER_DIED, ended.get(0).endReason());
	}

	@Test
	public void endSnapshotIsSkippedWhenUnavailable()
	{
		armAndStart(solo());
		snapshots.available = false;
		session.leave();

		assertEquals(List.of("fight-start", "supplies", "fight-end"), types(ended.get(0)));
	}

	@Test
	public void secondKillInSameInstanceGetsAFreshIdAndTicks()
	{
		armAndStart(solo());
		session.endTick();
		session.endTick();
		session.yamaDied();
		session.endTick();

		session.yamaSpawned(solo());
		session.endTick();
		session.yamaDied();
		session.endTick();

		assertEquals(2, ended.size());
		assertEquals("kill-2", ended.get(1).getHeader().getKillId());
		assertEquals(0, ended.get(1).eventsOf(FightStarted.class).get(0).getTick());
		assertEquals(1, ended.get(1).lastTick());
	}

	@Test
	public void spawnWhileFightingIsIgnored()
	{
		armAndStart(solo());
		session.endTick();
		session.yamaSpawned(new FightStart("Me", new Position(9, 9, 0), List.of("Someone")));
		session.leave();

		assertEquals(1, ended.get(0).eventsOf(FightStarted.class).size());
		assertTrue(ended.get(0).eventsOf(PlayerSeen.class).isEmpty());
	}

	@Test
	public void playersPresentOrArrivingAreSeenAndTheFirstIsThePartner()
	{
		capture = true;
		armAndStart(new FightStart("Me", new Position(1, 2, 0), List.of("Buddy")));
		session.endTick();
		session.playerSeen("Late");
		session.playerLeft("Late");

		assertEquals(Optional.of("Buddy"), session.partnerName());
		session.leave();
		KillLog kill = ended.get(0);
		assertEquals(List.of(new PlayerSeen(0, "Buddy"), new PlayerSeen(1, "Late")), kill.eventsOf(PlayerSeen.class));
		assertTrue(kill.getHeader().isCapture());
	}

	@Test
	public void entryChoiceDuringAFightIsIgnored()
	{
		session.chooseEntry(EntryChoice.TRAVEL);
		armAndStart(solo());
		session.chooseEntry(EntryChoice.JOIN);
		session.leave();

		assertEquals(EntryChoice.TRAVEL, ended.get(0).eventsOf(EntryChosen.class).get(0).getChoice());
	}

	private static final class FakeSnapshots implements SnapshotSource
	{
		private boolean available = true;

		@Override
		public Optional<SuppliesSnapshot> take(int tick, SnapshotKind kind)
		{
			return available ? Optional.of(new SuppliesSnapshot(tick, kind, List.of())) : Optional.empty();
		}
	}
}
