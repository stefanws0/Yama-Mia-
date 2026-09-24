# Yama Reviewer Part 3: P3 Prayer Review, Crashes, Waves, Specs and Drains Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the Part 2 review with everything P3-specific: Yama's attacks and their styles, the per-attack prayer review, the opener, the P3 tick log, Shadow Crash lines, Shadow Waves, special attacks with the drain model, and their lines in chat, panel, clipboard and history.

**Architecture:** Eight new pure projections in `domain.projection` read the Part 1 event stream and the Part 2 sections already in the `ProjectionContext` (phases, contract, mode) and put their own `Section<T>` results into it. `Attacks` and `Glyphs` are intermediate sections that feed `PrayerReview`, `Opener`, `TickLog`, `Crashes`, `Waves` and the damage attribution rules 1, 3 and 4. Prayer outcomes are an ordered list of `OutcomeRule`s in `domain.outcome`; drains are one `DrainRule` per weapon applied by a `DrainModel` in `domain.drain`. Text output goes through one new formatter class, `P3Text`, that Part 2's `ReviewFormatter`, `ChatLines` and `ClipboardExport` call.

**Tech Stack:** Java 11 (release target), Gradle from the RuneLite example-plugin template, Lombok, JUnit 4.12, Mockito 5, ArchUnit 1.3 (rules from Part 1 keep passing), the Part 2 test DSL `KillLogBuilder` and `TestIds`.

**Spec:** `docs/superpowers/specs/2026-09-24-yama-reviewer-design.md`, sections 3, 5.5, 6.1, 6.3, 6.4–6.7, 6.9, 6.10 (rules 1, 3, 4), 7.2, 7.3, 7.4, 12 and 13 (part 3). The Part 1 plan (`docs/superpowers/plans/2026-09-24-yama-reviewer-part1-foundation.md`) is binding for every Part 1 type; the Part 2 plan (`docs/superpowers/plans/2026-09-24-yama-reviewer-part2-review.md`) for every Part 2 type named below.

## Global Constraints

- Java 11 only; `options.release.set(11)`; no records, no `switch` expressions, no `instanceof` patterns.
- RuneLite code style: tabs, braces on their own line (Allman), Lombok `@Value` for value objects, `@Builder(toBuilder = true)` only where Part 2 already uses it.
- No reflection in main code. `log.debug` for per-event logging.
- `domain` imports nothing from `application`, `adapter`, `net.runelite`, `com.google`, Swing or AWT. `application` imports nothing from `adapter`, `net.runelite`, `com.google`, Swing or AWT. Every class of this part except the test tools lives in `domain`.
- Only `adapter.recording` imports `net.runelite.api.events` or declares `@Subscribe` methods. This part adds no adapter code and no game IDs; game IDs stay in `adapter.ids.BuiltInIds` and tests use `TestIds`.
- Nothing is drawn, printed or played during a fight; this part only changes what is computed and shown after `FightEnded`.
- All file I/O through RuneLite's `Filepath`; `java.io.File` and `java.nio.file.Files` only in the test source set (the ArchUnit rules exclude tests).
- Use the injected `Gson` in main code; tests and tools may use `new Gson()`.
- The ArchUnit rules of `src/test/java/com/yamareviewer/ArchitectureTest.java` must keep passing after every task; never weaken a rule.
- Event type names (`EventType`) never change; this part adds no events.
- Binding names from the interface brief are used exactly: result types `Attack`, `AttackTimeline`, `GlyphCount`, `PrayerOutcome`, `AttackResult`, `PrayerReview`, `Opener`, `TickLogEntry`, `TickLog`, `CrashLine`, `CrashSummary`, `WaveHit`, `WaveSummary`, `SpecResult`, `DrainStep`, `SpecSummary`; section keys `GLYPHS`, `ATTACKS`, `CRASHES`, `WAVES`, `SPECS`, `PRAYER_REVIEW`, `OPENER`, `TICK_LOG`; projections `GlyphsProjection`, `AttacksProjection`, `CrashesProjection`, `WavesProjection`, `SpecsProjection`, `PrayerReviewProjection`, `OpenerProjection`, `TickLogProjection`; `domain.outcome.OutcomeRule`; `domain.drain.YamaStats`, `DrainRule`, `DrainModel`, `SpecWeapon`; `KillReview` fields `prayerReview`, `opener`, `tickLog`, `crashes`, `waves`, `specs`.
- Tunables come from `Rules` (`prayerCheck`, `prayerCheckOffset`, `blockedMaxHit`, `p3AttackCycle`, `crashImpactWindow`, `crashSetGap`, `waveDisableWindow`, `specResultWindow`, `statRestoreTicks`) and per-contract values from `ContractRules`; no projection hard-codes them.
- Player names never appear in any text this part produces: actors are "you" and "partner".

## Review Focus

1. A P3 attack whose style cannot be determined (no cast graphic within a tick, no impact graphic within 3 ticks): the attack keeps a null style and the prayer review counts it as unscored instead of scoring `NO_PRAYER` (AttacksProjectionTest `styleStaysNullWithoutCastOrImpact`; PrayerReviewProjectionTest `attacksWithUnknownStyleAreNotScored`).
2. In duo, attacks aimed at the partner are never scored, but they still set the alternation expectation and the switch-timing origin for the next attack on you (PrayerReviewProjectionTest `partnerAttacksAreNotScoredButSetTheSwitchOrigin`).
3. The prayer-check tick falls beyond the last `TickState` (a death or a logout right after Yama's last cast): the rules read an empty prayer set and score `NO_PRAYER` instead of throwing (PrayerReviewProjectionTest `checkTickPastTheEndOfTheLogScoresNoPrayer`); with `HITSPLAT` checking and no landing hitsplat the cast tick is used (PrayerReviewProjectionTest `hitsplatCheckFallsBackToTheCastTick`).
4. A spec-energy drop with an unknown weapon is recorded as an `OTHER_WEAPON` spec with its damage and no drain; a drop with a known spec weapon but no matching animation is recorded with `animationSeen = false` so Part 4's `SpecsMatched` check can find it (SpecsProjectionTest `otherWeaponDropIsRecordedWithoutDrain`, `knownWeaponWithoutAnimationIsRecordedUnmatched`).
5. Two crash lines on the same tick in duo (six fireballs) are split by proximity and each belongs to the player nearest its centre; a solo kill with a null partner position never assigns a line to `PARTNER` (CrashesProjectionTest `twoLinesOnOneTickBelongToDifferentPlayers`, `soloLinesAlwaysBelongToYou`).

---

## File Structure

```
src/main/java/com/yamareviewer/
  domain/drain/
    YamaStats.java                 modelled Defence, Magic and magic-defence bonus drained; normal-form limits
    DrainRule.java                 interface: YamaStats apply(YamaStats stats, int damage)
    ElderMaulDrain.java, DragonWarhammerDrain.java, BandosGodswordDrain.java, AccursedSceptreDrain.java,
    EmberlightDrain.java, EyeOfAyakDrain.java, NoDrain.java
    SpecWeapon.java                WEAPON_* role -> SPEC_* role -> DrainRule, display name, melee flag
    DrainModel.java                applies landed specs in tick order with the stat restore
  domain/review/
    Attack.java, AttackTimeline.java, GlyphCount.java, PrayerOutcome.java, AttackResult.java, PrayerReview.java,
    Opener.java, TickLogEntry.java, TickLog.java, CrashLine.java, CrashSummary.java, WaveHit.java, WaveSummary.java,
    SpecOutcome.java, SpecResult.java, HornUse.java, DrainStep.java, SpecSummary.java
    KillReview.java                (modify) six new Section fields with null-safe getters, allSections()
  domain/outcome/
    PrayerContext.java             what the rules look at for one attack
    OutcomeRule.java               interface: Optional<PrayerOutcome> apply(PrayerContext context)
    PrayerDisabledRule.java, BlockedRule.java, TooEarlyRule.java, LateRule.java, LostAlternationRule.java, NoPrayerRule.java
    OutcomeRules.java              the ordered list and evaluate()
  domain/projection/
    Sections.java                  (modify) eight new keys
    PhaseLookup.java               phase of a tick from PhaseTimes
    Upstream.java                  hidden-reason propagation from sections a projection depends on
    GlyphsProjection.java, AttacksProjection.java, CrashesProjection.java, WavesProjection.java, SpecsProjection.java,
    PrayerReviewProjection.java, OpenerProjection.java, TickLogProjection.java
    P3DamageSources.java           damage attribution rules 1, 3 and 4
    DamageAttributionProjection.java (modify) calls P3DamageSources
    KillReviewAssembler.java       (modify) sets the six new fields
    Projections.java               (modify) final order
  domain/text/
    P3Text.java                    every P3 line: chat line 2, the specs fragment of line 4, six panel sections
    ReviewFormatter.java, ChatLines.java, ClipboardExport.java   (modify) call P3Text
  domain/history/
    HistoryProjector.java          (modify) p3Accuracy, defenceDrained, specLandedShare
src/test/java/com/yamareviewer/
  testing/KillLogBuilder.java      (modify) P3 helper methods
  testing/Fights.java              a fight skeleton through to P3 and matching PhaseTimes
  testing/TestContext.java         ProjectionContext with sections put by hand
  domain/drain/…, domain/review/…, domain/outcome/…, domain/projection/…, domain/text/…, domain/history/… tests
  tools/Replay.java                (replace) also writes the review JSON for a golden fixture
  GoldenReviewTest.java            replays every fixture and compares with its expected JSON
src/test/resources/fixtures/       raw logs of the logging kills and their expected review JSON (last task)
```

---

### Task 1: Drain model

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/drain/YamaStats.java`, `DrainRule.java`, `ElderMaulDrain.java`, `DragonWarhammerDrain.java`, `BandosGodswordDrain.java`, `AccursedSceptreDrain.java`, `EmberlightDrain.java`, `EyeOfAyakDrain.java`, `NoDrain.java`, `SpecWeapon.java`, `DrainModel.java`
- Test: `src/test/java/com/yamareviewer/domain/drain/DrainModelTest.java`, `SpecWeaponTest.java`

**Interfaces:**
- Consumes: `Role`, `IdRegistry` (Part 1), `ContractRules` (Part 2: `getBaseDefence()`, `getBaseMagic()`), `Rules.getStatRestoreTicks()`, `TestIds` (Part 2).
- Produces: `YamaStats(int defence, int magic, int magicDefenceBonusDrained)` with `NORMAL_DEFENCE = 225`, `NORMAL_MAGIC = 250`, `DEFENCE_FLOOR = 145`, `withDefence(int)` (floored at 145), `withMagic(int)`, `plusBonusDrained(int)`; `DrainRule.apply(YamaStats, int damage)`; `SpecWeapon` enum (`EMBERLIGHT`, `ELDER_MAUL`, `DRAGON_WARHAMMER`, `BANDOS_GODSWORD`, `ACCURSED_SCEPTRE`, `EYE_OF_AYAK`, `SOULFLAME_HORN`, `PURGING_STAFF`, `SARADOMIN_GODSWORD`) with `weaponRole()`, `specRole()`, `displayName()`, `isMelee()`, `drains()`, `drainRule()`, `static Optional<SpecWeapon> ofWeapon(IdRegistry, int itemId)`, `static Optional<SpecWeapon> ofAnimation(IdRegistry, int animationId)`; `DrainModel(YamaStats base, int statRestoreTicks)`, `static DrainModel forFight(ContractRules, Rules)`, `base()`, `stats()`, `advanceTo(int tick)`, `apply(int tick, DrainRule, int damage)`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/drain/DrainModelTest.java`:

```java
package com.yamareviewer.domain.drain;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/** The pinned numbers of spec 6.9. */
public class DrainModelTest
{
	private static DrainModel normal()
	{
		return DrainModel.forFight(ContractRules.of(Contract.NONE, Rules.DEFAULT), Rules.DEFAULT);
	}

	private static DrainModel contract()
	{
		return DrainModel.forFight(ContractRules.of(Contract.CATALYST_ACQUISITION, Rules.DEFAULT), Rules.DEFAULT);
	}

	@Test
	public void startsAtTheFightsBase()
	{
		assertEquals(new YamaStats(225, 250, 0), normal().stats());
		assertEquals(new YamaStats(247, 275, 0), contract().stats());
	}

	@Test
	public void elderMaulTakesThirtyFivePercentOfCurrentDefence()
	{
		assertEquals(147, normal().apply(0, SpecWeapon.ELDER_MAUL.drainRule(), 60).getDefence());
	}

	@Test
	public void sceptreStopsAtTheNormalFormLimitsAndDoesNotStack()
	{
		DrainModel model = normal();

		YamaStats first = model.apply(0, SpecWeapon.ACCURSED_SCEPTRE.drainRule(), 30);
		YamaStats second = model.apply(5, SpecWeapon.ACCURSED_SCEPTRE.drainRule(), 30);

		assertEquals(191, first.getDefence());
		assertEquals(212, first.getMagic());
		assertEquals(first, second);
	}

	@Test
	public void sceptreDrainsTwiceUnderAContract()
	{
		DrainModel model = contract();

		YamaStats first = model.apply(0, SpecWeapon.ACCURSED_SCEPTRE.drainRule(), 30);
		YamaStats second = model.apply(5, SpecWeapon.ACCURSED_SCEPTRE.drainRule(), 30);

		assertEquals(213, first.getDefence());
		assertEquals(237, first.getMagic());
		assertEquals(191, second.getDefence());
		assertEquals(212, second.getMagic());
	}

	@Test
	public void emberlightStacksDownToTheFloor()
	{
		DrainModel model = normal();
		DrainRule emberlight = SpecWeapon.EMBERLIGHT.drainRule();

		assertEquals(191, model.apply(0, emberlight, 40).getDefence());
		assertEquals(157, model.apply(4, emberlight, 40).getDefence());
		assertEquals(145, model.apply(8, emberlight, 40).getDefence());
	}

	@Test
	public void sceptreAfterThreeEmberlightsChangesNothing()
	{
		DrainModel model = normal();
		DrainRule emberlight = SpecWeapon.EMBERLIGHT.drainRule();
		model.apply(0, emberlight, 40);
		model.apply(4, emberlight, 40);
		model.apply(8, emberlight, 40);

		assertEquals(145, model.apply(12, SpecWeapon.ACCURSED_SCEPTRE.drainRule(), 30).getDefence());
	}

	@Test
	public void dragonWarhammerOnlyDrainsWhenItDealtDamage()
	{
		assertEquals(225, normal().apply(0, SpecWeapon.DRAGON_WARHAMMER.drainRule(), 0).getDefence());
		assertEquals(158, normal().apply(0, SpecWeapon.DRAGON_WARHAMMER.drainRule(), 50).getDefence());
	}

	@Test
	public void bandosGodswordDrainsTheDamageDealt()
	{
		assertEquals(180, normal().apply(0, SpecWeapon.BANDOS_GODSWORD.drainRule(), 45).getDefence());
		assertEquals(145, normal().apply(0, SpecWeapon.BANDOS_GODSWORD.drainRule(), 90).getDefence());
	}

	@Test
	public void eyeOfAyakAddsItsDamageToTheBonusDrained()
	{
		DrainModel model = normal();
		model.apply(0, SpecWeapon.EYE_OF_AYAK.drainRule(), 30);

		YamaStats stats = model.apply(3, SpecWeapon.EYE_OF_AYAK.drainRule(), 12);

		assertEquals(42, stats.getMagicDefenceBonusDrained());
		assertEquals(225, stats.getDefence());
		assertEquals(250, stats.getMagic());
	}

	@Test
	public void noDrainWeaponsChangeNothing()
	{
		assertEquals(new YamaStats(225, 250, 0), normal().apply(0, SpecWeapon.SOULFLAME_HORN.drainRule(), 30));
		assertEquals(new YamaStats(225, 250, 0), normal().apply(0, SpecWeapon.PURGING_STAFF.drainRule(), 30));
		assertEquals(new YamaStats(225, 250, 0), normal().apply(0, SpecWeapon.SARADOMIN_GODSWORD.drainRule(), 30));
	}

	@Test
	public void oneRestoreStepAfterAHundredTicks()
	{
		DrainModel model = normal();
		model.apply(0, SpecWeapon.ELDER_MAUL.drainRule(), 60);

		assertEquals(147, model.advanceTo(99).getDefence());
		assertEquals(148, model.advanceTo(100).getDefence());
		assertEquals(148, model.advanceTo(150).getDefence());
		assertEquals(150, model.advanceTo(300).getDefence());
	}

	@Test
	public void restoreGoesTowardsTheFightsBaseAndStopsThere()
	{
		DrainModel model = contract();
		model.apply(0, SpecWeapon.ACCURSED_SCEPTRE.drainRule(), 30);
		model.apply(5, SpecWeapon.ACCURSED_SCEPTRE.drainRule(), 30);

		YamaStats after = model.advanceTo(100);

		assertEquals(192, after.getDefence());
		assertEquals(213, after.getMagic());
		assertEquals(275, model.advanceTo(100_000).getMagic());
		assertEquals(0, after.getMagicDefenceBonusDrained());
	}

	@Test
	public void applyRestoresFirst()
	{
		DrainModel model = normal();
		model.apply(0, SpecWeapon.EMBERLIGHT.drainRule(), 40);

		assertEquals(158, model.apply(100, SpecWeapon.EMBERLIGHT.drainRule(), 40).getDefence());
	}
}
```

`src/test/java/com/yamareviewer/domain/drain/SpecWeaponTest.java`:

```java
package com.yamareviewer.domain.drain;

import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.testing.TestIds;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SpecWeaponTest
{
	@Test
	public void resolvesWeaponsAndAnimationsThroughTheRegistry()
	{
		assertEquals(Optional.of(SpecWeapon.ELDER_MAUL), SpecWeapon.ofWeapon(TestIds.registry(), TestIds.id(Role.WEAPON_ELDER_MAUL)));
		assertEquals(Optional.of(SpecWeapon.PURGING_STAFF), SpecWeapon.ofAnimation(TestIds.registry(), TestIds.id(Role.SPEC_PURGING_STAFF)));
		assertEquals(Optional.empty(), SpecWeapon.ofWeapon(TestIds.registry(), 4151));
		assertEquals(Optional.empty(), SpecWeapon.ofAnimation(TestIds.registry(), 1));
	}

	@Test
	public void everyWeaponHasARoleOfEachKindAndAName()
	{
		for (SpecWeapon weapon : SpecWeapon.values())
		{
			assertTrue(weapon.name(), weapon.weaponRole().name().startsWith("WEAPON_"));
			assertTrue(weapon.name(), weapon.specRole().name().startsWith("SPEC_"));
			assertFalse(weapon.name(), weapon.displayName().isEmpty());
		}
	}

	@Test
	public void meleeAndDrainFlags()
	{
		assertTrue(SpecWeapon.ELDER_MAUL.isMelee());
		assertTrue(SpecWeapon.SARADOMIN_GODSWORD.isMelee());
		assertFalse(SpecWeapon.ACCURSED_SCEPTRE.isMelee());
		assertFalse(SpecWeapon.SOULFLAME_HORN.isMelee());
		assertTrue(SpecWeapon.EYE_OF_AYAK.drains());
		assertFalse(SpecWeapon.SOULFLAME_HORN.drains());
		assertFalse(SpecWeapon.PURGING_STAFF.drains());
		assertFalse(SpecWeapon.SARADOMIN_GODSWORD.drains());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.drain.*'`
Expected: FAIL — `cannot find symbol` for `DrainModel`, `YamaStats`, `SpecWeapon`.

- [ ] **Step 3: Write `YamaStats`, `DrainRule` and the rules**

`src/main/java/com/yamareviewer/domain/drain/YamaStats.java`:

```java
package com.yamareviewer.domain.drain;

import lombok.Value;

/**
 * Yama's modelled stats (spec 6.9). Drains refer to the normal-form limits even under a contract,
 * and Defence never goes below the floor.
 */
@Value
public class YamaStats
{
	public static final int NORMAL_DEFENCE = 225;
	public static final int NORMAL_MAGIC = 250;
	public static final int DEFENCE_FLOOR = 145;

	int defence;
	int magic;
	/** Drained by Eye of Ayak specs; not a stat, so it never restores. */
	int magicDefenceBonusDrained;

	public YamaStats withDefence(int value)
	{
		return new YamaStats(Math.max(DEFENCE_FLOOR, value), magic, magicDefenceBonusDrained);
	}

	public YamaStats withMagic(int value)
	{
		return new YamaStats(defence, value, magicDefenceBonusDrained);
	}

	public YamaStats plusBonusDrained(int amount)
	{
		return new YamaStats(defence, magic, magicDefenceBonusDrained + amount);
	}
}
```

`src/main/java/com/yamareviewer/domain/drain/DrainRule.java`:

```java
package com.yamareviewer.domain.drain;

/** What one landed special attack does to Yama's stats (spec 6.9). */
public interface DrainRule
{
	/** The stats after a landed spec that dealt {@code damage}. */
	YamaStats apply(YamaStats stats, int damage);
}
```

`src/main/java/com/yamareviewer/domain/drain/ElderMaulDrain.java`:

```java
package com.yamareviewer.domain.drain;

/** Defence minus 35% of current Defence, rounded down. */
public final class ElderMaulDrain implements DrainRule
{
	@Override
	public YamaStats apply(YamaStats stats, int damage)
	{
		return stats.withDefence(stats.getDefence() - stats.getDefence() * 35 / 100);
	}
}
```

`src/main/java/com/yamareviewer/domain/drain/DragonWarhammerDrain.java`:

```java
package com.yamareviewer.domain.drain;

/** Defence minus 30% of current Defence, rounded down, only when the hit dealt damage. */
public final class DragonWarhammerDrain implements DrainRule
{
	@Override
	public YamaStats apply(YamaStats stats, int damage)
	{
		if (damage <= 0)
		{
			return stats;
		}
		return stats.withDefence(stats.getDefence() - stats.getDefence() * 30 / 100);
	}
}
```

`src/main/java/com/yamareviewer/domain/drain/BandosGodswordDrain.java`:

```java
package com.yamareviewer.domain.drain;

/** Defence minus the damage dealt. */
public final class BandosGodswordDrain implements DrainRule
{
	@Override
	public YamaStats apply(YamaStats stats, int damage)
	{
		return stats.withDefence(stats.getDefence() - Math.max(0, damage));
	}
}
```

`src/main/java/com/yamareviewer/domain/drain/AccursedSceptreDrain.java`:

```java
package com.yamareviewer.domain.drain;

/**
 * Defence -34 down to 191 and Magic -38 down to 212: the limits are 15% below the normal-form stats,
 * so the drain does not stack in a normal fight and drains twice under a contract.
 */
public final class AccursedSceptreDrain implements DrainRule
{
	static final int DEFENCE_LIMIT = 191;
	static final int MAGIC_LIMIT = 212;
	static final int DEFENCE_DRAIN = 34;
	static final int MAGIC_DRAIN = 38;

	@Override
	public YamaStats apply(YamaStats stats, int damage)
	{
		YamaStats result = stats;
		if (result.getDefence() > DEFENCE_LIMIT)
		{
			result = result.withDefence(Math.max(DEFENCE_LIMIT, result.getDefence() - DEFENCE_DRAIN));
		}
		if (result.getMagic() > MAGIC_LIMIT)
		{
			result = result.withMagic(Math.max(MAGIC_LIMIT, result.getMagic() - MAGIC_DRAIN));
		}
		return result;
	}
}
```

`src/main/java/com/yamareviewer/domain/drain/EmberlightDrain.java`:

```java
package com.yamareviewer.domain.drain;

/** Defence minus 34 (15% of the normal-form Defence plus one); stacks down to the floor. */
public final class EmberlightDrain implements DrainRule
{
	static final int DRAIN = 34;

	@Override
	public YamaStats apply(YamaStats stats, int damage)
	{
		return stats.withDefence(stats.getDefence() - DRAIN);
	}
}
```

`src/main/java/com/yamareviewer/domain/drain/EyeOfAyakDrain.java`:

```java
package com.yamareviewer.domain.drain;

/** Adds the damage dealt to the magic defence bonus drained; not a stat, never restores. */
public final class EyeOfAyakDrain implements DrainRule
{
	@Override
	public YamaStats apply(YamaStats stats, int damage)
	{
		return stats.plusBonusDrained(Math.max(0, damage));
	}
}
```

`src/main/java/com/yamareviewer/domain/drain/NoDrain.java`:

```java
package com.yamareviewer.domain.drain;

/** Soulflame horn, purging staff and Saradomin godsword drain nothing. */
public final class NoDrain implements DrainRule
{
	@Override
	public YamaStats apply(YamaStats stats, int damage)
	{
		return stats;
	}
}
```

- [ ] **Step 4: Write `SpecWeapon` and `DrainModel`**

`src/main/java/com/yamareviewer/domain/drain/SpecWeapon.java`:

```java
package com.yamareviewer.domain.drain;

import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import java.util.Optional;

/** Every special-attack weapon the review knows: its item role, its animation role and its drain. */
public enum SpecWeapon
{
	EMBERLIGHT(Role.WEAPON_EMBERLIGHT, Role.SPEC_EMBERLIGHT, "Emberlight", true, new EmberlightDrain()),
	ELDER_MAUL(Role.WEAPON_ELDER_MAUL, Role.SPEC_ELDER_MAUL, "Elder maul", true, new ElderMaulDrain()),
	DRAGON_WARHAMMER(Role.WEAPON_DRAGON_WARHAMMER, Role.SPEC_DRAGON_WARHAMMER, "Dragon warhammer", true, new DragonWarhammerDrain()),
	BANDOS_GODSWORD(Role.WEAPON_BANDOS_GODSWORD, Role.SPEC_BANDOS_GODSWORD, "Bandos godsword", true, new BandosGodswordDrain()),
	ACCURSED_SCEPTRE(Role.WEAPON_ACCURSED_SCEPTRE, Role.SPEC_ACCURSED_SCEPTRE, "Accursed sceptre", false, new AccursedSceptreDrain()),
	EYE_OF_AYAK(Role.WEAPON_EYE_OF_AYAK, Role.SPEC_EYE_OF_AYAK, "Eye of Ayak", false, new EyeOfAyakDrain()),
	SOULFLAME_HORN(Role.WEAPON_SOULFLAME_HORN, Role.SPEC_SOULFLAME_HORN, "Soulflame horn", false, new NoDrain()),
	PURGING_STAFF(Role.WEAPON_PURGING_STAFF, Role.SPEC_PURGING_STAFF, "Purging staff", false, new NoDrain()),
	SARADOMIN_GODSWORD(Role.WEAPON_SARADOMIN_GODSWORD, Role.SPEC_SARADOMIN_GODSWORD, "Saradomin godsword", true, new NoDrain());

	private final Role weaponRole;
	private final Role specRole;
	private final String displayName;
	private final boolean melee;
	private final DrainRule drainRule;

	SpecWeapon(Role weaponRole, Role specRole, String displayName, boolean melee, DrainRule drainRule)
	{
		this.weaponRole = weaponRole;
		this.specRole = specRole;
		this.displayName = displayName;
		this.melee = melee;
		this.drainRule = drainRule;
	}

	public Role weaponRole()
	{
		return weaponRole;
	}

	public Role specRole()
	{
		return specRole;
	}

	public String displayName()
	{
		return displayName;
	}

	/** A melee spec can be assisted by a soulflame horn (spec 6.9). */
	public boolean isMelee()
	{
		return melee;
	}

	/** False for the weapons whose spec drains nothing. */
	public boolean drains()
	{
		return !(drainRule instanceof NoDrain);
	}

	public DrainRule drainRule()
	{
		return drainRule;
	}

	public static Optional<SpecWeapon> ofWeapon(IdRegistry ids, int itemId)
	{
		for (SpecWeapon weapon : values())
		{
			if (ids.is(weapon.weaponRole, itemId))
			{
				return Optional.of(weapon);
			}
		}
		return Optional.empty();
	}

	public static Optional<SpecWeapon> ofAnimation(IdRegistry ids, int animationId)
	{
		for (SpecWeapon weapon : values())
		{
			if (ids.is(weapon.specRole, animationId))
			{
				return Optional.of(weapon);
			}
		}
		return Optional.empty();
	}
}
```

`src/main/java/com/yamareviewer/domain/drain/DrainModel.java`:

```java
package com.yamareviewer.domain.drain;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.Rules;

/**
 * Applies landed specs in tick order to Yama's modelled stats (spec 6.9). Every {@code statRestoreTicks}
 * ticks after fight start, drained Defence and Magic each restore one level towards the fight's base.
 * Not thread-safe; one instance per review.
 */
public final class DrainModel
{
	private final YamaStats base;
	private final int restoreTicks;
	private YamaStats stats;
	private int restoredUpTo;

	public DrainModel(YamaStats base, int statRestoreTicks)
	{
		this.base = base;
		this.restoreTicks = Math.max(1, statRestoreTicks);
		this.stats = base;
	}

	/** Base 225/250, or 247/275 under a contract, from the contract rules. */
	public static DrainModel forFight(ContractRules contract, Rules rules)
	{
		return new DrainModel(new YamaStats(contract.getBaseDefence(), contract.getBaseMagic(), 0), rules.getStatRestoreTicks());
	}

	public YamaStats base()
	{
		return base;
	}

	public YamaStats stats()
	{
		return stats;
	}

	/** Applies every restore step due up to and including {@code tick}; ticks already passed are ignored. */
	public YamaStats advanceTo(int tick)
	{
		int due = tick / restoreTicks;
		int done = restoredUpTo / restoreTicks;
		for (int step = done; step < due; step++)
		{
			restoreOneLevel();
		}
		restoredUpTo = Math.max(restoredUpTo, tick);
		return stats;
	}

	/** Restores up to {@code tick}, then applies the rule for a landed spec that dealt {@code damage}. */
	public YamaStats apply(int tick, DrainRule rule, int damage)
	{
		advanceTo(tick);
		stats = rule.apply(stats, damage);
		return stats;
	}

	private void restoreOneLevel()
	{
		int defence = stats.getDefence() < base.getDefence() ? stats.getDefence() + 1 : stats.getDefence();
		int magic = stats.getMagic() < base.getMagic() ? stats.getMagic() + 1 : stats.getMagic();
		stats = new YamaStats(defence, magic, stats.getMagicDefenceBonusDrained());
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.drain.*'`
Expected: PASS (16 tests). `restoreGoesTowardsTheFightsBaseAndStopsThere` expects Defence 192 because the second sceptre spec took it to 191 and one restore step adds one level; `applyRestoresFirst` expects 158 because tick 100 restores 191 to 192 before the second Emberlight takes 34.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/drain src/test/java/com/yamareviewer/domain/drain
git commit -m "feat: add the drain model with one rule per spec weapon"
```

---

### Task 2: Result types and section keys

**Files:**
- Create in `src/main/java/com/yamareviewer/domain/review/`: `Attack.java`, `AttackTimeline.java`, `GlyphCount.java`, `PrayerOutcome.java`, `AttackResult.java`, `PrayerReview.java`, `Opener.java`, `TickLogEntry.java`, `TickLog.java`, `CrashLine.java`, `CrashSummary.java`, `WaveHit.java`, `WaveSummary.java`, `SpecOutcome.java`, `SpecResult.java`, `HornUse.java`, `DrainStep.java`, `SpecSummary.java`
- Modify: `src/main/java/com/yamareviewer/domain/projection/Sections.java` (add eight keys)
- Test: `src/test/java/com/yamareviewer/domain/review/P3ResultTypesTest.java`, `src/test/java/com/yamareviewer/domain/projection/SectionsTest.java`

**Interfaces:**
- Consumes: `Actor`, `Position` (Part 1), `Phase`, `Style` (Part 2), `SectionKey` (Part 2), `SpecWeapon`, `YamaStats` (Task 1).
- Produces: the value types below, exactly as written (later tasks and Part 4 construct them by constructor), and `Sections.GLYPHS` ("glyphs"), `ATTACKS` ("attacks"), `CRASHES` ("crashes"), `WAVES` ("waves"), `SPECS` ("specs"), `PRAYER_REVIEW` ("prayer-review"), `OPENER` ("opener"), `TICK_LOG` ("tick-log").

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/review/P3ResultTypesTest.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.drain.SpecWeapon;
import com.yamareviewer.domain.drain.YamaStats;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class P3ResultTypesTest
{
	private static Attack attack(int tick, Style style, Actor target)
	{
		return new Attack(tick, Phase.P3, style, target, tick + 2, 2);
	}

	@Test
	public void expectedStyleIsTheOppositeOfThePreviousAttackWhoeverItTargeted()
	{
		AttackTimeline timeline = new AttackTimeline(List.of(
			attack(40, Style.MAGIC, Actor.SELF),
			attack(47, Style.RANGED, Actor.PARTNER),
			attack(54, null, Actor.SELF),
			attack(61, Style.MAGIC, Actor.SELF)));

		assertEquals(Optional.empty(), timeline.expectedStyle(0));
		assertEquals(Optional.of(Style.RANGED), timeline.expectedStyle(1));
		assertEquals(Optional.of(Style.MAGIC), timeline.expectedStyle(2));
		assertEquals(Optional.empty(), timeline.expectedStyle(3));
		assertEquals(3, timeline.inPhase(Phase.P3).size() - 1);
	}

	@Test
	public void glyphMajorityGivesTheExpectedOpener()
	{
		assertEquals(Optional.of(Style.MAGIC), new GlyphCount(3, 1).expectedOpener());
		assertEquals(Optional.of(Style.RANGED), new GlyphCount(1, 2).expectedOpener());
		assertEquals(Optional.empty(), new GlyphCount(2, 2).expectedOpener());
	}

	@Test
	public void mistakesAreEverythingButBlockedAndDisabled()
	{
		assertFalse(PrayerOutcome.BLOCKED.isMistake());
		assertFalse(PrayerOutcome.PRAYER_DISABLED.isMistake());
		assertTrue(PrayerOutcome.TOO_EARLY.isMistake());
		assertTrue(PrayerOutcome.LATE.isMistake());
		assertTrue(PrayerOutcome.LOST_ALTERNATION.isMistake());
		assertTrue(PrayerOutcome.NO_PRAYER.isMistake());
	}

	@Test
	public void prayerReviewCountsAndAccuracy()
	{
		PrayerReview review = new PrayerReview(List.of(
			new AttackResult(attack(40, Style.MAGIC, Actor.SELF), 40, PrayerOutcome.BLOCKED, null),
			new AttackResult(attack(47, Style.RANGED, Actor.SELF), 47, PrayerOutcome.TOO_EARLY, 3),
			new AttackResult(attack(54, Style.MAGIC, Actor.SELF), 54, PrayerOutcome.PRAYER_DISABLED, null),
			new AttackResult(attack(61, Style.RANGED, Actor.SELF), 61, PrayerOutcome.BLOCKED, 5)),
			1, "wrong prayer", List.of());

		assertEquals(3, review.scored());
		assertEquals(2, review.blocked());
		assertEquals(1, review.mistakes());
		assertEquals(1, review.count(PrayerOutcome.TOO_EARLY));
		assertEquals(Optional.of(2.0 / 3.0), review.accuracy());
		assertEquals("wrong prayer", review.label(PrayerOutcome.LOST_ALTERNATION));
		assertEquals("too early", review.label(PrayerOutcome.TOO_EARLY));
		assertEquals(Optional.empty(), new PrayerReview(List.of(), 0, "lost alternation", List.of()).accuracy());
	}

	@Test
	public void tickLogShareOfGapsOnTheCycle()
	{
		TickLog log = new TickLog(List.of(
			new TickLogEntry(40, Style.MAGIC, Actor.SELF, null, List.of()),
			new TickLogEntry(47, Style.RANGED, Actor.SELF, 7, List.of()),
			new TickLogEntry(56, Style.MAGIC, Actor.SELF, 9, List.of("crash line at 50")),
			new TickLogEntry(63, Style.RANGED, Actor.SELF, 7, List.of())), 7);

		assertEquals(3, log.gaps());
		assertEquals(2, log.gapsOnCycle());
		assertEquals(Optional.of(2.0 / 3.0), log.cycleShare());
		assertEquals(Optional.empty(), new TickLog(List.of(), 7).cycleShare());
	}

	@Test
	public void crashSummaryPerPlayer()
	{
		Position centre = new Position(3200, 3200, 0);
		CrashSummary summary = new CrashSummary(List.of(
			new CrashLine(50, Phase.P3, Actor.SELF, 1, 1, centre, false, 0),
			new CrashLine(54, Phase.P3, Actor.SELF, 1, 2, centre, true, 12),
			new CrashLine(58, Phase.P3, Actor.SELF, 1, 3, centre, false, 0),
			new CrashLine(50, Phase.P3, Actor.PARTNER, 1, 1, centre, true, 8),
			new CrashLine(90, Phase.P3, Actor.SELF, 2, 1, centre, false, 0)));

		assertEquals(4, summary.total(Actor.SELF));
		assertEquals(3, summary.dodged(Actor.SELF));
		assertEquals(12, summary.damage(Actor.SELF));
		assertEquals(1, summary.total(Actor.PARTNER));
		assertEquals(0, summary.dodged(Actor.PARTNER));
		assertEquals(2, summary.sets());
	}

	@Test
	public void waveSummaryPerPlayer()
	{
		WaveSummary summary = new WaveSummary(List.of(
			new WaveHit(60, Phase.P3, Actor.SELF, true, 15, true),
			new WaveHit(64, Phase.P3, Actor.SELF, false, 0, false),
			new WaveHit(60, Phase.P3, Actor.PARTNER, false, 0, false)));

		assertEquals(2, summary.total(Actor.SELF));
		assertEquals(1, summary.dodged(Actor.SELF));
		assertEquals(15, summary.damage(Actor.SELF));
		assertEquals(1, summary.prayersDisabled(Actor.SELF));
		assertEquals(1, summary.dodged(Actor.PARTNER));
	}

	@Test
	public void specSummaryTallies()
	{
		SpecResult emberlight = new SpecResult(10, Phase.P1, Actor.SELF, SpecWeapon.EMBERLIGHT, true, Actor.YAMA, SpecOutcome.LANDED, 41, 25, null, null);
		SpecResult missed = new SpecResult(14, Phase.P1, Actor.SELF, SpecWeapon.EMBERLIGHT, true, Actor.YAMA, SpecOutcome.MISSED, 0, 25, null, null);
		SpecResult other = new SpecResult(20, Phase.P1, Actor.SELF, null, false, Actor.YAMA, SpecOutcome.LANDED, 30, 50, null, null);
		SpecResult partner = new SpecResult(30, Phase.P1, Actor.PARTNER, SpecWeapon.ELDER_MAUL, true, Actor.YAMA, SpecOutcome.UNKNOWN, null, 0, null, null);
		SpecSummary summary = new SpecSummary(List.of(emberlight, missed, other, partner), List.of(), List.of(),
			new YamaStats(225, 250, 0), new YamaStats(192, 250, 0), 191, Phase.P1);

		assertEquals(2, summary.used(SpecWeapon.EMBERLIGHT));
		assertEquals(1, summary.landed(SpecWeapon.EMBERLIGHT));
		assertEquals(List.of(SpecWeapon.EMBERLIGHT, SpecWeapon.ELDER_MAUL), summary.weaponsUsed());
		assertEquals(3, summary.ownSpecs().size());
		assertEquals(50, summary.ownEnergyUsedOnDrains());
		assertEquals(34, summary.defenceDrained());
		assertEquals(80, summary.maxDefenceDrain());
		assertEquals(Optional.of(2.0 / 3.0), summary.landedShare());
		assertEquals("Other weapon", other.weaponName());
		assertTrue(other.isOtherWeapon());
		assertTrue(new HornUse(5, Actor.SELF, null).isUnused());
	}
}
```

`src/test/java/com/yamareviewer/domain/projection/SectionsTest.java`:

```java
package com.yamareviewer.domain.projection;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class SectionsTest
{
	@Test
	public void part3KeysHaveStableNames()
	{
		assertEquals("glyphs", Sections.GLYPHS.name());
		assertEquals("attacks", Sections.ATTACKS.name());
		assertEquals("crashes", Sections.CRASHES.name());
		assertEquals("waves", Sections.WAVES.name());
		assertEquals("specs", Sections.SPECS.name());
		assertEquals("prayer-review", Sections.PRAYER_REVIEW.name());
		assertEquals("opener", Sections.OPENER.name());
		assertEquals("tick-log", Sections.TICK_LOG.name());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.review.P3ResultTypesTest' --tests 'com.yamareviewer.domain.projection.SectionsTest'`
Expected: FAIL — `cannot find symbol` for `Attack`, `AttackTimeline`, `Sections.GLYPHS`.

- [ ] **Step 3: Write the attack, prayer, opener and tick-log types**

`Attack.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import lombok.Value;

/** One standard attack of Yama (spec 6.4). */
@Value
public class Attack
{
	int castTick;
	/** Null when the phases are unknown. */
	Phase phase;
	/** Null when neither a cast nor an impact graphic identified the style. */
	Style style;
	/** The player the attack was aimed at; null when unknown. */
	Actor target;
	/** Tick of the landing hitsplat on the target; null when none was seen. */
	Integer landingTick;
	/** Amount of the landing hitsplat (0 for a blocked hit); null when none was seen. */
	Integer damage;
}
```

`AttackTimeline.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.Value;

/** Every standard attack of the fight in cast order. */
@Value
public class AttackTimeline
{
	List<Attack> attacks;

	public List<Attack> inPhase(Phase phase)
	{
		return attacks.stream().filter(attack -> attack.getPhase() == phase).collect(Collectors.toList());
	}

	/** Alternation is global: the opposite of the previous attack's style, whoever it targeted (spec 6.4). */
	public Optional<Style> expectedStyle(int index)
	{
		if (index <= 0 || index >= attacks.size())
		{
			return Optional.empty();
		}
		Style previous = attacks.get(index - 1).getStyle();
		return previous == null ? Optional.empty() : Optional.of(opposite(previous));
	}

	public static Style opposite(Style style)
	{
		return style == Style.MAGIC ? Style.RANGED : Style.MAGIC;
	}
}
```

`GlyphCount.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.model.Style;
import java.util.Optional;
import lombok.Value;

/** Glyphs conjured in P2: the majority colour sets the expected P3 opener (spec 6.5). */
@Value
public class GlyphCount
{
	int fire;
	int shadow;

	/** Fire majority: magic opener; shadow majority: ranged; tie: no expectation. */
	public Optional<Style> expectedOpener()
	{
		if (fire > shadow)
		{
			return Optional.of(Style.MAGIC);
		}
		if (shadow > fire)
		{
			return Optional.of(Style.RANGED);
		}
		return Optional.empty();
	}
}
```

`PrayerOutcome.java`:

```java
package com.yamareviewer.domain.review;

/** The outcome of one P3 attack aimed at you, in the order the rules of spec 6.5 are tried. */
public enum PrayerOutcome
{
	PRAYER_DISABLED,
	BLOCKED,
	TOO_EARLY,
	LATE,
	LOST_ALTERNATION,
	NO_PRAYER;

	public boolean isMistake()
	{
		return this != PRAYER_DISABLED && this != BLOCKED;
	}
}
```

`AttackResult.java`:

```java
package com.yamareviewer.domain.review;

import lombok.Value;

@Value
public class AttackResult
{
	Attack attack;
	/** The prayer-check tick L of spec 6.5. */
	int checkTick;
	PrayerOutcome outcome;
	/** Ticks from the previous attack's check tick to the first tick the correct prayer was active; null when not computed. */
	Integer switchTicks;
}
```

`PrayerReview.java`:

```java
package com.yamareviewer.domain.review;

import java.util.List;
import java.util.Optional;
import lombok.Value;

/** The P3 prayer review: every P3 attack aimed at you with its outcome (spec 6.5). */
@Value
public class PrayerReview
{
	List<AttackResult> results;
	/** P3 attacks aimed at you whose style could not be determined; they are not scored. */
	int unscored;
	/** "lost alternation", or "wrong prayer" under Shard Acquisition. */
	String lostAlternationLabel;
	/** Contract notes shown above the timeline, for example the Divine Severance auto-off note. */
	List<String> notes;

	public int count(PrayerOutcome outcome)
	{
		int count = 0;
		for (AttackResult result : results)
		{
			if (result.getOutcome() == outcome)
			{
				count++;
			}
		}
		return count;
	}

	/** Attacks that got an outcome other than PRAYER_DISABLED. */
	public int scored()
	{
		return results.size() - count(PrayerOutcome.PRAYER_DISABLED);
	}

	public int blocked()
	{
		return count(PrayerOutcome.BLOCKED);
	}

	public int mistakes()
	{
		int mistakes = 0;
		for (AttackResult result : results)
		{
			if (result.getOutcome().isMistake())
			{
				mistakes++;
			}
		}
		return mistakes;
	}

	/** Blocked out of scored; empty when nothing was scored. */
	public Optional<Double> accuracy()
	{
		return scored() == 0 ? Optional.empty() : Optional.of((double) blocked() / scored());
	}

	public String label(PrayerOutcome outcome)
	{
		switch (outcome)
		{
			case PRAYER_DISABLED:
				return "prayers disabled";
			case BLOCKED:
				return "blocked";
			case TOO_EARLY:
				return "too early";
			case LATE:
				return "late";
			case LOST_ALTERNATION:
				return lostAlternationLabel;
			default:
				return "no prayer";
		}
	}
}
```

`Opener.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.model.Style;
import lombok.Value;

/** The P3 opener (spec 6.5): the glyph majority against Yama's first P3 attack, and your first outcome. */
@Value
public class Opener
{
	GlyphCount glyphs;
	/** Null on a glyph tie. */
	Style expectedStyle;
	/** Yama's first P3 attack on anyone; null when there was none. */
	Attack firstAttack;
	/** Null when there is no expectation, no first attack, or its style is unknown. */
	Boolean asExpected;
	/** The outcome of your first P3 attack; null when none was scored. */
	AttackResult firstOwnResult;
}
```

`TickLogEntry.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Style;
import java.util.List;
import lombok.Value;

@Value
public class TickLogEntry
{
	int tick;
	Style style;
	Actor target;
	/** Ticks since the previous P3 attack; null for the first. */
	Integer gap;
	/** Crash, wave, flare-summon and melee events between the two attacks when the gap is off the cycle. */
	List<String> annotations;
}
```

`TickLog.java`:

```java
package com.yamareviewer.domain.review;

import java.util.List;
import java.util.Optional;
import lombok.Value;

/** Every P3 attack with its tick and gap. Shows observed gaps only; never advice (spec 2, rule 4). */
@Value
public class TickLog
{
	List<TickLogEntry> entries;
	/** The p3AttackCycle the gaps are compared with. */
	int cycle;

	public int gaps()
	{
		return Math.max(0, entries.size() - 1);
	}

	public int gapsOnCycle()
	{
		int count = 0;
		for (TickLogEntry entry : entries)
		{
			if (entry.getGap() != null && entry.getGap() == cycle)
			{
				count++;
			}
		}
		return count;
	}

	public Optional<Double> cycleShare()
	{
		return gaps() == 0 ? Optional.empty() : Optional.of((double) gapsOnCycle() / gaps());
	}
}
```

- [ ] **Step 4: Write the crash, wave and spec types**

`CrashLine.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.model.Phase;
import lombok.Value;

/** One line of three Shadow Crash fireballs (spec 6.6). */
@Value
public class CrashLine
{
	/** Tick the fireballs landed. */
	int tick;
	/** Null when the phases are unknown. */
	Phase phase;
	/** The player nearest the centre fireball. */
	Actor player;
	/** 1-based set number (lines within crashSetGap ticks of each other, across players). */
	int set;
	/** 1-based index of this line among the player's lines of the set. */
	int indexInSet;
	Position centre;
	boolean hit;
	int damage;
}
```

`CrashSummary.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;

@Value
public class CrashSummary
{
	List<CrashLine> lines;

	public List<CrashLine> forPlayer(Actor player)
	{
		return lines.stream().filter(line -> line.getPlayer().equals(player)).collect(Collectors.toList());
	}

	public int total(Actor player)
	{
		return forPlayer(player).size();
	}

	public int dodged(Actor player)
	{
		return (int) forPlayer(player).stream().filter(line -> !line.isHit()).count();
	}

	public int damage(Actor player)
	{
		return forPlayer(player).stream().mapToInt(CrashLine::getDamage).sum();
	}

	public int sets()
	{
		return lines.stream().mapToInt(CrashLine::getSet).max().orElse(0);
	}
}
```

`WaveHit.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Phase;
import lombok.Value;

/** One Shadow Wave reaching one player (spec 6.7). */
@Value
public class WaveHit
{
	/** Tick the wave reached the player. */
	int tick;
	/** Null when the phases are unknown. */
	Phase phase;
	Actor player;
	boolean hit;
	int damage;
	/** Only ever true for you: the game said your protection prayers were disabled. */
	boolean prayersDisabled;
}
```

`WaveSummary.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;

@Value
public class WaveSummary
{
	List<WaveHit> waves;

	public List<WaveHit> forPlayer(Actor player)
	{
		return waves.stream().filter(wave -> wave.getPlayer().equals(player)).collect(Collectors.toList());
	}

	public int total(Actor player)
	{
		return forPlayer(player).size();
	}

	public int dodged(Actor player)
	{
		return (int) forPlayer(player).stream().filter(wave -> !wave.isHit()).count();
	}

	public int damage(Actor player)
	{
		return forPlayer(player).stream().mapToInt(WaveHit::getDamage).sum();
	}

	public int prayersDisabled(Actor player)
	{
		return (int) forPlayer(player).stream().filter(WaveHit::isPrayersDisabled).count();
	}
}
```

`SpecOutcome.java`:

```java
package com.yamareviewer.domain.review;

public enum SpecOutcome
{
	LANDED,
	MISSED,
	UNKNOWN
}
```

`SpecResult.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.drain.SpecWeapon;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Phase;
import lombok.Value;

/** One special attack (spec 6.9). */
@Value
public class SpecResult
{
	int tick;
	/** Null when the phases are unknown. */
	Phase phase;
	/** SELF or PARTNER. */
	Actor user;
	/** Null for an OTHER_WEAPON spec: a spec-energy drop with a weapon the review doesn't know. */
	SpecWeapon weapon;
	/** False when the drop had no matching SPEC_* animation on the same or the previous tick. */
	boolean animationSeen;
	/** Yama, or the flare for a purging staff spec; null when no result hitsplat was found. */
	Actor target;
	SpecOutcome outcome;
	/** The result hitsplat's amount; null when UNKNOWN. */
	Integer damage;
	/** Percent of spec energy the drop consumed; 0 for partner specs and for a refunded purging staff spec. */
	int energyUsed;
	/** Saradomin godsword only: hitpoints restored by the hit; null otherwise. */
	Integer hpRestored;
	/** Saradomin godsword only: prayer points restored by the hit; null otherwise. */
	Integer prayerRestored;

	public boolean isOwn()
	{
		return Actor.SELF.equals(user);
	}

	public boolean isOtherWeapon()
	{
		return weapon == null;
	}

	public String weaponName()
	{
		return weapon == null ? "Other weapon" : weapon.displayName();
	}
}
```

`HornUse.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import lombok.Value;

/** A soulflame horn blown, and the melee spec within 10 ticks it assisted (spec 6.9). */
@Value
public class HornUse
{
	int tick;
	Actor user;
	/** The first melee spec by anyone within 10 ticks; null when the horn was unused. */
	SpecResult assistedSpec;

	public boolean isUnused()
	{
		return assistedSpec == null;
	}
}
```

`DrainStep.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.drain.YamaStats;
import com.yamareviewer.domain.model.Phase;
import lombok.Value;

/** One change of the modelled stats: a landed spec's drain, or a stat restore step. */
@Value
public class DrainStep
{
	int tick;
	/** Null when the phases are unknown. */
	Phase phase;
	/** The weapon's display name, or "Stat restore". */
	String cause;
	YamaStats before;
	YamaStats after;
}
```

`SpecSummary.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.drain.SpecWeapon;
import com.yamareviewer.domain.drain.YamaStats;
import com.yamareviewer.domain.model.Phase;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.Value;

/** Special attacks, horns, and the modelled drains of one kill (spec 6.9). */
@Value
public class SpecSummary
{
	List<SpecResult> specs;
	List<HornUse> horns;
	List<DrainStep> drains;
	/** The fight's base stats. */
	YamaStats base;
	/** The modelled stats at the end of the fight, after restores. */
	YamaStats finalStats;
	/** The lowest modelled Defence of the fight. */
	int lowestDefence;
	/** The phase in which the lowest Defence was first reached; null when nothing was drained. */
	Phase lowestDefencePhase;

	public List<SpecResult> ownSpecs()
	{
		return specs.stream().filter(SpecResult::isOwn).collect(Collectors.toList());
	}

	/** In order of first use, own and partner specs alike; OTHER_WEAPON specs are not weapons. */
	public List<SpecWeapon> weaponsUsed()
	{
		List<SpecWeapon> weapons = new ArrayList<>();
		for (SpecResult spec : specs)
		{
			if (spec.getWeapon() != null && !weapons.contains(spec.getWeapon()))
			{
				weapons.add(spec.getWeapon());
			}
		}
		return weapons;
	}

	public int used(SpecWeapon weapon)
	{
		return (int) specs.stream().filter(spec -> spec.getWeapon() == weapon).count();
	}

	public int landed(SpecWeapon weapon)
	{
		return (int) specs.stream().filter(spec -> spec.getWeapon() == weapon && spec.getOutcome() == SpecOutcome.LANDED).count();
	}

	public int otherWeaponSpecs()
	{
		return (int) specs.stream().filter(SpecResult::isOtherWeapon).count();
	}

	/** Spec energy you spent on weapons that drain, for "energy per Defence point drained". */
	public int ownEnergyUsedOnDrains()
	{
		return ownSpecs().stream()
			.filter(spec -> spec.getWeapon() != null && spec.getWeapon().drains())
			.mapToInt(SpecResult::getEnergyUsed)
			.sum();
	}

	public int defenceDrained()
	{
		return base.getDefence() - lowestDefence;
	}

	public int maxDefenceDrain()
	{
		return base.getDefence() - YamaStats.DEFENCE_FLOOR;
	}

	/** Landed out of your specs with a known outcome; empty when there are none. */
	public Optional<Double> landedShare()
	{
		List<SpecResult> known = ownSpecs().stream().filter(spec -> spec.getOutcome() != SpecOutcome.UNKNOWN).collect(Collectors.toList());
		if (known.isEmpty())
		{
			return Optional.empty();
		}
		long landed = known.stream().filter(spec -> spec.getOutcome() == SpecOutcome.LANDED).count();
		return Optional.of((double) landed / known.size());
	}
}
```

- [ ] **Step 5: Add the section keys**

In `src/main/java/com/yamareviewer/domain/projection/Sections.java`, add these imports and constants after Part 2's `DEATH_RECAP`:

```java
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.CrashSummary;
import com.yamareviewer.domain.review.GlyphCount;
import com.yamareviewer.domain.review.Opener;
import com.yamareviewer.domain.review.PrayerReview;
import com.yamareviewer.domain.review.SpecSummary;
import com.yamareviewer.domain.review.TickLog;
import com.yamareviewer.domain.review.WaveSummary;
```

```java
	/** Intermediate (spec 6.1): feeds Opener, not shown on its own. */
	public static final SectionKey<GlyphCount> GLYPHS = SectionKey.of("glyphs");
	/** Intermediate (spec 6.1): feeds PrayerReview, Opener, TickLog, Crashes, Waves and DamageAttribution. */
	public static final SectionKey<AttackTimeline> ATTACKS = SectionKey.of("attacks");
	public static final SectionKey<CrashSummary> CRASHES = SectionKey.of("crashes");
	public static final SectionKey<WaveSummary> WAVES = SectionKey.of("waves");
	public static final SectionKey<SpecSummary> SPECS = SectionKey.of("specs");
	public static final SectionKey<PrayerReview> PRAYER_REVIEW = SectionKey.of("prayer-review");
	public static final SectionKey<Opener> OPENER = SectionKey.of("opener");
	public static final SectionKey<TickLog> TICK_LOG = SectionKey.of("tick-log");
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.review.P3ResultTypesTest' --tests 'com.yamareviewer.domain.projection.SectionsTest'`
Expected: PASS (9 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/review src/main/java/com/yamareviewer/domain/projection/Sections.java src/test/java/com/yamareviewer/domain/review/P3ResultTypesTest.java src/test/java/com/yamareviewer/domain/projection/SectionsTest.java
git commit -m "feat: add the P3 review result types and their section keys"
```

---

### Task 3: Test support for P3 fights

**Files:**
- Modify: `src/test/java/com/yamareviewer/testing/KillLogBuilder.java` (add methods)
- Create: `src/test/java/com/yamareviewer/testing/Fights.java`, `src/test/java/com/yamareviewer/testing/TestContext.java`
- Test: `src/test/java/com/yamareviewer/testing/KillLogBuilderP3Test.java`

**Interfaces:**
- Consumes: the binding `KillLogBuilder` and `TestIds` API of Part 2 (`kill()`, `yamaAnimates`, `animates`, `graphicOn`, `groundGraphic`, `message`, `objectSpawns`, `npcSpawns`, `npcDespawns`, `ticks`, `tick()`, `TestIds.id`, `TestIds.text`), `PhaseTimes`/`PhaseSpan`, `ProjectionContext`, `ReviewSettings`, `Sections` (Part 2).
- Produces: `KillLogBuilder.yamaCasts(Style)`, `impactOn(Actor, Style)`, `crashLine(int centreX, int centreY)`, `crashImpactOn(Actor)`, `waveOn(Actor)`, `waveAt(int, int)`, `prayersDisabledMessage()`, `glyph(Role, int, int)`, `specAnimation(Actor, Role)`, `judgeSpawns()`, `judgeDespawns()`; `Fights.throughToP3(int fireGlyphs, int shadowGlyphs)`, `Fights.throughToP3()`, `Fights.P3_START = 40`, `Fights.phases(int endTick)`; `TestContext.empty()`, `withRules(Rules)`, `withPhases(int endTick)`, `withPhases(Rules, int endTick)`, `duo(int endTick)`, `under(Contract, int endTick)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/testing/KillLogBuilderP3Test.java`:

```java
package com.yamareviewer.testing;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.ObjectSpawnObserved;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.PhaseSpan;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class KillLogBuilderP3Test
{
	@Test
	public void yamaCastsEmitsTheAnimationAndTheCastGraphicAtTheCursor()
	{
		KillLog log = KillLogBuilder.kill().ticks(3).yamaCasts(Style.MAGIC).build();

		assertEquals(List.of(new AnimationObserved(3, Actor.YAMA, TestIds.id(Role.YAMA_STANDARD_ATTACK))), log.eventsOf(AnimationObserved.class));
		assertEquals(List.of(new GraphicObserved(3, Actor.YAMA, TestIds.id(Role.YAMA_CAST_MAGIC))), log.eventsOf(GraphicObserved.class));
	}

	@Test
	public void crashLineIsThreeFireballsInARow()
	{
		KillLog log = KillLogBuilder.kill().crashLine(3200, 3205).build();

		List<GroundGraphicObserved> fireballs = log.eventsOf(GroundGraphicObserved.class);
		assertEquals(3, fireballs.size());
		assertEquals(3199, fireballs.get(0).getPosition().getX());
		assertEquals(3200, fireballs.get(1).getPosition().getX());
		assertEquals(3201, fireballs.get(2).getPosition().getX());
		assertEquals(TestIds.id(Role.CRASH_FIREBALL), fireballs.get(1).getGraphicId());
	}

	@Test
	public void throughToP3LeavesTheCursorAtTheStartOfP3WithTheGlyphsInP2()
	{
		KillLogBuilder kill = Fights.throughToP3(2, 1);

		assertEquals(Fights.P3_START, kill.tick());
		KillLog log = kill.build();
		List<NpcSpawnObserved> spawns = log.eventsOf(NpcSpawnObserved.class);
		List<NpcDespawnObserved> despawns = log.eventsOf(NpcDespawnObserved.class);
		assertEquals(List.of(10, 30), List.of(spawns.get(0).getTick(), spawns.get(1).getTick()));
		assertEquals(List.of(20, 40), List.of(despawns.get(0).getTick(), despawns.get(1).getTick()));
		List<ObjectSpawnObserved> glyphs = log.eventsOf(ObjectSpawnObserved.class);
		assertEquals(3, glyphs.size());
		assertEquals(22, glyphs.get(0).getTick());
		assertEquals(TestIds.id(Role.GLYPH_SHADOW), glyphs.get(2).getObjectId());
	}

	@Test
	public void phasesMatchTheSkeleton()
	{
		assertEquals(Optional.of(new PhaseSpan(Phase.P3, 40, 100)), Fights.phases(100).span(Phase.P3));
		assertEquals(Optional.of(new PhaseSpan(Phase.P2, 20, 30)), Fights.phases(100).span(Phase.P2));
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.testing.KillLogBuilderP3Test'`
Expected: FAIL — `cannot find symbol` for `yamaCasts`, `crashLine`, `Fights`.

- [ ] **Step 3: Add the helper methods to `KillLogBuilder`**

Add these imports to `src/test/java/com/yamareviewer/testing/KillLogBuilder.java` if they are missing (`Actor` and `Role` are already imported by Part 2's code):

```java
import com.yamareviewer.domain.model.Style;
```

Add these methods to the class, before `endTick()`:

```java
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
```

- [ ] **Step 4: Write `Fights` and `TestContext`**

`src/test/java/com/yamareviewer/testing/Fights.java`:

```java
package com.yamareviewer.testing;

import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import java.util.List;

/**
 * A fight skeleton: P1 ticks 0-9, Judge 1 ticks 10-19 (Judge spawns at 10, despawns at 20), P2 ticks 20-29,
 * Judge 2 ticks 30-39, P3 from tick 40. Tests append P3 events from the cursor and end the kill.
 */
public final class Fights
{
	public static final int JUDGE_1_START = 10;
	public static final int P2_START = 20;
	public static final int GLYPH_TICK = 22;
	public static final int JUDGE_2_START = 30;
	public static final int P3_START = 40;

	private Fights()
	{
	}

	public static KillLogBuilder throughToP3()
	{
		return throughToP3(0, 0);
	}

	/** The glyphs are conjured (spawned) at GLYPH_TICK in P2. The cursor is left at P3_START. */
	public static KillLogBuilder throughToP3(int fireGlyphs, int shadowGlyphs)
	{
		KillLogBuilder kill = KillLogBuilder.kill();
		kill.ticks(JUDGE_1_START);
		kill.judgeSpawns();
		kill.ticks(P2_START - JUDGE_1_START);
		kill.judgeDespawns();
		kill.ticks(GLYPH_TICK - P2_START);
		for (int i = 0; i < fireGlyphs; i++)
		{
			kill.glyph(Role.GLYPH_FIRE, 3190 + i, 3210);
		}
		for (int i = 0; i < shadowGlyphs; i++)
		{
			kill.glyph(Role.GLYPH_SHADOW, 3210 + i, 3210);
		}
		kill.ticks(JUDGE_2_START - GLYPH_TICK);
		kill.judgeSpawns();
		kill.ticks(P3_START - JUDGE_2_START);
		kill.judgeDespawns();
		return kill;
	}

	/** The PhaseTimes of this skeleton with P3 ending at endTick, for contexts built by hand. */
	public static PhaseTimes phases(int endTick)
	{
		return new PhaseTimes(List.of(
			new PhaseSpan(Phase.P1, 0, JUDGE_1_START),
			new PhaseSpan(Phase.JUDGE_1, JUDGE_1_START, P2_START),
			new PhaseSpan(Phase.P2, P2_START, JUDGE_2_START),
			new PhaseSpan(Phase.JUDGE_2, JUDGE_2_START, P3_START),
			new PhaseSpan(Phase.P3, P3_START, endTick)), endTick);
	}
}
```

`src/test/java/com/yamareviewer/testing/TestContext.java`:

```java
package com.yamareviewer.testing;

import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.Section;

/** ProjectionContexts with the upstream sections a projection under test needs, put by hand. */
public final class TestContext
{
	private TestContext()
	{
	}

	public static ProjectionContext empty()
	{
		return withRules(Rules.DEFAULT);
	}

	public static ProjectionContext withRules(Rules rules)
	{
		return new ProjectionContext(TestIds.registry(), rules, ReviewSettings.DEFAULT);
	}

	/** Solo, no contract, the Fights skeleton with P3 ending at endTick. */
	public static ProjectionContext withPhases(int endTick)
	{
		return withPhases(Rules.DEFAULT, endTick);
	}

	public static ProjectionContext withPhases(Rules rules, int endTick)
	{
		ProjectionContext context = withRules(rules);
		context.put(Sections.PHASES, Section.ok(Fights.phases(endTick)));
		context.put(Sections.CONTRACT, Section.ok(Contract.NONE));
		context.put(Sections.MODE, Section.ok(Mode.SOLO));
		return context;
	}

	public static ProjectionContext duo(int endTick)
	{
		ProjectionContext context = withPhases(endTick);
		context.put(Sections.MODE, Section.ok(Mode.DUO_HOST));
		return context;
	}

	public static ProjectionContext under(Contract contract, int endTick)
	{
		ProjectionContext context = withPhases(endTick);
		context.put(Sections.CONTRACT, Section.ok(contract));
		return context;
	}
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.testing.KillLogBuilderP3Test'`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/yamareviewer/testing
git commit -m "test: add P3 helpers to the kill log DSL and a fight skeleton"
```

---

### Task 4: KillReview fields and the assembler

**Files:**
- Modify: `src/main/java/com/yamareviewer/domain/review/KillReview.java` (six fields, getters, `allSections()`)
- Modify: `src/main/java/com/yamareviewer/domain/projection/KillReviewAssembler.java` (six builder lines)
- Test: `src/test/java/com/yamareviewer/domain/review/KillReviewP3Test.java`, `src/test/java/com/yamareviewer/domain/projection/KillReviewAssemblerP3Test.java`

**Interfaces:**
- Consumes: `KillReview` builder (Part 2), `KillReviewAssembler.assemble(KillLog, ProjectionContext)`, `Section`, `HiddenReason`, `Sections` (Task 2), `TestContext` (Task 3).
- Produces: `KillReview.getPrayerReview()`, `getOpener()`, `getTickLog()`, `getCrashes()`, `getWaves()`, `getSpecs()` (never null: `Section.hidden(NOT_APPLICABLE)` for a review stored before the field existed) and builder methods of the same names; `allSections()` includes them in display order.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/review/KillReviewP3Test.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class KillReviewP3Test
{
	private static KillReview.KillReviewBuilder part2Only()
	{
		return KillReview.builder()
			.reviewSchemaVersion(KillReview.SCHEMA_VERSION)
			.killId("k")
			.startEpochMs(1L)
			.endEpochMs(2L)
			.endReason(EndReason.YAMA_DIED)
			.mode(Mode.SOLO)
			.contract(Contract.NONE)
			.status(ReviewStatus.COMPLETE)
			.skippedEvents(0)
			.phases(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.damage(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.flares(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.supplies(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.deathRecap(Section.hidden(HiddenReason.NOT_APPLICABLE));
	}

	@Test
	public void missingPart3FieldsReadAsNotApplicable()
	{
		KillReview review = part2Only().build();

		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getPrayerReview().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getOpener().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getTickLog().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getCrashes().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getWaves().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getSpecs().hiddenReason());
	}

	@Test
	public void part3SectionsCountTowardsTheStatus()
	{
		KillReview review = part2Only().specs(Section.hidden(HiddenReason.ERROR)).build().withRecomputedStatus();

		assertEquals(ReviewStatus.INCOMPLETE, review.getStatus());
		assertEquals(11, review.allSections().size());
	}

	@Test
	public void allSectionsAreInDisplayOrder()
	{
		Section<TickLog> tickLog = Section.ok(new TickLog(List.of(), 7));
		KillReview review = part2Only().tickLog(tickLog).build();

		List<Section<?>> sections = review.allSections();

		assertTrue(sections.contains(tickLog));
		assertEquals(sections.size() - 2, sections.indexOf(tickLog));
	}
}
```

`src/test/java/com/yamareviewer/domain/projection/KillReviewAssemblerP3Test.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.TickLog;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class KillReviewAssemblerP3Test
{
	private final KillLog log = KillLogBuilder.kill().ticks(5).end(EndReason.YAMA_DIED);

	@Test
	public void copiesThePart3SectionsFromTheContext()
	{
		ProjectionContext context = TestContext.withPhases(5);
		Section<TickLog> tickLog = Section.ok(new TickLog(List.of(), 7));
		context.put(Sections.TICK_LOG, tickLog);
		context.put(Sections.SPECS, Section.hidden(HiddenReason.IDS_NOT_CAPTURED));

		KillReview review = KillReviewAssembler.assemble(log, context);

		assertEquals(tickLog, review.getTickLog());
		assertEquals(Optional.of(HiddenReason.IDS_NOT_CAPTURED), review.getSpecs().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getPrayerReview().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getOpener().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getCrashes().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getWaves().hiddenReason());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.review.KillReviewP3Test' --tests 'com.yamareviewer.domain.projection.KillReviewAssemblerP3Test'`
Expected: FAIL — `cannot find symbol: method getPrayerReview()` (and `tickLog(...)` on the builder).

- [ ] **Step 3: Add the fields to `KillReview`**

In `src/main/java/com/yamareviewer/domain/review/KillReview.java`, add these fields after Part 2's `Section<DeathRecap> deathRecap;` (the class is `@Value @Builder(toBuilder = true)`, so the builder gains the same names):

```java
	Section<PrayerReview> prayerReview;
	Section<Opener> opener;
	Section<TickLog> tickLog;
	Section<CrashSummary> crashes;
	Section<WaveSummary> waves;
	Section<SpecSummary> specs;
```

Add these hand-written getters (a hand-written getter replaces the Lombok one, so a review stored before the field existed never returns null):

```java
	public Section<PrayerReview> getPrayerReview()
	{
		return prayerReview == null ? Section.hidden(HiddenReason.NOT_APPLICABLE) : prayerReview;
	}

	public Section<Opener> getOpener()
	{
		return opener == null ? Section.hidden(HiddenReason.NOT_APPLICABLE) : opener;
	}

	public Section<TickLog> getTickLog()
	{
		return tickLog == null ? Section.hidden(HiddenReason.NOT_APPLICABLE) : tickLog;
	}

	public Section<CrashSummary> getCrashes()
	{
		return crashes == null ? Section.hidden(HiddenReason.NOT_APPLICABLE) : crashes;
	}

	public Section<WaveSummary> getWaves()
	{
		return waves == null ? Section.hidden(HiddenReason.NOT_APPLICABLE) : waves;
	}

	public Section<SpecSummary> getSpecs()
	{
		return specs == null ? Section.hidden(HiddenReason.NOT_APPLICABLE) : specs;
	}
```

Replace the body of `allSections()` with the display order of spec 7.3 (phases, damage, opener, prayer review, crash lines, waves, flares, specs, supplies, tick log, death recap; `java.util.ArrayList` and `java.util.List` imported):

```java
	/** Every shown section, in display order (spec 7.3). */
	public List<Section<?>> allSections()
	{
		List<Section<?>> sections = new ArrayList<>();
		sections.add(getPhases());
		sections.add(getDamage());
		sections.add(getOpener());
		sections.add(getPrayerReview());
		sections.add(getCrashes());
		sections.add(getWaves());
		sections.add(getFlares());
		sections.add(getSpecs());
		sections.add(getSupplies());
		sections.add(getTickLog());
		sections.add(getDeathRecap());
		return sections;
	}
```

`withRecomputedStatus()` already reads `allSections()`, so the new sections count towards `INCOMPLETE` without further change.

- [ ] **Step 4: Set the fields in `KillReviewAssembler`**

In `src/main/java/com/yamareviewer/domain/projection/KillReviewAssembler.java`, `assemble` fills a `KillReview.builder()` chain from the context (`.phases(context.section(Sections.PHASES))` and so on). Add these six calls to that chain, before `.build()`:

```java
			.prayerReview(context.section(Sections.PRAYER_REVIEW))
			.opener(context.section(Sections.OPENER))
			.tickLog(context.section(Sections.TICK_LOG))
			.crashes(context.section(Sections.CRASHES))
			.waves(context.section(Sections.WAVES))
			.specs(context.section(Sections.SPECS))
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.review.KillReviewP3Test' --tests 'com.yamareviewer.domain.projection.KillReviewAssemblerP3Test' --tests 'com.yamareviewer.domain.review.*' --tests 'com.yamareviewer.domain.projection.*'`
Expected: PASS, including every Part 2 test of those packages (the assembler still fills every Part 2 field).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/review/KillReview.java src/main/java/com/yamareviewer/domain/projection/KillReviewAssembler.java src/test/java/com/yamareviewer/domain/review/KillReviewP3Test.java src/test/java/com/yamareviewer/domain/projection/KillReviewAssemblerP3Test.java
git commit -m "feat: add the P3 sections to KillReview and the assembler"
```

---

### Task 5: Glyphs projection and the phase lookup

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/projection/PhaseLookup.java`, `Upstream.java`, `GlyphsProjection.java`
- Test: `src/test/java/com/yamareviewer/domain/projection/PhaseLookupTest.java`, `GlyphsProjectionTest.java`

**Interfaces:**
- Consumes: `Projection<T>`, `ProjectionContext`, `Section`, `HiddenReason`, `PhaseTimes`, `PhaseSpan` (Part 2), `ObjectSpawnObserved`, `ObjectAnimationObserved`, `IdRegistry.roleOf` (Part 1), `GlyphCount`, `Sections.GLYPHS` (Task 2), `Fights`, `TestContext` (Task 3).
- Produces: package-private `PhaseLookup.phaseAt(Section<PhaseTimes>, int)` and `phaseAt(PhaseTimes, int)` (null outside every span or when hidden); package-private `Upstream.hidden(Section<?>...)` returning the first hidden section's reason; `GlyphsProjection` (key `GLYPHS`, requires `GLYPH_FIRE` and `GLYPH_SHADOW`, hides with the phases' reason when the phases are hidden).

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/projection/PhaseLookupTest.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Fights;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class PhaseLookupTest
{
	private final PhaseTimes phases = Fights.phases(100);

	@Test
	public void aTickBelongsToTheLastSpanThatStartedBeforeIt()
	{
		assertEquals(Phase.P1, PhaseLookup.phaseAt(phases, 0));
		assertEquals(Phase.P1, PhaseLookup.phaseAt(phases, 9));
		assertEquals(Phase.JUDGE_1, PhaseLookup.phaseAt(phases, 10));
		assertEquals(Phase.P2, PhaseLookup.phaseAt(phases, 20));
		assertEquals(Phase.P3, PhaseLookup.phaseAt(phases, 40));
		assertEquals(Phase.P3, PhaseLookup.phaseAt(phases, 100));
	}

	@Test
	public void outsideEverySpanIsNull()
	{
		assertNull(PhaseLookup.phaseAt(phases, -1));
		assertNull(PhaseLookup.phaseAt(phases, 101));
		assertNull(PhaseLookup.phaseAt(Section.<PhaseTimes>hidden(HiddenReason.IDS_NOT_CAPTURED), 5));
	}

	@Test
	public void upstreamReportsTheFirstHiddenReason()
	{
		assertEquals(java.util.Optional.empty(), Upstream.hidden(Section.ok(1), Section.ok("a")));
		assertEquals(java.util.Optional.of(HiddenReason.ERROR),
			Upstream.hidden(Section.ok(1), Section.hidden(HiddenReason.ERROR), Section.hidden(HiddenReason.CONTRACT)));
	}
}
```

`src/test/java/com/yamareviewer/domain/projection/GlyphsProjectionTest.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.ObjectAnimationObserved;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.GlyphCount;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import com.yamareviewer.testing.TestIds;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class GlyphsProjectionTest
{
	private final GlyphsProjection projection = new GlyphsProjection();

	@Test
	public void keyAndRoles()
	{
		assertEquals(Sections.GLYPHS, projection.key());
		assertEquals(Set.of(Role.GLYPH_FIRE, Role.GLYPH_SHADOW), projection.requiredRoles());
	}

	@Test
	public void countsGlyphsConjuredInP2()
	{
		KillLog log = Fights.throughToP3(3, 1).ticks(10).end(EndReason.YAMA_DIED);

		assertEquals(Section.ok(new GlyphCount(3, 1)), projection.project(log, TestContext.withPhases(50)));
	}

	@Test
	public void glyphsOutsideP2AreIgnored()
	{
		KillLog log = Fights.throughToP3().ticks(5).glyph(Role.GLYPH_FIRE, 3200, 3200).ticks(5).end(EndReason.YAMA_DIED);

		assertEquals(Section.ok(new GlyphCount(0, 0)), projection.project(log, TestContext.withPhases(50)));
	}

	@Test
	public void aGlyphThatSpawnsAndAnimatesOnTheSameTickCountsOnceButALaterAnimationCountsAgain()
	{
		int fire = TestIds.id(Role.GLYPH_FIRE);
		Position tile = new Position(3190, 3210, 0);
		KillLog log = Fights.throughToP3(1, 0)
			.event(new ObjectAnimationObserved(Fights.GLYPH_TICK, fire, tile, 12169))
			.event(new ObjectAnimationObserved(Fights.GLYPH_TICK + 4, fire, tile, 12169))
			.ticks(10)
			.end(EndReason.YAMA_DIED);

		assertEquals(Section.ok(new GlyphCount(2, 0)), projection.project(log, TestContext.withPhases(50)));
	}

	@Test
	public void hiddenPhasesHideTheGlyphsWithTheSameReason()
	{
		KillLog log = Fights.throughToP3(3, 1).ticks(10).end(EndReason.YAMA_DIED);
		ProjectionContext context = TestContext.empty();
		context.put(Sections.PHASES, Section.<PhaseTimes>hidden(HiddenReason.IDS_NOT_CAPTURED));

		assertEquals(Optional.of(HiddenReason.IDS_NOT_CAPTURED), projection.project(log, context).hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), projection.project(log, TestContext.empty()).hiddenReason());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.PhaseLookupTest' --tests 'com.yamareviewer.domain.projection.GlyphsProjectionTest'`
Expected: FAIL — `cannot find symbol` for `PhaseLookup`, `Upstream`, `GlyphsProjection`.

- [ ] **Step 3: Write the helpers**

`src/main/java/com/yamareviewer/domain/projection/PhaseLookup.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;

/** Which phase a tick belongs to. Spans are contiguous: the tick a Judge despawns already belongs to the next phase. */
final class PhaseLookup
{
	private PhaseLookup()
	{
	}

	/** Null when the phases are hidden or the tick is outside every span. */
	static Phase phaseAt(Section<PhaseTimes> phases, int tick)
	{
		return phases.isOk() ? phaseAt(phases.value(), tick) : null;
	}

	static Phase phaseAt(PhaseTimes phases, int tick)
	{
		PhaseSpan current = null;
		for (PhaseSpan span : phases.getSpans())
		{
			if (span.getStartTick() <= tick)
			{
				current = span;
			}
		}
		return current != null && tick <= current.getEndTick() ? current.getPhase() : null;
	}
}
```

`src/main/java/com/yamareviewer/domain/projection/Upstream.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Section;
import java.util.Optional;

/** A projection that needs an earlier section hides for that section's reason when it is hidden. */
final class Upstream
{
	private Upstream()
	{
	}

	static Optional<HiddenReason> hidden(Section<?>... sections)
	{
		for (Section<?> section : sections)
		{
			if (!section.isOk())
			{
				return Optional.of(section.hiddenReason().orElse(HiddenReason.NOT_APPLICABLE));
			}
		}
		return Optional.empty();
	}
}
```

- [ ] **Step 4: Write `GlyphsProjection`**

`src/main/java/com/yamareviewer/domain/projection/GlyphsProjection.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.ObjectAnimationObserved;
import com.yamareviewer.domain.event.ObjectSpawnObserved;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.GlyphCount;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Counts the fire and shadow glyphs conjured in P2 (spec 6.5): every GLYPH_FIRE or GLYPH_SHADOW object
 * spawned or animated in P2, counted once per object, tile and tick, so a glyph that spawns and animates
 * on the same tick counts once while a second conjure of the same glyph counts again.
 */
public final class GlyphsProjection implements Projection<GlyphCount>
{
	@Override
	public SectionKey<GlyphCount> key()
	{
		return Sections.GLYPHS;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.GLYPH_FIRE, Role.GLYPH_SHADOW);
	}

	@Override
	public Section<GlyphCount> project(KillLog log, ProjectionContext context)
	{
		Section<PhaseTimes> phases = context.section(Sections.PHASES);
		Optional<HiddenReason> hidden = Upstream.hidden(phases);
		if (hidden.isPresent())
		{
			return Section.hidden(hidden.get());
		}
		IdRegistry ids = context.ids();
		Set<String> fire = new HashSet<>();
		Set<String> shadow = new HashSet<>();
		for (ObjectSpawnObserved spawn : log.eventsOf(ObjectSpawnObserved.class))
		{
			count(ids, phases.value(), spawn.getTick(), spawn.getObjectId(), spawn.getPosition(), fire, shadow);
		}
		for (ObjectAnimationObserved animation : log.eventsOf(ObjectAnimationObserved.class))
		{
			count(ids, phases.value(), animation.getTick(), animation.getObjectId(), animation.getPosition(), fire, shadow);
		}
		return Section.ok(new GlyphCount(fire.size(), shadow.size()));
	}

	private static void count(IdRegistry ids, PhaseTimes phases, int tick, int objectId, Position position,
		Set<String> fire, Set<String> shadow)
	{
		if (PhaseLookup.phaseAt(phases, tick) != Phase.P2)
		{
			return;
		}
		String key = objectId + "/" + (position == null ? "?" : position.getX() + "," + position.getY()) + "/" + tick;
		if (ids.is(Role.GLYPH_FIRE, objectId))
		{
			fire.add(key);
		}
		else if (ids.is(Role.GLYPH_SHADOW, objectId))
		{
			shadow.add(key);
		}
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.PhaseLookupTest' --tests 'com.yamareviewer.domain.projection.GlyphsProjectionTest'`
Expected: PASS (8 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/projection/PhaseLookup.java src/main/java/com/yamareviewer/domain/projection/Upstream.java src/main/java/com/yamareviewer/domain/projection/GlyphsProjection.java src/test/java/com/yamareviewer/domain/projection/PhaseLookupTest.java src/test/java/com/yamareviewer/domain/projection/GlyphsProjectionTest.java
git commit -m "feat: count the P2 glyphs for the opener"
```

---

### Task 6: Attacks projection

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/projection/AttacksProjection.java`
- Test: `src/test/java/com/yamareviewer/domain/projection/AttacksProjectionTest.java`

**Interfaces:**
- Consumes: `AnimationObserved`, `GraphicObserved`, `HitsplatObserved`, `TickState`, `HitsplatKind`, `Actor` (Part 1), `Attack`, `AttackTimeline`, `Sections.ATTACKS` (Task 2), `PhaseLookup` (Task 5).
- Produces: `AttacksProjection` (key `ATTACKS`, requires `YAMA` and `YAMA_STANDARD_ATTACK`; never hides for missing phases: `Attack.phase` is then null). Constants `IMPACT_WINDOW = 3`, `LANDING_AFTER_IMPACT = 1`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/domain/projection/AttacksProjectionTest.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class AttacksProjectionTest
{
	private final AttacksProjection projection = new AttacksProjection();

	private List<Attack> attacksOf(KillLog log, ProjectionContext context)
	{
		return projection.project(log, context).value().getAttacks();
	}

	@Test
	public void keyAndRoles()
	{
		assertEquals(Sections.ATTACKS, projection.key());
		assertEquals(Set.of(Role.YAMA, Role.YAMA_STANDARD_ATTACK), projection.requiredRoles());
	}

	@Test
	public void styleFromTheCastGraphicTargetFromTheTickStateAndTheLandingHitsplat()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(2).hitsplatOn(Actor.SELF, 2).ticks(5).end(EndReason.YAMA_DIED);

		assertEquals(List.of(new Attack(40, Phase.P3, Style.MAGIC, Actor.SELF, 42, 2)), attacksOf(log, TestContext.withPhases(47)));
	}

	@Test
	public void castGraphicOnTheNextTickStillCounts()
	{
		KillLog log = Fights.throughToP3().yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).ticks(1)
			.graphicOn(Actor.YAMA, TestIds.id(Role.YAMA_CAST_RANGED)).ticks(1).hitsplatOn(Actor.SELF, 0).ticks(5).end(EndReason.YAMA_DIED);

		assertEquals(List.of(new Attack(40, Phase.P3, Style.RANGED, Actor.SELF, 42, 0)), attacksOf(log, TestContext.withPhases(47)));
	}

	@Test
	public void styleAndTargetFromTheImpactWhenThereIsNoCastGraphic()
	{
		KillLog log = Fights.throughToP3().yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).ticks(3)
			.impactOn(Actor.PARTNER, Style.RANGED).hitsplatOn(Actor.PARTNER, 5).ticks(4).end(EndReason.YAMA_DIED);

		assertEquals(List.of(new Attack(40, Phase.P3, Style.RANGED, Actor.PARTNER, 43, 5)), attacksOf(log, TestContext.duo(47)));
	}

	@Test
	public void theImpactWinsOverTheTickStateForTheTarget()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).yamaTarget(Actor.PARTNER).ticks(2)
			.impactOn(Actor.SELF, Style.MAGIC).hitsplatOn(Actor.SELF, 1).ticks(5).end(EndReason.YAMA_DIED);

		assertEquals(Actor.SELF, attacksOf(log, TestContext.duo(47)).get(0).getTarget());
	}

	@Test
	public void styleStaysNullWithoutCastOrImpact()
	{
		KillLog log = Fights.throughToP3().yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).ticks(2)
			.hitsplatOn(Actor.SELF, 3).ticks(5).end(EndReason.YAMA_DIED);

		Attack attack = attacksOf(log, TestContext.withPhases(47)).get(0);

		assertNull(attack.getStyle());
		assertEquals(Actor.SELF, attack.getTarget());
		assertEquals(Integer.valueOf(42), attack.getLandingTick());
	}

	@Test
	public void anImpactMoreThanThreeTicksAfterTheCastIsNotThisAttacks()
	{
		KillLog log = Fights.throughToP3().yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).ticks(4)
			.impactOn(Actor.SELF, Style.MAGIC).hitsplatOn(Actor.SELF, 3).ticks(3).end(EndReason.YAMA_DIED);

		Attack attack = attacksOf(log, TestContext.withPhases(47)).get(0);

		assertNull(attack.getStyle());
		assertNull(attack.getLandingTick());
		assertNull(attack.getDamage());
	}

	@Test
	public void yourOwnHitsplatsAndHitsOnOtherActorsAreNotTheLanding()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(2)
			.myHitOn(Actor.YAMA, 40).hitsplatOn(Actor.PARTNER, 9).ticks(1).hitsplatOn(Actor.SELF, 2).ticks(4).end(EndReason.YAMA_DIED);

		Attack attack = attacksOf(log, TestContext.withPhases(47)).get(0);

		assertEquals(Integer.valueOf(43), attack.getLandingTick());
		assertEquals(Integer.valueOf(2), attack.getDamage());
	}

	@Test
	public void attacksInEveryPhaseAreKeptWithTheirPhase()
	{
		KillLog log = KillLogBuilder.kill().ticks(5).yamaCasts(Style.RANGED).ticks(3).end(EndReason.LEFT);

		List<Attack> attacks = attacksOf(log, TestContext.withPhases(8));

		assertEquals(1, attacks.size());
		assertEquals(Phase.P1, attacks.get(0).getPhase());
		assertNull(attacksOf(log, TestContext.empty()).get(0).getPhase());
	}

	@Test
	public void attacksComeInCastOrderAndOtherAnimationsAreIgnored()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(7).yamaAnimates(TestIds.id(Role.YAMA_MELEE)).ticks(2)
			.yamaCasts(Style.RANGED).ticks(3).end(EndReason.YAMA_DIED);

		AttackTimeline timeline = projection.project(log, TestContext.withPhases(52)).value();

		assertEquals(List.of(40, 49), List.of(timeline.getAttacks().get(0).getCastTick(), timeline.getAttacks().get(1).getCastTick()));
		assertEquals(java.util.Optional.of(Style.RANGED), timeline.expectedStyle(1));
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.AttacksProjectionTest'`
Expected: FAIL — `cannot find symbol: class AttacksProjection`.

- [ ] **Step 3: Write `AttacksProjection`**

`src/main/java/com/yamareviewer/domain/projection/AttacksProjection.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Yama's standard attacks in every phase (spec 6.4). Style: the cast graphic on Yama on the cast tick or
 * the tick after, else the impact graphic on a player within IMPACT_WINDOW ticks. Target: the player with
 * the impact graphic, else Yama's target in the cast tick's TickState. Landing: the first hitsplat Yama
 * dealt to the target at the impact tick or the tick after, or within IMPACT_WINDOW ticks after the cast
 * when no impact was seen.
 */
public final class AttacksProjection implements Projection<AttackTimeline>
{
	static final int IMPACT_WINDOW = 3;
	static final int LANDING_AFTER_IMPACT = 1;

	@Override
	public SectionKey<AttackTimeline> key()
	{
		return Sections.ATTACKS;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.YAMA, Role.YAMA_STANDARD_ATTACK);
	}

	@Override
	public Section<AttackTimeline> project(KillLog log, ProjectionContext context)
	{
		IdRegistry ids = context.ids();
		Section<PhaseTimes> phases = context.section(Sections.PHASES);
		List<GraphicObserved> graphics = log.eventsOf(GraphicObserved.class);
		List<HitsplatObserved> hitsplats = log.eventsOf(HitsplatObserved.class);
		Map<Integer, TickState> states = new HashMap<>();
		for (TickState state : log.eventsOf(TickState.class))
		{
			states.put(state.getTick(), state);
		}

		List<Attack> attacks = new ArrayList<>();
		for (AnimationObserved animation : log.eventsOf(AnimationObserved.class))
		{
			if (!Actor.YAMA.equals(animation.getActor()) || !ids.is(Role.YAMA_STANDARD_ATTACK, animation.getAnimationId()))
			{
				continue;
			}
			int cast = animation.getTick();
			GraphicObserved impact = firstImpact(ids, graphics, cast);
			Style style = castStyle(ids, graphics, cast);
			if (style == null && impact != null)
			{
				style = ids.is(Role.IMPACT_MAGIC, impact.getGraphicId()) ? Style.MAGIC : Style.RANGED;
			}
			Actor target = impact != null ? impact.getActor() : yamaTargetAt(states, cast);
			HitsplatObserved landing = landing(hitsplats, target, cast, impact);
			attacks.add(new Attack(cast, PhaseLookup.phaseAt(phases, cast), style, target,
				landing == null ? null : landing.getTick(), landing == null ? null : landing.getAmount()));
		}
		return Section.ok(new AttackTimeline(attacks));
	}

	private static Style castStyle(IdRegistry ids, List<GraphicObserved> graphics, int cast)
	{
		for (GraphicObserved graphic : graphics)
		{
			if (!Actor.YAMA.equals(graphic.getActor()) || graphic.getTick() < cast || graphic.getTick() > cast + 1)
			{
				continue;
			}
			if (ids.is(Role.YAMA_CAST_MAGIC, graphic.getGraphicId()))
			{
				return Style.MAGIC;
			}
			if (ids.is(Role.YAMA_CAST_RANGED, graphic.getGraphicId()))
			{
				return Style.RANGED;
			}
		}
		return null;
	}

	private static GraphicObserved firstImpact(IdRegistry ids, List<GraphicObserved> graphics, int cast)
	{
		for (GraphicObserved graphic : graphics)
		{
			if (graphic.getActor().isPlayer() && graphic.getTick() >= cast && graphic.getTick() <= cast + IMPACT_WINDOW
				&& (ids.is(Role.IMPACT_MAGIC, graphic.getGraphicId()) || ids.is(Role.IMPACT_RANGED, graphic.getGraphicId())))
			{
				return graphic;
			}
		}
		return null;
	}

	private static Actor yamaTargetAt(Map<Integer, TickState> states, int tick)
	{
		TickState state = states.get(tick);
		Actor target = state == null ? null : state.getYamaTarget();
		return target != null && target.isPlayer() ? target : null;
	}

	private static HitsplatObserved landing(List<HitsplatObserved> hitsplats, Actor target, int cast, GraphicObserved impact)
	{
		if (target == null)
		{
			return null;
		}
		int from = impact == null ? cast + 1 : impact.getTick();
		int to = impact == null ? cast + IMPACT_WINDOW : impact.getTick() + LANDING_AFTER_IMPACT;
		for (HitsplatObserved hitsplat : hitsplats)
		{
			if (hitsplat.getTick() >= from && hitsplat.getTick() <= to && target.equals(hitsplat.getTarget()) && !hitsplat.isMine()
				&& (hitsplat.getKind() == HitsplatKind.DAMAGE || hitsplat.getKind() == HitsplatKind.BLOCK))
			{
				return hitsplat;
			}
		}
		return null;
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.AttacksProjectionTest'`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/projection/AttacksProjection.java src/test/java/com/yamareviewer/domain/projection/AttacksProjectionTest.java
git commit -m "feat: detect Yama's standard attacks with style, target and landing"
```

---

### Task 7: Outcome rules

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/outcome/PrayerContext.java`, `OutcomeRule.java`, `PrayerDisabledRule.java`, `BlockedRule.java`, `TooEarlyRule.java`, `LateRule.java`, `LostAlternationRule.java`, `NoPrayerRule.java`, `OutcomeRules.java`
- Test: `src/test/java/com/yamareviewer/domain/outcome/OutcomeRulesTest.java`

**Interfaces:**
- Consumes: `ProtectionPrayer` (Part 1), `Style` (Part 2), `PrayerOutcome` (Task 2).
- Produces: `PrayerContext(int checkTick, ProtectionPrayer correctPrayer, Map<Integer, Set<ProtectionPrayer>> prayersByTick, List<Integer> prayerDisabledTicks, int waveDisableWindow)` with `prayersAt(int)`, `correctAt(int)`, `static correctFor(Style)`; `OutcomeRule.apply(PrayerContext) -> Optional<PrayerOutcome>`; `OutcomeRules.standard()` (the six rules in order) and `OutcomeRules.evaluate(PrayerContext) -> PrayerOutcome`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/domain/outcome/OutcomeRulesTest.java`:

```java
package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class OutcomeRulesTest
{
	private static final int L = 50;

	private final Map<Integer, Set<ProtectionPrayer>> prayers = new HashMap<>();

	private void praying(int tick, ProtectionPrayer... active)
	{
		prayers.put(tick, Set.of(active));
	}

	private PrayerOutcome evaluate(Integer... disabledMessages)
	{
		return OutcomeRules.evaluate(new PrayerContext(L, ProtectionPrayer.MAGIC, prayers, List.of(disabledMessages), 5));
	}

	@Test
	public void rulesAreTriedInTheSpecsOrder()
	{
		assertEquals(List.of("PrayerDisabledRule", "BlockedRule", "TooEarlyRule", "LateRule", "LostAlternationRule", "NoPrayerRule"),
			OutcomeRules.standard().stream().map(rule -> rule.getClass().getSimpleName()).collect(Collectors.toList()));
	}

	@Test
	public void correctPrayerAtTheCheckTickIsBlocked()
	{
		praying(L, ProtectionPrayer.MAGIC);

		assertEquals(PrayerOutcome.BLOCKED, evaluate());
		assertEquals(PrayerOutcome.BLOCKED, evaluate(L - 1));
	}

	@Test
	public void disabledWhenTheMessageIsInTheWindowAndNothingWasActiveSince()
	{
		assertEquals(PrayerOutcome.PRAYER_DISABLED, evaluate(L - 2));
		assertEquals(PrayerOutcome.PRAYER_DISABLED, evaluate(L - 5));
		assertEquals(PrayerOutcome.PRAYER_DISABLED, evaluate(L));
	}

	@Test
	public void notDisabledWhenTheMessageIsTooOldOrAPrayerWasActiveSince()
	{
		assertEquals(PrayerOutcome.NO_PRAYER, evaluate(L - 6));
		assertEquals(PrayerOutcome.NO_PRAYER, evaluate(L + 1));
		praying(L - 1, ProtectionPrayer.MELEE);
		assertEquals(PrayerOutcome.NO_PRAYER, evaluate(L - 2));
	}

	@Test
	public void correctPrayerInTheThreeTicksBeforeIsTooEarly()
	{
		praying(L - 3, ProtectionPrayer.MAGIC);

		assertEquals(PrayerOutcome.TOO_EARLY, evaluate());
	}

	@Test
	public void correctPrayerFourTicksBeforeIsNotTooEarly()
	{
		praying(L - 4, ProtectionPrayer.MAGIC);

		assertEquals(PrayerOutcome.NO_PRAYER, evaluate());
	}

	@Test
	public void correctPrayerOnTheNextTickIsLate()
	{
		praying(L + 1, ProtectionPrayer.MAGIC);

		assertEquals(PrayerOutcome.LATE, evaluate());
	}

	@Test
	public void tooEarlyWinsOverLate()
	{
		praying(L - 1, ProtectionPrayer.MAGIC);
		praying(L + 1, ProtectionPrayer.MAGIC);

		assertEquals(PrayerOutcome.TOO_EARLY, evaluate());
	}

	@Test
	public void anyOtherProtectionPrayerIsLostAlternationMeleeIncluded()
	{
		praying(L, ProtectionPrayer.MISSILES);
		assertEquals(PrayerOutcome.LOST_ALTERNATION, evaluate());

		praying(L, ProtectionPrayer.MELEE);
		assertEquals(PrayerOutcome.LOST_ALTERNATION, evaluate());
	}

	@Test
	public void tooEarlyIsTriedBeforeLostAlternation()
	{
		praying(L - 1, ProtectionPrayer.MAGIC);
		praying(L, ProtectionPrayer.MISSILES);

		assertEquals(PrayerOutcome.TOO_EARLY, evaluate());
	}

	@Test
	public void nothingAtAllIsNoPrayerAlsoForATickWithoutAState()
	{
		assertEquals(PrayerOutcome.NO_PRAYER, evaluate());
		praying(L, ProtectionPrayer.MAGIC);
		assertEquals(PrayerOutcome.NO_PRAYER, OutcomeRules.evaluate(new PrayerContext(L + 100, ProtectionPrayer.MAGIC, prayers, List.of(), 5)));
	}

	@Test
	public void rangedNeedsProtectFromMissiles()
	{
		assertEquals(ProtectionPrayer.MISSILES, PrayerContext.correctFor(Style.RANGED));
		assertEquals(ProtectionPrayer.MAGIC, PrayerContext.correctFor(Style.MAGIC));
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.domain.outcome.OutcomeRulesTest'`
Expected: FAIL — `cannot find symbol` for `OutcomeRules`, `PrayerContext`.

- [ ] **Step 3: Write the context and the interface**

`src/main/java/com/yamareviewer/domain/outcome/PrayerContext.java`:

```java
package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.model.Style;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Value;

/** What the outcome rules of spec 6.5 look at for one P3 attack aimed at you. */
@Value
public class PrayerContext
{
	/** The prayer-check tick L. */
	int checkTick;
	ProtectionPrayer correctPrayer;
	/** The protection prayers of each TickState by tick; a tick without a state reads as no prayers. */
	Map<Integer, Set<ProtectionPrayer>> prayersByTick;
	/** Ticks of PRAYER_DISABLED_MESSAGE lines. */
	List<Integer> prayerDisabledTicks;
	int waveDisableWindow;

	public Set<ProtectionPrayer> prayersAt(int tick)
	{
		Set<ProtectionPrayer> active = prayersByTick.get(tick);
		return active == null ? Set.of() : active;
	}

	public boolean correctAt(int tick)
	{
		return prayersAt(tick).contains(correctPrayer);
	}

	/** Protect from Magic for magic, Protect from Missiles for ranged. */
	public static ProtectionPrayer correctFor(Style style)
	{
		return style == Style.MAGIC ? ProtectionPrayer.MAGIC : ProtectionPrayer.MISSILES;
	}
}
```

`src/main/java/com/yamareviewer/domain/outcome/OutcomeRule.java`:

```java
package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.Optional;

/** One row of the outcome table of spec 6.5; the first rule that returns an outcome wins. */
public interface OutcomeRule
{
	Optional<PrayerOutcome> apply(PrayerContext context);
}
```

- [ ] **Step 4: Write the six rules and the list**

`PrayerDisabledRule.java`:

```java
package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.Optional;

/** Rule 0: a PRAYER_DISABLED_MESSAGE within waveDisableWindow ticks before L, and no protection prayer active since. */
public final class PrayerDisabledRule implements OutcomeRule
{
	@Override
	public Optional<PrayerOutcome> apply(PrayerContext context)
	{
		int check = context.getCheckTick();
		for (int message : context.getPrayerDisabledTicks())
		{
			if (message > check || message < check - context.getWaveDisableWindow())
			{
				continue;
			}
			boolean noneSince = true;
			for (int tick = message; tick <= check; tick++)
			{
				if (!context.prayersAt(tick).isEmpty())
				{
					noneSince = false;
					break;
				}
			}
			if (noneSince)
			{
				return Optional.of(PrayerOutcome.PRAYER_DISABLED);
			}
		}
		return Optional.empty();
	}
}
```

`BlockedRule.java`:

```java
package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.Optional;

/** Rule 1: the correct prayer was active at L. */
public final class BlockedRule implements OutcomeRule
{
	@Override
	public Optional<PrayerOutcome> apply(PrayerContext context)
	{
		return context.correctAt(context.getCheckTick()) ? Optional.of(PrayerOutcome.BLOCKED) : Optional.empty();
	}
}
```

`TooEarlyRule.java`:

```java
package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.Optional;

/** Rule 2: the correct prayer was active at a tick in L-3 … L-1 but not at L. */
public final class TooEarlyRule implements OutcomeRule
{
	static final int LOOKBACK = 3;

	@Override
	public Optional<PrayerOutcome> apply(PrayerContext context)
	{
		int check = context.getCheckTick();
		if (context.correctAt(check))
		{
			return Optional.empty();
		}
		for (int tick = check - LOOKBACK; tick < check; tick++)
		{
			if (context.correctAt(tick))
			{
				return Optional.of(PrayerOutcome.TOO_EARLY);
			}
		}
		return Optional.empty();
	}
}
```

`LateRule.java`:

```java
package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.Optional;

/** Rule 3: the correct prayer was active at L+1. */
public final class LateRule implements OutcomeRule
{
	@Override
	public Optional<PrayerOutcome> apply(PrayerContext context)
	{
		int check = context.getCheckTick();
		return !context.correctAt(check) && context.correctAt(check + 1) ? Optional.of(PrayerOutcome.LATE) : Optional.empty();
	}
}
```

`LostAlternationRule.java`:

```java
package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.Optional;

/** Rule 4: another protection prayer, Protect from Melee included, was active at L. */
public final class LostAlternationRule implements OutcomeRule
{
	@Override
	public Optional<PrayerOutcome> apply(PrayerContext context)
	{
		int check = context.getCheckTick();
		return !context.correctAt(check) && !context.prayersAt(check).isEmpty()
			? Optional.of(PrayerOutcome.LOST_ALTERNATION)
			: Optional.empty();
	}
}
```

`NoPrayerRule.java`:

```java
package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.Optional;

/** Rule 5: otherwise. */
public final class NoPrayerRule implements OutcomeRule
{
	@Override
	public Optional<PrayerOutcome> apply(PrayerContext context)
	{
		return Optional.of(PrayerOutcome.NO_PRAYER);
	}
}
```

`OutcomeRules.java`:

```java
package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.List;
import java.util.Optional;

/** The ordered rules of spec 6.5; adding an outcome means adding a class to this list. */
public final class OutcomeRules
{
	private static final List<OutcomeRule> STANDARD = List.of(
		new PrayerDisabledRule(), new BlockedRule(), new TooEarlyRule(), new LateRule(), new LostAlternationRule(), new NoPrayerRule());

	private OutcomeRules()
	{
	}

	public static List<OutcomeRule> standard()
	{
		return STANDARD;
	}

	/** The first matching rule's outcome; NoPrayerRule always matches. */
	public static PrayerOutcome evaluate(PrayerContext context)
	{
		for (OutcomeRule rule : STANDARD)
		{
			Optional<PrayerOutcome> outcome = rule.apply(context);
			if (outcome.isPresent())
			{
				return outcome.get();
			}
		}
		return PrayerOutcome.NO_PRAYER;
	}
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.domain.outcome.OutcomeRulesTest'`
Expected: PASS (12 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/outcome src/test/java/com/yamareviewer/domain/outcome
git commit -m "feat: add the six prayer outcome rules of the P3 review"
```

---

### Task 8: Prayer review projection

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/projection/PrayerReviewProjection.java`
- Test: `src/test/java/com/yamareviewer/domain/projection/PrayerReviewProjectionTest.java`

**Interfaces:**
- Consumes: `AttackTimeline`, `Attack`, `PrayerReview`, `AttackResult`, `PrayerOutcome` (Task 2), `PrayerContext`, `OutcomeRules` (Task 7), `Rules.getPrayerCheck()`, `getPrayerCheckOffset()`, `getWaveDisableWindow()`, `PrayerCheck` (Part 1), `ContractRules.isRandomAttackStyles()`, `getPrayerAutoOffTicks()` (Part 2), `TickState`, `GameMessageObserved`, `IdRegistry.matchesText` (Part 1), `Upstream`, `AttacksProjection` (Tasks 5, 6).
- Produces: `PrayerReviewProjection` (key `PRAYER_REVIEW`, requires `YAMA_CAST_MAGIC` and `YAMA_CAST_RANGED`, hides with the reason of hidden `PHASES` or `ATTACKS`); package-private `static int checkTick(Attack, Rules)` reused by the tick log and Part 4's checks; constants `LOST_ALTERNATION_LABEL`, `WRONG_PRAYER_LABEL`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/domain/projection/PrayerReviewProjectionTest.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.ids.PrayerCheck;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.AttackResult;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PrayerOutcome;
import com.yamareviewer.domain.review.PrayerReview;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import com.yamareviewer.testing.TestIds;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PrayerReviewProjectionTest
{
	private final PrayerReviewProjection projection = new PrayerReviewProjection();

	private PrayerReview review(KillLog log, ProjectionContext context)
	{
		context.put(Sections.ATTACKS, new AttacksProjection().project(log, context));
		return projection.project(log, context).value();
	}

	private static List<PrayerOutcome> outcomes(PrayerReview review)
	{
		return review.getResults().stream().map(AttackResult::getOutcome).collect(Collectors.toList());
	}

	private static List<Integer> switches(PrayerReview review)
	{
		return review.getResults().stream().map(AttackResult::getSwitchTicks).collect(Collectors.toList());
	}

	@Test
	public void keyAndRoles()
	{
		assertEquals(Sections.PRAYER_REVIEW, projection.key());
		assertEquals(Set.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED), projection.requiredRoles());
	}

	@Test
	public void blockedAttacksAndTheSwitchTimingBetweenThem()
	{
		KillLogBuilder kill = Fights.throughToP3();
		kill.prayers(ProtectionPrayer.MAGIC).yamaCasts(Style.MAGIC).ticks(2).impactOn(Actor.SELF, Style.MAGIC).hitsplatOn(Actor.SELF, 2).ticks(5);
		kill.prayers(ProtectionPrayer.MISSILES).yamaCasts(Style.RANGED).ticks(2).impactOn(Actor.SELF, Style.RANGED).hitsplatOn(Actor.SELF, 0).ticks(5);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		PrayerReview review = review(log, TestContext.withPhases(54));

		assertEquals(List.of(PrayerOutcome.BLOCKED, PrayerOutcome.BLOCKED), outcomes(review));
		assertEquals(Arrays.asList(null, 7), switches(review));
		assertEquals(List.of(40, 47), review.getResults().stream().map(AttackResult::getCheckTick).collect(Collectors.toList()));
		assertEquals(Optional.of(1.0), review.accuracy());
		assertEquals(PrayerReviewProjection.LOST_ALTERNATION_LABEL, review.getLostAlternationLabel());
		assertTrue(review.getNotes().isEmpty());
	}

	@Test
	public void everyMistakeKind()
	{
		KillLogBuilder kill = Fights.throughToP3();
		kill.prayers(ProtectionPrayer.MAGIC).yamaCasts(Style.MAGIC).ticks(4);
		kill.prayers(ProtectionPrayer.MISSILES).ticks(3);
		kill.prayers().yamaCasts(Style.RANGED).ticks(7);
		kill.yamaCasts(Style.MAGIC).ticks(1);
		kill.prayers(ProtectionPrayer.MAGIC).ticks(6);
		kill.yamaCasts(Style.RANGED).ticks(7);
		kill.prayers().yamaCasts(Style.MAGIC).ticks(1);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		PrayerReview review = review(log, TestContext.withPhases(69));

		assertEquals(List.of(PrayerOutcome.BLOCKED, PrayerOutcome.TOO_EARLY, PrayerOutcome.LATE, PrayerOutcome.LOST_ALTERNATION, PrayerOutcome.NO_PRAYER),
			outcomes(review));
		assertEquals(Arrays.asList(null, 4, 8, null, null), switches(review));
		assertEquals(5, review.scored());
		assertEquals(4, review.mistakes());
	}

	@Test
	public void prayersDisabledByAWaveIsNotAMistake()
	{
		KillLogBuilder kill = Fights.throughToP3();
		kill.prayers(ProtectionPrayer.MAGIC).yamaCasts(Style.MAGIC).ticks(4);
		kill.prayers().prayersDisabledMessage().ticks(3);
		kill.yamaCasts(Style.RANGED).ticks(7);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		PrayerReview review = review(log, TestContext.withPhases(54));

		assertEquals(List.of(PrayerOutcome.BLOCKED, PrayerOutcome.PRAYER_DISABLED), outcomes(review));
		assertEquals(1, review.scored());
		assertEquals(0, review.mistakes());
		assertEquals(Optional.of(1.0), review.accuracy());
	}

	@Test
	public void partnerAttacksAreNotScoredButSetTheSwitchOrigin()
	{
		KillLogBuilder kill = Fights.throughToP3();
		kill.yamaCasts(Style.MAGIC).ticks(2).impactOn(Actor.PARTNER, Style.MAGIC).hitsplatOn(Actor.PARTNER, 3).ticks(3);
		kill.prayers(ProtectionPrayer.MISSILES).ticks(2);
		kill.yamaCasts(Style.RANGED).ticks(2).impactOn(Actor.SELF, Style.RANGED).hitsplatOn(Actor.SELF, 0).ticks(5);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		PrayerReview review = review(log, TestContext.duo(54));

		assertEquals(List.of(PrayerOutcome.BLOCKED), outcomes(review));
		assertEquals(List.of(5), switches(review));
		assertEquals(47, review.getResults().get(0).getAttack().getCastTick());
	}

	@Test
	public void attacksWithUnknownStyleAreNotScored()
	{
		KillLog log = Fights.throughToP3().yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).ticks(2).hitsplatOn(Actor.SELF, 3).ticks(5)
			.end(EndReason.YAMA_DIED);

		PrayerReview review = review(log, TestContext.withPhases(47));

		assertTrue(review.getResults().isEmpty());
		assertEquals(1, review.getUnscored());
		assertEquals(Optional.empty(), review.accuracy());
	}

	@Test
	public void hitsplatCheckUsesTheLandingTickAndFallsBackToTheCastTick()
	{
		KillLogBuilder kill = Fights.throughToP3();
		kill.yamaCasts(Style.MAGIC).ticks(2);
		kill.prayers(ProtectionPrayer.MAGIC).impactOn(Actor.SELF, Style.MAGIC).hitsplatOn(Actor.SELF, 1).ticks(1);
		kill.prayers().ticks(4);
		kill.prayers(ProtectionPrayer.MISSILES).yamaCasts(Style.RANGED).ticks(7);
		KillLog log = kill.end(EndReason.YAMA_DIED);
		Rules hitsplat = Rules.DEFAULT.toBuilder().prayerCheck(PrayerCheck.HITSPLAT).build();

		assertEquals(List.of(PrayerOutcome.BLOCKED, PrayerOutcome.BLOCKED), outcomes(review(log, TestContext.withPhases(hitsplat, 54))));
		assertEquals(List.of(42, 47), review(log, TestContext.withPhases(hitsplat, 54)).getResults().stream()
			.map(AttackResult::getCheckTick).collect(Collectors.toList()));
		assertEquals(List.of(PrayerOutcome.NO_PRAYER, PrayerOutcome.BLOCKED), outcomes(review(log, TestContext.withPhases(54))));
	}

	@Test
	public void checkTickPastTheEndOfTheLogScoresNoPrayer()
	{
		KillLog log = Fights.throughToP3().prayers(ProtectionPrayer.MAGIC).yamaCasts(Style.MAGIC).end(EndReason.PLAYER_DIED);
		Rules offset = Rules.DEFAULT.toBuilder().prayerCheckOffset(2).build();

		PrayerReview review = review(log, TestContext.withPhases(offset, 40));

		assertEquals(List.of(PrayerOutcome.NO_PRAYER), outcomes(review));
		assertEquals(42, review.getResults().get(0).getCheckTick());
	}

	@Test
	public void contractLabelsAndNotes()
	{
		KillLog log = Fights.throughToP3().prayers(ProtectionPrayer.MELEE).yamaCasts(Style.MAGIC).ticks(7).end(EndReason.YAMA_DIED);

		PrayerReview shard = review(log, TestContext.under(Contract.SHARD_ACQUISITION, 47));
		PrayerReview divine = review(log, TestContext.under(Contract.DIVINE_SEVERANCE, 47));

		assertEquals(PrayerReviewProjection.WRONG_PRAYER_LABEL, shard.getLostAlternationLabel());
		assertEquals("wrong prayer", shard.label(PrayerOutcome.LOST_ALTERNATION));
		assertTrue(shard.getNotes().get(0).contains("random"));
		assertEquals(PrayerReviewProjection.LOST_ALTERNATION_LABEL, divine.getLostAlternationLabel());
		assertTrue(divine.getNotes().get(0).contains("switch off 2 ticks after activation"));
	}

	@Test
	public void hiddenUpstreamSectionsHideTheReviewWithTheSameReason()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(7).end(EndReason.YAMA_DIED);
		ProjectionContext context = TestContext.withPhases(47);
		context.put(Sections.ATTACKS, Section.<AttackTimeline>hidden(HiddenReason.IDS_NOT_CAPTURED));

		assertEquals(Optional.of(HiddenReason.IDS_NOT_CAPTURED), projection.project(log, context).hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), projection.project(log, TestContext.empty()).hiddenReason());
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.PrayerReviewProjectionTest'`
Expected: FAIL — `cannot find symbol: class PrayerReviewProjection`.

- [ ] **Step 3: Write `PrayerReviewProjection`**

`src/main/java/com/yamareviewer/domain/projection/PrayerReviewProjection.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.GameMessageObserved;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.PrayerCheck;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.outcome.OutcomeRules;
import com.yamareviewer.domain.outcome.PrayerContext;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackResult;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.PrayerOutcome;
import com.yamareviewer.domain.review.PrayerReview;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Scores every P3 attack aimed at you with the outcome rules of spec 6.5. Attacks on the partner are not
 * scored but still move the switch-timing origin, because alternation is global.
 */
public final class PrayerReviewProjection implements Projection<PrayerReview>
{
	static final String LOST_ALTERNATION_LABEL = "lost alternation";
	static final String WRONG_PRAYER_LABEL = "wrong prayer";
	static final String SHARD_NOTE = "Under Shard Acquisition Yama's attack styles are random, so a wrong prayer is reported as a wrong prayer, not a lost alternation.";
	static final String AUTO_OFF_NOTE = "Under Divine Severance protection prayers switch off %d ticks after activation: a correct prayer switched on 3 or more ticks before the check tick is already off again and scores too early.";

	@Override
	public SectionKey<PrayerReview> key()
	{
		return Sections.PRAYER_REVIEW;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED);
	}

	@Override
	public Section<PrayerReview> project(KillLog log, ProjectionContext context)
	{
		Section<PhaseTimes> phases = context.section(Sections.PHASES);
		Section<AttackTimeline> attacks = context.section(Sections.ATTACKS);
		Optional<HiddenReason> hidden = Upstream.hidden(phases, attacks);
		if (hidden.isPresent())
		{
			return Section.hidden(hidden.get());
		}
		Rules rules = context.rules();
		ContractRules contract = context.contractRules();
		Map<Integer, Set<ProtectionPrayer>> prayersByTick = prayersByTick(log);
		List<Integer> disabledTicks = prayerDisabledTicks(log, context.ids());

		List<AttackResult> results = new ArrayList<>();
		int unscored = 0;
		Integer previousCheck = null;
		for (Attack attack : attacks.value().inPhase(Phase.P3))
		{
			int check = checkTick(attack, rules);
			if (Actor.SELF.equals(attack.getTarget()))
			{
				if (attack.getStyle() == null)
				{
					unscored++;
				}
				else
				{
					PrayerContext prayerContext = new PrayerContext(check, PrayerContext.correctFor(attack.getStyle()),
						prayersByTick, disabledTicks, rules.getWaveDisableWindow());
					PrayerOutcome outcome = OutcomeRules.evaluate(prayerContext);
					results.add(new AttackResult(attack, check, outcome, switchTicks(prayerContext, outcome, previousCheck)));
				}
			}
			previousCheck = check;
		}

		List<String> notes = new ArrayList<>();
		if (contract.isRandomAttackStyles())
		{
			notes.add(SHARD_NOTE);
		}
		if (contract.getPrayerAutoOffTicks() > 0)
		{
			notes.add(String.format(AUTO_OFF_NOTE, contract.getPrayerAutoOffTicks()));
		}
		String label = contract.isRandomAttackStyles() ? WRONG_PRAYER_LABEL : LOST_ALTERNATION_LABEL;
		return Section.ok(new PrayerReview(results, unscored, label, notes));
	}

	/** The prayer-check tick L: the cast tick, or under HITSPLAT the landing tick (the cast tick when none), plus the offset. */
	static int checkTick(Attack attack, Rules rules)
	{
		int base = rules.getPrayerCheck() == PrayerCheck.HITSPLAT && attack.getLandingTick() != null
			? attack.getLandingTick()
			: attack.getCastTick();
		return base + rules.getPrayerCheckOffset();
	}

	private static Integer switchTicks(PrayerContext context, PrayerOutcome outcome, Integer previousCheck)
	{
		if (previousCheck == null || outcome == PrayerOutcome.NO_PRAYER || outcome == PrayerOutcome.PRAYER_DISABLED)
		{
			return null;
		}
		for (int tick = previousCheck; tick <= context.getCheckTick() + 1; tick++)
		{
			if (context.correctAt(tick))
			{
				return tick - previousCheck;
			}
		}
		return null;
	}

	private static Map<Integer, Set<ProtectionPrayer>> prayersByTick(KillLog log)
	{
		Map<Integer, Set<ProtectionPrayer>> prayers = new HashMap<>();
		for (TickState state : log.eventsOf(TickState.class))
		{
			prayers.put(state.getTick(), state.getPrayers());
		}
		return prayers;
	}

	private static List<Integer> prayerDisabledTicks(KillLog log, IdRegistry ids)
	{
		List<Integer> ticks = new ArrayList<>();
		for (GameMessageObserved message : log.eventsOf(GameMessageObserved.class))
		{
			if (ids.matchesText(Role.PRAYER_DISABLED_MESSAGE, message.getText()))
			{
				ticks.add(message.getTick());
			}
		}
		return ticks;
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.PrayerReviewProjectionTest'`
Expected: PASS (10 tests). In `everyMistakeKind` the second attack (cast 47) is TOO_EARLY because Protect from Missiles was on at ticks 44–46 and off at 47, the third (cast 54) is LATE because Protect from Magic came on at 55, the fourth (cast 61) is LOST_ALTERNATION because Protect from Magic was still on, and the fifth (cast 68) has no prayer at all.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/projection/PrayerReviewProjection.java src/test/java/com/yamareviewer/domain/projection/PrayerReviewProjectionTest.java
git commit -m "feat: score every P3 attack aimed at you with the outcome rules"
```

---

### Task 9: Opener projection

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/projection/OpenerProjection.java`
- Test: `src/test/java/com/yamareviewer/domain/projection/OpenerProjectionTest.java`

**Interfaces:**
- Consumes: `GlyphCount`, `AttackTimeline`, `PrayerReview`, `Opener` (Task 2), `ContractRules.isRandomAttackStyles()` (Part 2), `Upstream` (Task 5), `GlyphsProjection`, `AttacksProjection`, `PrayerReviewProjection` (Tasks 5, 6, 8).
- Produces: `OpenerProjection` (key `OPENER`, requires `YAMA_CAST_MAGIC` and `YAMA_CAST_RANGED`; hidden `CONTRACT` under random attack styles; hides with the reason of hidden `GLYPHS`, `PHASES` or `ATTACKS`; a hidden `PRAYER_REVIEW` only leaves `firstOwnResult` null).

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/domain/projection/OpenerProjectionTest.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.GlyphCount;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Opener;
import com.yamareviewer.domain.review.PrayerOutcome;
import com.yamareviewer.domain.review.PrayerReview;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class OpenerProjectionTest
{
	private final OpenerProjection projection = new OpenerProjection();

	private static ProjectionContext upstream(KillLog log, ProjectionContext context)
	{
		context.put(Sections.GLYPHS, new GlyphsProjection().project(log, context));
		context.put(Sections.ATTACKS, new AttacksProjection().project(log, context));
		context.put(Sections.PRAYER_REVIEW, new PrayerReviewProjection().project(log, context));
		return context;
	}

	private static KillLog fireMajorityThenMagicOpener()
	{
		return Fights.throughToP3(3, 1).prayers(ProtectionPrayer.MAGIC).yamaCasts(Style.MAGIC).ticks(2)
			.impactOn(Actor.SELF, Style.MAGIC).hitsplatOn(Actor.SELF, 1).ticks(5).end(EndReason.YAMA_DIED);
	}

	@Test
	public void keyAndRoles()
	{
		assertEquals(Sections.OPENER, projection.key());
		assertEquals(Set.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED), projection.requiredRoles());
	}

	@Test
	public void fireMajorityExpectsAMagicOpenerAndYamaDelivered()
	{
		KillLog log = fireMajorityThenMagicOpener();

		Opener opener = projection.project(log, upstream(log, TestContext.withPhases(47))).value();

		assertEquals(new GlyphCount(3, 1), opener.getGlyphs());
		assertEquals(Style.MAGIC, opener.getExpectedStyle());
		assertEquals(40, opener.getFirstAttack().getCastTick());
		assertEquals(Boolean.TRUE, opener.getAsExpected());
		assertEquals(PrayerOutcome.BLOCKED, opener.getFirstOwnResult().getOutcome());
	}

	@Test
	public void shadowMajorityWithAMagicOpenerOnThePartnerIsUnexpectedAndYourFirstAttackIsTheSecondOne()
	{
		KillLogBuilder kill = Fights.throughToP3(1, 2);
		kill.yamaCasts(Style.MAGIC).ticks(2).impactOn(Actor.PARTNER, Style.MAGIC).hitsplatOn(Actor.PARTNER, 4).ticks(5);
		kill.prayers().yamaCasts(Style.RANGED).ticks(2).impactOn(Actor.SELF, Style.RANGED).hitsplatOn(Actor.SELF, 9).ticks(5);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		Opener opener = projection.project(log, upstream(log, TestContext.duo(54))).value();

		assertEquals(Style.RANGED, opener.getExpectedStyle());
		assertEquals(Actor.PARTNER, opener.getFirstAttack().getTarget());
		assertEquals(Boolean.FALSE, opener.getAsExpected());
		assertEquals(47, opener.getFirstOwnResult().getAttack().getCastTick());
		assertEquals(PrayerOutcome.NO_PRAYER, opener.getFirstOwnResult().getOutcome());
	}

	@Test
	public void aGlyphTieHasNoExpectation()
	{
		KillLog log = Fights.throughToP3(2, 2).yamaCasts(Style.MAGIC).ticks(7).end(EndReason.YAMA_DIED);

		Opener opener = projection.project(log, upstream(log, TestContext.withPhases(47))).value();

		assertNull(opener.getExpectedStyle());
		assertNull(opener.getAsExpected());
		assertEquals(40, opener.getFirstAttack().getCastTick());
	}

	@Test
	public void noP3AttackLeavesTheAttackFieldsNull()
	{
		KillLog log = Fights.throughToP3(3, 0).ticks(3).end(EndReason.LEFT);

		Opener opener = projection.project(log, upstream(log, TestContext.withPhases(43))).value();

		assertEquals(Style.MAGIC, opener.getExpectedStyle());
		assertNull(opener.getFirstAttack());
		assertNull(opener.getAsExpected());
		assertNull(opener.getFirstOwnResult());
	}

	@Test
	public void hiddenUnderShardAcquisitionBecauseStylesAreRandom()
	{
		KillLog log = fireMajorityThenMagicOpener();

		assertEquals(Optional.of(HiddenReason.CONTRACT),
			projection.project(log, upstream(log, TestContext.under(Contract.SHARD_ACQUISITION, 47))).hiddenReason());
	}

	@Test
	public void hiddenGlyphsHideTheOpenerButAHiddenPrayerReviewOnlyDropsYourFirstResult()
	{
		KillLog log = fireMajorityThenMagicOpener();
		ProjectionContext noGlyphs = upstream(log, TestContext.withPhases(47));
		noGlyphs.put(Sections.GLYPHS, Section.<GlyphCount>hidden(HiddenReason.IDS_NOT_CAPTURED));
		ProjectionContext noReview = upstream(log, TestContext.withPhases(47));
		noReview.put(Sections.PRAYER_REVIEW, Section.<PrayerReview>hidden(HiddenReason.ERROR));

		assertEquals(Optional.of(HiddenReason.IDS_NOT_CAPTURED), projection.project(log, noGlyphs).hiddenReason());
		Opener opener = projection.project(log, noReview).value();
		assertEquals(Boolean.TRUE, opener.getAsExpected());
		assertNull(opener.getFirstOwnResult());
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.OpenerProjectionTest'`
Expected: FAIL — `cannot find symbol: class OpenerProjection`.

- [ ] **Step 3: Write `OpenerProjection`**

`src/main/java/com/yamareviewer/domain/projection/OpenerProjection.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackResult;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.GlyphCount;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Opener;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.PrayerReview;
import com.yamareviewer.domain.review.Section;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The P3 opener (spec 6.5): the glyph majority of P2 against the style of Yama's first P3 attack on anyone,
 * plus the outcome of your first P3 attack. Hidden under a contract with random attack styles.
 */
public final class OpenerProjection implements Projection<Opener>
{
	@Override
	public SectionKey<Opener> key()
	{
		return Sections.OPENER;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED);
	}

	@Override
	public Section<Opener> project(KillLog log, ProjectionContext context)
	{
		if (context.contractRules().isRandomAttackStyles())
		{
			return Section.hidden(HiddenReason.CONTRACT);
		}
		Section<GlyphCount> glyphs = context.section(Sections.GLYPHS);
		Section<PhaseTimes> phases = context.section(Sections.PHASES);
		Section<AttackTimeline> attacks = context.section(Sections.ATTACKS);
		Optional<HiddenReason> hidden = Upstream.hidden(glyphs, phases, attacks);
		if (hidden.isPresent())
		{
			return Section.hidden(hidden.get());
		}
		Style expected = glyphs.value().expectedOpener().orElse(null);
		List<Attack> p3 = attacks.value().inPhase(Phase.P3);
		Attack first = p3.isEmpty() ? null : p3.get(0);
		Boolean asExpected = expected == null || first == null || first.getStyle() == null ? null : first.getStyle() == expected;
		AttackResult firstOwn = context.value(Sections.PRAYER_REVIEW)
			.map(PrayerReview::getResults)
			.filter(results -> !results.isEmpty())
			.map(results -> results.get(0))
			.orElse(null);
		return Section.ok(new Opener(glyphs.value(), expected, first, asExpected, firstOwn));
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.OpenerProjectionTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/projection/OpenerProjection.java src/test/java/com/yamareviewer/domain/projection/OpenerProjectionTest.java
git commit -m "feat: review the P3 opener against the P2 glyph majority"
```

---

### Task 10: Tick log projection

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/projection/TickLogProjection.java`
- Test: `src/test/java/com/yamareviewer/domain/projection/TickLogProjectionTest.java`

**Interfaces:**
- Consumes: `AttackTimeline`, `TickLog`, `TickLogEntry` (Task 2), `Rules.getP3AttackCycle()` (Part 1), `AnimationObserved`, `GraphicObserved`, `GroundGraphicObserved`, `IdRegistry` (Part 1), `Upstream` (Task 5).
- Produces: `TickLogProjection` (key `TICK_LOG`, requires `YAMA` and `YAMA_STANDARD_ATTACK`; hides with the reason of hidden `PHASES` or `ATTACKS`). Annotation labels `"crash line"`, `"wave"`, `"flare summon"`, `"melee"`, each formatted `"<label> at <tick>"`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/domain/projection/TickLogProjectionTest.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.TickLog;
import com.yamareviewer.domain.review.TickLogEntry;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import com.yamareviewer.testing.TestIds;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TickLogProjectionTest
{
	private final TickLogProjection projection = new TickLogProjection();

	private TickLog tickLog(KillLog log, ProjectionContext context)
	{
		context.put(Sections.ATTACKS, new AttacksProjection().project(log, context));
		return projection.project(log, context).value();
	}

	@Test
	public void keyAndRoles()
	{
		assertEquals(Sections.TICK_LOG, projection.key());
		assertEquals(Set.of(Role.YAMA, Role.YAMA_STANDARD_ATTACK), projection.requiredRoles());
	}

	@Test
	public void logsEveryP3AttackWithItsGap()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(7).yamaCasts(Style.RANGED).ticks(9).yamaCasts(Style.MAGIC).ticks(2)
			.end(EndReason.YAMA_DIED);

		TickLog tickLog = tickLog(log, TestContext.withPhases(58));

		assertEquals(List.of(40, 47, 56), tickLog.getEntries().stream().map(TickLogEntry::getTick).collect(Collectors.toList()));
		assertEquals(Arrays.asList(null, 7, 9), tickLog.getEntries().stream().map(TickLogEntry::getGap).collect(Collectors.toList()));
		assertEquals(List.of(Style.MAGIC, Style.RANGED, Style.MAGIC), tickLog.getEntries().stream().map(TickLogEntry::getStyle).collect(Collectors.toList()));
		assertEquals(Actor.SELF, tickLog.getEntries().get(0).getTarget());
		assertEquals(7, tickLog.getCycle());
		assertEquals(Optional.of(0.5), tickLog.cycleShare());
	}

	@Test
	public void offCycleGapsAreAnnotatedWithTheEventsInBetween()
	{
		KillLogBuilder kill = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(3);
		kill.crashLine(3200, 3205).ticks(2);
		kill.waveOn(Actor.SELF).waveAt(3200, 3210).ticks(1);
		kill.yamaAnimates(TestIds.id(Role.YAMA_FLARE_SUMMON)).ticks(1);
		kill.yamaAnimates(TestIds.id(Role.YAMA_MELEE)).ticks(2);
		kill.yamaCasts(Style.RANGED).ticks(1);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		TickLog tickLog = tickLog(log, TestContext.withPhases(50));

		assertEquals(Integer.valueOf(9), tickLog.getEntries().get(1).getGap());
		assertEquals(List.of("crash line at 43", "wave at 45", "flare summon at 46", "melee at 47"), tickLog.getEntries().get(1).getAnnotations());
	}

	@Test
	public void gapsOnTheCycleOrAMultipleOfItAreNotAnnotated()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(3).waveOn(Actor.SELF).ticks(4).yamaCasts(Style.RANGED).ticks(7)
			.waveOn(Actor.SELF).ticks(7).yamaCasts(Style.MAGIC).ticks(1).end(EndReason.YAMA_DIED);

		TickLog tickLog = tickLog(log, TestContext.withPhases(62));

		assertEquals(Arrays.asList(null, 7, 14), tickLog.getEntries().stream().map(TickLogEntry::getGap).collect(Collectors.toList()));
		assertEquals(List.of(), tickLog.getEntries().get(1).getAnnotations());
		assertEquals(List.of(), tickLog.getEntries().get(2).getAnnotations());
		assertEquals(1, tickLog.gapsOnCycle());
	}

	@Test
	public void usesTheConfiguredCycle()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(8).yamaCasts(Style.RANGED).ticks(1).end(EndReason.YAMA_DIED);
		Rules eight = Rules.DEFAULT.toBuilder().p3AttackCycle(8).build();

		assertEquals(1, tickLog(log, TestContext.withPhases(eight, 49)).gapsOnCycle());
		assertEquals(0, tickLog(log, TestContext.withPhases(49)).gapsOnCycle());
	}

	@Test
	public void attacksOutsideP3AreNotLogged()
	{
		KillLog log = KillLogBuilder.kill().ticks(3).yamaCasts(Style.MAGIC).ticks(37).yamaCasts(Style.RANGED).ticks(1).end(EndReason.YAMA_DIED);

		TickLog tickLog = tickLog(log, TestContext.withPhases(41));

		assertEquals(List.of(40), tickLog.getEntries().stream().map(TickLogEntry::getTick).collect(Collectors.toList()));
	}

	@Test
	public void hiddenAttacksHideTheLogWithTheSameReason()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(1).end(EndReason.YAMA_DIED);
		ProjectionContext context = TestContext.withPhases(41);
		context.put(Sections.ATTACKS, Section.<AttackTimeline>hidden(HiddenReason.ERROR));

		assertEquals(Optional.of(HiddenReason.ERROR), projection.project(log, context).hiddenReason());
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.TickLogProjectionTest'`
Expected: FAIL — `cannot find symbol: class TickLogProjection`.

- [ ] **Step 3: Write `TickLogProjection`**

`src/main/java/com/yamareviewer/domain/projection/TickLogProjection.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.TickLog;
import com.yamareviewer.domain.review.TickLogEntry;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every P3 attack with the gap from the previous one (spec 6.5). A gap that is not a multiple of the
 * P3 attack cycle is annotated with the crash, wave, flare-summon and melee events between the two
 * attacks. It shows what was observed and gives no advice (spec 2, rule 4).
 */
public final class TickLogProjection implements Projection<TickLog>
{
	static final String CRASH_LINE = "crash line";
	static final String WAVE = "wave";
	static final String FLARE_SUMMON = "flare summon";
	static final String MELEE = "melee";

	@Override
	public SectionKey<TickLog> key()
	{
		return Sections.TICK_LOG;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.YAMA, Role.YAMA_STANDARD_ATTACK);
	}

	@Override
	public Section<TickLog> project(KillLog log, ProjectionContext context)
	{
		Section<PhaseTimes> phases = context.section(Sections.PHASES);
		Section<AttackTimeline> attacks = context.section(Sections.ATTACKS);
		Optional<HiddenReason> hidden = Upstream.hidden(phases, attacks);
		if (hidden.isPresent())
		{
			return Section.hidden(hidden.get());
		}
		int cycle = Math.max(1, context.rules().getP3AttackCycle());
		TreeMap<Integer, Set<String>> events = annotationEvents(log, context.ids());

		List<TickLogEntry> entries = new ArrayList<>();
		Integer previous = null;
		for (Attack attack : attacks.value().inPhase(Phase.P3))
		{
			Integer gap = previous == null ? null : attack.getCastTick() - previous;
			List<String> annotations = new ArrayList<>();
			if (gap != null && gap % cycle != 0)
			{
				for (Map.Entry<Integer, Set<String>> entry : events.subMap(previous, false, attack.getCastTick(), false).entrySet())
				{
					for (String label : entry.getValue())
					{
						annotations.add(label + " at " + entry.getKey());
					}
				}
			}
			entries.add(new TickLogEntry(attack.getCastTick(), attack.getStyle(), attack.getTarget(), gap, annotations));
			previous = attack.getCastTick();
		}
		return Section.ok(new TickLog(entries, cycle));
	}

	/** One label per kind per tick, in the order crash line, wave, flare summon, melee. */
	private static TreeMap<Integer, Set<String>> annotationEvents(KillLog log, IdRegistry ids)
	{
		TreeMap<Integer, Set<String>> events = new TreeMap<>();
		for (GroundGraphicObserved graphic : log.eventsOf(GroundGraphicObserved.class))
		{
			if (ids.is(Role.CRASH_FIREBALL, graphic.getGraphicId()))
			{
				add(events, graphic.getTick(), CRASH_LINE);
			}
		}
		for (GroundGraphicObserved graphic : log.eventsOf(GroundGraphicObserved.class))
		{
			if (ids.is(Role.SHADOW_WAVE, graphic.getGraphicId()))
			{
				add(events, graphic.getTick(), WAVE);
			}
		}
		for (GraphicObserved graphic : log.eventsOf(GraphicObserved.class))
		{
			if (graphic.getActor().isPlayer() && ids.is(Role.SHADOW_WAVE, graphic.getGraphicId()))
			{
				add(events, graphic.getTick(), WAVE);
			}
		}
		for (AnimationObserved animation : log.eventsOf(AnimationObserved.class))
		{
			if (!Actor.YAMA.equals(animation.getActor()))
			{
				continue;
			}
			if (ids.is(Role.YAMA_FLARE_SUMMON, animation.getAnimationId()))
			{
				add(events, animation.getTick(), FLARE_SUMMON);
			}
			else if (ids.is(Role.YAMA_MELEE, animation.getAnimationId()))
			{
				add(events, animation.getTick(), MELEE);
			}
		}
		return events;
	}

	private static void add(TreeMap<Integer, Set<String>> events, int tick, String label)
	{
		events.computeIfAbsent(tick, key -> new LinkedHashSet<>()).add(label);
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.TickLogProjectionTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/projection/TickLogProjection.java src/test/java/com/yamareviewer/domain/projection/TickLogProjectionTest.java
git commit -m "feat: log every P3 attack with its gap and the events in between"
```

---
