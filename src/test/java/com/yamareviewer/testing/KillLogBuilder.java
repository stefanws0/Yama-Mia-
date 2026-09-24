package com.yamareviewer.testing;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.EntryChosen;
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
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.ObjectSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.event.PlayerSeen;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.SupplyItem;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.event.WidgetTextObserved;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Builds a KillLog like the recorder does: events at the cursor tick; endTick() appends a TickState for the
 * cursor and advances it. Starts with FightStarted("Me", position(3200,3200,0)) and a START SuppliesSnapshot
 * at tick 0. end(YAMA_DIED) and end(PLAYER_DIED) finish like KillSession does: an END snapshot (the START
 * items when none was given), the cursor tick's TickState, then FightEnded at the cursor. end(LEFT) appends
 * only FightEnded (a logout mid-fight); build() has no FightEnded at all.
 */
public final class KillLogBuilder
{
	public static final Position START_POSITION = new Position(3200, 3200, 0);
	/** RuneLite's HitsplatID.DAMAGE_ME, BLOCK_ME and HEAL; projections read the kind, never the raw type. */
	public static final int RAW_DAMAGE = 16;
	public static final int RAW_BLOCK = 12;
	public static final int RAW_HEAL = 6;
	public static final int YAMA_INDEX = 1;
	public static final int JUDGE_INDEX = 2;

	private final List<DomainEvent> events = new ArrayList<>();
	private int cursor;
	private int startSnapshotIndex;

	private Set<ProtectionPrayer> prayers = Set.of();
	private int hitpoints = 99;
	private int prayerPoints = 99;
	private int specEnergy = 100;
	private int runEnergy = 100;
	private int weaponId = -1;
	private Actor yamaTarget = Actor.SELF;
	private Position selfPosition = START_POSITION;
	private Position partnerPosition;

	private KillLogBuilder()
	{
		events.add(new FightStarted(0, "Me", START_POSITION));
		startSnapshotIndex = events.size();
		events.add(new SuppliesSnapshot(0, SnapshotKind.START, List.of()));
	}

	public static KillLogBuilder kill()
	{
		return new KillLogBuilder();
	}

	/** Inserts EntryChosen(0) at the front, as the recorder does. */
	public KillLogBuilder entry(EntryChoice choice)
	{
		events.add(0, new EntryChosen(0, choice));
		startSnapshotIndex++;
		return this;
	}

	public KillLogBuilder event(DomainEvent event)
	{
		events.add(event);
		return this;
	}

	public KillLogBuilder yamaAnimates(int animationId)
	{
		return animates(Actor.YAMA, animationId);
	}

	public KillLogBuilder animates(Actor actor, int animationId)
	{
		return event(new AnimationObserved(cursor, actor, animationId));
	}

	public KillLogBuilder graphicOn(Actor actor, int graphicId)
	{
		return event(new GraphicObserved(cursor, actor, graphicId));
	}

	public KillLogBuilder groundGraphic(int graphicId, int x, int y)
	{
		return event(new GroundGraphicObserved(cursor, graphicId, new Position(x, y, 0)));
	}

	/** DAMAGE, or BLOCK when the amount is 0; mine = false. */
	public KillLogBuilder hitsplatOn(Actor target, int amount)
	{
		return hit(target, amount, false);
	}

	/** The local player's own hit: mine = true. */
	public KillLogBuilder myHitOn(Actor target, int amount)
	{
		return hit(target, amount, true);
	}

	public KillLogBuilder yamaHealed(int amount)
	{
		return event(new HitsplatObserved(cursor, Actor.YAMA, HitsplatKind.HEAL, amount, RAW_HEAL, false));
	}

	/** The NPC index is the flare's index for flares, YAMA_INDEX / JUDGE_INDEX for Yama and the Judge. */
	public KillLogBuilder npcSpawns(Actor actor, int npcId)
	{
		return npcSpawns(actor, npcId, indexOf(actor));
	}

	public KillLogBuilder npcSpawns(Actor actor, int npcId, int npcIndex)
	{
		return event(new NpcSpawnObserved(cursor, actor, npcId, npcIndex));
	}

	public KillLogBuilder npcDespawns(Actor actor, int npcId, boolean dying)
	{
		return npcDespawns(actor, npcId, indexOf(actor), dying);
	}

	public KillLogBuilder npcDespawns(Actor actor, int npcId, int npcIndex, boolean dying)
	{
		return event(new NpcDespawnObserved(cursor, actor, npcId, npcIndex, dying));
	}

	public KillLogBuilder objectSpawns(int objectId, int x, int y)
	{
		return event(new ObjectSpawnObserved(cursor, objectId, new Position(x, y, 0)));
	}

	public KillLogBuilder overhead(Actor actor, String text)
	{
		return event(new OverheadTextObserved(cursor, actor, text));
	}

	public KillLogBuilder message(String text)
	{
		return event(new GameMessageObserved(cursor, text));
	}

	public KillLogBuilder varbit(int varbitId, int value)
	{
		return event(new VarbitObserved(cursor, varbitId, value));
	}

	public KillLogBuilder widgetText(int componentId, String text)
	{
		return event(new WidgetTextObserved(cursor, componentId, text));
	}

	public KillLogBuilder playerSeen(String name)
	{
		return event(new PlayerSeen(cursor, name));
	}

	public KillLogBuilder gameState(GameStateKind state)
	{
		return event(new GameStateObserved(cursor, state));
	}

	public KillLogBuilder inventory(int itemId, String name, int change)
	{
		return event(new InventoryDelta(cursor, itemId, name, change));
	}

	/** START replaces the builder's default START snapshot (tick 0); END is appended at the cursor. */
	public KillLogBuilder supplies(SnapshotKind kind, SupplyItem... items)
	{
		if (kind == SnapshotKind.START)
		{
			events.set(startSnapshotIndex, new SuppliesSnapshot(0, SnapshotKind.START, List.of(items)));
			return this;
		}
		return event(new SuppliesSnapshot(cursor, SnapshotKind.END, List.of(items)));
	}

	public KillLogBuilder prayers(ProtectionPrayer... active)
	{
		prayers = active.length == 0 ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(active)));
		return this;
	}

	public KillLogBuilder hp(int value)
	{
		hitpoints = value;
		return this;
	}

	public KillLogBuilder prayerPoints(int points)
	{
		prayerPoints = points;
		return this;
	}

	public KillLogBuilder spec(int percent)
	{
		specEnergy = percent;
		return this;
	}

	public KillLogBuilder run(int percent)
	{
		runEnergy = percent;
		return this;
	}

	public KillLogBuilder weapon(int itemId)
	{
		weaponId = itemId;
		return this;
	}

	public KillLogBuilder yamaTarget(Actor target)
	{
		yamaTarget = target;
		return this;
	}

	public KillLogBuilder selfAt(int x, int y)
	{
		selfPosition = new Position(x, y, 0);
		return this;
	}

	public KillLogBuilder partnerAt(int x, int y)
	{
		partnerPosition = new Position(x, y, 0);
		return this;
	}

	/** Yama's standard attack animation plus the cast graphic of the style, both at the cursor tick. */
	public KillLogBuilder yamaCasts(Style style)
	{
		yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK));
		return graphicOn(Actor.YAMA, TestIds.id(style == Style.MAGIC ? Role.YAMA_CAST_MAGIC : Role.YAMA_CAST_RANGED));
	}

	/** The impact graphic of the style on the player it hit. */
	public KillLogBuilder impactOn(Actor target, Style style)
	{
		return graphicOn(target, TestIds.id(style == Style.MAGIC ? Role.IMPACT_MAGIC : Role.IMPACT_RANGED));
	}

	/** Three CRASH_FIREBALL ground graphics in a row: (x-1, y), (x, y), (x+1, y). */
	public KillLogBuilder crashLine(int centreX, int centreY)
	{
		int fireball = TestIds.id(Role.CRASH_FIREBALL);
		groundGraphic(fireball, centreX - 1, centreY);
		groundGraphic(fireball, centreX, centreY);
		return groundGraphic(fireball, centreX + 1, centreY);
	}

	public KillLogBuilder crashImpactOn(Actor player)
	{
		return graphicOn(player, TestIds.id(Role.CRASH_IMPACT));
	}

	public KillLogBuilder waveOn(Actor player)
	{
		return graphicOn(player, TestIds.id(Role.SHADOW_WAVE));
	}

	public KillLogBuilder waveAt(int x, int y)
	{
		return groundGraphic(TestIds.id(Role.SHADOW_WAVE), x, y);
	}

	public KillLogBuilder prayersDisabledMessage()
	{
		return message(TestIds.text(Role.PRAYER_DISABLED_MESSAGE));
	}

	/** A glyph object spawning: GLYPH_FIRE or GLYPH_SHADOW. */
	public KillLogBuilder glyph(Role glyph, int x, int y)
	{
		return objectSpawns(TestIds.id(glyph), x, y);
	}

	/** A SPEC_* animation on a player. */
	public KillLogBuilder specAnimation(Actor actor, Role spec)
	{
		return animates(actor, TestIds.id(spec));
	}

	public KillLogBuilder judgeSpawns()
	{
		return npcSpawns(Actor.JUDGE, TestIds.id(Role.JUDGE));
	}

	public KillLogBuilder judgeDespawns()
	{
		return npcDespawns(Actor.JUDGE, TestIds.id(Role.JUDGE), true);
	}

	/** Appends the cursor tick's TickState and advances the cursor. */
	public KillLogBuilder endTick()
	{
		events.add(tickState());
		cursor++;
		return this;
	}

	public KillLogBuilder ticks(int count)
	{
		for (int i = 0; i < count; i++)
		{
			endTick();
		}
		return this;
	}

	/** The cursor: the tick the next event goes to. */
	public int tick()
	{
		return cursor;
	}

	/** FightEnded at the cursor, then build; deaths also get the END snapshot and the death tick's state first. */
	public KillLog end(EndReason reason)
	{
		if (reason != EndReason.LEFT)
		{
			if (events.stream().noneMatch(event -> event instanceof SuppliesSnapshot && ((SuppliesSnapshot) event).getKind() == SnapshotKind.END))
			{
				SuppliesSnapshot start = (SuppliesSnapshot) events.get(startSnapshotIndex);
				events.add(new SuppliesSnapshot(cursor, SnapshotKind.END, start.getItems()));
			}
			events.add(tickState());
		}
		events.add(new FightEnded(cursor, reason));
		return build();
	}

	/** No FightEnded: a LEFT kill. */
	public KillLog build()
	{
		KillHeader header = new KillHeader("test-kill", 1_700_000_000_000L, 1_700_000_000_000L + cursor * 600L, "test",
			KillLog.SCHEMA_VERSION, "test", false);
		return KillLog.of(header, events, 0);
	}

	private KillLogBuilder hit(Actor target, int amount, boolean mine)
	{
		if (amount > 0)
		{
			return event(new HitsplatObserved(cursor, target, HitsplatKind.DAMAGE, amount, RAW_DAMAGE, mine));
		}
		return event(new HitsplatObserved(cursor, target, HitsplatKind.BLOCK, 0, RAW_BLOCK, mine));
	}

	private TickState tickState()
	{
		return new TickState(cursor, prayers, hitpoints, prayerPoints, specEnergy, runEnergy, weaponId, yamaTarget, selfPosition, partnerPosition);
	}

	private static int indexOf(Actor actor)
	{
		switch (actor.getKind())
		{
			case FLARE:
				return actor.getRef();
			case YAMA:
				return YAMA_INDEX;
			case JUDGE:
				return JUDGE_INDEX;
			default:
				return 100 + actor.getKind().ordinal();
		}
	}
}
