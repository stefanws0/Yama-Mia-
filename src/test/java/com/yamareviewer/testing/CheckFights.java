package com.yamareviewer.testing;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.Projections;
import com.yamareviewer.domain.projection.ReviewBuilder;
import com.yamareviewer.domain.projection.ReviewSettings;

/**
 * Whole synthetic kills that drive the Part 2 and Part 3 projections the way a recorded fight does (spec 6.2,
 * 6.4, 6.5, 6.6 and 6.9), built on Part 3's KillLogBuilder helpers (yamaCasts, impactOn, crashLine, judgeSpawns,
 * judgeDespawns, specAnimation) with TestIds. Every helper leaves the cursor on the tick after what it added.
 * P1 attacks are never 7 ticks apart here, because that would make ContractProjection see a contract (spec 6.3).
 * Part 3's testing.Fights is a 10-tick-phase skeleton for projection unit tests; this fixture is for the checks.
 */
public final class CheckFights
{
	/** The P3 attack cycle in ticks (spec 3). */
	public static final int CYCLE = 7;
	/** The first P3 tick of a kill built with soloUntilP3() or duoUntilP3(). */
	public static final int P3_START = 120;
	public static final int SELF_X = 3200;
	public static final int SELF_Y = 3200;
	public static final int PARTNER_X = 3210;
	public static final int PARTNER_Y = 3210;

	private CheckFights()
	{
	}

	/** A fresh solo kill (Travel, nobody else seen) at tick 0. */
	public static KillLogBuilder solo()
	{
		return KillLogBuilder.kill().entry(EntryChoice.TRAVEL);
	}

	/** A fresh duo-host kill: Travel, a partner seen at tick 0 standing at (3210, 3210). */
	public static KillLogBuilder duo()
	{
		return KillLogBuilder.kill().entry(EntryChoice.TRAVEL).playerSeen("Buddy").partnerAt(PARTNER_X, PARTNER_Y);
	}

	/** P1 ticks 0-39, Judge 1 40-59, P2 60-99, Judge 2 100-119; the cursor ends on tick 120, the first P3 tick. */
	public static KillLogBuilder soloUntilP3()
	{
		return untilP3(solo());
	}

	public static KillLogBuilder duoUntilP3()
	{
		return untilP3(duo());
	}

	/** From the cursor: 40 more ticks of the current phase, a Judge phase, 40 ticks, a Judge phase. */
	public static KillLogBuilder untilP3(KillLogBuilder kill)
	{
		kill.ticks(40);
		judgePhase(kill);
		kill.ticks(40);
		judgePhase(kill);
		return kill;
	}

	/** A 20-tick Judge phase: the Judge spawns at the cursor and despawns dying 20 ticks later (spec 6.2). */
	public static KillLogBuilder judgePhase(KillLogBuilder kill)
	{
		return kill.judgeSpawns().ticks(20).judgeDespawns();
	}

	public static int castGraphic(Style style)
	{
		return TestIds.id(style == Style.MAGIC ? Role.YAMA_CAST_MAGIC : Role.YAMA_CAST_RANGED);
	}

	public static int impactGraphic(Style style)
	{
		return TestIds.id(style == Style.MAGIC ? Role.IMPACT_MAGIC : Role.IMPACT_RANGED);
	}

	/**
	 * One standard attack cast at the cursor (spec 6.4): Yama's attack animation and cast graphic on the cast
	 * tick, the impact graphic and a hitsplat of {@code damage} on the target two ticks later. SELF's protection
	 * prayers from the cast tick on are {@code prayersAtCast}. Leaves the cursor CYCLE ticks after the cast.
	 */
	public static KillLogBuilder attack(KillLogBuilder kill, Style style, Actor target, int damage, ProtectionPrayer... prayersAtCast)
	{
		return kill.prayers(prayersAtCast)
			.yamaTarget(target)
			.yamaCasts(style)
			.ticks(2)
			.impactOn(target, style)
			.hitsplatOn(target, damage)
			.ticks(CYCLE - 2);
	}

	/** {@code count} attacks on SELF alternating magic, ranged, ..., each prayed correctly and hitting for 2. */
	public static KillLogBuilder blockedAttacks(KillLogBuilder kill, int count)
	{
		for (int i = 0; i < count; i++)
		{
			Style style = alternating(i);
			attack(kill, style, Actor.SELF, 2, correct(style));
		}
		return kill;
	}

	public static Style alternating(int index)
	{
		return index % 2 == 0 ? Style.MAGIC : Style.RANGED;
	}

	public static ProtectionPrayer correct(Style style)
	{
		return style == Style.MAGIC ? ProtectionPrayer.MAGIC : ProtectionPrayer.MISSILES;
	}

	public static ProtectionPrayer wrong(Style style)
	{
		return style == Style.MAGIC ? ProtectionPrayer.MISSILES : ProtectionPrayer.MAGIC;
	}

	/** One crash line at the cursor: three CRASH_FIREBALL ground graphics in a row centred on (x, y) (spec 6.6). */
	public static KillLogBuilder crashLine(KillLogBuilder kill, int x, int y)
	{
		return kill.crashLine(x, y);
	}

	/** A complete crash set for the player at (x, y): three lines two ticks apart. Advances the cursor CYCLE ticks. */
	public static KillLogBuilder crashSet(KillLogBuilder kill, int x, int y)
	{
		crashLine(kill, x, y).ticks(2);
		crashLine(kill, x, y).ticks(2);
		crashLine(kill, x, y).ticks(CYCLE - 4);
		return kill;
	}

	/**
	 * An Emberlight spec (spec 6.9): at the cursor the weapon is wielded with {@code energyBefore}% spec energy;
	 * on the next tick SELF plays the spec animation, hits Yama for 40 and the energy is 25 lower. Advances CYCLE ticks.
	 */
	public static KillLogBuilder emberlightSpec(KillLogBuilder kill, int energyBefore)
	{
		return kill.weapon(TestIds.id(Role.WEAPON_EMBERLIGHT)).spec(energyBefore).endTick()
			.specAnimation(Actor.SELF, Role.SPEC_EMBERLIGHT).myHitOn(Actor.YAMA, 40).spec(energyBefore - 25).endTick()
			.ticks(CYCLE - 2);
	}

	/** Ends the fight the way the recorder does: END supplies snapshot, then FightEnded at the cursor. */
	public static KillLog finish(KillLogBuilder kill, EndReason reason)
	{
		return kill.supplies(SnapshotKind.END).end(reason);
	}

	/** A complete healthy solo kill: 20 blocked P3 attacks around one crash set and one Emberlight spec; Yama dies at tick 274. */
	public static KillLog healthySolo()
	{
		KillLogBuilder kill = soloUntilP3();
		blockedAttacks(kill, 10);
		crashSet(kill, SELF_X, SELF_Y);
		emberlightSpec(kill, 100);
		blockedAttacks(kill, 10);
		return finish(kill, EndReason.YAMA_DIED);
	}

	/** The standard projections run over a kill with TestIds and Rules.DEFAULT. */
	public static ProjectionContext context(KillLog kill)
	{
		return context(kill, Rules.DEFAULT);
	}

	public static ProjectionContext context(KillLog kill, Rules rules)
	{
		return new ReviewBuilder(Projections.standard(), TestIds.registry(), rules).run(kill, ReviewSettings.DEFAULT);
	}
}
