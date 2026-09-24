package com.yamareviewer.testing;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.EventType;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.SupplyItem;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.model.KillLog;
import java.util.List;
import java.util.Set;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class KillLogBuilderTest
{
	private static List<String> types(KillLog kill)
	{
		return kill.getEvents().stream().map(event -> EventType.of(event).typeName()).collect(toList());
	}

	@Test
	public void startsLikeTheRecorder()
	{
		KillLog kill = KillLogBuilder.kill().build();

		assertEquals(List.of("fight-start", "supplies"), types(kill));
		assertEquals(new FightStarted(0, "Me", new Position(3200, 3200, 0)), kill.getEvents().get(0));
		assertEquals(new SuppliesSnapshot(0, SnapshotKind.START, List.of()), kill.getEvents().get(1));
		assertEquals(EndReason.LEFT, kill.endReason());
	}

	@Test
	public void entryGoesToTheFront()
	{
		KillLog kill = KillLogBuilder.kill().yamaAnimates(1).entry(EntryChoice.JOIN).build();

		assertEquals(List.of("entry", "fight-start", "supplies", "animation"), types(kill));
	}

	@Test
	public void eventsGoAtTheCursorAndEndTickRecordsTheStateThenAdvances()
	{
		KillLogBuilder builder = KillLogBuilder.kill().yamaAnimates(5).endTick();
		assertEquals(1, builder.tick());
		builder.hp(50).prayers(ProtectionPrayer.MAGIC).yamaTarget(Actor.PARTNER).partnerAt(3210, 3200).endTick();
		assertEquals(2, builder.tick());

		KillLog kill = builder.build();

		assertEquals(new AnimationObserved(0, Actor.YAMA, 5), kill.eventsOf(AnimationObserved.class).get(0));
		List<TickState> states = kill.eventsOf(TickState.class);
		assertEquals(new TickState(0, Set.of(), 99, 99, 100, 100, -1, Actor.SELF, new Position(3200, 3200, 0), null), states.get(0));
		assertEquals(new TickState(1, Set.of(ProtectionPrayer.MAGIC), 50, 99, 100, 100, -1, Actor.PARTNER,
			new Position(3200, 3200, 0), new Position(3210, 3200, 0)), states.get(1));
		assertEquals(1, kill.lastTick());
	}

	@Test
	public void aDeathEndsWithTheEndSnapshotTheDeathTicksStateAndFightEnded()
	{
		SupplyItem shark = new SupplyItem(385, "Shark", 5, 800, 90);

		KillLog kill = KillLogBuilder.kill().supplies(SnapshotKind.START, shark).ticks(3).hitsplatOn(Actor.SELF, 40).hp(0).end(EndReason.PLAYER_DIED);

		List<DomainEvent> events = kill.getEvents();
		int n = events.size();
		assertEquals(new SuppliesSnapshot(3, SnapshotKind.END, List.of(shark)), events.get(n - 3));
		assertEquals(3, events.get(n - 2).getTick());
		assertEquals(0, ((TickState) events.get(n - 2)).getHitpoints());
		assertEquals(new FightEnded(3, EndReason.PLAYER_DIED), events.get(n - 1));
		List<SuppliesSnapshot> snapshots = kill.eventsOf(SuppliesSnapshot.class);
		assertEquals(2, snapshots.size());
		assertEquals(new SuppliesSnapshot(0, SnapshotKind.START, List.of(shark)), snapshots.get(0));
		assertEquals(EndReason.PLAYER_DIED, kill.endReason());
	}

	@Test
	public void anExplicitEndSnapshotIsKept()
	{
		SupplyItem shark = new SupplyItem(385, "Shark", 3, 800, 90);

		KillLog kill = KillLogBuilder.kill().ticks(2).supplies(SnapshotKind.END, shark).end(EndReason.YAMA_DIED);

		List<SuppliesSnapshot> ends = kill.eventsOf(SuppliesSnapshot.class).stream()
			.filter(snapshot -> snapshot.getKind() == SnapshotKind.END).collect(toList());
		assertEquals(List.of(new SuppliesSnapshot(2, SnapshotKind.END, List.of(shark))), ends);
		assertEquals(EndReason.YAMA_DIED, kill.endReason());
		assertEquals(2, kill.lastTick());
	}

	@Test
	public void leavingAddsOnlyFightEndedAtTheCursor()
	{
		KillLog kill = KillLogBuilder.kill().ticks(2).end(EndReason.LEFT);

		List<DomainEvent> events = kill.getEvents();
		assertEquals(new FightEnded(2, EndReason.LEFT), events.get(events.size() - 1));
		assertEquals(1, events.get(events.size() - 2).getTick());
		assertTrue(kill.eventsOf(SuppliesSnapshot.class).stream().noneMatch(snapshot -> snapshot.getKind() == SnapshotKind.END));
	}

	@Test
	public void hitsplatsCarryKindAndOwnership()
	{
		KillLog kill = KillLogBuilder.kill().hitsplatOn(Actor.SELF, 7).hitsplatOn(Actor.YAMA, 0).myHitOn(Actor.YAMA, 30).yamaHealed(12).build();

		assertEquals(List.of(
			new HitsplatObserved(0, Actor.SELF, HitsplatKind.DAMAGE, 7, KillLogBuilder.RAW_DAMAGE, false),
			new HitsplatObserved(0, Actor.YAMA, HitsplatKind.BLOCK, 0, KillLogBuilder.RAW_BLOCK, false),
			new HitsplatObserved(0, Actor.YAMA, HitsplatKind.DAMAGE, 30, KillLogBuilder.RAW_DAMAGE, true),
			new HitsplatObserved(0, Actor.YAMA, HitsplatKind.HEAL, 12, KillLogBuilder.RAW_HEAL, false)),
			kill.eventsOf(HitsplatObserved.class));
	}

	@Test
	public void npcEventsCarryTheFlareIndexAndAnExplicitIndexWhenGiven()
	{
		KillLog kill = KillLogBuilder.kill()
			.npcSpawns(Actor.flare(7), 500)
			.npcDespawns(Actor.flare(7), 500, true)
			.npcSpawns(Actor.JUDGE, 501)
			.npcSpawns(Actor.JUDGE, 501, 9)
			.build();

		assertEquals(new NpcSpawnObserved(0, Actor.flare(7), 500, 7), kill.eventsOf(NpcSpawnObserved.class).get(0));
		assertEquals(new NpcDespawnObserved(0, Actor.flare(7), 500, 7, true), kill.eventsOf(NpcDespawnObserved.class).get(0));
		assertEquals(new NpcSpawnObserved(0, Actor.JUDGE, 501, KillLogBuilder.JUDGE_INDEX), kill.eventsOf(NpcSpawnObserved.class).get(1));
		assertEquals(new NpcSpawnObserved(0, Actor.JUDGE, 501, 9), kill.eventsOf(NpcSpawnObserved.class).get(2));
	}

	@Test
	public void theStartSnapshotReplacesTheDefaultOne()
	{
		SupplyItem shark = new SupplyItem(385, "Shark", 5, 800, 90);

		KillLog kill = KillLogBuilder.kill().entry(EntryChoice.TRAVEL).ticks(1).supplies(SnapshotKind.START, shark).build();

		assertEquals(List.of(new SuppliesSnapshot(0, SnapshotKind.START, List.of(shark))), kill.eventsOf(SuppliesSnapshot.class));
		assertEquals(List.of("entry", "fight-start", "supplies", "tick"), types(kill));
	}
}
