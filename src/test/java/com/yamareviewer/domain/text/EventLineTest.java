package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.EntryChosen;
import com.yamareviewer.domain.event.EventType;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.GameMessageObserved;
import com.yamareviewer.domain.event.GameStateKind;
import com.yamareviewer.domain.event.GameStateObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.NpcChangedObserved;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.ObjectAnimationObserved;
import com.yamareviewer.domain.event.ObjectDespawnObserved;
import com.yamareviewer.domain.event.ObjectSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.event.PlayerLeft;
import com.yamareviewer.domain.event.PlayerSeen;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.SupplyItem;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.event.WidgetTextObserved;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class EventLineTest
{
	private static final Position HERE = new Position(3200, 3200, 0);

	private static List<DomainEvent> oneOfEach()
	{
		return List.of(
			new EntryChosen(0, EntryChoice.JOIN),
			new FightStarted(0, "Me", HERE),
			new FightEnded(90, EndReason.PLAYER_DIED),
			new PlayerSeen(0, "Buddy"),
			new PlayerLeft(80, "Buddy"),
			new GameStateObserved(3, GameStateKind.LOADING),
			new NpcSpawnObserved(1, Actor.flare(7), 1234, 7),
			new NpcDespawnObserved(2, Actor.YAMA, 1235, 1, true),
			new NpcChangedObserved(2, Actor.YAMA, 1235, 1236),
			new ObjectSpawnObserved(3, 555, HERE),
			new ObjectDespawnObserved(4, 555, HERE),
			new ObjectAnimationObserved(4, 555, HERE, 12169),
			new OverheadTextObserved(4, Actor.YAMA, "Enough."),
			new GameMessageObserved(4, "<col=ef1020>You've been injured and can't use protection prayers!</col>"),
			new VarbitObserved(5, 16550, 2),
			new WidgetTextObserved(5, 59899907, "Contract of Bloodied Blows"),
			new AnimationObserved(6, Actor.PARTNER, 42),
			new GraphicObserved(7, Actor.other("Stranger"), 43),
			new GroundGraphicObserved(8, 44, HERE),
			new ProjectileObserved(9, 45, null, 11),
			new HitsplatObserved(10, Actor.JUDGE, HitsplatKind.BLOCK, 0, 12, true),
			new TickState(11, Set.of(ProtectionPrayer.MAGIC), 80, 50, 100, 64, 4151, Actor.SELF, HERE, null),
			new InventoryDelta(12, 385, "Shark", -1),
			new SuppliesSnapshot(13, SnapshotKind.END, List.of(new SupplyItem(385, "Shark", 3, 800, 90))));
	}

	@Test
	public void everyEventTypeHasALineStartingWithItsTickAndTypeName()
	{
		List<DomainEvent> events = oneOfEach();
		assertEquals(Set.copyOf(Arrays.asList(EventType.values())), events.stream().map(EventType::of).collect(Collectors.toSet()));
		for (DomainEvent event : events)
		{
			String line = EventLine.of(event, "Buddy");
			String prefix = event.getTick() + " " + EventType.of(event).typeName() + " ";
			assertTrue(line, line.startsWith(prefix));
			assertTrue(line, line.length() > prefix.length());
			assertFalse(line, line.contains("Buddy"));
			assertFalse(line, line.contains("Stranger"));
			assertFalse(line, line.contains("Me "));
		}
	}

	@Test
	public void formatsActorsPositionsAndStates()
	{
		assertEquals("0 fight-start at (3200,3200,0)", EventLine.of(new FightStarted(0, "Me", HERE), null));
		assertEquals("0 player-seen PARTNER", EventLine.of(new PlayerSeen(0, "Buddy"), "Buddy"));
		assertEquals("0 player-seen OTHER", EventLine.of(new PlayerSeen(0, "Someone"), "Buddy"));
		assertEquals("1 npc-spawn FLARE(7) id=1234 index=7", EventLine.of(new NpcSpawnObserved(1, Actor.flare(7), 1234, 7), null));
		assertEquals("2 npc-despawn YAMA id=1235 index=1 dying=true", EventLine.of(new NpcDespawnObserved(2, Actor.YAMA, 1235, 1, true), null));
		assertEquals("2 npc-changed YAMA 1235 -> 1236", EventLine.of(new NpcChangedObserved(2, Actor.YAMA, 1235, 1236), null));
		assertEquals("7 graphic OTHER 43", EventLine.of(new GraphicObserved(7, Actor.other("Stranger"), 43), null));
		assertEquals("7 graphic NPC(14182) 43", EventLine.of(new GraphicObserved(7, Actor.npc(14182), 43), null));
		assertEquals("8 ground-graphic 44 at (3200,3200,0)", EventLine.of(new GroundGraphicObserved(8, 44, HERE), null));
		assertEquals("9 projectile 45 -> tile lands 11", EventLine.of(new ProjectileObserved(9, 45, null, 11), null));
		assertEquals("9 projectile 45 -> SELF lands 11", EventLine.of(new ProjectileObserved(9, 45, Actor.SELF, 11), null));
		assertEquals("10 hitsplat JUDGE BLOCK 0 type=12 mine=true", EventLine.of(new HitsplatObserved(10, Actor.JUDGE, HitsplatKind.BLOCK, 0, 12, true), null));
		assertEquals("11 tick prayers=[MAGIC] hp=80 prayer=50 spec=100 run=64 weapon=4151 target=SELF self=(3200,3200,0) partner=-",
			EventLine.of(new TickState(11, Set.of(ProtectionPrayer.MAGIC), 80, 50, 100, 64, 4151, Actor.SELF, HERE, null), null));
		assertEquals("12 inventory 385 \"Shark\" -1", EventLine.of(new InventoryDelta(12, 385, "Shark", -1), null));
		assertEquals("13 supplies END 1 items", EventLine.of(new SuppliesSnapshot(13, SnapshotKind.END, List.of(new SupplyItem(385, "Shark", 3, 800, 90))), null));
		assertEquals("4 overhead YAMA \"Enough.\"", EventLine.of(new OverheadTextObserved(4, Actor.YAMA, "Enough."), null));
		assertEquals("5 varbit 16550=2", EventLine.of(new VarbitObserved(5, 16550, 2), null));
	}
}
