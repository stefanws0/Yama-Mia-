# Yama Reviewer Part 2: Review v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After every recorded Yama kill, build a review from the raw event stream (phases, contract, mode, void flares, damage taken by source, supplies and cost, death recap), store it, keep a history per mode and contract, and show it in chat and a side panel, all of it only after the kill has ended.

**Architecture:** The read side of the Part 1 event stream. `domain.projection` turns a `KillLog` into sections through an ordered list of `Projection`s that read each other's results from a `ProjectionContext`; `KillReviewAssembler` freezes them into an immutable `KillReview`. `domain.history` derives averages, personal bests and trends from stored reviews, `domain.text` renders reviews to strings, and `application.handler.KillEndedHandler` runs the whole pipeline on the plugin executor and hands the result to the `ReviewPublisher` port. Adapters: `GsonReviewRepository` (files), `ChatReviewPublisher` (chat), `PanelReviewPublisher` + `ReviewPanel` (Swing). The root plugin only wires and attaches.

**Tech Stack:** Java 11 (release target), Gradle from the RuneLite example-plugin template, RuneLite client API (`latest.release`: `PluginPanel`, `NavigationButton`, `ClientToolbar`, `ChatMessageManager`, `QueuedMessage`, `ColorScheme`), Lombok, Gson (RuneLite's injected instance), JUnit 4.12, Mockito 5, ArchUnit 1.3.

**Spec:** `docs/superpowers/specs/2026-09-24-yama-reviewer-design.md`, sections 4, 5.3, 5.4, 6.1–6.3, 6.8, 6.10–6.12, 7, 10, 11, 12 and 13 (part 2). Every Part 1 type is defined in `docs/superpowers/plans/2026-09-24-yama-reviewer-part1-foundation.md` and used unchanged here. The class names, packages, fields and signatures in this plan's **Interfaces** blocks are binding for Parts 3 and 4 (they were agreed in the Parts 2–4 interface brief): never rename them.

## Global Constraints

- Everything in the Part 1 plan's Global Constraints still holds: Java 11 (`options.release.set(11)`), root package `com.yamareviewer`, `@PluginDescriptor(internalName = "yama-reviewer")`, config group `yamareviewer`, tabs and Allman braces, Lombok `@Value`, no reflection in main code, all file I/O through `Filepath` (`Filepath.Unchecked`, `java.io.File` and `java.nio.file.Files` only in tests), the injected `Gson` in main code, `log.debug` for per-event logging.
- Java 11 only: no records, switch expressions, text blocks, `Stream.toList()` or pattern matching; `List.of`, `Map.of`, `Optional.isEmpty` and `String.isBlank` are fine.
- Nothing is drawn, printed or played during a fight. Chat lines and panel updates come only from `KillEndedHandler` after `FightEnded`, and only while the plugin's `active` flag is set. `HistoryLoader` may fill the panel at start-up.
- Layering (enforced by `ArchitectureTest`, which must keep passing unchanged): `domain` imports only the JDK, Lombok and slf4j; `application` never imports `adapter`, `net.runelite`, `com.google`, Swing or AWT; only `adapter.recording` sees `net.runelite.api.events`; only `adapter.publish` depends on `ChatMessageManager`; only `adapter.ui` and the root package depend on `javax.swing` and `net.runelite.client.ui`; `adapter.recording`, `application.command` and `domain` never depend on `adapter.publish`, `adapter.ui` or `ReviewPublisher`.
- A review is a pure function of `KillLog`, `IdRegistry`, `Rules` and `ReviewSettings`. Prices come from the log's supplies snapshots; no projection reads the client, the clock or a file.
- An exception inside a projection hides that section with `ERROR` and is logged; it never reaches the client thread.
- No player name reaches `KillReview`, the chat lines, the clipboard text or the panel: the players are "you" and "partner".
- Stored reviews: `reviews/<MODE>/<13-digit startEpochMs>-<killId>.json` with `reviewSchemaVersion` (`KillReview.SCHEMA_VERSION = 1`); files of another version are skipped, unreadable files are renamed `.corrupt`; the newest `historySize` (default 50, range 10–500) per mode and contract are kept. Raw logs stay as Part 1 wrote them.
- Chat lines are `GAMEMESSAGE`s through `ChatMessageManager.queue` (thread-safe); each of the four lines has its own config key; a hidden value prints `n/a`; the death recap block is off by default.
- Config keys are never renamed: `chatPhaseLine`, `chatPrayerLine`, `chatFlareLine`, `chatSpecLine` (default on), `chatDeathRecap` (off), `priceSource` (`GRAND_EXCHANGE`), `modeOverride` (`AUTO`), `historySize` (50), `rawLogsKept` (20), `captureMode` (off).
- Tests: JUnit 4.12, Mockito 5, ArchUnit; every projection test builds its stream with `KillLogBuilder` and IDs from `TestIds`; `new Gson()` only in tests. Nothing in this plan automates game input; the in-game check is done by the user.

## Review Focus

1. A player who appears only after the first Judge phase has ended (a spectator, a late joiner) must not turn a Travel kill into a duo: `ModeProjectionTest.aPlayerSeenAfterTheFirstJudgeEndedIsNotAPartner` (Task 7).
2. A scene reload inside a Judge phase, where the Judge and the flares despawn and respawn while the game state is `LOADING`, must neither end the Judge phase early nor count a second flare: `PhasesProjectionTest.aSceneReloadInsideAJudgePhaseIsIgnored` (Task 6), `FlaresProjectionTest.aSceneReloadDoesNotRespawnAFlare` (Task 8).
3. Potion doses that end the fight in a variant the START snapshot never had (`Super restore(4)` drunk down to `Super restore(2)`) must count as remaining doses, not as fully drunk: `SuppliesProjectionTest.endOnlyPotionVariantsCountAsRemainingDoses` (Task 10).
4. A review file of another schema version or a corrupt file in `reviews/` must not stop the history from loading, and a `LEFT` kill without an `END` snapshot must hide supplies rather than price the whole inventory as used: `GsonReviewRepositoryTest.otherSchemaVersionsAreSkippedButKept` and `corruptFileIsRenamedAndSkipped` (Task 12), `SuppliesProjectionTest.aLeftKillWithoutAnEndSnapshotHidesSupplies` (Task 10).
5. Disabling the plugin between the end of the kill and the end of the review build must print nothing, and a hidden section must print `n/a` instead of throwing while the chat lines are built: `KillEndedHandlerTest.nothingIsPublishedWhenThePluginIsInactive` (Task 15), `ChatLinesTest.hiddenValuesPrintAsNotAvailable` (Task 14).

---

## File Structure

```
build.gradle                                     add the `replay` task; version 0.2.0
src/main/java/com/yamareviewer/
  YamaReviewerPlugin.java                        composition root (replaced): panel, publishers, handler, history loader, active flag
  YamaReviewerConfig.java                        config (replaced): chat, review and development sections
  ModeOverride.java                              AUTO / SOLO / DUO_HOST / DUO_JOINER for the config
  domain/model/                                  Mode, Phase, Style, Contract (Part 1: KillHeader, KillLog)
  domain/contract/ContractRules.java             per-contract values of spec 6.3.1
  domain/review/                                 HiddenReason, ReviewStatus, Section, the section result types, KillReview
  domain/projection/                             SectionKey, Sections, ReviewSettings, Projection, ProjectionContext, ReviewBuilder,
                                                 KillReviewAssembler, Projections, one class per projection
  domain/history/                                HistoryKey, KillSummary, Trend, HistoryView, HistoryIndex, HistoryProjector
  domain/text/                                   Formats, ViewSection, ReviewView, ReviewFormatter, ChatLineOptions, ChatLines,
                                                 ClipboardExport, HistoryFormatter
  application/port/                              ReviewRepository, ReviewPublisher (Part 1: LogRepository, SnapshotSource)
  application/handler/                           KillEndedHandler (replaced), HistoryLoader
  adapter/persistence/GsonReviewRepository.java  reviews/<MODE>/<startEpochMs>-<killId>.json
  adapter/publish/                               CompositeReviewPublisher, ChatReviewPublisher, ChatReviewPublisherFactory, PanelReviewPublisher
  adapter/ui/                                    ReviewPanel, ReviewTab, HistoryTab, TextLine, PanelIcon, SystemClipboard, KillReviewOpener
src/test/java/com/yamareviewer/
  testing/TestIds.java, KillLogBuilder.java, Reviews.java   the test DSL and a sample review
  tools/Replay.java                              `./gradlew replay --args="<raw log> [--json]"`
  SilenceReplayTest.java                         spec 4.4: nothing published before the kill ends, one review after
  domain/…, application/…, adapter/… tests mirroring main
```

---
### Task 1: Review model: Mode, Phase, Style, Contract and Section

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/model/Mode.java`, `Phase.java`, `Style.java`, `Contract.java`
- Create: `src/main/java/com/yamareviewer/domain/review/HiddenReason.java`, `ReviewStatus.java`, `Section.java`
- Test: `src/test/java/com/yamareviewer/domain/model/ContractTest.java`, `src/test/java/com/yamareviewer/domain/review/SectionTest.java`

**Interfaces:**
- Consumes: `Role` (Part 1 Task 3).
- Produces: `Mode { SOLO, DUO_HOST, DUO_JOINER }` with `label()`; `Phase { P1, JUDGE_1, P2, JUDGE_2, P3 }` with `label()`, `isJudge()`; `Style { MAGIC, RANGED }`; `Contract` with `shortName()`, `itemRole()`, `isContract()`, `displayName()`, `static Optional<Contract> fromWidgetText(String)`; `HiddenReason { IDS_NOT_CAPTURED, NOT_APPLICABLE, CONTRACT, HEALTH_CHECK_FAILED, ERROR }` with `label()`; `ReviewStatus { COMPLETE, INCOMPLETE }`; `Section<T>` with `ok(T)`, `hidden(HiddenReason)`, `isOk()`, `value()`, `asOptional()`, `hiddenReason()`, `map(Function)`, `equals`/`hashCode`, Gson fields `value` and `hidden`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/model/ContractTest.java`:

```java
package com.yamareviewer.domain.model;

import com.yamareviewer.domain.ids.Role;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ContractTest
{
	@Test
	public void widgetTextMatchesTheShortNameIgnoringCase()
	{
		assertEquals(Optional.of(Contract.BLOODIED_BLOWS), Contract.fromWidgetText("Contract of Bloodied Blows"));
		assertEquals(Optional.of(Contract.FORFEIT_BREATH), Contract.fromWidgetText("CONTRACT OF FORFEIT BREATH"));
		assertEquals(Optional.of(Contract.HARMONY_ACQUISITION), Contract.fromWidgetText("harmony acquisition"));
		assertEquals(Optional.empty(), Contract.fromWidgetText("Yama"));
		assertEquals(Optional.empty(), Contract.fromWidgetText(""));
		assertEquals(Optional.empty(), Contract.fromWidgetText(null));
	}

	@Test
	public void everyRealContractHasAShortNameAndAnItemRole()
	{
		for (Contract contract : Contract.values())
		{
			if (contract == Contract.NONE || contract == Contract.UNKNOWN_CONTRACT)
			{
				assertNull(contract.shortName());
				assertNull(contract.itemRole());
			}
			else
			{
				assertTrue(contract.name(), Role.CONTRACT_ITEMS.contains(contract.itemRole()));
				assertEquals(Optional.of(contract), Contract.fromWidgetText("Contract of " + contract.shortName()));
			}
		}
	}

	@Test
	public void onlyNoneIsNotAContract()
	{
		assertFalse(Contract.NONE.isContract());
		assertTrue(Contract.UNKNOWN_CONTRACT.isContract());
		assertTrue(Contract.SHARD_ACQUISITION.isContract());
	}

	@Test
	public void displayNames()
	{
		assertEquals("No contract", Contract.NONE.displayName());
		assertEquals("Unknown contract", Contract.UNKNOWN_CONTRACT.displayName());
		assertEquals("Contract of Divine Severance", Contract.DIVINE_SEVERANCE.displayName());
	}

	@Test
	public void labelsForChatAndPanel()
	{
		assertEquals("duo host", Mode.DUO_HOST.label());
		assertEquals("Judge 1", Phase.JUDGE_1.label());
		assertTrue(Phase.JUDGE_2.isJudge());
		assertFalse(Phase.P3.isJudge());
	}
}
```

`src/test/java/com/yamareviewer/domain/review/SectionTest.java`:

```java
package com.yamareviewer.domain.review;

import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SectionTest
{
	@Test
	public void okCarriesItsValue()
	{
		Section<Integer> section = Section.ok(42);

		assertTrue(section.isOk());
		assertEquals(Integer.valueOf(42), section.value());
		assertEquals(Optional.of(42), section.asOptional());
		assertEquals(Optional.empty(), section.hiddenReason());
	}

	@Test
	public void hiddenCarriesItsReason()
	{
		Section<Integer> section = Section.hidden(HiddenReason.IDS_NOT_CAPTURED);

		assertFalse(section.isOk());
		assertEquals(Optional.empty(), section.asOptional());
		assertEquals(Optional.of(HiddenReason.IDS_NOT_CAPTURED), section.hiddenReason());
	}

	@Test(expected = IllegalStateException.class)
	public void aHiddenSectionHasNoValue()
	{
		Section.hidden(HiddenReason.ERROR).value();
	}

	@Test(expected = NullPointerException.class)
	public void okRejectsNull()
	{
		Section.ok(null);
	}

	@Test
	public void mapKeepsTheHiddenReason()
	{
		assertEquals(Section.ok("42"), Section.ok(42).map(String::valueOf));
		assertEquals(Section.<String>hidden(HiddenReason.CONTRACT), Section.<Integer>hidden(HiddenReason.CONTRACT).map(String::valueOf));
	}

	@Test
	public void equalityIsByValueAndReason()
	{
		assertEquals(Section.ok(1), Section.ok(1));
		assertNotEquals(Section.ok(1), Section.ok(2));
		assertEquals(Section.hidden(HiddenReason.ERROR), Section.hidden(HiddenReason.ERROR));
		assertNotEquals(Section.hidden(HiddenReason.ERROR), Section.hidden(HiddenReason.NOT_APPLICABLE));
		assertEquals(Section.ok(1).hashCode(), Section.ok(1).hashCode());
	}

	@Test
	public void reasonLabelsAreReadable()
	{
		assertEquals("IDs not captured", HiddenReason.IDS_NOT_CAPTURED.label());
		assertEquals("Not meaningful under this contract", HiddenReason.CONTRACT.label());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.model.ContractTest' --tests 'com.yamareviewer.domain.review.SectionTest'`
Expected: FAIL — `cannot find symbol` for `Contract`, `Mode`, `Phase`, `Section`, `HiddenReason`.

- [ ] **Step 3: Write the model enums**

`src/main/java/com/yamareviewer/domain/model/Mode.java`:

```java
package com.yamareviewer.domain.model;

/** How the kill was done; the first half of a history key (spec 5.4). */
public enum Mode
{
	SOLO("solo"),
	DUO_HOST("duo host"),
	DUO_JOINER("duo joiner");

	private final String label;

	Mode(String label)
	{
		this.label = label;
	}

	public String label()
	{
		return label;
	}
}
```

`src/main/java/com/yamareviewer/domain/model/Phase.java`:

```java
package com.yamareviewer.domain.model;

/** The five phases of a kill, in order (spec 3). */
public enum Phase
{
	P1("P1"),
	JUDGE_1("Judge 1"),
	P2("P2"),
	JUDGE_2("Judge 2"),
	P3("P3");

	private final String label;

	Phase(String label)
	{
		this.label = label;
	}

	public String label()
	{
		return label;
	}

	public boolean isJudge()
	{
		return this == JUDGE_1 || this == JUDGE_2;
	}
}
```

`src/main/java/com/yamareviewer/domain/model/Style.java`:

```java
package com.yamareviewer.domain.model;

/** Yama's standard attack styles (used by Part 3). */
public enum Style
{
	MAGIC,
	RANGED
}
```

`src/main/java/com/yamareviewer/domain/model/Contract.java`:

```java
package com.yamareviewer.domain.model;

import com.yamareviewer.domain.ids.Role;
import java.util.Locale;
import java.util.Optional;

/** The contracts of spec 6.3.1, plus NONE and the unknown contract detected from the attack cycle. */
public enum Contract
{
	NONE(null, null),
	UNKNOWN_CONTRACT(null, null),
	FORFEIT_BREATH("Forfeit Breath", Role.CONTRACT_ITEM_FORFEIT_BREATH),
	GLYPHIC_ATTENUATION("Glyphic Attenuation", Role.CONTRACT_ITEM_GLYPHIC_ATTENUATION),
	SENSORY_CLOUDING("Sensory Clouding", Role.CONTRACT_ITEM_SENSORY_CLOUDING),
	DIVINE_SEVERANCE("Divine Severance", Role.CONTRACT_ITEM_DIVINE_SEVERANCE),
	BLOODIED_BLOWS("Bloodied Blows", Role.CONTRACT_ITEM_BLOODIED_BLOWS),
	FAMILIAR_ACQUISITION("Familiar Acquisition", Role.CONTRACT_ITEM_FAMILIAR),
	CATALYST_ACQUISITION("Catalyst Acquisition", Role.CONTRACT_ITEM_CATALYST),
	WORM_ACQUISITION("Worm Acquisition", Role.CONTRACT_ITEM_WORM),
	SHARD_ACQUISITION("Shard Acquisition", Role.CONTRACT_ITEM_SHARD),
	OATHPLATE_ACQUISITION("Oathplate Acquisition", Role.CONTRACT_ITEM_OATHPLATE),
	HARMONY_ACQUISITION("Harmony Acquisition", Role.CONTRACT_ITEM_HARMONY);

	private final String shortName;
	private final Role itemRole;

	Contract(String shortName, Role itemRole)
	{
		this.shortName = shortName;
		this.itemRole = itemRole;
	}

	/** Null for NONE and UNKNOWN_CONTRACT. */
	public String shortName()
	{
		return shortName;
	}

	/** Null for NONE and UNKNOWN_CONTRACT. */
	public Role itemRole()
	{
		return itemRole;
	}

	/** False only for NONE. */
	public boolean isContract()
	{
		return this != NONE;
	}

	public String displayName()
	{
		if (this == NONE)
		{
			return "No contract";
		}
		if (this == UNKNOWN_CONTRACT)
		{
			return "Unknown contract";
		}
		return "Contract of " + shortName;
	}

	/** Case-insensitive: the first contract whose shortName occurs in the text (tags already removed). */
	public static Optional<Contract> fromWidgetText(String text)
	{
		if (text == null)
		{
			return Optional.empty();
		}
		String lower = text.toLowerCase(Locale.ROOT);
		for (Contract contract : values())
		{
			if (contract.shortName != null && lower.contains(contract.shortName.toLowerCase(Locale.ROOT)))
			{
				return Optional.of(contract);
			}
		}
		return Optional.empty();
	}
}
```

- [ ] **Step 4: Write the review enums and `Section`**

`src/main/java/com/yamareviewer/domain/review/HiddenReason.java`:

```java
package com.yamareviewer.domain.review;

/** Why a section is not shown (spec 6.1). Only HEALTH_CHECK_FAILED and ERROR make a review incomplete. */
public enum HiddenReason
{
	IDS_NOT_CAPTURED("IDs not captured"),
	NOT_APPLICABLE("Not applicable"),
	CONTRACT("Not meaningful under this contract"),
	HEALTH_CHECK_FAILED("Health check failed"),
	ERROR("Error while reviewing");

	private final String label;

	HiddenReason(String label)
	{
		this.label = label;
	}

	public String label()
	{
		return label;
	}
}
```

`src/main/java/com/yamareviewer/domain/review/ReviewStatus.java`:

```java
package com.yamareviewer.domain.review;

public enum ReviewStatus
{
	COMPLETE,
	INCOMPLETE
}
```

`src/main/java/com/yamareviewer/domain/review/Section.java`:

```java
package com.yamareviewer.domain.review;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * A review section: a value, or the reason it is hidden. Gson-serialisable through its two fields
 * ({@code value}, {@code hidden}); a section read back with neither is treated as hidden with ERROR.
 */
public final class Section<T>
{
	private final T value;
	private final HiddenReason hidden;

	private Section(T value, HiddenReason hidden)
	{
		this.value = value;
		this.hidden = hidden;
	}

	public static <T> Section<T> ok(T value)
	{
		return new Section<>(Objects.requireNonNull(value, "value"), null);
	}

	public static <T> Section<T> hidden(HiddenReason reason)
	{
		return new Section<>(null, Objects.requireNonNull(reason, "reason"));
	}

	public boolean isOk()
	{
		return hidden == null && value != null;
	}

	/** @throws IllegalStateException when hidden */
	public T value()
	{
		if (!isOk())
		{
			throw new IllegalStateException("Section is hidden: " + hiddenReason().map(HiddenReason::name).orElse("?"));
		}
		return value;
	}

	public Optional<T> asOptional()
	{
		return isOk() ? Optional.of(value) : Optional.empty();
	}

	public Optional<HiddenReason> hiddenReason()
	{
		if (isOk())
		{
			return Optional.empty();
		}
		return Optional.of(hidden == null ? HiddenReason.ERROR : hidden);
	}

	public <R> Section<R> map(Function<? super T, ? extends R> mapper)
	{
		return isOk() ? Section.<R>ok(mapper.apply(value)) : Section.<R>hidden(hiddenReason().get());
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof Section))
		{
			return false;
		}
		Section<?> that = (Section<?>) other;
		return Objects.equals(value, that.value) && hidden == that.hidden;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(value, hidden);
	}

	@Override
	public String toString()
	{
		return isOk() ? "Section.ok(" + value + ")" : "Section.hidden(" + hiddenReason().get() + ")";
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.model.ContractTest' --tests 'com.yamareviewer.domain.review.SectionTest'`
Expected: PASS (12 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/model src/main/java/com/yamareviewer/domain/review src/test/java/com/yamareviewer/domain/model src/test/java/com/yamareviewer/domain/review
git commit -m "feat: add the review model enums, contracts and Section"
```

---
### Task 2: ContractRules

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/contract/ContractRules.java`
- Test: `src/test/java/com/yamareviewer/domain/contract/ContractRulesTest.java`

**Interfaces:**
- Consumes: `Contract` (Task 1), `Rules` (Part 1 Task 3).
- Produces: `ContractRules` (`@Value @Builder(toBuilder = true)`) with fields `yamaHp`, `baseDefence`, `baseMagic`, `p1p2AttackCycle`, `Integer blockedMaxHit` (null = prayer penetration changed), `soloOnly`, `randomAttackStyles`, `crashInEveryPhase`, `p3CrashSets`, `consumablesHaveNoEffect`, `specsAlwaysHit`, `runEnergyDeath`, `shadowPools`, `prayerAutoOffTicks`; `static ContractRules of(Contract, Rules)`; `ContractRules.CONTRACT_P1P2_ATTACK_CYCLE = 7`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/domain/contract/ContractRulesTest.java`:

```java
package com.yamareviewer.domain.contract;

import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ContractRulesTest
{
	private static ContractRules of(Contract contract)
	{
		return ContractRules.of(contract, Rules.DEFAULT);
	}

	@Test
	public void noContractUsesTheNormalFormValues()
	{
		ContractRules rules = of(Contract.NONE);

		assertEquals(2500, rules.getYamaHp());
		assertEquals(225, rules.getBaseDefence());
		assertEquals(250, rules.getBaseMagic());
		assertEquals(8, rules.getP1p2AttackCycle());
		assertEquals(Integer.valueOf(3), rules.getBlockedMaxHit());
		assertEquals(3, rules.getP3CrashSets());
		assertEquals(0, rules.getPrayerAutoOffTicks());
		assertFalse(rules.isSoloOnly());
		assertFalse(rules.isRandomAttackStyles());
		assertFalse(rules.isCrashInEveryPhase());
		assertFalse(rules.isConsumablesHaveNoEffect());
		assertFalse(rules.isSpecsAlwaysHit());
		assertFalse(rules.isRunEnergyDeath());
		assertFalse(rules.isShadowPools());
	}

	@Test
	public void everyContractSharesTheCommonRules()
	{
		for (Contract contract : Contract.values())
		{
			if (!contract.isContract())
			{
				continue;
			}
			ContractRules rules = of(contract);
			assertEquals(contract.name(), 247, rules.getBaseDefence());
			assertEquals(contract.name(), 275, rules.getBaseMagic());
			assertEquals(contract.name(), 7, rules.getP1p2AttackCycle());
		}
	}

	@Test
	public void theUnknownContractHasOnlyTheCommonRules()
	{
		ContractRules rules = of(Contract.UNKNOWN_CONTRACT);

		assertEquals(2500, rules.getYamaHp());
		assertEquals(Integer.valueOf(3), rules.getBlockedMaxHit());
		assertEquals(3, rules.getP3CrashSets());
		assertFalse(rules.isSoloOnly());
		assertFalse(rules.isCrashInEveryPhase());
	}

	@Test
	public void theContractTableOfSpec631()
	{
		assertTrue(of(Contract.FORFEIT_BREATH).isSoloOnly());
		assertTrue(of(Contract.FORFEIT_BREATH).isRunEnergyDeath());

		assertEquals(2875, of(Contract.GLYPHIC_ATTENUATION).getYamaHp());
		assertTrue(of(Contract.GLYPHIC_ATTENUATION).isSoloOnly());

		assertNull(of(Contract.SENSORY_CLOUDING).getBlockedMaxHit());
		assertTrue(of(Contract.SENSORY_CLOUDING).isSoloOnly());

		assertEquals(Integer.valueOf(0), of(Contract.DIVINE_SEVERANCE).getBlockedMaxHit());
		assertEquals(2, of(Contract.DIVINE_SEVERANCE).getPrayerAutoOffTicks());
		assertTrue(of(Contract.DIVINE_SEVERANCE).isSoloOnly());

		assertEquals(3000, of(Contract.BLOODIED_BLOWS).getYamaHp());
		assertNull(of(Contract.BLOODIED_BLOWS).getBlockedMaxHit());
		assertTrue(of(Contract.BLOODIED_BLOWS).isSoloOnly());

		assertFalse(of(Contract.FAMILIAR_ACQUISITION).isSoloOnly());
		assertNull(of(Contract.FAMILIAR_ACQUISITION).getBlockedMaxHit());
		assertTrue(of(Contract.FAMILIAR_ACQUISITION).isConsumablesHaveNoEffect());
		assertTrue(of(Contract.FAMILIAR_ACQUISITION).isSpecsAlwaysHit());

		for (Contract acquisition : new Contract[]{Contract.CATALYST_ACQUISITION, Contract.WORM_ACQUISITION, Contract.SHARD_ACQUISITION,
			Contract.OATHPLATE_ACQUISITION, Contract.HARMONY_ACQUISITION})
		{
			assertTrue(acquisition.name(), of(acquisition).isCrashInEveryPhase());
			assertEquals(acquisition.name(), 5, of(acquisition).getP3CrashSets());
			assertEquals(acquisition.name(), Integer.valueOf(3), of(acquisition).getBlockedMaxHit());
		}
		assertFalse(of(Contract.CATALYST_ACQUISITION).isSoloOnly());
		assertFalse(of(Contract.WORM_ACQUISITION).isSoloOnly());
		assertFalse(of(Contract.SHARD_ACQUISITION).isSoloOnly());
		assertTrue(of(Contract.SHARD_ACQUISITION).isRandomAttackStyles());
		assertFalse(of(Contract.CATALYST_ACQUISITION).isRandomAttackStyles());
		assertTrue(of(Contract.OATHPLATE_ACQUISITION).isSoloOnly());
		assertTrue(of(Contract.OATHPLATE_ACQUISITION).isShadowPools());
		assertTrue(of(Contract.HARMONY_ACQUISITION).isShadowPools());
		assertFalse(of(Contract.SHARD_ACQUISITION).isShadowPools());
	}

	@Test
	public void tunablesFlowIntoTheRules()
	{
		Rules tuned = Rules.DEFAULT.toBuilder().blockedMaxHit(5).p1p2AttackCycle(9).build();

		assertEquals(Integer.valueOf(5), ContractRules.of(Contract.NONE, tuned).getBlockedMaxHit());
		assertEquals(9, ContractRules.of(Contract.NONE, tuned).getP1p2AttackCycle());
		assertEquals(Integer.valueOf(5), ContractRules.of(Contract.CATALYST_ACQUISITION, tuned).getBlockedMaxHit());
		assertEquals(7, ContractRules.of(Contract.CATALYST_ACQUISITION, tuned).getP1p2AttackCycle());
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.domain.contract.ContractRulesTest'`
Expected: FAIL — `cannot find symbol: class ContractRules`.

- [ ] **Step 3: Write `ContractRules`**

`src/main/java/com/yamareviewer/domain/contract/ContractRules.java`:

```java
package com.yamareviewer.domain.contract;

import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import lombok.Builder;
import lombok.Value;

/** The per-contract values of spec 6.3 and 6.3.1, as data. Every contract shares the common rules. */
@Value
@Builder(toBuilder = true)
public class ContractRules
{
	/** P1/P2 standard attacks come every 7 ticks under any contract (spec 6.3). */
	public static final int CONTRACT_P1P2_ATTACK_CYCLE = 7;

	int yamaHp;
	int baseDefence;
	int baseMagic;
	int p1p2AttackCycle;
	/** Null when the contract changes prayer penetration; BlockedDamage and GraphicVsBehaviour are then skipped. */
	Integer blockedMaxHit;
	boolean soloOnly;
	boolean randomAttackStyles;
	boolean crashInEveryPhase;
	int p3CrashSets;
	boolean consumablesHaveNoEffect;
	boolean specsAlwaysHit;
	boolean runEnergyDeath;
	boolean shadowPools;
	/** Ticks after which the game switches protection prayers off (Divine Severance: 2), 0 = never. */
	int prayerAutoOffTicks;

	/** The values of spec 6.3 / 6.3.1. NONE: 2500 HP, Defence 225, Magic 250, the tunable cycle and blockedMaxHit, 3 P3 crash sets. */
	public static ContractRules of(Contract contract, Rules rules)
	{
		ContractRulesBuilder common = contract.isContract() ? contractCommon(rules) : normal(rules);
		switch (contract)
		{
			case FORFEIT_BREATH:
				return common.soloOnly(true).runEnergyDeath(true).build();
			case GLYPHIC_ATTENUATION:
				return common.soloOnly(true).yamaHp(2875).build();
			case SENSORY_CLOUDING:
				return common.soloOnly(true).blockedMaxHit(null).build();
			case DIVINE_SEVERANCE:
				return common.soloOnly(true).blockedMaxHit(0).prayerAutoOffTicks(2).build();
			case BLOODIED_BLOWS:
				return common.soloOnly(true).yamaHp(3000).blockedMaxHit(null).build();
			case FAMILIAR_ACQUISITION:
				return common.blockedMaxHit(null).consumablesHaveNoEffect(true).specsAlwaysHit(true).build();
			case CATALYST_ACQUISITION:
			case WORM_ACQUISITION:
				return acquisition(common).build();
			case SHARD_ACQUISITION:
				return acquisition(common).randomAttackStyles(true).build();
			case OATHPLATE_ACQUISITION:
			case HARMONY_ACQUISITION:
				return acquisition(common).soloOnly(true).shadowPools(true).build();
			default:
				return common.build();
		}
	}

	private static ContractRulesBuilder normal(Rules rules)
	{
		return ContractRules.builder()
			.yamaHp(2500)
			.baseDefence(225)
			.baseMagic(250)
			.p1p2AttackCycle(rules.getP1p2AttackCycle())
			.blockedMaxHit(rules.getBlockedMaxHit())
			.soloOnly(false)
			.randomAttackStyles(false)
			.crashInEveryPhase(false)
			.p3CrashSets(3)
			.consumablesHaveNoEffect(false)
			.specsAlwaysHit(false)
			.runEnergyDeath(false)
			.shadowPools(false)
			.prayerAutoOffTicks(0);
	}

	/** Spec 6.3 "common rules under any contract": Defence 247, Magic 275, the 7-tick cycle. */
	private static ContractRulesBuilder contractCommon(Rules rules)
	{
		return normal(rules).baseDefence(247).baseMagic(275).p1p2AttackCycle(CONTRACT_P1P2_ATTACK_CYCLE);
	}

	/** Catalyst, Worm, Shard, Oathplate and Harmony: Shadow Crash in every phase, five P3 sets. */
	private static ContractRulesBuilder acquisition(ContractRulesBuilder common)
	{
		return common.crashInEveryPhase(true).p3CrashSets(5);
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.domain.contract.ContractRulesTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/contract src/test/java/com/yamareviewer/domain/contract
git commit -m "feat: add the per-contract review rules of spec 6.3.1"
```

---
### Task 3: Section result types and KillReview

**Files:**
- Create in `src/main/java/com/yamareviewer/domain/review/`: `PhaseSpan.java`, `PhaseTimes.java`, `DamageSource.java`, `DamageHit.java`, `DamageSummary.java`, `FlareCause.java`, `FlareFate.java`, `FlareResult.java`, `FlareWave.java`, `FlareSummary.java`, `PriceMode.java`, `SupplyLine.java`, `SupplySummary.java`, `RecapHit.java`, `RecapTick.java`, `DeathRecap.java`, `KillReview.java`
- Create: `src/test/java/com/yamareviewer/testing/Reviews.java` (a sample review for later tests)
- Test: `src/test/java/com/yamareviewer/domain/review/PhaseTimesTest.java`, `DamageSummaryTest.java`, `FlareSummaryTest.java`, `KillReviewTest.java`

**Interfaces:**
- Consumes: `Section`, `HiddenReason`, `ReviewStatus` (Task 1), `Mode`, `Phase`, `Contract` (Task 1), `Actor`, `EndReason` (Part 1 Task 2).
- Produces: the `@Value` types listed in the code below with exactly these fields; `PhaseTimes.span(Phase)`, `phaseAt(int)`, `last()`; `DamageSummary.total(Actor)`, `total(Actor, Phase)`, `bySource(Actor)`, `shared()`; `DamageSource.shared()`, `label()`; `FlareSummary.spawned()`, `killed()`, `exploded()`, `purgingStaffKills()`; `FlareWave.cause()`, `ticks()`; `KillReview` (`@Value @Builder(toBuilder = true)`, `SCHEMA_VERSION = 1`) with `allSections()` and `withRecomputedStatus()`; test helper `Reviews.builder(killId, startEpochMs, mode, contract)` and `Reviews.sample()`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/review/PhaseTimesTest.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.model.Phase;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class PhaseTimesTest
{
	private final PhaseTimes times = new PhaseTimes(List.of(
		new PhaseSpan(Phase.P1, 0, 50),
		new PhaseSpan(Phase.JUDGE_1, 50, 80),
		new PhaseSpan(Phase.P2, 80, 130)), 130);

	@Test
	public void spanDurations()
	{
		PhaseSpan judge = times.span(Phase.JUDGE_1).get();

		assertEquals(30, judge.ticks());
		assertEquals(18.0, judge.seconds(), 0.001);
		assertEquals(Optional.empty(), times.span(Phase.P3));
		assertEquals(Optional.of(new PhaseSpan(Phase.P2, 80, 130)), times.last());
	}

	@Test
	public void phaseAtUsesHalfOpenSpansExceptTheLastOne()
	{
		assertEquals(Optional.of(Phase.P1), times.phaseAt(0));
		assertEquals(Optional.of(Phase.P1), times.phaseAt(49));
		assertEquals(Optional.of(Phase.JUDGE_1), times.phaseAt(50));
		assertEquals(Optional.of(Phase.P2), times.phaseAt(80));
		assertEquals(Optional.of(Phase.P2), times.phaseAt(130));
		assertEquals(Optional.empty(), times.phaseAt(131));
		assertEquals(Optional.empty(), times.phaseAt(-1));
	}
}
```

`src/test/java/com/yamareviewer/domain/review/DamageSummaryTest.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Phase;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DamageSummaryTest
{
	private final DamageSummary summary = new DamageSummary(List.of(
		new DamageHit(10, Phase.P1, Actor.SELF, DamageSource.MELEE, 20),
		new DamageHit(12, Phase.P1, Actor.PARTNER, DamageSource.MELEE_SPLASH, 15),
		new DamageHit(300, Phase.P3, Actor.SELF, DamageSource.FLARE, 40),
		new DamageHit(301, Phase.P3, Actor.SELF, DamageSource.MELEE, 5),
		new DamageHit(305, null, Actor.SELF, DamageSource.OTHER, 1)));

	@Test
	public void totalsPerPlayerAndPhase()
	{
		assertEquals(66, summary.total(Actor.SELF));
		assertEquals(15, summary.total(Actor.PARTNER));
		assertEquals(20, summary.total(Actor.SELF, Phase.P1));
		assertEquals(45, summary.total(Actor.SELF, Phase.P3));
		assertEquals(0, summary.total(Actor.PARTNER, Phase.P3));
	}

	@Test
	public void bySourceOmitsSourcesWithoutDamage()
	{
		Map<DamageSource, Integer> self = summary.bySource(Actor.SELF);

		assertEquals(Integer.valueOf(25), self.get(DamageSource.MELEE));
		assertEquals(Integer.valueOf(40), self.get(DamageSource.FLARE));
		assertEquals(Integer.valueOf(1), self.get(DamageSource.OTHER));
		assertFalse(self.containsKey(DamageSource.SHADOW_CRASH));
		assertEquals(List.of(DamageSource.MELEE, DamageSource.FLARE, DamageSource.OTHER), List.copyOf(self.keySet()));
	}

	@Test
	public void sharedMechanicsAreMeleeSplashSpecialsAndFlares()
	{
		assertTrue(DamageSource.MELEE_SPLASH.shared());
		assertTrue(DamageSource.SPECIAL.shared());
		assertTrue(DamageSource.FLARE.shared());
		assertFalse(DamageSource.MELEE.shared());
		assertFalse(DamageSource.STANDARD.shared());
		assertEquals(55, summary.shared());
	}
}
```

`src/test/java/com/yamareviewer/domain/review/FlareSummaryTest.java`:

```java
package com.yamareviewer.domain.review;

import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FlareSummaryTest
{
	@Test
	public void countsAcrossWaves()
	{
		FlareSummary summary = new FlareSummary(List.of(
			new FlareWave(30, 60, List.of(
				new FlareResult(1, 30, 45, FlareCause.SUMMONED, FlareFate.KILLED, true),
				new FlareResult(2, 30, 60, FlareCause.SUMMONED, FlareFate.EXPLODED, false))),
			new FlareWave(200, 230, List.of(
				new FlareResult(3, 200, null, FlareCause.MELEE, FlareFate.UNKNOWN, false)))), 30);

		assertEquals(3, summary.spawned());
		assertEquals(1, summary.killed());
		assertEquals(1, summary.exploded());
		assertEquals(1, summary.purgingStaffKills());
		assertEquals(FlareCause.SUMMONED, summary.getWaves().get(0).cause());
		assertEquals(30, summary.getWaves().get(0).ticks());
		assertEquals(FlareCause.UNKNOWN, new FlareWave(0, 0, List.of()).cause());
	}
}
```

`src/test/java/com/yamareviewer/domain/review/KillReviewTest.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.testing.Reviews;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class KillReviewTest
{
	private static KillReview.KillReviewBuilder review()
	{
		return Reviews.builder("kill-1", 1_000L, Mode.SOLO, Contract.NONE);
	}

	@Test
	public void sectionsAreListedInDisplayOrder()
	{
		KillReview review = review().build();

		assertEquals(List.of(review.getPhases(), review.getDamage(), review.getFlares(), review.getSupplies(), review.getDeathRecap()),
			review.allSections());
	}

	@Test
	public void onlyHealthCheckFailuresErrorsAndSkippedEventsMakeAReviewIncomplete()
	{
		assertEquals(ReviewStatus.COMPLETE, review().build().withRecomputedStatus().getStatus());
		assertEquals(ReviewStatus.COMPLETE,
			review().flares(Section.hidden(HiddenReason.IDS_NOT_CAPTURED)).supplies(Section.hidden(HiddenReason.NOT_APPLICABLE))
				.damage(Section.hidden(HiddenReason.CONTRACT)).build().withRecomputedStatus().getStatus());
		assertEquals(ReviewStatus.INCOMPLETE,
			review().flares(Section.hidden(HiddenReason.ERROR)).build().withRecomputedStatus().getStatus());
		assertEquals(ReviewStatus.INCOMPLETE,
			review().phases(Section.hidden(HiddenReason.HEALTH_CHECK_FAILED)).build().withRecomputedStatus().getStatus());
		assertEquals(ReviewStatus.INCOMPLETE, review().skippedEvents(1).build().withRecomputedStatus().getStatus());
	}

	@Test
	public void aSectionMissingFromAStoredReviewReadsAsNotApplicable()
	{
		KillReview review = review().deathRecap(null).build();

		assertEquals(Section.hidden(HiddenReason.NOT_APPLICABLE), review.getDeathRecap());
		assertEquals(5, review.allSections().size());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.review.*'`
Expected: FAIL — `cannot find symbol` for `PhaseTimes`, `DamageSummary`, `FlareSummary`, `KillReview`, `Reviews`.

- [ ] **Step 3: Write the phase, damage and flare types**

`src/main/java/com/yamareviewer/domain/review/PhaseSpan.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.model.Phase;
import lombok.Value;

/** One phase: from its start tick (inclusive) to the next phase's start (exclusive), or the fight end. */
@Value
public class PhaseSpan
{
	Phase phase;
	int startTick;
	int endTick;

	public int ticks()
	{
		return endTick - startTick;
	}

	public double seconds()
	{
		return ticks() * 0.6;
	}
}
```

`src/main/java/com/yamareviewer/domain/review/PhaseTimes.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.model.Phase;
import java.util.List;
import java.util.Optional;
import lombok.Value;

/** The phases reached, in order; totalTicks is the tick the fight ended on. */
@Value
public class PhaseTimes
{
	List<PhaseSpan> spans;
	int totalTicks;

	public Optional<PhaseSpan> span(Phase phase)
	{
		return spans.stream().filter(span -> span.getPhase() == phase).findFirst();
	}

	public Optional<PhaseSpan> last()
	{
		return spans.isEmpty() ? Optional.empty() : Optional.of(spans.get(spans.size() - 1));
	}

	/** Spans are half-open, except the last one, which includes its end tick (the fight end). */
	public Optional<Phase> phaseAt(int tick)
	{
		for (int i = 0; i < spans.size(); i++)
		{
			PhaseSpan span = spans.get(i);
			boolean last = i == spans.size() - 1;
			if (tick >= span.getStartTick() && (tick < span.getEndTick() || (last && tick == span.getEndTick())))
			{
				return Optional.of(span.getPhase());
			}
		}
		return Optional.empty();
	}
}
```

`src/main/java/com/yamareviewer/domain/review/DamageSource.java`:

```java
package com.yamareviewer.domain.review;

/** Where a damage hitsplat on a player came from (spec 6.10). */
public enum DamageSource
{
	STANDARD("Standard attacks", false),
	MELEE("Melee", false),
	MELEE_SPLASH("Melee splash", true),
	SHADOW_CRASH("Shadow Crash", false),
	SHADOW_WAVE("Shadow Waves", false),
	FLARE("Flare explosions", true),
	FIRE_STREAK("Fire Streaks", false),
	SPECIAL("Specials", true),
	SHADOW_POOL("Shadow pools", false),
	JUDGE("Judge", false),
	OTHER("Other", false);

	private final String label;
	private final boolean shared;

	DamageSource(String label, boolean shared)
	{
		this.label = label;
		this.shared = shared;
	}

	public String label()
	{
		return label;
	}

	/** MELEE_SPLASH, SPECIAL and FLARE: totalled as shared mechanics (spec 6.10). */
	public boolean shared()
	{
		return shared;
	}
}
```

`src/main/java/com/yamareviewer/domain/review/DamageHit.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Phase;
import lombok.Value;

/** One attributed damage hitsplat. player is Actor.SELF or Actor.PARTNER; phase is null when the phases are unknown. */
@Value
public class DamageHit
{
	int tick;
	Phase phase;
	Actor player;
	DamageSource source;
	int amount;
}
```

`src/main/java/com/yamareviewer/domain/review/DamageSummary.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Phase;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.Value;

@Value
public class DamageSummary
{
	List<DamageHit> hits;

	public int total(Actor player)
	{
		return hits.stream().filter(hit -> hit.getPlayer().equals(player)).mapToInt(DamageHit::getAmount).sum();
	}

	public int total(Actor player, Phase phase)
	{
		return hits.stream()
			.filter(hit -> hit.getPlayer().equals(player) && hit.getPhase() == phase)
			.mapToInt(DamageHit::getAmount)
			.sum();
	}

	/** In declaration order of DamageSource; sources without damage are omitted. */
	public Map<DamageSource, Integer> bySource(Actor player)
	{
		Map<DamageSource, Integer> result = new EnumMap<>(DamageSource.class);
		for (DamageHit hit : hits)
		{
			if (hit.getPlayer().equals(player))
			{
				result.merge(hit.getSource(), hit.getAmount(), Integer::sum);
			}
		}
		return result;
	}

	/** Damage from shared mechanics, both players together. */
	public int shared()
	{
		return hits.stream().filter(hit -> hit.getSource().shared()).mapToInt(DamageHit::getAmount).sum();
	}
}
```

`src/main/java/com/yamareviewer/domain/review/FlareCause.java`:

```java
package com.yamareviewer.domain.review;

public enum FlareCause
{
	SUMMONED,
	MELEE,
	UNKNOWN
}
```

`src/main/java/com/yamareviewer/domain/review/FlareFate.java`:

```java
package com.yamareviewer.domain.review;

public enum FlareFate
{
	KILLED,
	EXPLODED,
	UNKNOWN
}
```

`src/main/java/com/yamareviewer/domain/review/FlareResult.java`:

```java
package com.yamareviewer.domain.review;

import lombok.Value;

/** One void flare; despawnTick is null when the fight ended while it was alive. */
@Value
public class FlareResult
{
	int npcIndex;
	int spawnTick;
	Integer despawnTick;
	FlareCause cause;
	FlareFate fate;
	boolean killedByPurgingStaff;
}
```

`src/main/java/com/yamareviewer/domain/review/FlareWave.java`:

```java
package com.yamareviewer.domain.review;

import java.util.List;
import lombok.Value;

/** Flares spawned within 3 ticks of each other; lastDespawnTick is the fight end for flares still alive. */
@Value
public class FlareWave
{
	int firstSpawnTick;
	int lastDespawnTick;
	List<FlareResult> flares;

	/** The cause of the first flare, UNKNOWN for an empty wave. */
	public FlareCause cause()
	{
		return flares.isEmpty() ? FlareCause.UNKNOWN : flares.get(0).getCause();
	}

	public int ticks()
	{
		return lastDespawnTick - firstSpawnTick;
	}
}
```

`src/main/java/com/yamareviewer/domain/review/FlareSummary.java`:

```java
package com.yamareviewer.domain.review;

import java.util.List;
import java.util.stream.Stream;
import lombok.Value;

@Value
public class FlareSummary
{
	List<FlareWave> waves;
	/** Yama's healing from explosions: every HEAL hitsplat on him during the fight. */
	int yamaHealing;

	public int spawned()
	{
		return (int) flares().count();
	}

	public int killed()
	{
		return (int) flares().filter(flare -> flare.getFate() == FlareFate.KILLED).count();
	}

	public int exploded()
	{
		return (int) flares().filter(flare -> flare.getFate() == FlareFate.EXPLODED).count();
	}

	public int purgingStaffKills()
	{
		return (int) flares().filter(FlareResult::isKilledByPurgingStaff).count();
	}

	private Stream<FlareResult> flares()
	{
		return waves.stream().flatMap(wave -> wave.getFlares().stream());
	}
}
```

- [ ] **Step 4: Write the supplies, death recap and review types**

`src/main/java/com/yamareviewer/domain/review/PriceMode.java`:

```java
package com.yamareviewer.domain.review;

public enum PriceMode
{
	GRAND_EXCHANGE,
	HIGH_ALCHEMY
}
```

`src/main/java/com/yamareviewer/domain/review/SupplyLine.java`:

```java
package com.yamareviewer.domain.review;

import lombok.Value;

/** One consumed item or potion; unit is "x" (items) or "doses". */
@Value
public class SupplyLine
{
	String name;
	int quantity;
	String unit;
	long cost;
	/** Under Familiar Acquisition: food and potions were used but did nothing. */
	boolean noEffect;
}
```

`src/main/java/com/yamareviewer/domain/review/SupplySummary.java`:

```java
package com.yamareviewer.domain.review;

import java.util.List;
import lombok.Value;

@Value
public class SupplySummary
{
	List<SupplyLine> lines;
	long totalCost;
	PriceMode priceMode;
}
```

`src/main/java/com/yamareviewer/domain/review/RecapHit.java`:

```java
package com.yamareviewer.domain.review;

import lombok.Value;

@Value
public class RecapHit
{
	DamageSource source;
	int amount;
}
```

`src/main/java/com/yamareviewer/domain/review/RecapTick.java`:

```java
package com.yamareviewer.domain.review;

import java.util.List;
import lombok.Value;

/** One of the last ticks before a death. hitpoints and prayerPoints are -1 when the tick has no state; runEnergy is set under Forfeit Breath only. */
@Value
public class RecapTick
{
	int tick;
	List<RecapHit> hits;
	int hitpoints;
	int prayerPoints;
	Integer runEnergy;
	/** "you", "partner", "other" or "nobody". */
	String yamaTarget;
	List<String> consumed;
}
```

`src/main/java/com/yamareviewer/domain/review/DeathRecap.java`:

```java
package com.yamareviewer.domain.review;

import java.util.List;
import lombok.Value;

@Value
public class DeathRecap
{
	List<RecapTick> ticks;
	boolean outOfRunEnergy;
}
```

`src/main/java/com/yamareviewer/domain/review/KillReview.java`:

```java
package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * The immutable read model of one kill. Section getters never return null, so a review stored before a
 * section existed still loads (the missing section reads as hidden, NOT_APPLICABLE). Part 3 adds its
 * sections here and to allSections().
 */
@Value
@Builder(toBuilder = true)
public class KillReview
{
	public static final int SCHEMA_VERSION = 1;

	int reviewSchemaVersion;
	String killId;
	long startEpochMs;
	long endEpochMs;
	EndReason endReason;
	Mode mode;
	Contract contract;
	ReviewStatus status;
	int skippedEvents;
	Section<PhaseTimes> phases;
	Section<DamageSummary> damage;
	Section<FlareSummary> flares;
	Section<SupplySummary> supplies;
	Section<DeathRecap> deathRecap;

	public Section<PhaseTimes> getPhases()
	{
		return orHidden(phases);
	}

	public Section<DamageSummary> getDamage()
	{
		return orHidden(damage);
	}

	public Section<FlareSummary> getFlares()
	{
		return orHidden(flares);
	}

	public Section<SupplySummary> getSupplies()
	{
		return orHidden(supplies);
	}

	public Section<DeathRecap> getDeathRecap()
	{
		return orHidden(deathRecap);
	}

	/** Every shown section, in display order. */
	public List<Section<?>> allSections()
	{
		return List.of(getPhases(), getDamage(), getFlares(), getSupplies(), getDeathRecap());
	}

	/** INCOMPLETE when any section is hidden for HEALTH_CHECK_FAILED or ERROR, or skippedEvents > 0. */
	public KillReview withRecomputedStatus()
	{
		boolean incomplete = skippedEvents > 0 || allSections().stream()
			.anyMatch(section -> section.hiddenReason()
				.filter(reason -> reason == HiddenReason.HEALTH_CHECK_FAILED || reason == HiddenReason.ERROR)
				.isPresent());
		return toBuilder().status(incomplete ? ReviewStatus.INCOMPLETE : ReviewStatus.COMPLETE).build();
	}

	private static <T> Section<T> orHidden(Section<T> section)
	{
		return section == null ? Section.hidden(HiddenReason.NOT_APPLICABLE) : section;
	}
}
```

- [ ] **Step 5: Write the sample review used by later tests**

`src/test/java/com/yamareviewer/testing/Reviews.java`:

```java
package com.yamareviewer.testing;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.DamageHit;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.FlareCause;
import com.yamareviewer.domain.review.FlareFate;
import com.yamareviewer.domain.review.FlareResult;
import com.yamareviewer.domain.review.FlareSummary;
import com.yamareviewer.domain.review.FlareWave;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.PriceMode;
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.SupplyLine;
import com.yamareviewer.domain.review.SupplySummary;
import java.util.List;

/**
 * A complete 4:12 solo kill: P1 1:05, Judge 1 0:19, P2 1:10, Judge 2 0:14, P3 1:24; 212 damage taken
 * (partner 188); 6 flares, 5 killed; supplies 318,450 gp. The numbers match the chat example of spec 7.2.
 */
public final class Reviews
{
	public static final PhaseTimes PHASES = new PhaseTimes(List.of(
		new PhaseSpan(Phase.P1, 0, 108),
		new PhaseSpan(Phase.JUDGE_1, 108, 140),
		new PhaseSpan(Phase.P2, 140, 257),
		new PhaseSpan(Phase.JUDGE_2, 257, 280),
		new PhaseSpan(Phase.P3, 280, 420)), 420);

	private Reviews()
	{
	}

	public static KillReview.KillReviewBuilder builder(String killId, long startEpochMs, Mode mode, Contract contract)
	{
		return KillReview.builder()
			.reviewSchemaVersion(KillReview.SCHEMA_VERSION)
			.killId(killId)
			.startEpochMs(startEpochMs)
			.endEpochMs(startEpochMs + 252_000L)
			.endReason(EndReason.YAMA_DIED)
			.mode(mode)
			.contract(contract)
			.status(ReviewStatus.COMPLETE)
			.skippedEvents(0)
			.phases(Section.ok(PHASES))
			.damage(Section.ok(new DamageSummary(List.of(
				new DamageHit(20, Phase.P1, Actor.SELF, DamageSource.MELEE, 22),
				new DamageHit(300, Phase.P3, Actor.SELF, DamageSource.FLARE, 190),
				new DamageHit(310, Phase.P3, Actor.PARTNER, DamageSource.OTHER, 188)))))
			.flares(Section.ok(new FlareSummary(List.of(
				new FlareWave(30, 60, List.of(
					flare(1, 30, 45, FlareFate.KILLED),
					flare(2, 30, 60, FlareFate.EXPLODED))),
				new FlareWave(200, 230, List.of(
					flare(3, 200, 210, FlareFate.KILLED),
					flare(4, 200, 215, FlareFate.KILLED),
					flare(5, 201, 220, FlareFate.KILLED),
					flare(6, 201, 230, FlareFate.KILLED)))), 30)))
			.supplies(Section.ok(new SupplySummary(List.of(
				new SupplyLine("Shark", 2, "x", 1_600L, false),
				new SupplyLine("Super restore", 6, "doses", 316_850L, false)), 318_450L, PriceMode.GRAND_EXCHANGE)))
			.deathRecap(Section.hidden(HiddenReason.NOT_APPLICABLE));
	}

	public static KillReview sample()
	{
		return builder("kill-1", 1_700_000_000_000L, Mode.SOLO, Contract.NONE).build();
	}

	private static FlareResult flare(int index, int spawn, int despawn, FlareFate fate)
	{
		return new FlareResult(index, spawn, despawn, FlareCause.SUMMONED, fate, false);
	}
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.review.*'`
Expected: PASS (16 tests: 7 from Task 1's `SectionTest`, 2 `PhaseTimesTest`, 3 `DamageSummaryTest`, 1 `FlareSummaryTest`, 3 `KillReviewTest`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/review src/test/java/com/yamareviewer/domain/review src/test/java/com/yamareviewer/testing/Reviews.java
git commit -m "feat: add the section result types and KillReview"
```

---
### Task 4: Test DSL: TestIds and KillLogBuilder

**Files:**
- Create: `src/test/java/com/yamareviewer/testing/TestIds.java`, `KillLogBuilder.java`
- Test: `src/test/java/com/yamareviewer/testing/TestIdsTest.java`, `KillLogBuilderTest.java`

**Interfaces:**
- Consumes: the Part 1 events, `KillLog`, `KillHeader`, `IdRegistry`, `Role`, `RoleKind`.
- Produces (test source set, binding for Parts 3 and 4): `TestIds.registry()`, `TestIds.registryWithout(Role...)`, `TestIds.id(Role)` (= 100000 + ordinal × 10), `TestIds.text(Role)`; `KillLogBuilder.kill()` and the fluent methods listed in the code below, `KillLogBuilder.RAW_DAMAGE/RAW_BLOCK/RAW_HEAL`, `KillLogBuilder.JUDGE_INDEX`, `KillLogBuilder.START_POSITION`. A `KillLog` from `end(YAMA_DIED)` or `end(PLAYER_DIED)` ends like the recorder's: an `END` snapshot (the START items when none was given), the cursor tick's `TickState`, then `FightEnded` at the cursor; `end(LEFT)` appends only `FightEnded` (a logout); `build()` has no `FightEnded`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/testing/TestIdsTest.java`:

```java
package com.yamareviewer.testing;

import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TestIdsTest
{
	@Test
	public void everyRoleIsCapturedWithAUniqueIdOrItsRealText()
	{
		IdRegistry ids = TestIds.registry();
		Set<Integer> seen = new HashSet<>();
		for (Role role : Role.values())
		{
			assertTrue(role.name(), ids.isCaptured(role));
			if (role.kind() != RoleKind.OVERHEAD && role.kind() != RoleKind.MESSAGE)
			{
				assertTrue(role.name(), seen.add(TestIds.id(role)));
				assertTrue(role.name(), ids.is(role, TestIds.id(role)));
			}
		}
		assertTrue(ids.matchesText(Role.PHASE_TRANSITION_TEXT, TestIds.text(Role.PHASE_TRANSITION_TEXT)));
		assertTrue(ids.matchesText(Role.PHASE_TRANSITION_TEXT, "Begone"));
		assertTrue(ids.matchesText(Role.PRAYER_DISABLED_MESSAGE, "<col=ef1020>" + TestIds.text(Role.PRAYER_DISABLED_MESSAGE) + "</col>"));
		assertTrue(ids.matchesText(Role.GLYPH_CONJURE_MESSAGE, "Yama conjures a Glyph of Fire."));
	}

	@Test
	public void registryWithoutLeavesRolesUncaptured()
	{
		IdRegistry ids = TestIds.registryWithout(Role.JUDGE, Role.PHASE_TRANSITION_TEXT);

		assertFalse(ids.isCaptured(Role.JUDGE));
		assertFalse(ids.isCaptured(Role.PHASE_TRANSITION_TEXT));
		assertTrue(ids.isCaptured(Role.YAMA));
	}
}
```

`src/test/java/com/yamareviewer/testing/KillLogBuilderTest.java`:

```java
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.testing.*'`
Expected: FAIL — `cannot find symbol` for `TestIds`, `KillLogBuilder`.

- [ ] **Step 3: Write `TestIds`**

`src/test/java/com/yamareviewer/testing/TestIds.java`:

```java
package com.yamareviewer.testing;

import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Made-up IDs: every non-text role gets 100000 + ordinal * 10; text roles get their real built-in texts. */
public final class TestIds
{
	private static final int BASE = 100_000;
	private static final int STEP = 10;
	private static final Map<Role, Set<String>> TEXTS = Map.of(
		Role.PHASE_TRANSITION_TEXT, Set.of("Begone", "You bore me.", "Enough."),
		Role.PRAYER_DISABLED_MESSAGE, Set.of("You've been injured and can't use protection prayers!"),
		Role.GLYPH_CONJURE_MESSAGE, Set.of("Yama conjures"));

	private TestIds()
	{
	}

	public static IdRegistry registry()
	{
		return registryWithout();
	}

	/** The full registry minus the given roles, which are then uncaptured. */
	public static IdRegistry registryWithout(Role... uncaptured)
	{
		Set<Role> skip = uncaptured.length == 0 ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(Arrays.asList(uncaptured));
		Map<Role, Set<Integer>> ids = new EnumMap<>(Role.class);
		Map<Role, Set<String>> texts = new EnumMap<>(Role.class);
		for (Role role : Role.values())
		{
			if (skip.contains(role))
			{
				continue;
			}
			if (isText(role))
			{
				texts.put(role, TEXTS.get(role));
			}
			else
			{
				ids.put(role, Set.of(id(role)));
			}
		}
		return new IdRegistry(ids, texts);
	}

	public static int id(Role role)
	{
		if (isText(role))
		{
			throw new IllegalArgumentException(role + " is a text role; use text()");
		}
		return BASE + role.ordinal() * STEP;
	}

	/** The canonical text of an overhead or message role: "Enough.", the prayer-disabled line, "Yama conjures". */
	public static String text(Role role)
	{
		if (role == Role.PHASE_TRANSITION_TEXT)
		{
			return "Enough.";
		}
		Set<String> texts = TEXTS.get(role);
		if (texts == null)
		{
			throw new IllegalArgumentException(role + " is not a text role; use id()");
		}
		return texts.iterator().next();
	}

	private static boolean isText(Role role)
	{
		return role.kind() == RoleKind.OVERHEAD || role.kind() == RoleKind.MESSAGE;
	}
}
```

- [ ] **Step 4: Write `KillLogBuilder`**

`src/test/java/com/yamareviewer/testing/KillLogBuilder.java`:

```java
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
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
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
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.testing.*'`
Expected: PASS (11 tests).

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/yamareviewer/testing
git commit -m "test: add the KillLogBuilder DSL and TestIds"
```

---
### Task 5: Projection framework: SectionKey, ProjectionContext and ReviewBuilder

**Files:**
- Create in `src/main/java/com/yamareviewer/domain/projection/`: `SectionKey.java`, `Sections.java`, `ReviewSettings.java`, `Projection.java`, `ProjectionContext.java`, `ReviewBuilder.java`
- Test: `src/test/java/com/yamareviewer/domain/projection/ReviewBuilderTest.java`

**Interfaces:**
- Consumes: `Section`, `HiddenReason` (Task 1), `ContractRules` (Task 2), the result types (Task 3), `IdRegistry`, `Role`, `Rules`, `KillLog` (Part 1), `TestIds`, `KillLogBuilder` (Task 4).
- Produces: `SectionKey<T>` (`of(String)`, `name()`, identity-compared); `Sections.PHASES/CONTRACT/MODE/FLARES/DAMAGE/SUPPLIES/DEATH_RECAP`; `ReviewSettings(PriceMode priceMode, Mode modeOverride)` with `DEFAULT`; `Projection<T> { key(); requiredRoles(); project(KillLog, ProjectionContext) }`; `ProjectionContext(IdRegistry, Rules, ReviewSettings)` with `ids()`, `rules()`, `settings()`, `contractRules()`, `section(key)`, `value(key)`, `put(key, section)`; `ReviewBuilder(List<Projection<?>>, IdRegistry, Rules)` with `run(KillLog, ReviewSettings)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/domain/projection/ReviewBuilderTest.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PriceMode;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ReviewBuilderTest
{
	private static final SectionKey<String> FIRST = SectionKey.of("first");
	private static final SectionKey<String> SECOND = SectionKey.of("second");

	private final KillLog kill = KillLogBuilder.kill().ticks(2).end(EndReason.YAMA_DIED);

	private static Projection<String> projection(SectionKey<String> key, Set<Role> roles,
		BiFunction<KillLog, ProjectionContext, Section<String>> body)
	{
		return new Projection<String>()
		{
			@Override
			public SectionKey<String> key()
			{
				return key;
			}

			@Override
			public Set<Role> requiredRoles()
			{
				return roles;
			}

			@Override
			public Section<String> project(KillLog log, ProjectionContext context)
			{
				return body.apply(log, context);
			}
		};
	}

	@SafeVarargs
	private static ReviewBuilder builder(IdRegistry ids, Projection<String>... projections)
	{
		return new ReviewBuilder(List.of(projections), ids, Rules.DEFAULT);
	}

	@Test
	public void runsProjectionsInOrderAndLaterOnesSeeEarlierSections()
	{
		ProjectionContext context = builder(TestIds.registry(),
			projection(FIRST, Set.of(), (log, ctx) -> Section.ok("a")),
			projection(SECOND, Set.of(), (log, ctx) -> Section.ok(ctx.value(FIRST).orElse("?") + "b")))
			.run(kill, ReviewSettings.DEFAULT);

		assertEquals(Section.ok("a"), context.section(FIRST));
		assertEquals(Section.ok("ab"), context.section(SECOND));
	}

	@Test
	public void anUncapturedRequiredRoleHidesTheSectionWithoutRunningIt()
	{
		ProjectionContext context = builder(TestIds.registryWithout(Role.JUDGE),
			projection(FIRST, EnumSet.of(Role.YAMA, Role.JUDGE), (log, ctx) ->
			{
				throw new AssertionError("must not run");
			}))
			.run(kill, ReviewSettings.DEFAULT);

		assertEquals(Section.hidden(HiddenReason.IDS_NOT_CAPTURED), context.section(FIRST));
	}

	@Test
	public void anExceptionHidesTheSectionWithErrorAndTheRestStillRuns()
	{
		ProjectionContext context = builder(TestIds.registry(),
			projection(FIRST, Set.of(), (log, ctx) ->
			{
				throw new IllegalStateException("boom");
			}),
			projection(SECOND, Set.of(), (log, ctx) -> Section.ok("still " + ctx.section(FIRST).hiddenReason().get())))
			.run(kill, ReviewSettings.DEFAULT);

		assertEquals(Section.hidden(HiddenReason.ERROR), context.section(FIRST));
		assertEquals(Section.ok("still ERROR"), context.section(SECOND));
	}

	@Test
	public void aNullSectionIsAnError()
	{
		ProjectionContext context = builder(TestIds.registry(), projection(FIRST, Set.of(), (log, ctx) -> null))
			.run(kill, ReviewSettings.DEFAULT);

		assertEquals(Section.hidden(HiddenReason.ERROR), context.section(FIRST));
	}

	@Test
	public void aSectionNobodyProducedIsNotApplicable()
	{
		ProjectionContext context = builder(TestIds.registry()).run(kill, ReviewSettings.DEFAULT);

		assertEquals(Section.hidden(HiddenReason.NOT_APPLICABLE), context.section(SECOND));
		assertEquals(Optional.empty(), context.value(SECOND));
	}

	@Test
	public void contractRulesFollowTheContractSection()
	{
		ProjectionContext context = new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT);
		assertEquals(2500, context.contractRules().getYamaHp());

		context.put(Sections.CONTRACT, Section.ok(Contract.BLOODIED_BLOWS));

		assertEquals(ContractRules.of(Contract.BLOODIED_BLOWS, Rules.DEFAULT), context.contractRules());
	}

	@Test
	public void settingsIdsAndRulesAreAvailableToProjections()
	{
		ReviewSettings settings = new ReviewSettings(PriceMode.HIGH_ALCHEMY, Mode.DUO_HOST);
		Rules rules = Rules.DEFAULT.toBuilder().specResultWindow(9).build();
		Projection<String> reads = projection(FIRST, Set.of(), (log, ctx) -> Section.ok(
			ctx.settings().getPriceMode() + "/" + ctx.settings().getModeOverride() + "/" + ctx.rules().getSpecResultWindow()
				+ "/" + ctx.ids().isCaptured(Role.YAMA)));

		ProjectionContext context = new ReviewBuilder(List.of(reads), TestIds.registry(), rules).run(kill, settings);

		assertEquals(Section.ok("HIGH_ALCHEMY/DUO_HOST/9/true"), context.section(FIRST));
		assertEquals(new ReviewSettings(PriceMode.GRAND_EXCHANGE, null), ReviewSettings.DEFAULT);
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.ReviewBuilderTest'`
Expected: FAIL — `cannot find symbol` for `SectionKey`, `Projection`, `ProjectionContext`, `ReviewBuilder`, `ReviewSettings`, `Sections`.

- [ ] **Step 3: Write the keys, settings and the projection interface**

`src/main/java/com/yamareviewer/domain/projection/SectionKey.java`:

```java
package com.yamareviewer.domain.projection;

/** Identity-compared key of a section in the ProjectionContext. */
public final class SectionKey<T>
{
	private final String name;

	private SectionKey(String name)
	{
		this.name = name;
	}

	public static <T> SectionKey<T> of(String name)
	{
		return new SectionKey<>(name);
	}

	public String name()
	{
		return name;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
```

`src/main/java/com/yamareviewer/domain/projection/Sections.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.FlareSummary;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.SupplySummary;

/** All section keys. Part 3 adds its keys here. */
public final class Sections
{
	public static final SectionKey<PhaseTimes> PHASES = SectionKey.of("phases");
	public static final SectionKey<Contract> CONTRACT = SectionKey.of("contract");
	public static final SectionKey<Mode> MODE = SectionKey.of("mode");
	public static final SectionKey<FlareSummary> FLARES = SectionKey.of("flares");
	public static final SectionKey<DamageSummary> DAMAGE = SectionKey.of("damage");
	public static final SectionKey<SupplySummary> SUPPLIES = SectionKey.of("supplies");
	public static final SectionKey<DeathRecap> DEATH_RECAP = SectionKey.of("death-recap");

	private Sections()
	{
	}
}
```

`src/main/java/com/yamareviewer/domain/projection/ReviewSettings.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.PriceMode;
import lombok.Value;

/** The user's review settings at the time of the review (spec 10). */
@Value
public class ReviewSettings
{
	public static final ReviewSettings DEFAULT = new ReviewSettings(PriceMode.GRAND_EXCHANGE, null);

	PriceMode priceMode;
	/** Null = detect the mode. */
	Mode modeOverride;
}
```

`src/main/java/com/yamareviewer/domain/projection/Projection.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.Section;
import java.util.Set;

/** One metric of the review, computed from the log and the sections produced before it (spec 6.1). */
public interface Projection<T>
{
	SectionKey<T> key();

	/** If any of these roles is uncaptured, ReviewBuilder hides the section with IDS_NOT_CAPTURED without calling project. */
	Set<Role> requiredRoles();

	Section<T> project(KillLog log, ProjectionContext context);
}
```

- [ ] **Step 4: Write `ProjectionContext` and `ReviewBuilder`**

`src/main/java/com/yamareviewer/domain/projection/ProjectionContext.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Section;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The sections produced so far plus everything a projection may read besides the log. Not thread-safe. */
public final class ProjectionContext
{
	private final IdRegistry ids;
	private final Rules rules;
	private final ReviewSettings settings;
	private final Map<SectionKey<?>, Section<?>> sections = new LinkedHashMap<>();

	public ProjectionContext(IdRegistry ids, Rules rules, ReviewSettings settings)
	{
		this.ids = ids;
		this.rules = rules;
		this.settings = settings;
	}

	public IdRegistry ids()
	{
		return ids;
	}

	public Rules rules()
	{
		return rules;
	}

	public ReviewSettings settings()
	{
		return settings;
	}

	/** ContractRules.of(value(Sections.CONTRACT).orElse(Contract.NONE), rules()). */
	public ContractRules contractRules()
	{
		return ContractRules.of(value(Sections.CONTRACT).orElse(Contract.NONE), rules);
	}

	/** Hidden(NOT_APPLICABLE) when no projection produced this key. */
	@SuppressWarnings("unchecked")
	public <T> Section<T> section(SectionKey<T> key)
	{
		Section<?> section = sections.get(key);
		return section == null ? Section.hidden(HiddenReason.NOT_APPLICABLE) : (Section<T>) section;
	}

	public <T> Optional<T> value(SectionKey<T> key)
	{
		return section(key).asOptional();
	}

	public <T> void put(SectionKey<T> key, Section<T> section)
	{
		sections.put(key, section);
	}
}
```

`src/main/java/com/yamareviewer/domain/projection/ReviewBuilder.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Section;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/** Runs the projections in order; missing roles → IDS_NOT_CAPTURED; an exception → ERROR (logged). Pure: no clock, no I/O. */
@Slf4j
public final class ReviewBuilder
{
	private final List<Projection<?>> projections;
	private final IdRegistry ids;
	private final Rules rules;

	public ReviewBuilder(List<Projection<?>> projections, IdRegistry ids, Rules rules)
	{
		this.projections = List.copyOf(projections);
		this.ids = ids;
		this.rules = rules;
	}

	public ProjectionContext run(KillLog kill, ReviewSettings settings)
	{
		ProjectionContext context = new ProjectionContext(ids, rules, settings);
		for (Projection<?> projection : projections)
		{
			run(projection, kill, context);
		}
		return context;
	}

	private <T> void run(Projection<T> projection, KillLog kill, ProjectionContext context)
	{
		context.put(projection.key(), sectionOf(projection, kill, context));
	}

	private <T> Section<T> sectionOf(Projection<T> projection, KillLog kill, ProjectionContext context)
	{
		for (Role role : projection.requiredRoles())
		{
			if (!ids.isCaptured(role))
			{
				log.debug("Section {} hidden: role {} is not captured", projection.key().name(), role);
				return Section.hidden(HiddenReason.IDS_NOT_CAPTURED);
			}
		}
		try
		{
			return Objects.requireNonNull(projection.project(kill, context), "projection returned null");
		}
		catch (RuntimeException e)
		{
			log.warn("Projection {} failed for kill {}", projection.key().name(), kill.getHeader().getKillId(), e);
			return Section.hidden(HiddenReason.ERROR);
		}
	}
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.ReviewBuilderTest'`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/projection src/test/java/com/yamareviewer/domain/projection
git commit -m "feat: add the projection framework with per-section failure isolation"
```

---
### Task 6: PhasesProjection

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/projection/PhasesProjection.java`
- Test: `src/test/java/com/yamareviewer/domain/projection/PhasesProjectionTest.java`

**Interfaces:**
- Consumes: Task 5 framework, `PhaseTimes`, `PhaseSpan` (Task 3), the DSL (Task 4).
- Produces: `PhasesProjection` (key `Sections.PHASES`, required role `JUDGE`, `TRANSITION_SETTLE_TICKS = 10`).

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/domain/projection/PhasesProjectionTest.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.GameStateKind;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class PhasesProjectionTest
{
	private static final int JUDGE_ID = TestIds.id(Role.JUDGE);
	private static final int YAMA_ID = TestIds.id(Role.YAMA);
	private static final int PHASE_VARBIT = TestIds.id(Role.PHASE_VARBIT);
	private static final int TRANSITION_GRAPHIC = TestIds.id(Role.PHASE_TRANSITION_GRAPHIC);

	private static PhaseTimes project(KillLog kill)
	{
		return new PhasesProjection().project(kill, new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT)).value();
	}

	private static List<PhaseSpan> spans(Object... phaseStartEnd)
	{
		List<PhaseSpan> spans = new java.util.ArrayList<>();
		for (int i = 0; i < phaseStartEnd.length; i += 3)
		{
			spans.add(new PhaseSpan((Phase) phaseStartEnd[i], (Integer) phaseStartEnd[i + 1], (Integer) phaseStartEnd[i + 2]));
		}
		return spans;
	}

	/** Judge spawns at 50 and 130, dies at 80 and 160; the cursor ends at 220. */
	private static KillLogBuilder fullKill()
	{
		return KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(60);
	}

	@Test
	public void judgeSpawnsAndDeathsDelimitThePhases()
	{
		PhaseTimes times = project(fullKill().end(EndReason.YAMA_DIED));

		assertEquals(spans(Phase.P1, 0, 50, Phase.JUDGE_1, 50, 80, Phase.P2, 80, 130, Phase.JUDGE_2, 130, 160, Phase.P3, 160, 220), times.getSpans());
		assertEquals(220, times.getTotalTicks());
	}

	@Test
	public void theEarliestSignalStartsTheJudgePhase()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(48).varbit(PHASE_VARBIT, 1)
			.ticks(2).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(20).end(EndReason.LEFT);

		assertEquals(spans(Phase.P1, 0, 48, Phase.JUDGE_1, 48, 80, Phase.P2, 80, 100), project(kill).getSpans());
	}

	@Test
	public void overheadTextAndTheTransitionGraphicAreSignals()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(40).overhead(Actor.YAMA, TestIds.text(Role.PHASE_TRANSITION_TEXT))
			.ticks(10).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(45).graphicOn(Actor.YAMA, TRANSITION_GRAPHIC)
			.ticks(5).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(60).end(EndReason.YAMA_DIED);

		assertEquals(spans(Phase.P1, 0, 40, Phase.JUDGE_1, 40, 80, Phase.P2, 80, 125, Phase.JUDGE_2, 125, 160, Phase.P3, 160, 220),
			project(kill).getSpans());
	}

	@Test
	public void aRepeatedOrResetVarbitIsNotASignal()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(48).varbit(PHASE_VARBIT, 1)
			.ticks(2).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(20).varbit(PHASE_VARBIT, 1)
			.ticks(1).varbit(PHASE_VARBIT, 0)
			.ticks(29).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(60).end(EndReason.YAMA_DIED);

		assertEquals(spans(Phase.P1, 0, 48, Phase.JUDGE_1, 48, 80, Phase.P2, 80, 130, Phase.JUDGE_2, 130, 160, Phase.P3, 160, 220),
			project(kill).getSpans());
	}

	@Test
	public void aSceneReloadInsideAJudgePhaseIsIgnored()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(10).gameState(GameStateKind.LOADING)
			.npcDespawns(Actor.JUDGE, JUDGE_ID, false).npcDespawns(Actor.YAMA, YAMA_ID, false)
			.npcSpawns(Actor.JUDGE, JUDGE_ID).npcSpawns(Actor.YAMA, YAMA_ID)
			.ticks(1).gameState(GameStateKind.LOGGED_IN)
			.ticks(19).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(20).end(EndReason.LEFT);

		assertEquals(spans(Phase.P1, 0, 50, Phase.JUDGE_1, 50, 80, Phase.P2, 80, 100), project(kill).getSpans());
	}

	@Test
	public void aDuoJudgePhaseEndsWhenBothJudgesAreGone()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID, 7).npcSpawns(Actor.JUDGE, JUDGE_ID, 8)
			.ticks(25).npcDespawns(Actor.JUDGE, JUDGE_ID, 7, true)
			.ticks(5).npcDespawns(Actor.JUDGE, JUDGE_ID, 8, true)
			.ticks(20).end(EndReason.LEFT);

		assertEquals(spans(Phase.P1, 0, 50, Phase.JUDGE_1, 50, 80, Phase.P2, 80, 100), project(kill).getSpans());
	}

	@Test
	public void aFightThatEndedEarlyHasOnlyThePhasesReached()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(20).end(EndReason.LEFT);

		PhaseTimes times = project(kill);

		assertEquals(spans(Phase.P1, 0, 50, Phase.JUDGE_1, 50, 80, Phase.P2, 80, 100), times.getSpans());
		assertEquals(100, times.getTotalTicks());
	}

	@Test
	public void aDeathInsideAJudgePhaseEndsThatSpan()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(10).hitsplatOn(Actor.SELF, 99).hp(0).end(EndReason.PLAYER_DIED);

		PhaseTimes times = project(kill);

		assertEquals(spans(Phase.P1, 0, 50, Phase.JUDGE_1, 50, 60), times.getSpans());
		assertEquals(60, times.getTotalTicks());
	}

	@Test
	public void signalsRightAfterAJudgePhaseBelongToTheReturnToTheArena()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(2).graphicOn(Actor.YAMA, TRANSITION_GRAPHIC).varbit(PHASE_VARBIT, 1)
			.ticks(48).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(60).end(EndReason.YAMA_DIED);

		assertEquals(spans(Phase.P1, 0, 50, Phase.JUDGE_1, 50, 80, Phase.P2, 80, 130, Phase.JUDGE_2, 130, 160, Phase.P3, 160, 220),
			project(kill).getSpans());
	}

	@Test
	public void signalsAfterYamaDiedAreIgnored()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(20).npcDespawns(Actor.YAMA, YAMA_ID, true).graphicOn(Actor.YAMA, TRANSITION_GRAPHIC)
			.end(EndReason.YAMA_DIED);

		assertEquals(spans(Phase.P1, 0, 50, Phase.JUDGE_1, 50, 80, Phase.P2, 80, 100), project(kill).getSpans());
	}

	@Test
	public void aLogWithoutFightEndedEndsAtItsLastEvent()
	{
		PhaseTimes times = project(KillLogBuilder.kill().ticks(5).build());

		assertEquals(spans(Phase.P1, 0, 4), times.getSpans());
		assertEquals(4, times.getTotalTicks());
	}

	@Test
	public void needsTheJudgeRole()
	{
		PhasesProjection projection = new PhasesProjection();
		assertEquals(Sections.PHASES, projection.key());
		assertEquals(Set.of(Role.JUDGE), projection.requiredRoles());

		ReviewBuilder builder = new ReviewBuilder(List.of(projection), TestIds.registryWithout(Role.JUDGE), Rules.DEFAULT);

		assertEquals(Section.hidden(HiddenReason.IDS_NOT_CAPTURED),
			builder.run(fullKill().end(EndReason.YAMA_DIED), ReviewSettings.DEFAULT).section(Sections.PHASES));
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.PhasesProjectionTest'`
Expected: FAIL — `cannot find symbol: class PhasesProjection`.

- [ ] **Step 3: Write `PhasesProjection`**

`src/main/java/com/yamareviewer/domain/projection/PhasesProjection.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.GameStateKind;
import com.yamareviewer.domain.event.GameStateObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Spec 6.2 and 5.3. P1 starts at FightStarted. A Judge phase starts at the earliest signal while Yama is
 * alive: a change of PHASE_VARBIT to a new non-zero value, a PHASE_TRANSITION_TEXT line from Yama,
 * PHASE_TRANSITION_GRAPHIC on Yama, or a Judge spawn. It ends when every Judge that spawned in it is gone;
 * a non-dying despawn inside a scene reload (LOADING) is ignored and a respawn inside one adds nothing.
 * The log cannot tell the two Judges of a duo apart (hitsplats carry no NPC index), so the phase ends at
 * the last Judge despawn. P3 ends at FightEnded; a log without FightEnded ends at its last event.
 */
public final class PhasesProjection implements Projection<PhaseTimes>
{
	/** Transition signals this soon after a Judge phase ended belong to the return to the arena, not to a new Judge. */
	static final int TRANSITION_SETTLE_TICKS = 10;

	@Override
	public SectionKey<PhaseTimes> key()
	{
		return Sections.PHASES;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.JUDGE);
	}

	@Override
	public Section<PhaseTimes> project(KillLog log, ProjectionContext context)
	{
		IdRegistry ids = context.ids();
		List<PhaseSpan> spans = new ArrayList<>();
		Set<Integer> liveJudges = new HashSet<>();
		Phase phase = Phase.P1;
		int start = 0;
		int end = log.lastTick();
		boolean loading = false;
		boolean yamaAlive = true;
		boolean judgeSeen = false;
		int lastJudgeEnd = -TRANSITION_SETTLE_TICKS - 1;
		int previousVarbit = 0;

		for (DomainEvent event : log.getEvents())
		{
			int tick = event.getTick();
			if (event instanceof GameStateObserved)
			{
				loading = ((GameStateObserved) event).getState() == GameStateKind.LOADING;
				continue;
			}
			if (event instanceof FightEnded)
			{
				end = tick;
				break;
			}
			if (isYamaDeath(event))
			{
				yamaAlive = false;
				continue;
			}
			boolean varbitChanged = false;
			if (event instanceof VarbitObserved && ids.is(Role.PHASE_VARBIT, ((VarbitObserved) event).getVarbitId()))
			{
				int value = ((VarbitObserved) event).getValue();
				varbitChanged = value != previousVarbit && value != 0;
				previousVarbit = value;
			}

			if (phase.isJudge())
			{
				if (isJudgeSpawn(event, ids))
				{
					liveJudges.add(((NpcSpawnObserved) event).getNpcIndex());
					judgeSeen = true;
				}
				else if (isJudgeDespawn(event, ids) && (!loading || ((NpcDespawnObserved) event).isDying()))
				{
					liveJudges.remove(((NpcDespawnObserved) event).getNpcIndex());
					if (judgeSeen && liveJudges.isEmpty())
					{
						spans.add(new PhaseSpan(phase, start, tick));
						phase = next(phase);
						start = tick;
						lastJudgeEnd = tick;
					}
				}
			}
			else if (phase != Phase.P3 && yamaAlive && tick - lastJudgeEnd > TRANSITION_SETTLE_TICKS
				&& (varbitChanged || isTransitionSignal(event, ids) || isJudgeSpawn(event, ids)))
			{
				spans.add(new PhaseSpan(phase, start, tick));
				phase = next(phase);
				start = tick;
				liveJudges.clear();
				judgeSeen = false;
				if (isJudgeSpawn(event, ids))
				{
					liveJudges.add(((NpcSpawnObserved) event).getNpcIndex());
					judgeSeen = true;
				}
			}
		}
		spans.add(new PhaseSpan(phase, start, end));
		return Section.ok(new PhaseTimes(List.copyOf(spans), end));
	}

	private static Phase next(Phase phase)
	{
		return Phase.values()[phase.ordinal() + 1];
	}

	private static boolean isYamaDeath(DomainEvent event)
	{
		if (!(event instanceof NpcDespawnObserved))
		{
			return false;
		}
		NpcDespawnObserved despawn = (NpcDespawnObserved) event;
		return despawn.getActor().equals(Actor.YAMA) && despawn.isDying();
	}

	private static boolean isJudgeSpawn(DomainEvent event, IdRegistry ids)
	{
		if (!(event instanceof NpcSpawnObserved))
		{
			return false;
		}
		NpcSpawnObserved spawn = (NpcSpawnObserved) event;
		return spawn.getActor().equals(Actor.JUDGE) || ids.is(Role.JUDGE, spawn.getNpcId());
	}

	private static boolean isJudgeDespawn(DomainEvent event, IdRegistry ids)
	{
		if (!(event instanceof NpcDespawnObserved))
		{
			return false;
		}
		NpcDespawnObserved despawn = (NpcDespawnObserved) event;
		return despawn.getActor().equals(Actor.JUDGE) || ids.is(Role.JUDGE, despawn.getNpcId());
	}

	private static boolean isTransitionSignal(DomainEvent event, IdRegistry ids)
	{
		if (event instanceof OverheadTextObserved)
		{
			OverheadTextObserved text = (OverheadTextObserved) event;
			return text.getActor().equals(Actor.YAMA) && ids.matchesText(Role.PHASE_TRANSITION_TEXT, text.getText());
		}
		if (event instanceof GraphicObserved)
		{
			GraphicObserved graphic = (GraphicObserved) event;
			return graphic.getActor().equals(Actor.YAMA) && ids.is(Role.PHASE_TRANSITION_GRAPHIC, graphic.getGraphicId());
		}
		return false;
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.PhasesProjectionTest'`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/projection/PhasesProjection.java src/test/java/com/yamareviewer/domain/projection/PhasesProjectionTest.java
git commit -m "feat: derive phase times from Judge spawns and transition signals"
```

---
### Task 7: Contract and mode detection

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/projection/ContractProjection.java`, `ModeProjection.java`
- Test: `src/test/java/com/yamareviewer/domain/projection/ContractProjectionTest.java`, `ModeProjectionTest.java`

**Interfaces:**
- Consumes: Task 5 framework, `PhasesProjection` (Task 6), `Contract`, `Mode` (Task 1), `ContractRules` (Task 2), the DSL (Task 4).
- Produces: `ContractProjection` (key `Sections.CONTRACT`, no required roles, `CONTRACT_ITEM_WINDOW_TICKS = 10`, `MIN_CONTRACT_CYCLE_GAPS = 3`); `ModeProjection` (key `Sections.MODE`, no required roles). Both always produce an OK section.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/projection/ContractProjectionTest.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ContractProjectionTest
{
	private static final int WIDGET = TestIds.id(Role.CONTRACT_NAME_WIDGET);
	private static final int STANDARD_ATTACK = TestIds.id(Role.YAMA_STANDARD_ATTACK);
	private static final int JUDGE_ID = TestIds.id(Role.JUDGE);
	private static final int SHARD_ITEM = TestIds.id(Role.CONTRACT_ITEM_SHARD);

	private static Contract project(KillLog kill)
	{
		return project(kill, TestIds.registry());
	}

	private static Contract project(KillLog kill, IdRegistry ids)
	{
		ReviewBuilder builder = new ReviewBuilder(List.of(new PhasesProjection(), new ContractProjection()), ids, Rules.DEFAULT);
		return builder.run(kill, ReviewSettings.DEFAULT).value(Sections.CONTRACT).get();
	}

	/** Standard attacks every {@code cycle} ticks from tick 5 through a 60-tick P1, then the Judge. */
	private static KillLogBuilder p1WithAttacksEvery(int cycle)
	{
		KillLogBuilder kill = KillLogBuilder.kill();
		for (int tick = 5; tick < 60; tick += cycle)
		{
			kill.ticks(tick - kill.tick()).yamaAnimates(STANDARD_ATTACK);
		}
		return kill.ticks(60 - kill.tick()).npcSpawns(Actor.JUDGE, JUDGE_ID).ticks(10);
	}

	@Test
	public void theContractPlateWins()
	{
		KillLog kill = KillLogBuilder.kill()
			.inventory(SHARD_ITEM, "Shard contract", -1)
			.ticks(3).widgetText(WIDGET, "Contract of Bloodied Blows")
			.ticks(5).end(EndReason.LEFT);

		assertEquals(Contract.BLOODIED_BLOWS, project(kill));
	}

	@Test
	public void aContractItemLeavingTheInventoryEarlyIsTheContract()
	{
		KillLog kill = KillLogBuilder.kill().inventory(SHARD_ITEM, "Shard contract", -1).ticks(5).end(EndReason.LEFT);

		assertEquals(Contract.SHARD_ACQUISITION, project(kill));
	}

	@Test
	public void aContractItemLeavingLateOrArrivingIsNoCue()
	{
		assertEquals(Contract.NONE, project(KillLogBuilder.kill().ticks(10).inventory(SHARD_ITEM, "Shard contract", -1).ticks(5).end(EndReason.LEFT)));
		assertEquals(Contract.NONE, project(KillLogBuilder.kill().inventory(SHARD_ITEM, "Shard contract", 1).ticks(5).end(EndReason.LEFT)));
		assertEquals(Contract.NONE, project(KillLogBuilder.kill().widgetText(WIDGET + 1, "Contract of Bloodied Blows").ticks(5).end(EndReason.LEFT)));
	}

	@Test
	public void aSevenTickP1CycleMeansAnUnknownContract()
	{
		assertEquals(Contract.UNKNOWN_CONTRACT, project(p1WithAttacksEvery(7).end(EndReason.LEFT)));
		assertEquals(Contract.NONE, project(p1WithAttacksEvery(8).end(EndReason.LEFT)));
	}

	@Test
	public void theCycleIsNotUsedWhenPhasesAreUnknown()
	{
		assertEquals(Contract.NONE, project(p1WithAttacksEvery(7).end(EndReason.LEFT), TestIds.registryWithout(Role.JUDGE)));
		assertEquals(Contract.NONE, project(p1WithAttacksEvery(7).end(EndReason.LEFT), TestIds.registryWithout(Role.YAMA_STANDARD_ATTACK)));
	}

	@Test
	public void fewGapsAreNotEnough()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(5).yamaAnimates(STANDARD_ATTACK).ticks(7).yamaAnimates(STANDARD_ATTACK).ticks(7).yamaAnimates(STANDARD_ATTACK)
			.ticks(41).npcSpawns(Actor.JUDGE, JUDGE_ID).ticks(10).end(EndReason.LEFT);

		assertEquals(Contract.NONE, project(kill));
	}

	@Test
	public void keyAndRoles()
	{
		ContractProjection projection = new ContractProjection();

		assertEquals(Sections.CONTRACT, projection.key());
		assertEquals(Set.of(), projection.requiredRoles());
	}
}
```

`src/test/java/com/yamareviewer/domain/projection/ModeProjectionTest.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.PriceMode;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ModeProjectionTest
{
	private static final int JUDGE_ID = TestIds.id(Role.JUDGE);

	private static Mode project(KillLog kill)
	{
		return project(kill, ReviewSettings.DEFAULT);
	}

	private static Mode project(KillLog kill, ReviewSettings settings)
	{
		ReviewBuilder builder = new ReviewBuilder(List.of(new PhasesProjection(), new ModeProjection()), TestIds.registry(), Rules.DEFAULT);
		return builder.run(kill, settings).value(Sections.MODE).get();
	}

	/** Continues a builder with a Judge phase from tick 50 to 80 and ends at 100. */
	private static KillLog withJudge(KillLogBuilder kill)
	{
		return kill.ticks(50 - kill.tick()).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(20).end(EndReason.LEFT);
	}

	@Test
	public void theConfigOverrideWins()
	{
		KillLog kill = KillLogBuilder.kill().entry(EntryChoice.JOIN).playerSeen("Buddy").ticks(5).end(EndReason.LEFT);

		assertEquals(Mode.SOLO, project(kill, new ReviewSettings(PriceMode.GRAND_EXCHANGE, Mode.SOLO)));
	}

	@Test
	public void joinMeansDuoJoiner()
	{
		assertEquals(Mode.DUO_JOINER, project(KillLogBuilder.kill().entry(EntryChoice.JOIN).ticks(5).end(EndReason.LEFT)));
	}

	@Test
	public void travelWithAPartnerMeansDuoHost()
	{
		assertEquals(Mode.DUO_HOST, project(KillLogBuilder.kill().entry(EntryChoice.TRAVEL).playerSeen("Buddy").ticks(5).end(EndReason.LEFT)));
		assertEquals(Mode.DUO_HOST, project(withJudge(KillLogBuilder.kill().entry(EntryChoice.TRAVEL).ticks(70).playerSeen("Buddy"))));
	}

	@Test
	public void travelAloneMeansSolo()
	{
		assertEquals(Mode.SOLO, project(KillLogBuilder.kill().entry(EntryChoice.TRAVEL).ticks(5).end(EndReason.LEFT)));
	}

	@Test
	public void aPlayerSeenAfterTheFirstJudgeEndedIsNotAPartner()
	{
		assertEquals(Mode.SOLO, project(withJudge(KillLogBuilder.kill().entry(EntryChoice.TRAVEL).ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true).ticks(10).playerSeen("Spectator"))));
		assertEquals(Mode.SOLO, project(withJudge(KillLogBuilder.kill().ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true).ticks(1).playerSeen("Spectator"))));
	}

	@Test
	public void withoutAnEntryChoiceThePositionAtTheFirstJudgeDecides()
	{
		KillLog east = withJudge(KillLogBuilder.kill().playerSeen("Buddy").ticks(55).selfAt(3220, 3200).partnerAt(3200, 3200));
		KillLog west = withJudge(KillLogBuilder.kill().playerSeen("Buddy").ticks(55).selfAt(3200, 3200).partnerAt(3220, 3200));

		assertEquals(Mode.DUO_JOINER, project(east));
		assertEquals(Mode.DUO_HOST, project(west));
	}

	@Test
	public void withoutAnEntryChoiceAndNoPositionsAPartnerMeansDuoHost()
	{
		assertEquals(Mode.DUO_HOST, project(withJudge(KillLogBuilder.kill().playerSeen("Buddy"))));
		assertEquals(Mode.DUO_HOST, project(KillLogBuilder.kill().playerSeen("Buddy").ticks(5).end(EndReason.LEFT)));
	}

	@Test
	public void withoutAnEntryChoiceAndNobodySeenMeansSolo()
	{
		assertEquals(Mode.SOLO, project(withJudge(KillLogBuilder.kill())));
	}

	@Test
	public void keyAndRoles()
	{
		ModeProjection projection = new ModeProjection();

		assertEquals(Sections.MODE, projection.key());
		assertEquals(Set.of(), projection.requiredRoles());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.ContractProjectionTest' --tests 'com.yamareviewer.domain.projection.ModeProjectionTest'`
Expected: FAIL — `cannot find symbol` for `ContractProjection`, `ModeProjection`.

- [ ] **Step 3: Write `ContractProjection`**

`src/main/java/com/yamareviewer/domain/projection/ContractProjection.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.WidgetTextObserved;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Spec 6.3 detection, first match wins: the contract plate text, a contract item leaving the inventory in
 * the first ticks (the recorder writes a contract consumed at the challenge as an InventoryDelta at tick 0),
 * a 7-tick P1 attack cycle (UNKNOWN_CONTRACT), otherwise NONE. No role is required: an uncaptured cue is
 * simply never matched.
 */
public final class ContractProjection implements Projection<Contract>
{
	/** An InventoryDelta of a contract item before this tick counts as the contract being consumed. */
	static final int CONTRACT_ITEM_WINDOW_TICKS = 10;
	/** How many 7-tick gaps between P1 standard attacks are needed before the cycle means a contract. */
	static final int MIN_CONTRACT_CYCLE_GAPS = 3;

	@Override
	public SectionKey<Contract> key()
	{
		return Sections.CONTRACT;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return Set.of();
	}

	@Override
	public Section<Contract> project(KillLog log, ProjectionContext context)
	{
		IdRegistry ids = context.ids();
		for (WidgetTextObserved widget : log.eventsOf(WidgetTextObserved.class))
		{
			if (ids.is(Role.CONTRACT_NAME_WIDGET, widget.getComponentId()))
			{
				Optional<Contract> named = Contract.fromWidgetText(widget.getText());
				if (named.isPresent())
				{
					return Section.ok(named.get());
				}
			}
		}
		for (InventoryDelta delta : log.eventsOf(InventoryDelta.class))
		{
			if (delta.getChange() < 0 && delta.getTick() < CONTRACT_ITEM_WINDOW_TICKS)
			{
				for (Contract contract : Contract.values())
				{
					if (contract.itemRole() != null && ids.is(contract.itemRole(), delta.getItemId()))
					{
						return Section.ok(contract);
					}
				}
			}
		}
		if (hasContractCycleInP1(log, context))
		{
			return Section.ok(Contract.UNKNOWN_CONTRACT);
		}
		return Section.ok(Contract.NONE);
	}

	/** True when P1 standard attacks come every 7 ticks rather than every p1p2AttackCycle ticks. */
	private static boolean hasContractCycleInP1(KillLog log, ProjectionContext context)
	{
		IdRegistry ids = context.ids();
		Optional<PhaseSpan> p1 = context.value(Sections.PHASES).flatMap(times -> times.span(Phase.P1));
		if (p1.isEmpty() || !ids.isCaptured(Role.YAMA_STANDARD_ATTACK))
		{
			return false;
		}
		int contractCycle = ContractRules.CONTRACT_P1P2_ATTACK_CYCLE;
		int normalCycle = context.rules().getP1p2AttackCycle();
		List<Integer> attackTicks = new ArrayList<>();
		for (AnimationObserved animation : log.eventsOf(AnimationObserved.class))
		{
			if (animation.getActor().equals(Actor.YAMA) && ids.is(Role.YAMA_STANDARD_ATTACK, animation.getAnimationId())
				&& animation.getTick() >= p1.get().getStartTick() && animation.getTick() < p1.get().getEndTick())
			{
				attackTicks.add(animation.getTick());
			}
		}
		int contractGaps = 0;
		int normalGaps = 0;
		for (int i = 1; i < attackTicks.size(); i++)
		{
			int gap = attackTicks.get(i) - attackTicks.get(i - 1);
			if (gap == contractCycle)
			{
				contractGaps++;
			}
			else if (gap == normalCycle)
			{
				normalGaps++;
			}
		}
		return contractGaps >= MIN_CONTRACT_CYCLE_GAPS && contractGaps > normalGaps;
	}
}
```

- [ ] **Step 4: Write `ModeProjection`**

`src/main/java/com/yamareviewer/domain/projection/ModeProjection.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.EntryChosen;
import com.yamareviewer.domain.event.PlayerSeen;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import java.util.Optional;
import java.util.Set;

/**
 * Spec 5.4, first match wins: the config override; JOIN → duo joiner; TRAVEL → duo host when another player
 * was seen before the first Judge ended, else solo; no entry choice → with a partner, the east player at the
 * first Judge is the joiner (host when positions are unknown), nobody → solo. Always resolves.
 */
public final class ModeProjection implements Projection<Mode>
{
	@Override
	public SectionKey<Mode> key()
	{
		return Sections.MODE;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return Set.of();
	}

	@Override
	public Section<Mode> project(KillLog log, ProjectionContext context)
	{
		Mode override = context.settings().getModeOverride();
		if (override != null)
		{
			return Section.ok(override);
		}
		Optional<PhaseTimes> phases = context.value(Sections.PHASES);
		Optional<PhaseSpan> firstJudge = phases.flatMap(times -> times.span(Phase.JUDGE_1));
		int partnerDeadline = firstJudge.map(PhaseSpan::getEndTick).orElse(Integer.MAX_VALUE);
		boolean partnerSeen = log.eventsOf(PlayerSeen.class).stream().anyMatch(seen -> seen.getTick() <= partnerDeadline);
		Optional<EntryChoice> entry = log.eventsOf(EntryChosen.class).stream().map(EntryChosen::getChoice).findFirst();

		if (entry.isPresent())
		{
			if (entry.get() == EntryChoice.JOIN)
			{
				return Section.ok(Mode.DUO_JOINER);
			}
			return Section.ok(partnerSeen ? Mode.DUO_HOST : Mode.SOLO);
		}
		if (!partnerSeen)
		{
			return Section.ok(Mode.SOLO);
		}
		return Section.ok(selfIsEastAtTheJudge(log, firstJudge) ? Mode.DUO_JOINER : Mode.DUO_HOST);
	}

	/** The instance creator is placed west at the Judge (spec 3); unknown positions count as west. */
	private static boolean selfIsEastAtTheJudge(KillLog log, Optional<PhaseSpan> firstJudge)
	{
		if (firstJudge.isEmpty())
		{
			return false;
		}
		PhaseSpan judge = firstJudge.get();
		for (TickState state : log.eventsOf(TickState.class))
		{
			if (state.getTick() >= judge.getStartTick() && state.getTick() <= judge.getEndTick()
				&& state.getSelfPosition() != null && state.getPartnerPosition() != null)
			{
				return state.getSelfPosition().getX() > state.getPartnerPosition().getX();
			}
		}
		return false;
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.ContractProjectionTest' --tests 'com.yamareviewer.domain.projection.ModeProjectionTest'`
Expected: PASS (16 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/projection/ContractProjection.java src/main/java/com/yamareviewer/domain/projection/ModeProjection.java src/test/java/com/yamareviewer/domain/projection/ContractProjectionTest.java src/test/java/com/yamareviewer/domain/projection/ModeProjectionTest.java
git commit -m "feat: detect the contract and the mode of a kill"
```

---
### Task 8: FlaresProjection

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/projection/FlaresProjection.java`
- Test: `src/test/java/com/yamareviewer/domain/projection/FlaresProjectionTest.java`

**Interfaces:**
- Consumes: Task 5 framework, `FlareSummary`, `FlareWave`, `FlareResult`, `FlareCause`, `FlareFate` (Task 3), `Rules.getSpecResultWindow()` (Part 1), the DSL (Task 4).
- Produces: `FlaresProjection` (key `Sections.FLARES`, required role `VOID_FLARE`, `WAVE_GAP_TICKS = 3`, `CAUSE_WINDOW_TICKS = 5`, `EXPLOSION_WINDOW_TICKS = 1`).

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/domain/projection/FlaresProjectionTest.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.GameStateKind;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.FlareCause;
import com.yamareviewer.domain.review.FlareFate;
import com.yamareviewer.domain.review.FlareResult;
import com.yamareviewer.domain.review.FlareSummary;
import com.yamareviewer.domain.review.FlareWave;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class FlaresProjectionTest
{
	private static final int FLARE_ID = TestIds.id(Role.VOID_FLARE);
	private static final int SUMMON = TestIds.id(Role.YAMA_FLARE_SUMMON);
	private static final int MELEE = TestIds.id(Role.YAMA_MELEE);
	private static final int EXPLODE = TestIds.id(Role.FLARE_EXPLODE);
	private static final int DEATH = TestIds.id(Role.FLARE_DEATH);
	private static final int HEAL_GRAPHIC = TestIds.id(Role.FLARE_HEAL);
	private static final int PURGING_SPEC = TestIds.id(Role.SPEC_PURGING_STAFF);

	private static FlareSummary project(KillLog kill)
	{
		return project(kill, TestIds.registry());
	}

	private static FlareSummary project(KillLog kill, IdRegistry ids)
	{
		return new FlaresProjection().project(kill, new ProjectionContext(ids, Rules.DEFAULT, ReviewSettings.DEFAULT)).value();
	}

	private static Actor flare(int index)
	{
		return Actor.flare(index);
	}

	@Test
	public void aSummonedWaveWithAKillAndAnExplosion()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(20).yamaAnimates(SUMMON)
			.ticks(2).npcSpawns(flare(1), FLARE_ID).npcSpawns(flare(2), FLARE_ID)
			.ticks(10).myHitOn(flare(1), 40).animates(flare(1), DEATH).npcDespawns(flare(1), FLARE_ID, true)
			.ticks(13).animates(flare(2), EXPLODE)
			.ticks(1).yamaHealed(30).npcDespawns(flare(2), FLARE_ID, false)
			.ticks(10).end(EndReason.YAMA_DIED);

		FlareSummary summary = project(kill);

		assertEquals(2, summary.spawned());
		assertEquals(1, summary.killed());
		assertEquals(1, summary.exploded());
		assertEquals(30, summary.getYamaHealing());
		assertEquals(List.of(new FlareWave(22, 46, List.of(
			new FlareResult(1, 22, 32, FlareCause.SUMMONED, FlareFate.KILLED, false),
			new FlareResult(2, 22, 46, FlareCause.SUMMONED, FlareFate.EXPLODED, false)))), summary.getWaves());
	}

	@Test
	public void aFlareAfterMeleeIsItsOwnWaveAndAliveFlaresEndWithTheFight()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(20).yamaAnimates(SUMMON)
			.ticks(1).npcSpawns(flare(1), FLARE_ID)
			.ticks(10).yamaAnimates(MELEE)
			.ticks(1).npcSpawns(flare(2), FLARE_ID)
			.ticks(5).end(EndReason.LEFT);

		FlareSummary summary = project(kill);

		assertEquals(List.of(
			new FlareWave(21, 37, List.of(new FlareResult(1, 21, null, FlareCause.SUMMONED, FlareFate.UNKNOWN, false))),
			new FlareWave(32, 37, List.of(new FlareResult(2, 32, null, FlareCause.MELEE, FlareFate.UNKNOWN, false)))),
			summary.getWaves());
		assertEquals(FlareCause.MELEE, summary.getWaves().get(1).cause());
		assertEquals(0, summary.killed());
		assertEquals(0, summary.exploded());
	}

	@Test
	public void aHealOrHealGraphicNextToTheDespawnMeansExploded()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(10).npcSpawns(flare(1), FLARE_ID).npcSpawns(flare(2), FLARE_ID).npcSpawns(flare(3), FLARE_ID)
			.ticks(30).npcDespawns(flare(1), FLARE_ID, false)
			.ticks(1).yamaHealed(20)
			.ticks(18).graphicOn(Actor.YAMA, HEAL_GRAPHIC)
			.ticks(1).npcDespawns(flare(2), FLARE_ID, false)
			.ticks(10).npcDespawns(flare(3), FLARE_ID, false)
			.ticks(5).end(EndReason.YAMA_DIED);

		List<FlareResult> flares = project(kill).getWaves().get(0).getFlares();

		assertEquals(FlareFate.EXPLODED, flares.get(0).getFate());
		assertEquals(FlareFate.EXPLODED, flares.get(1).getFate());
		assertEquals(FlareFate.UNKNOWN, flares.get(2).getFate());
		assertEquals(Integer.valueOf(70), flares.get(2).getDespawnTick());
		assertEquals(20, project(kill).getYamaHealing());
	}

	@Test
	public void aDyingDespawnWithoutAnExplosionIsAKill()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(10).npcSpawns(flare(1), FLARE_ID)
			.ticks(5).npcDespawns(flare(1), FLARE_ID, true)
			.ticks(2).end(EndReason.YAMA_DIED);

		FlareResult flare = project(kill).getWaves().get(0).getFlares().get(0);

		assertEquals(FlareFate.KILLED, flare.getFate());
		assertEquals(FlareCause.UNKNOWN, flare.getCause());
		assertFalse(flare.isKilledByPurgingStaff());
	}

	@Test
	public void purgingStaffKillsAreCountedWhenTheRoleIsCaptured()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(10).npcSpawns(flare(1), FLARE_ID)
			.ticks(5).animates(Actor.SELF, PURGING_SPEC)
			.ticks(1).myHitOn(flare(1), 71)
			.ticks(1).animates(flare(1), DEATH).npcDespawns(flare(1), FLARE_ID, true)
			.ticks(3).end(EndReason.YAMA_DIED);

		assertTrue(project(kill).getWaves().get(0).getFlares().get(0).isKilledByPurgingStaff());
		assertEquals(1, project(kill).purgingStaffKills());
		assertFalse(project(kill, TestIds.registryWithout(Role.SPEC_PURGING_STAFF)).getWaves().get(0).getFlares().get(0).isKilledByPurgingStaff());
	}

	@Test
	public void aHitOutsideTheSpecWindowIsNotAPurgingStaffKill()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(10).npcSpawns(flare(1), FLARE_ID)
			.ticks(5).animates(Actor.SELF, PURGING_SPEC)
			.ticks(8).myHitOn(flare(1), 71).animates(flare(1), DEATH).npcDespawns(flare(1), FLARE_ID, true)
			.ticks(2).end(EndReason.YAMA_DIED);

		assertFalse(project(kill).getWaves().get(0).getFlares().get(0).isKilledByPurgingStaff());
	}

	@Test
	public void aSceneReloadDoesNotRespawnAFlare()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(10).npcSpawns(flare(1), FLARE_ID)
			.ticks(5).gameState(GameStateKind.LOADING).npcDespawns(flare(1), FLARE_ID, false).npcSpawns(flare(1), FLARE_ID)
			.ticks(1).gameState(GameStateKind.LOGGED_IN)
			.ticks(10).animates(flare(1), DEATH).npcDespawns(flare(1), FLARE_ID, true)
			.ticks(2).end(EndReason.YAMA_DIED);

		FlareSummary summary = project(kill);

		assertEquals(1, summary.spawned());
		assertEquals(1, summary.killed());
		assertEquals(Integer.valueOf(26), summary.getWaves().get(0).getFlares().get(0).getDespawnTick());
	}

	@Test
	public void needsTheFlareRole()
	{
		FlaresProjection projection = new FlaresProjection();

		assertEquals(Sections.FLARES, projection.key());
		assertEquals(Set.of(Role.VOID_FLARE), projection.requiredRoles());
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.FlaresProjectionTest'`
Expected: FAIL — `cannot find symbol: class FlaresProjection`.

- [ ] **Step 3: Write `FlaresProjection`**

`src/main/java/com/yamareviewer/domain/projection/FlaresProjection.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ActorKind;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.GameStateKind;
import com.yamareviewer.domain.event.GameStateObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.FlareCause;
import com.yamareviewer.domain.review.FlareFate;
import com.yamareviewer.domain.review.FlareResult;
import com.yamareviewer.domain.review.FlareSummary;
import com.yamareviewer.domain.review.FlareWave;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spec 6.8. A flare is spawned by its first NpcSpawnObserved (a respawn inside a scene reload adds nothing);
 * waves are spawns within 3 ticks of each other; the cause is the latest YAMA_FLARE_SUMMON or YAMA_MELEE in
 * the 5 ticks before the spawn. Exploded: FLARE_EXPLODE on the flare, or a heal hitsplat on Yama or FLARE_HEAL
 * on Yama within 1 tick of the despawn. Killed: FLARE_DEATH or a dying despawn without an explosion; killed
 * by the purging staff when the last own hit on the flare lies within specResultWindow ticks after a
 * SPEC_PURGING_STAFF animation of the local player (never when that role is uncaptured).
 */
public final class FlaresProjection implements Projection<FlareSummary>
{
	static final int WAVE_GAP_TICKS = 3;
	static final int CAUSE_WINDOW_TICKS = 5;
	static final int EXPLOSION_WINDOW_TICKS = 1;

	@Override
	public SectionKey<FlareSummary> key()
	{
		return Sections.FLARES;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.VOID_FLARE);
	}

	@Override
	public Section<FlareSummary> project(KillLog log, ProjectionContext context)
	{
		IdRegistry ids = context.ids();
		int specWindow = context.rules().getSpecResultWindow();
		Map<Integer, Tracked> flares = new LinkedHashMap<>();
		List<Integer> summonTicks = new ArrayList<>();
		List<Integer> meleeTicks = new ArrayList<>();
		List<Integer> purgingTicks = new ArrayList<>();
		List<Integer> explosionSignalTicks = new ArrayList<>();
		int yamaHealing = 0;
		boolean loading = false;

		for (DomainEvent event : log.getEvents())
		{
			int tick = event.getTick();
			if (event instanceof GameStateObserved)
			{
				loading = ((GameStateObserved) event).getState() == GameStateKind.LOADING;
			}
			else if (event instanceof NpcSpawnObserved)
			{
				NpcSpawnObserved spawn = (NpcSpawnObserved) event;
				if (isFlare(spawn.getActor(), spawn.getNpcId(), ids))
				{
					int index = indexOf(spawn.getActor(), spawn.getNpcIndex());
					flares.computeIfAbsent(index, i -> new Tracked(i, tick, causeOf(tick, summonTicks, meleeTicks)));
				}
			}
			else if (event instanceof NpcDespawnObserved)
			{
				NpcDespawnObserved despawn = (NpcDespawnObserved) event;
				Tracked flare = isFlare(despawn.getActor(), despawn.getNpcId(), ids) ? flares.get(indexOf(despawn.getActor(), despawn.getNpcIndex())) : null;
				if (flare != null && flare.despawnTick == null && (!loading || despawn.isDying()))
				{
					flare.despawnTick = tick;
					flare.dying = despawn.isDying();
				}
			}
			else if (event instanceof AnimationObserved)
			{
				AnimationObserved animation = (AnimationObserved) event;
				Actor actor = animation.getActor();
				int id = animation.getAnimationId();
				if (actor.equals(Actor.YAMA) && ids.is(Role.YAMA_FLARE_SUMMON, id))
				{
					summonTicks.add(tick);
				}
				else if (actor.equals(Actor.YAMA) && ids.is(Role.YAMA_MELEE, id))
				{
					meleeTicks.add(tick);
				}
				else if (actor.equals(Actor.SELF) && ids.is(Role.SPEC_PURGING_STAFF, id))
				{
					purgingTicks.add(tick);
				}
				else if (actor.getKind() == ActorKind.FLARE && flares.containsKey(actor.getRef()))
				{
					Tracked flare = flares.get(actor.getRef());
					if (ids.is(Role.FLARE_EXPLODE, id))
					{
						flare.exploded = true;
					}
					else if (ids.is(Role.FLARE_DEATH, id))
					{
						flare.deathAnimation = true;
					}
				}
			}
			else if (event instanceof HitsplatObserved)
			{
				HitsplatObserved hitsplat = (HitsplatObserved) event;
				Actor target = hitsplat.getTarget();
				if (target.getKind() == ActorKind.FLARE && hitsplat.getKind() == HitsplatKind.DAMAGE && hitsplat.isMine()
					&& flares.containsKey(target.getRef()))
				{
					flares.get(target.getRef()).lastOwnHitTick = tick;
				}
				else if (target.equals(Actor.YAMA) && hitsplat.getKind() == HitsplatKind.HEAL)
				{
					yamaHealing += hitsplat.getAmount();
					explosionSignalTicks.add(tick);
				}
			}
			else if (event instanceof GraphicObserved)
			{
				GraphicObserved graphic = (GraphicObserved) event;
				if (graphic.getActor().equals(Actor.YAMA) && ids.is(Role.FLARE_HEAL, graphic.getGraphicId()))
				{
					explosionSignalTicks.add(tick);
				}
			}
		}

		int fightEnd = log.lastTick();
		List<FlareResult> results = new ArrayList<>();
		for (Tracked flare : flares.values())
		{
			boolean exploded = flare.exploded
				|| (flare.despawnTick != null && near(explosionSignalTicks, flare.despawnTick, EXPLOSION_WINDOW_TICKS));
			FlareFate fate = exploded ? FlareFate.EXPLODED : (flare.deathAnimation || flare.dying) ? FlareFate.KILLED : FlareFate.UNKNOWN;
			boolean purging = fate == FlareFate.KILLED && flare.lastOwnHitTick != null
				&& purgingTicks.stream().anyMatch(spec -> flare.lastOwnHitTick >= spec && flare.lastOwnHitTick <= spec + specWindow);
			results.add(new FlareResult(flare.index, flare.spawnTick, flare.despawnTick, flare.cause, fate, purging));
		}

		List<FlareWave> waves = new ArrayList<>();
		List<FlareResult> current = new ArrayList<>();
		int previousSpawn = 0;
		for (FlareResult result : results)
		{
			if (!current.isEmpty() && result.getSpawnTick() - previousSpawn > WAVE_GAP_TICKS)
			{
				waves.add(wave(current, fightEnd));
				current = new ArrayList<>();
			}
			current.add(result);
			previousSpawn = result.getSpawnTick();
		}
		if (!current.isEmpty())
		{
			waves.add(wave(current, fightEnd));
		}
		return Section.ok(new FlareSummary(List.copyOf(waves), yamaHealing));
	}

	private static boolean isFlare(Actor actor, int npcId, IdRegistry ids)
	{
		return actor.getKind() == ActorKind.FLARE || ids.is(Role.VOID_FLARE, npcId);
	}

	private static int indexOf(Actor actor, int npcIndex)
	{
		return actor.getKind() == ActorKind.FLARE ? actor.getRef() : npcIndex;
	}

	/** The latest summon or melee animation in the window before the spawn; a tie goes to the summon. */
	private static FlareCause causeOf(int spawnTick, List<Integer> summonTicks, List<Integer> meleeTicks)
	{
		int summon = latestWithin(summonTicks, spawnTick, CAUSE_WINDOW_TICKS);
		int melee = latestWithin(meleeTicks, spawnTick, CAUSE_WINDOW_TICKS);
		if (summon < 0 && melee < 0)
		{
			return FlareCause.UNKNOWN;
		}
		return summon >= melee ? FlareCause.SUMMONED : FlareCause.MELEE;
	}

	/** The latest tick in [tick - window, tick], or -1. */
	private static int latestWithin(List<Integer> ticks, int tick, int window)
	{
		int latest = -1;
		for (int candidate : ticks)
		{
			if (candidate <= tick && tick - candidate <= window)
			{
				latest = candidate;
			}
		}
		return latest;
	}

	private static boolean near(List<Integer> ticks, int tick, int window)
	{
		for (int candidate : ticks)
		{
			if (Math.abs(candidate - tick) <= window)
			{
				return true;
			}
		}
		return false;
	}

	private static FlareWave wave(List<FlareResult> flares, int fightEnd)
	{
		int lastDespawn = fightEnd;
		for (FlareResult flare : flares)
		{
			lastDespawn = Math.max(flares.get(0).getSpawnTick(), flare.getDespawnTick() == null ? fightEnd : flare.getDespawnTick());
		}
		int firstSpawn = flares.get(0).getSpawnTick();
		int last = firstSpawn;
		for (FlareResult flare : flares)
		{
			last = Math.max(last, flare.getDespawnTick() == null ? fightEnd : flare.getDespawnTick());
		}
		return new FlareWave(firstSpawn, last, List.copyOf(flares));
	}

	/** Mutable bookkeeping for one flare while the events are scanned. */
	private static final class Tracked
	{
		private final int index;
		private final int spawnTick;
		private final FlareCause cause;
		private Integer despawnTick;
		private boolean dying;
		private boolean exploded;
		private boolean deathAnimation;
		private Integer lastOwnHitTick;

		private Tracked(int index, int spawnTick, FlareCause cause)
		{
			this.index = index;
			this.spawnTick = spawnTick;
			this.cause = cause;
		}
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.FlaresProjectionTest'`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/projection/FlaresProjection.java src/test/java/com/yamareviewer/domain/projection/FlaresProjectionTest.java
git commit -m "feat: review void flares: waves, causes, fates and purging staff kills"
```

---
### Task 9: DamageAttributionProjection (rules 2 and 5–10)

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/projection/DamageAttributionProjection.java`
- Test: `src/test/java/com/yamareviewer/domain/projection/DamageAttributionProjectionTest.java`

**Interfaces:**
- Consumes: Task 5 framework, `PhasesProjection` (Task 6), `ContractProjection` (Task 7), `DamageSummary`, `DamageHit`, `DamageSource` (Task 3), `ContractRules.isShadowPools()` (Task 2), the DSL (Task 4).
- Produces: `DamageAttributionProjection` (key `Sections.DAMAGE`, required roles `YAMA_MELEE`, `FLARE_HIT`, `FLARE_EXPLODE`, `FIRE_STREAK`, `METEOR_STRIKE`, `SHADOW_STOMP`, `FIRE_ATTACK`, `GLYPH_PROTECTION`) with the package-private `SourceRule` interface and `List<SourceRule> rules(KillLog, ProjectionContext)`, which Part 3 extends with rules 1, 3 and 4.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/domain/projection/DamageAttributionProjectionTest.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.DamageHit;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class DamageAttributionProjectionTest
{
	private static final int MELEE = TestIds.id(Role.YAMA_MELEE);
	private static final int FLARE_HIT = TestIds.id(Role.FLARE_HIT);
	private static final int FLARE_EXPLODE = TestIds.id(Role.FLARE_EXPLODE);
	private static final int FIRE_STREAK = TestIds.id(Role.FIRE_STREAK);
	private static final int SHADOW_STOMP = TestIds.id(Role.SHADOW_STOMP);
	private static final int METEOR_STRIKE = TestIds.id(Role.METEOR_STRIKE);
	private static final int FIRE_ATTACK = TestIds.id(Role.FIRE_ATTACK);
	private static final int GLYPH_PROTECTION = TestIds.id(Role.GLYPH_PROTECTION);
	private static final int SHADOW_POOL = TestIds.id(Role.SHADOW_POOL);
	private static final int WIDGET = TestIds.id(Role.CONTRACT_NAME_WIDGET);
	private static final int JUDGE_ID = TestIds.id(Role.JUDGE);

	private static Section<DamageSummary> section(KillLog kill, IdRegistry ids)
	{
		ReviewBuilder builder = new ReviewBuilder(
			List.of(new PhasesProjection(), new ContractProjection(), new DamageAttributionProjection()), ids, Rules.DEFAULT);
		return builder.run(kill, ReviewSettings.DEFAULT).section(Sections.DAMAGE);
	}

	private static DamageSummary project(KillLog kill)
	{
		return section(kill, TestIds.registry()).value();
	}

	private static List<DamageSource> sources(DamageSummary summary)
	{
		return summary.getHits().stream().map(DamageHit::getSource).collect(toList());
	}

	@Test
	public void meleeWithinTwoTicksAndSplashOnTheOtherPlayer()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(10).yamaAnimates(MELEE).yamaTarget(Actor.SELF).endTick()
			.hitsplatOn(Actor.SELF, 20).hitsplatOn(Actor.PARTNER, 15)
			.ticks(2).hitsplatOn(Actor.SELF, 9)
			.end(EndReason.YAMA_DIED);

		DamageSummary summary = project(kill);

		assertEquals(List.of(DamageSource.MELEE, DamageSource.MELEE_SPLASH, DamageSource.OTHER), sources(summary));
		assertEquals(29, summary.total(Actor.SELF));
		assertEquals(15, summary.total(Actor.PARTNER));
		assertEquals(15, summary.shared());
	}

	@Test
	public void flareHitsAndExplosionsWithinOneTick()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(20).graphicOn(Actor.SELF, FLARE_HIT)
			.ticks(1).hitsplatOn(Actor.SELF, 40)
			.ticks(9).animates(Actor.flare(3), FLARE_EXPLODE).hitsplatOn(Actor.PARTNER, 42)
			.ticks(2).hitsplatOn(Actor.SELF, 5)
			.end(EndReason.YAMA_DIED);

		assertEquals(List.of(DamageSource.FLARE, DamageSource.FLARE, DamageSource.OTHER), sources(project(kill)));
	}

	@Test
	public void fireStreaksWithinTwoTicks()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(40).groundGraphic(FIRE_STREAK, 3210, 3200)
			.ticks(2).hitsplatOn(Actor.SELF, 30)
			.ticks(1).hitsplatOn(Actor.SELF, 30)
			.end(EndReason.YAMA_DIED);

		assertEquals(List.of(DamageSource.FIRE_STREAK, DamageSource.OTHER), sources(project(kill)));
	}

	@Test
	public void specialsFromStompMeteorFireAttackAndGlyphProtection()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).yamaAnimates(SHADOW_STOMP).ticks(1).hitsplatOn(Actor.SELF, 25)
			.ticks(9).graphicOn(Actor.SELF, METEOR_STRIKE).hitsplatOn(Actor.SELF, 30)
			.ticks(10).groundGraphic(FIRE_ATTACK, 3200, 3200).ticks(2).hitsplatOn(Actor.PARTNER, 12)
			.ticks(8).graphicOn(Actor.PARTNER, GLYPH_PROTECTION).ticks(1).hitsplatOn(Actor.SELF, 20)
			.end(EndReason.YAMA_DIED);

		DamageSummary summary = project(kill);

		assertEquals(List.of(DamageSource.SPECIAL, DamageSource.SPECIAL, DamageSource.SPECIAL, DamageSource.SPECIAL), sources(summary));
		assertEquals(87, summary.shared());
	}

	@Test
	public void shadowPoolsOnlyUnderAPoolContract()
	{
		KillLog oathplate = KillLogBuilder.kill().widgetText(WIDGET, "Contract of Oathplate Acquisition")
			.ticks(90).groundGraphic(SHADOW_POOL, 3205, 3200)
			.ticks(1).hitsplatOn(Actor.SELF, 13)
			.ticks(4).selfAt(3205, 3200).hitsplatOn(Actor.SELF, 12).endTick()
			.selfAt(3200, 3200).hitsplatOn(Actor.SELF, 12)
			.end(EndReason.YAMA_DIED);
		KillLog bloodied = KillLogBuilder.kill().widgetText(WIDGET, "Contract of Bloodied Blows")
			.ticks(90).groundGraphic(SHADOW_POOL, 3205, 3200)
			.ticks(1).hitsplatOn(Actor.SELF, 13)
			.ticks(4).selfAt(3205, 3200).hitsplatOn(Actor.SELF, 12).endTick()
			.selfAt(3200, 3200).hitsplatOn(Actor.SELF, 12)
			.end(EndReason.YAMA_DIED);

		assertEquals(List.of(DamageSource.SHADOW_POOL, DamageSource.SHADOW_POOL, DamageSource.OTHER), sources(project(oathplate)));
		assertEquals(List.of(DamageSource.OTHER, DamageSource.OTHER, DamageSource.OTHER), sources(project(bloodied)));
		assertEquals(Optional.of(HiddenReason.IDS_NOT_CAPTURED), section(oathplate, TestIds.registryWithout(Role.SHADOW_POOL)).hiddenReason());
		assertEquals(Optional.empty(), section(bloodied, TestIds.registryWithout(Role.SHADOW_POOL)).hiddenReason());
	}

	@Test
	public void damageDuringAJudgePhaseIsTheJudges()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(5).hitsplatOn(Actor.SELF, 10)
			.ticks(25).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(5).hitsplatOn(Actor.SELF, 10)
			.end(EndReason.YAMA_DIED);

		DamageSummary summary = project(kill);

		assertEquals(List.of(DamageSource.JUDGE, DamageSource.OTHER), sources(summary));
		assertEquals(Phase.JUDGE_1, summary.getHits().get(0).getPhase());
		assertEquals(Phase.P2, summary.getHits().get(1).getPhase());
		assertEquals(10, summary.total(Actor.SELF, Phase.JUDGE_1));
	}

	@Test
	public void earlierRulesWin()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(10).yamaAnimates(MELEE).yamaTarget(Actor.SELF).endTick()
			.graphicOn(Actor.SELF, FLARE_HIT).hitsplatOn(Actor.SELF, 20)
			.end(EndReason.YAMA_DIED);

		assertEquals(List.of(DamageSource.MELEE), sources(project(kill)));
	}

	@Test
	public void onlyDamageOnPlayersCounts()
	{
		KillLog kill = KillLogBuilder.kill()
			.hitsplatOn(Actor.YAMA, 30).myHitOn(Actor.YAMA, 40).hitsplatOn(Actor.SELF, 0).yamaHealed(5).hitsplatOn(Actor.SELF, 7)
			.end(EndReason.YAMA_DIED);

		assertEquals(List.of(new DamageHit(0, Phase.P1, Actor.SELF, DamageSource.OTHER, 7)), project(kill).getHits());
	}

	@Test
	public void hitsWithoutKnownPhasesHaveNoPhase()
	{
		KillLog kill = KillLogBuilder.kill().ticks(5).hitsplatOn(Actor.SELF, 7).end(EndReason.YAMA_DIED);

		DamageSummary summary = section(kill, TestIds.registryWithout(Role.JUDGE)).value();

		assertNull(summary.getHits().get(0).getPhase());
		assertEquals(7, summary.total(Actor.SELF));
	}

	@Test
	public void keyAndRoles()
	{
		DamageAttributionProjection projection = new DamageAttributionProjection();

		assertEquals(Sections.DAMAGE, projection.key());
		assertEquals(EnumSet.of(Role.YAMA_MELEE, Role.FLARE_HIT, Role.FLARE_EXPLODE, Role.FIRE_STREAK, Role.METEOR_STRIKE,
			Role.SHADOW_STOMP, Role.FIRE_ATTACK, Role.GLYPH_PROTECTION), projection.requiredRoles());
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.DamageAttributionProjectionTest'`
Expected: FAIL — `cannot find symbol: class DamageAttributionProjection`.

- [ ] **Step 3: Write `DamageAttributionProjection`**

`src/main/java/com/yamareviewer/domain/projection/DamageAttributionProjection.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ActorKind;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.DamageHit;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spec 6.10: every damage hitsplat on SELF or PARTNER gets exactly one source, first matching rule wins.
 * Part 2 applies rules 2 and 5–10; Part 3 adds rule 1 (STANDARD, from Sections.ATTACKS) and rules 3 and 4
 * (SHADOW_CRASH from Sections.CRASHES, SHADOW_WAVE from Sections.WAVES) in {@link #rules}.
 * Under a shadow-pool contract the section is hidden when SHADOW_POOL is uncaptured, so pool damage is
 * never shown as OTHER.
 */
public final class DamageAttributionProjection implements Projection<DamageSummary>
{
	static final int MELEE_WINDOW_TICKS = 2;
	static final int FLARE_WINDOW_TICKS = 1;
	static final int STREAK_WINDOW_TICKS = 2;
	static final int SPECIAL_WINDOW_TICKS = 2;
	static final int POOL_WINDOW_TICKS = 1;

	private static final Set<Role> REQUIRED = EnumSet.of(Role.YAMA_MELEE, Role.FLARE_HIT, Role.FLARE_EXPLODE, Role.FIRE_STREAK,
		Role.METEOR_STRIKE, Role.SHADOW_STOMP, Role.FIRE_ATTACK, Role.GLYPH_PROTECTION);

	/** One rule of spec 6.10; the first rule that answers decides. */
	interface SourceRule
	{
		Optional<DamageSource> apply(HitsplatObserved hit);
	}

	@Override
	public SectionKey<DamageSummary> key()
	{
		return Sections.DAMAGE;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.copyOf(REQUIRED);
	}

	@Override
	public Section<DamageSummary> project(KillLog log, ProjectionContext context)
	{
		if (context.contractRules().isShadowPools() && !context.ids().isCaptured(Role.SHADOW_POOL))
		{
			return Section.hidden(HiddenReason.IDS_NOT_CAPTURED);
		}
		Optional<PhaseTimes> phases = context.value(Sections.PHASES);
		List<SourceRule> rules = rules(log, context);
		List<DamageHit> hits = new ArrayList<>();
		for (HitsplatObserved hit : log.eventsOf(HitsplatObserved.class))
		{
			if (hit.getKind() != HitsplatKind.DAMAGE || !hit.getTarget().isPlayer())
			{
				continue;
			}
			DamageSource source = DamageSource.OTHER;
			for (SourceRule rule : rules)
			{
				Optional<DamageSource> answer = rule.apply(hit);
				if (answer.isPresent())
				{
					source = answer.get();
					break;
				}
			}
			Phase phase = phases.flatMap(times -> times.phaseAt(hit.getTick())).orElse(null);
			hits.add(new DamageHit(hit.getTick(), phase, hit.getTarget(), source, hit.getAmount()));
		}
		return Section.ok(new DamageSummary(List.copyOf(hits)));
	}

	/**
	 * The rules in spec order. Part 3 inserts rule 1 (STANDARD) at the front and rules 3 and 4
	 * (SHADOW_CRASH, SHADOW_WAVE) right after the melee rule.
	 */
	List<SourceRule> rules(KillLog log, ProjectionContext context)
	{
		IdRegistry ids = context.ids();
		Map<Integer, TickState> states = new HashMap<>();
		log.eventsOf(TickState.class).forEach(state -> states.put(state.getTick(), state));
		List<Integer> meleeTicks = yamaAnimationTicks(log, ids, Role.YAMA_MELEE);
		Map<Actor, List<Integer>> flareHitTicks = playerGraphicTicks(log, ids, Role.FLARE_HIT);
		List<Integer> explodeTicks = flareAnimationTicks(log, ids, Role.FLARE_EXPLODE);
		List<Integer> streakTicks = graphicTicks(log, ids, Role.FIRE_STREAK);
		List<Integer> specialTicks = new ArrayList<>();
		specialTicks.addAll(graphicTicks(log, ids, Role.METEOR_STRIKE));
		specialTicks.addAll(graphicTicks(log, ids, Role.FIRE_ATTACK));
		specialTicks.addAll(graphicTicks(log, ids, Role.GLYPH_PROTECTION));
		specialTicks.addAll(yamaAnimationTicks(log, ids, Role.SHADOW_STOMP));
		specialTicks.addAll(meteorNpcTicks(log, ids));
		List<GroundGraphicObserved> pools = context.contractRules().isShadowPools()
			? log.eventsOf(GroundGraphicObserved.class).stream().filter(graphic -> ids.is(Role.SHADOW_POOL, graphic.getGraphicId())).collect(Collectors.toList())
			: List.of();
		Optional<PhaseTimes> phases = context.value(Sections.PHASES);

		List<SourceRule> rules = new ArrayList<>();
		// Part 3: rule 1, STANDARD (the landing hitsplat of a standard attack), goes here.
		rules.add(hit -> lastAtOrBefore(meleeTicks, hit.getTick(), MELEE_WINDOW_TICKS).map(melee ->
		{
			TickState state = states.get(melee);
			Actor target = state == null ? null : state.getYamaTarget();
			boolean splash = target != null && target.isPlayer() && !target.equals(hit.getTarget());
			return splash ? DamageSource.MELEE_SPLASH : DamageSource.MELEE;
		}));
		// Part 3: rules 3 and 4, SHADOW_CRASH and SHADOW_WAVE, go here.
		rules.add(hit -> within(flareHitTicks.getOrDefault(hit.getTarget(), List.of()), hit.getTick(), FLARE_WINDOW_TICKS)
			|| within(explodeTicks, hit.getTick(), FLARE_WINDOW_TICKS)
			? Optional.of(DamageSource.FLARE) : Optional.<DamageSource>empty());
		rules.add(hit -> within(streakTicks, hit.getTick(), STREAK_WINDOW_TICKS)
			? Optional.of(DamageSource.FIRE_STREAK) : Optional.<DamageSource>empty());
		rules.add(hit -> within(specialTicks, hit.getTick(), SPECIAL_WINDOW_TICKS)
			? Optional.of(DamageSource.SPECIAL) : Optional.<DamageSource>empty());
		rules.add(hit -> onAPool(hit, pools, states) ? Optional.of(DamageSource.SHADOW_POOL) : Optional.<DamageSource>empty());
		rules.add(hit -> phases.flatMap(times -> times.phaseAt(hit.getTick())).filter(Phase::isJudge).map(phase -> DamageSource.JUDGE));
		return rules;
	}

	private static List<Integer> yamaAnimationTicks(KillLog log, IdRegistry ids, Role role)
	{
		return log.eventsOf(AnimationObserved.class).stream()
			.filter(animation -> animation.getActor().equals(Actor.YAMA) && ids.is(role, animation.getAnimationId()))
			.map(AnimationObserved::getTick)
			.collect(Collectors.toList());
	}

	private static List<Integer> flareAnimationTicks(KillLog log, IdRegistry ids, Role role)
	{
		return log.eventsOf(AnimationObserved.class).stream()
			.filter(animation -> animation.getActor().getKind() == ActorKind.FLARE && ids.is(role, animation.getAnimationId()))
			.map(AnimationObserved::getTick)
			.collect(Collectors.toList());
	}

	/** Ticks of the role's graphics on any actor plus its ground graphics. */
	private static List<Integer> graphicTicks(KillLog log, IdRegistry ids, Role role)
	{
		List<Integer> ticks = new ArrayList<>();
		for (GraphicObserved graphic : log.eventsOf(GraphicObserved.class))
		{
			if (ids.is(role, graphic.getGraphicId()))
			{
				ticks.add(graphic.getTick());
			}
		}
		for (GroundGraphicObserved graphic : log.eventsOf(GroundGraphicObserved.class))
		{
			if (ids.is(role, graphic.getGraphicId()))
			{
				ticks.add(graphic.getTick());
			}
		}
		return ticks;
	}

	private static Map<Actor, List<Integer>> playerGraphicTicks(KillLog log, IdRegistry ids, Role role)
	{
		Map<Actor, List<Integer>> ticks = new HashMap<>();
		for (GraphicObserved graphic : log.eventsOf(GraphicObserved.class))
		{
			if (graphic.getActor().isPlayer() && ids.is(role, graphic.getGraphicId()))
			{
				ticks.computeIfAbsent(graphic.getActor(), actor -> new ArrayList<>()).add(graphic.getTick());
			}
		}
		return ticks;
	}

	/** The meteor NPC's spawn and despawn both mark a Meteor Strike. */
	private static List<Integer> meteorNpcTicks(KillLog log, IdRegistry ids)
	{
		List<Integer> ticks = new ArrayList<>();
		for (NpcSpawnObserved spawn : log.eventsOf(NpcSpawnObserved.class))
		{
			if (ids.is(Role.METEOR_NPC, spawn.getNpcId()))
			{
				ticks.add(spawn.getTick());
			}
		}
		for (NpcDespawnObserved despawn : log.eventsOf(NpcDespawnObserved.class))
		{
			if (ids.is(Role.METEOR_NPC, despawn.getNpcId()))
			{
				ticks.add(despawn.getTick());
			}
		}
		return ticks;
	}

	/** True when some tick t has |tick - t| <= window. */
	private static boolean within(List<Integer> ticks, int tick, int window)
	{
		for (int candidate : ticks)
		{
			if (Math.abs(candidate - tick) <= window)
			{
				return true;
			}
		}
		return false;
	}

	/** The latest tick t with t <= tick <= t + window. */
	private static Optional<Integer> lastAtOrBefore(List<Integer> ticks, int tick, int window)
	{
		Integer latest = null;
		for (int candidate : ticks)
		{
			if (candidate <= tick && tick - candidate <= window)
			{
				latest = candidate;
			}
		}
		return Optional.ofNullable(latest);
	}

	/** A pool created within 1 tick of the hit, or earlier under the tile the player stands on at the hit. */
	private static boolean onAPool(HitsplatObserved hit, List<GroundGraphicObserved> pools, Map<Integer, TickState> states)
	{
		Position standingOn = positionOf(hit.getTarget(), states.get(hit.getTick()));
		for (GroundGraphicObserved pool : pools)
		{
			if (Math.abs(hit.getTick() - pool.getTick()) <= POOL_WINDOW_TICKS)
			{
				return true;
			}
			if (pool.getTick() <= hit.getTick() && standingOn != null && standingOn.equals(pool.getPosition()))
			{
				return true;
			}
		}
		return false;
	}

	private static Position positionOf(Actor player, TickState state)
	{
		if (state == null)
		{
			return null;
		}
		return player.equals(Actor.SELF) ? state.getSelfPosition() : state.getPartnerPosition();
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.DamageAttributionProjectionTest'`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/projection/DamageAttributionProjection.java src/test/java/com/yamareviewer/domain/projection/DamageAttributionProjectionTest.java
git commit -m "feat: attribute damage taken to melee, flares, streaks, specials, pools and Judges"
```

---
### Task 10: SuppliesProjection

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/projection/SuppliesProjection.java`
- Test: `src/test/java/com/yamareviewer/domain/projection/SuppliesProjectionTest.java`

**Interfaces:**
- Consumes: Task 5 framework, `ContractProjection` (Task 7), `SupplySummary`, `SupplyLine`, `PriceMode` (Task 3), `ContractRules.isConsumablesHaveNoEffect()` (Task 2), `SuppliesSnapshot`, `SupplyItem` (Part 1), the DSL (Task 4).
- Produces: `SuppliesProjection` (key `Sections.SUPPLIES`, no required roles). Uses the last `START` and the last `END` snapshot of the log. A `LEFT` kill without `END` → hidden `NOT_APPLICABLE`; any other kill missing a snapshot throws `IllegalStateException` (so `ReviewBuilder` hides it with `ERROR` and Part 4's `SuppliesSnapshot` check can still fail it).

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/domain/projection/SuppliesProjectionTest.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SupplyItem;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PriceMode;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.SupplyLine;
import com.yamareviewer.domain.review.SupplySummary;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class SuppliesProjectionTest
{
	private static final int WIDGET = TestIds.id(Role.CONTRACT_NAME_WIDGET);
	private static final SupplyItem SHARK_5 = new SupplyItem(385, "Shark", 5, 800, 90);
	private static final SupplyItem SHARK_3 = new SupplyItem(385, "Shark", 3, 900, 90);

	private static Section<SupplySummary> section(KillLog kill, ReviewSettings settings)
	{
		ReviewBuilder builder = new ReviewBuilder(List.of(new ContractProjection(), new SuppliesProjection()), TestIds.registry(), Rules.DEFAULT);
		return builder.run(kill, settings).section(Sections.SUPPLIES);
	}

	private static SupplySummary project(KillLog kill)
	{
		return section(kill, ReviewSettings.DEFAULT).value();
	}

	private static SupplyItem potion(String name, int quantity, int gePrice)
	{
		return new SupplyItem(name.hashCode(), name, quantity, gePrice, gePrice / 10);
	}

	@Test
	public void onlyDecreasesCountPricedFromTheStartSnapshot()
	{
		KillLog kill = KillLogBuilder.kill()
			.supplies(SnapshotKind.START, SHARK_5, new SupplyItem(13441, "Anglerfish", 2, 2000, 100), new SupplyItem(11212, "Dragon arrow", 100, 1500, 300))
			.ticks(5)
			.supplies(SnapshotKind.END, SHARK_3, new SupplyItem(13441, "Anglerfish", 4, 2000, 100), new SupplyItem(11212, "Dragon arrow", 90, 1500, 300))
			.end(EndReason.YAMA_DIED);

		SupplySummary summary = project(kill);

		assertEquals(List.of(
			new SupplyLine("Shark", 2, "x", 1_600L, false),
			new SupplyLine("Dragon arrow", 10, "x", 15_000L, false)), summary.getLines());
		assertEquals(16_600L, summary.getTotalCost());
		assertEquals(PriceMode.GRAND_EXCHANGE, summary.getPriceMode());
	}

	@Test
	public void potionsAreCountedInDosesPricedByTheHighestDoseVariant()
	{
		KillLog kill = KillLogBuilder.kill()
			.supplies(SnapshotKind.START, potion("Super restore(4)", 3, 12_000), potion("Super restore(2)", 1, 5_000))
			.ticks(5)
			.supplies(SnapshotKind.END, potion("Super restore(4)", 1, 12_000), potion("Super restore(2)", 1, 5_000), potion("Super restore(1)", 1, 2_000))
			.end(EndReason.YAMA_DIED);

		assertEquals(List.of(new SupplyLine("Super restore", 7, "doses", 21_000L, false)), project(kill).getLines());
	}

	@Test
	public void endOnlyPotionVariantsCountAsRemainingDoses()
	{
		KillLog kill = KillLogBuilder.kill()
			.supplies(SnapshotKind.START, potion("Super restore(4)", 3, 12_000))
			.ticks(5)
			.supplies(SnapshotKind.END, potion("Super restore(4)", 1, 12_000), potion("Super restore(2)", 1, 5_000))
			.end(EndReason.YAMA_DIED);

		assertEquals(List.of(new SupplyLine("Super restore", 6, "doses", 18_000L, false)), project(kill).getLines());
	}

	@Test
	public void anItemMissingFromTheEndSnapshotIsFullyUsed()
	{
		KillLog kill = KillLogBuilder.kill().supplies(SnapshotKind.START, SHARK_5).ticks(5).supplies(SnapshotKind.END).end(EndReason.YAMA_DIED);

		assertEquals(List.of(new SupplyLine("Shark", 5, "x", 4_000L, false)), project(kill).getLines());
	}

	@Test
	public void highAlchemyPricesWhenConfigured()
	{
		KillLog kill = KillLogBuilder.kill().supplies(SnapshotKind.START, SHARK_5).ticks(5).supplies(SnapshotKind.END, SHARK_3).end(EndReason.YAMA_DIED);

		SupplySummary summary = section(kill, new ReviewSettings(PriceMode.HIGH_ALCHEMY, null)).value();

		assertEquals(List.of(new SupplyLine("Shark", 2, "x", 180L, false)), summary.getLines());
		assertEquals(PriceMode.HIGH_ALCHEMY, summary.getPriceMode());
	}

	@Test
	public void aLeftKillWithoutAnEndSnapshotHidesSupplies()
	{
		KillLog kill = KillLogBuilder.kill().supplies(SnapshotKind.START, SHARK_5).ticks(3).end(EndReason.LEFT);

		assertEquals(Section.hidden(HiddenReason.NOT_APPLICABLE), section(kill, ReviewSettings.DEFAULT));
	}

	@Test
	public void aLeftKillWithAnEndSnapshotIsPriced()
	{
		KillLog kill = KillLogBuilder.kill().supplies(SnapshotKind.START, SHARK_5).ticks(3).supplies(SnapshotKind.END, SHARK_3).end(EndReason.LEFT);

		assertEquals(List.of(new SupplyLine("Shark", 2, "x", 1_600L, false)), project(kill).getLines());
	}

	@Test
	public void aFinishedKillWithoutAnEndSnapshotIsAnError()
	{
		KillLog kill = KillLogBuilder.kill().supplies(SnapshotKind.START, SHARK_5).ticks(3).event(new FightEnded(3, EndReason.YAMA_DIED)).build();

		assertEquals(Section.hidden(HiddenReason.ERROR), section(kill, ReviewSettings.DEFAULT));
	}

	@Test(expected = IllegalStateException.class)
	public void aMissingSnapshotThrowsSoTheBuilderLogsIt()
	{
		KillLog kill = KillLogBuilder.kill().ticks(3).event(new FightEnded(3, EndReason.PLAYER_DIED)).build();

		new SuppliesProjection().project(kill, new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT));
	}

	@Test
	public void underFamiliarAcquisitionFoodAndPotionsHaveNoEffectButRunesAndAmmunitionDo()
	{
		KillLog kill = KillLogBuilder.kill()
			.widgetText(WIDGET, "Contract of Familiar Acquisition")
			.supplies(SnapshotKind.START, SHARK_5, potion("Prayer potion(4)", 2, 10_000), new SupplyItem(565, "Blood rune", 500, 300, 60),
				new SupplyItem(11212, "Dragon arrow", 100, 1500, 300))
			.ticks(5)
			.supplies(SnapshotKind.END, SHARK_3, potion("Prayer potion(4)", 1, 10_000), new SupplyItem(565, "Blood rune", 400, 300, 60),
				new SupplyItem(11212, "Dragon arrow", 90, 1500, 300))
			.end(EndReason.YAMA_DIED);

		SupplySummary summary = project(kill);

		assertEquals(List.of(
			new SupplyLine("Shark", 2, "x", 1_600L, true),
			new SupplyLine("Prayer potion", 4, "doses", 10_000L, true),
			new SupplyLine("Blood rune", 100, "x", 30_000L, false),
			new SupplyLine("Dragon arrow", 10, "x", 15_000L, false)), summary.getLines());
		assertEquals(56_600L, summary.getTotalCost());
	}

	@Test
	public void keyAndRoles()
	{
		SuppliesProjection projection = new SuppliesProjection();

		assertEquals(Sections.SUPPLIES, projection.key());
		assertEquals(Set.of(), projection.requiredRoles());
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.SuppliesProjectionTest'`
Expected: FAIL — `cannot find symbol: class SuppliesProjection`.

- [ ] **Step 3: Write `SuppliesProjection`**

`src/main/java/com/yamareviewer/domain/projection/SuppliesProjection.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.SupplyItem;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PriceMode;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.SupplyLine;
import com.yamareviewer.domain.review.SupplySummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spec 6.11. Compares the last START and END snapshots; only decreases count. Potions are grouped by their
 * base name (without the dose suffix) and counted in doses; a dose costs the highest-dose START variant
 * divided by its doses. Prices are the snapshot's, in the configured source. Under Familiar Acquisition
 * every consumed line except runes and ammunition is marked "no effect".
 */
public final class SuppliesProjection implements Projection<SupplySummary>
{
	private static final Pattern DOSES = Pattern.compile("^(.*?)\\((\\d)\\)$");
	/** Name fragments of items that still work under Familiar Acquisition (runes and ammunition). */
	private static final List<String> STILL_EFFECTIVE = List.of(" rune", "arrow", "bolt", "dart", "javelin", "knife", "thrownaxe", "scales", "chinchompa");

	@Override
	public SectionKey<SupplySummary> key()
	{
		return Sections.SUPPLIES;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return Set.of();
	}

	@Override
	public Section<SupplySummary> project(KillLog log, ProjectionContext context)
	{
		Optional<SuppliesSnapshot> start = last(log, SnapshotKind.START);
		Optional<SuppliesSnapshot> end = last(log, SnapshotKind.END);
		if (end.isEmpty() && log.endReason() == EndReason.LEFT)
		{
			return Section.hidden(HiddenReason.NOT_APPLICABLE);
		}
		if (start.isEmpty() || end.isEmpty())
		{
			throw new IllegalStateException("Supplies snapshots missing: start=" + start.isPresent() + ", end=" + end.isPresent());
		}
		PriceMode mode = context.settings().getPriceMode();
		boolean noEffect = context.contractRules().isConsumablesHaveNoEffect();

		Map<String, Group> groups = new LinkedHashMap<>();
		for (SupplyItem item : start.get().getItems())
		{
			Matcher potion = DOSES.matcher(item.getName());
			if (potion.matches())
			{
				String base = potion.group(1).trim();
				int doses = Integer.parseInt(potion.group(2));
				Group group = groups.computeIfAbsent("potion:" + base, key -> new Group(base, "doses"));
				group.startUnits += item.getQuantity() * doses;
				if (doses > group.priceDoses)
				{
					group.priceDoses = doses;
					group.unitPrice = price(item, mode) / (double) doses;
				}
			}
			else
			{
				Group group = groups.computeIfAbsent("item:" + item.getItemId(), key -> new Group(item.getName(), "x"));
				group.startUnits += item.getQuantity();
				group.unitPrice = price(item, mode);
			}
		}
		for (SupplyItem item : end.get().getItems())
		{
			Matcher potion = DOSES.matcher(item.getName());
			boolean isPotion = potion.matches();
			Group group = groups.get(isPotion ? "potion:" + potion.group(1).trim() : "item:" + item.getItemId());
			if (group != null)
			{
				group.endUnits += isPotion ? item.getQuantity() * Integer.parseInt(potion.group(2)) : item.getQuantity();
			}
		}

		List<SupplyLine> lines = new ArrayList<>();
		long total = 0;
		for (Group group : groups.values())
		{
			int used = group.startUnits - group.endUnits;
			if (used <= 0)
			{
				continue;
			}
			long cost = Math.round(used * group.unitPrice);
			lines.add(new SupplyLine(group.name, used, group.unit, cost, noEffect && affectedByFamiliar(group)));
			total += cost;
		}
		return Section.ok(new SupplySummary(List.copyOf(lines), total, mode));
	}

	private static Optional<SuppliesSnapshot> last(KillLog log, SnapshotKind kind)
	{
		SuppliesSnapshot last = null;
		for (SuppliesSnapshot snapshot : log.eventsOf(SuppliesSnapshot.class))
		{
			if (snapshot.getKind() == kind)
			{
				last = snapshot;
			}
		}
		return Optional.ofNullable(last);
	}

	private static int price(SupplyItem item, PriceMode mode)
	{
		return mode == PriceMode.GRAND_EXCHANGE ? item.getGePrice() : item.getHaPrice();
	}

	/** Potions and food do nothing under Familiar Acquisition; runes and ammunition still work. */
	private static boolean affectedByFamiliar(Group group)
	{
		if ("doses".equals(group.unit))
		{
			return true;
		}
		String name = group.name.toLowerCase(Locale.ROOT);
		return STILL_EFFECTIVE.stream().noneMatch(name::contains);
	}

	/** One consumed line while the snapshots are compared. */
	private static final class Group
	{
		private final String name;
		private final String unit;
		private int startUnits;
		private int endUnits;
		private int priceDoses;
		private double unitPrice;

		private Group(String name, String unit)
		{
			this.name = name;
			this.unit = unit;
		}
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.SuppliesProjectionTest'`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/projection/SuppliesProjection.java src/test/java/com/yamareviewer/domain/projection/SuppliesProjectionTest.java
git commit -m "feat: price the supplies used from the log's snapshots"
```

---
### Task 11: DeathRecapProjection

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/projection/DeathRecapProjection.java`
- Test: `src/test/java/com/yamareviewer/domain/projection/DeathRecapProjectionTest.java`

**Interfaces:**
- Consumes: Task 5 framework, `PhasesProjection`, `ContractProjection`, `DamageAttributionProjection` (Tasks 6, 7, 9), `DeathRecap`, `RecapTick`, `RecapHit` (Task 3), `ContractRules.isRunEnergyDeath()` (Task 2), the DSL (Task 4).
- Produces: `DeathRecapProjection` (key `Sections.DEATH_RECAP`, no required roles, `RECAP_TICKS = 10`). Only `PLAYER_DIED` kills get a recap; the death tick is the `FightEnded` tick.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/domain/projection/DeathRecapProjectionTest.java`:

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.DeathRecapProjectionTest'`
Expected: FAIL — `cannot find symbol: class DeathRecapProjection`.

- [ ] **Step 3: Write `DeathRecapProjection`**

`src/main/java/com/yamareviewer/domain/projection/DeathRecapProjection.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.DamageHit;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.RecapHit;
import com.yamareviewer.domain.review.RecapTick;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Spec 6.12: for a PLAYER_DIED kill, the 10 ticks up to and including the death tick (the FightEnded tick).
 * Hit sources come from the damage section; when it is hidden every hit is OTHER. Run energy is shown only
 * under Forfeit Breath, and a death with 0% run energy there is "out of run energy".
 */
public final class DeathRecapProjection implements Projection<DeathRecap>
{
	static final int RECAP_TICKS = 10;

	@Override
	public SectionKey<DeathRecap> key()
	{
		return Sections.DEATH_RECAP;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return Set.of();
	}

	@Override
	public Section<DeathRecap> project(KillLog log, ProjectionContext context)
	{
		if (log.endReason() != EndReason.PLAYER_DIED)
		{
			return Section.hidden(HiddenReason.NOT_APPLICABLE);
		}
		List<FightEnded> ended = log.eventsOf(FightEnded.class);
		int deathTick = ended.get(ended.size() - 1).getTick();
		int firstTick = Math.max(0, deathTick - RECAP_TICKS + 1);
		boolean showRunEnergy = context.contractRules().isRunEnergyDeath();

		Map<Integer, TickState> states = new HashMap<>();
		log.eventsOf(TickState.class).forEach(state -> states.put(state.getTick(), state));
		Map<Integer, List<RecapHit>> hits = hitsByTick(log, context.value(Sections.DAMAGE));
		Map<Integer, List<String>> consumed = new HashMap<>();
		for (InventoryDelta delta : log.eventsOf(InventoryDelta.class))
		{
			if (delta.getChange() < 0)
			{
				consumed.computeIfAbsent(delta.getTick(), tick -> new ArrayList<>()).add(delta.getName());
			}
		}

		List<RecapTick> ticks = new ArrayList<>();
		for (int tick = firstTick; tick <= deathTick; tick++)
		{
			TickState state = states.get(tick);
			ticks.add(new RecapTick(
				tick,
				List.copyOf(hits.getOrDefault(tick, List.of())),
				state == null ? -1 : state.getHitpoints(),
				state == null ? -1 : state.getPrayerPoints(),
				showRunEnergy && state != null ? state.getRunEnergy() : null,
				targetLabel(state),
				List.copyOf(consumed.getOrDefault(tick, List.of()))));
		}
		TickState death = states.get(deathTick);
		boolean outOfRunEnergy = showRunEnergy && death != null && death.getRunEnergy() == 0;
		return Section.ok(new DeathRecap(List.copyOf(ticks), outOfRunEnergy));
	}

	private static Map<Integer, List<RecapHit>> hitsByTick(KillLog log, Optional<DamageSummary> damage)
	{
		Map<Integer, List<RecapHit>> hits = new HashMap<>();
		if (damage.isPresent())
		{
			for (DamageHit hit : damage.get().getHits())
			{
				if (hit.getPlayer().equals(Actor.SELF))
				{
					hits.computeIfAbsent(hit.getTick(), tick -> new ArrayList<>()).add(new RecapHit(hit.getSource(), hit.getAmount()));
				}
			}
			return hits;
		}
		for (HitsplatObserved hitsplat : log.eventsOf(HitsplatObserved.class))
		{
			if (hitsplat.getTarget().equals(Actor.SELF) && hitsplat.getKind() == HitsplatKind.DAMAGE)
			{
				hits.computeIfAbsent(hitsplat.getTick(), tick -> new ArrayList<>()).add(new RecapHit(DamageSource.OTHER, hitsplat.getAmount()));
			}
		}
		return hits;
	}

	private static String targetLabel(TickState state)
	{
		if (state == null || state.getYamaTarget() == null)
		{
			return "nobody";
		}
		if (state.getYamaTarget().equals(Actor.SELF))
		{
			return "you";
		}
		return state.getYamaTarget().equals(Actor.PARTNER) ? "partner" : "other";
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.DeathRecapProjectionTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/projection/DeathRecapProjection.java src/test/java/com/yamareviewer/domain/projection/DeathRecapProjectionTest.java
git commit -m "feat: add the death recap of the last ten ticks"
```

---
### Task 12: Projections.standard, KillReviewAssembler and review storage

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/projection/Projections.java`, `KillReviewAssembler.java`
- Create: `src/main/java/com/yamareviewer/application/port/ReviewRepository.java`
- Create: `src/main/java/com/yamareviewer/adapter/persistence/GsonReviewRepository.java`
- Test: `src/test/java/com/yamareviewer/domain/projection/ProjectionsTest.java`, `KillReviewAssemblerTest.java`, `src/test/java/com/yamareviewer/adapter/persistence/GsonReviewRepositoryTest.java`

**Interfaces:**
- Consumes: all projections (Tasks 6–11), `KillReview` (Task 3), `FileStore`, `InMemoryFileStore` (Part 1 Task 5), `Reviews` (Task 3).
- Produces: `Projections.standard()` (Phases, Contract, Mode, Flares, DamageAttribution, Supplies, DeathRecap; Part 3 inserts its projections here); `KillReviewAssembler.assemble(KillLog, ProjectionContext)`; port `ReviewRepository { save(KillReview); loadAll(); prune(int keepPerKey) }` (all `throws IOException`); `GsonReviewRepository(FileStore, Gson)` with package-private static `path(KillReview)` → `reviews/<MODE>/<13-digit startEpochMs>-<killId>.json`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/projection/ProjectionsTest.java`:

```java
package com.yamareviewer.domain.projection;

import java.util.List;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ProjectionsTest
{
	@Test
	public void theStandardOrderOfSpec61()
	{
		List<SectionKey<?>> keys = Projections.standard().stream().map(Projection::key).collect(toList());

		assertEquals(List.of(Sections.PHASES, Sections.CONTRACT, Sections.MODE, Sections.FLARES, Sections.DAMAGE, Sections.SUPPLIES,
			Sections.DEATH_RECAP), keys);
	}
}
```

`src/test/java/com/yamareviewer/domain/projection/KillReviewAssemblerTest.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SupplyItem;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.SupplyLine;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class KillReviewAssemblerTest
{
	private static final int JUDGE_ID = TestIds.id(Role.JUDGE);

	private static KillLog fullKill()
	{
		return KillLogBuilder.kill().entry(EntryChoice.TRAVEL)
			.supplies(SnapshotKind.START, new SupplyItem(385, "Shark", 5, 800, 90))
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(60).hitsplatOn(Actor.SELF, 12)
			.supplies(SnapshotKind.END, new SupplyItem(385, "Shark", 3, 800, 90))
			.end(EndReason.YAMA_DIED);
	}

	private static KillReview assemble(KillLog kill, IdRegistry ids)
	{
		ReviewBuilder builder = new ReviewBuilder(Projections.standard(), ids, Rules.DEFAULT);
		return KillReviewAssembler.assemble(kill, builder.run(kill, ReviewSettings.DEFAULT));
	}

	@Test
	public void aFullSyntheticKillAssemblesACompleteReview()
	{
		KillLog kill = fullKill();

		KillReview review = assemble(kill, TestIds.registry());

		assertEquals(KillReview.SCHEMA_VERSION, review.getReviewSchemaVersion());
		assertEquals("test-kill", review.getKillId());
		assertEquals(kill.getHeader().getStartEpochMs(), review.getStartEpochMs());
		assertEquals(kill.getHeader().getEndEpochMs(), review.getEndEpochMs());
		assertEquals(EndReason.YAMA_DIED, review.getEndReason());
		assertEquals(Mode.SOLO, review.getMode());
		assertEquals(Contract.NONE, review.getContract());
		assertEquals(ReviewStatus.COMPLETE, review.getStatus());
		assertEquals(0, review.getSkippedEvents());
		assertEquals(5, review.getPhases().value().getSpans().size());
		assertEquals(220, review.getPhases().value().getTotalTicks());
		assertEquals(12, review.getDamage().value().total(Actor.SELF));
		assertEquals(0, review.getFlares().value().spawned());
		assertEquals(List.of(new SupplyLine("Shark", 2, "x", 1_600L, false)), review.getSupplies().value().getLines());
		assertEquals(Section.hidden(HiddenReason.NOT_APPLICABLE), review.getDeathRecap());
		assertTrue(review.allSections().stream().allMatch(section -> section.isOk() || section.hiddenReason().get() == HiddenReason.NOT_APPLICABLE));
	}

	@Test
	public void skippedEventsMakeTheReviewIncomplete()
	{
		KillLog kill = fullKill();
		KillLog withSkipped = KillLog.of(kill.getHeader(), kill.getEvents(), 1);

		KillReview review = assemble(withSkipped, TestIds.registry());

		assertEquals(1, review.getSkippedEvents());
		assertEquals(ReviewStatus.INCOMPLETE, review.getStatus());
	}

	@Test
	public void hiddenSectionsAreCarriedOverWithoutMakingTheReviewIncomplete()
	{
		KillReview review = assemble(fullKill(), TestIds.registryWithout(Role.JUDGE, Role.VOID_FLARE));

		assertEquals(Section.hidden(HiddenReason.IDS_NOT_CAPTURED), review.getPhases());
		assertEquals(Section.hidden(HiddenReason.IDS_NOT_CAPTURED), review.getFlares());
		assertEquals(ReviewStatus.COMPLETE, review.getStatus());
		assertEquals(Mode.SOLO, review.getMode());
	}
}
```

`src/test/java/com/yamareviewer/adapter/persistence/GsonReviewRepositoryTest.java`:

```java
package com.yamareviewer.adapter.persistence;

import com.google.gson.Gson;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.RecapHit;
import com.yamareviewer.domain.review.RecapTick;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Reviews;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GsonReviewRepositoryTest
{
	private final InMemoryFileStore files = new InMemoryFileStore();
	private final GsonReviewRepository repository = new GsonReviewRepository(files, new Gson());

	private static KillReview review(String id, long start, Mode mode, Contract contract)
	{
		return Reviews.builder(id, start, mode, contract).build();
	}

	private List<String> loadedIds() throws IOException
	{
		return repository.loadAll().stream().map(KillReview::getKillId).collect(toList());
	}

	@Test
	public void savedReviewLoadsBackEqual() throws IOException
	{
		KillReview review = Reviews.builder("a", 1_000L, Mode.DUO_JOINER, Contract.BLOODIED_BLOWS)
			.endReason(EndReason.PLAYER_DIED)
			.flares(Section.hidden(HiddenReason.IDS_NOT_CAPTURED))
			.deathRecap(Section.ok(new DeathRecap(List.of(
				new RecapTick(419, List.of(new RecapHit(DamageSource.MELEE, 22)), 12, 5, null, "you", List.of("Shark")),
				new RecapTick(420, List.of(), 0, 5, 7, "partner", List.of())), false)))
			.build();

		repository.save(review);

		assertEquals(List.of(review), repository.loadAll());
		assertTrue(files.paths().contains("reviews/DUO_JOINER/0000000001000-a.json"));
		assertEquals("reviews/DUO_JOINER/0000000001000-a.json", GsonReviewRepository.path(review));
	}

	@Test
	public void loadsNewestFirstAcrossModes() throws IOException
	{
		repository.save(review("a", 1_000L, Mode.SOLO, Contract.NONE));
		repository.save(review("c", 3_000L, Mode.DUO_HOST, Contract.NONE));
		repository.save(review("b", 2_000L, Mode.SOLO, Contract.NONE));

		assertEquals(List.of("c", "b", "a"), loadedIds());
	}

	@Test
	public void pruneKeepsTheNewestPerModeAndContract() throws IOException
	{
		repository.save(review("s1", 1_000L, Mode.SOLO, Contract.NONE));
		repository.save(review("s2", 2_000L, Mode.SOLO, Contract.NONE));
		repository.save(review("s3", 3_000L, Mode.SOLO, Contract.NONE));
		repository.save(review("b1", 1_500L, Mode.SOLO, Contract.BLOODIED_BLOWS));
		repository.save(review("b2", 2_500L, Mode.SOLO, Contract.BLOODIED_BLOWS));
		repository.save(review("h1", 100L, Mode.DUO_HOST, Contract.NONE));

		repository.prune(2);

		assertEquals(List.of("s3", "b2", "s2", "b1", "h1"), loadedIds());
	}

	@Test
	public void otherSchemaVersionsAreSkippedButKept() throws IOException
	{
		repository.save(review("current", 2_000L, Mode.SOLO, Contract.NONE));
		repository.save(Reviews.builder("future", 3_000L, Mode.SOLO, Contract.NONE).reviewSchemaVersion(KillReview.SCHEMA_VERSION + 1).build());

		assertEquals(List.of("current"), loadedIds());
		assertEquals(2, files.list("reviews/SOLO").size());
	}

	@Test
	public void corruptFileIsRenamedAndSkipped() throws IOException
	{
		repository.save(review("good", 2_000L, Mode.SOLO, Contract.NONE));
		files.write("reviews/SOLO/0000000000500-bad.json", "{not json".getBytes(StandardCharsets.UTF_8));

		assertEquals(List.of("good"), loadedIds());
		assertTrue(files.paths().contains("reviews/SOLO/0000000000500-bad.json.corrupt"));
		assertFalse(files.paths().contains("reviews/SOLO/0000000000500-bad.json"));
	}

	@Test
	public void anEmptyStoreLoadsNothing() throws IOException
	{
		assertEquals(List.of(), repository.loadAll());
		repository.prune(5);
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.ProjectionsTest' --tests 'com.yamareviewer.domain.projection.KillReviewAssemblerTest' --tests 'com.yamareviewer.adapter.persistence.GsonReviewRepositoryTest'`
Expected: FAIL — `cannot find symbol` for `Projections`, `KillReviewAssembler`, `GsonReviewRepository`.

- [ ] **Step 3: Write `Projections` and `KillReviewAssembler`**

`src/main/java/com/yamareviewer/domain/projection/Projections.java`:

```java
package com.yamareviewer.domain.projection;

import java.util.List;

/**
 * The ordered projection list of spec 6.1. Part 3 inserts Glyphs, Attacks, Crashes, Waves before Flares;
 * Specs after Flares; PrayerReview, Opener, TickLog before DamageAttribution.
 */
public final class Projections
{
	private Projections()
	{
	}

	public static List<Projection<?>> standard()
	{
		return List.of(
			new PhasesProjection(),
			new ContractProjection(),
			new ModeProjection(),
			new FlaresProjection(),
			new DamageAttributionProjection(),
			new SuppliesProjection(),
			new DeathRecapProjection());
	}
}
```

`src/main/java/com/yamareviewer/domain/projection/KillReviewAssembler.java`:

```java
package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.ReviewStatus;

/** Builds the KillReview from a finished context. Part 3 sets its fields here too. */
public final class KillReviewAssembler
{
	private KillReviewAssembler()
	{
	}

	public static KillReview assemble(KillLog log, ProjectionContext context)
	{
		KillHeader header = log.getHeader();
		return KillReview.builder()
			.reviewSchemaVersion(KillReview.SCHEMA_VERSION)
			.killId(header.getKillId())
			.startEpochMs(header.getStartEpochMs())
			.endEpochMs(header.getEndEpochMs())
			.endReason(log.endReason())
			.mode(context.value(Sections.MODE).orElse(Mode.SOLO))
			.contract(context.value(Sections.CONTRACT).orElse(Contract.NONE))
			.status(ReviewStatus.COMPLETE)
			.skippedEvents(log.getSkippedEvents())
			.phases(context.section(Sections.PHASES))
			.damage(context.section(Sections.DAMAGE))
			.flares(context.section(Sections.FLARES))
			.supplies(context.section(Sections.SUPPLIES))
			.deathRecap(context.section(Sections.DEATH_RECAP))
			.build()
			.withRecomputedStatus();
	}
}
```

- [ ] **Step 4: Write the port and `GsonReviewRepository`**

`src/main/java/com/yamareviewer/application/port/ReviewRepository.java`:

```java
package com.yamareviewer.application.port;

import com.yamareviewer.domain.review.KillReview;
import java.io.IOException;
import java.util.List;

/** Stores the read model: one review per kill. */
public interface ReviewRepository
{
	void save(KillReview review) throws IOException;

	/** Newest first; files with another reviewSchemaVersion are skipped, unreadable files renamed .corrupt. */
	List<KillReview> loadAll() throws IOException;

	/** Keeps the newest keepPerKey reviews per HistoryKey (mode and contract). */
	void prune(int keepPerKey) throws IOException;
}
```

`src/main/java/com/yamareviewer/adapter/persistence/GsonReviewRepository.java`:

```java
package com.yamareviewer.adapter.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.history.HistoryKey;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.KillReview;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/** reviews/<MODE>/<startEpochMs, 13 digits>-<killId>.json, one KillReview per file (spec 7.5). */
@Slf4j
public final class GsonReviewRepository implements ReviewRepository
{
	private static final String REVIEWS = "reviews";
	private static final String SUFFIX = ".json";

	private final FileStore files;
	private final Gson gson;

	public GsonReviewRepository(FileStore files, Gson gson)
	{
		this.files = files;
		this.gson = gson;
	}

	@Override
	public void save(KillReview review) throws IOException
	{
		files.write(path(review), gson.toJson(review).getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public List<KillReview> loadAll() throws IOException
	{
		List<Stored> stored = new ArrayList<>();
		for (Mode mode : Mode.values())
		{
			String dir = REVIEWS + "/" + mode.name();
			for (String name : files.list(dir))
			{
				if (!name.endsWith(SUFFIX))
				{
					continue;
				}
				String path = dir + "/" + name;
				try
				{
					parse(new String(files.read(path), StandardCharsets.UTF_8)).ifPresent(review -> stored.add(new Stored(name, review)));
				}
				catch (IOException | RuntimeException e)
				{
					log.warn("Unreadable review {}, renaming it to .corrupt", path, e);
					files.rename(path, path + ".corrupt");
				}
			}
		}
		stored.sort(Comparator.comparing(Stored::getName).reversed());
		return stored.stream().map(Stored::getReview).collect(Collectors.toList());
	}

	@Override
	public void prune(int keepPerKey) throws IOException
	{
		Map<HistoryKey, Integer> kept = new HashMap<>();
		for (KillReview review : loadAll())
		{
			int count = kept.merge(new HistoryKey(review.getMode(), review.getContract()), 1, Integer::sum);
			if (count > keepPerKey)
			{
				files.delete(path(review));
			}
		}
	}

	static String path(KillReview review)
	{
		return REVIEWS + "/" + review.getMode().name() + "/" + String.format("%013d-%s%s", review.getStartEpochMs(), review.getKillId(), SUFFIX);
	}

	/** Empty for a review of another schema version. */
	private Optional<KillReview> parse(String json)
	{
		JsonObject object = gson.fromJson(json, JsonObject.class);
		JsonElement version = object.get("reviewSchemaVersion");
		if (version == null || version.getAsInt() != KillReview.SCHEMA_VERSION)
		{
			return Optional.empty();
		}
		return Optional.of(gson.fromJson(object, KillReview.class));
	}

	@Value
	private static class Stored
	{
		String name;
		KillReview review;
	}
}
```

`HistoryKey` is written in Task 13; until then, create it now with exactly the code of Task 13 Step 3 (`src/main/java/com/yamareviewer/domain/history/HistoryKey.java`, a `@Value` class with `Mode mode; Contract contract;`) so this task compiles on its own:

```java
package com.yamareviewer.domain.history;

import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import lombok.Value;

/** Kills are only compared like for like: per mode and per contract (spec 6.3). */
@Value
public class HistoryKey
{
	Mode mode;
	Contract contract;
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.projection.ProjectionsTest' --tests 'com.yamareviewer.domain.projection.KillReviewAssemblerTest' --tests 'com.yamareviewer.adapter.persistence.GsonReviewRepositoryTest'`
Expected: PASS (10 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/projection src/main/java/com/yamareviewer/domain/history/HistoryKey.java src/main/java/com/yamareviewer/application/port/ReviewRepository.java src/main/java/com/yamareviewer/adapter/persistence/GsonReviewRepository.java src/test/java/com/yamareviewer/domain/projection src/test/java/com/yamareviewer/adapter/persistence/GsonReviewRepositoryTest.java
git commit -m "feat: assemble kill reviews and store them per mode as JSON"
```

---
### Task 13: History: summaries, averages, personal bests, trends and the loader

**Files:**
- Create in `src/main/java/com/yamareviewer/domain/history/`: `KillSummary.java`, `Trend.java`, `HistoryView.java`, `HistoryIndex.java`, `HistoryProjector.java` (`HistoryKey.java` exists from Task 12)
- Create: `src/main/java/com/yamareviewer/application/port/ReviewPublisher.java`, `src/main/java/com/yamareviewer/application/handler/HistoryLoader.java`
- Test: `src/test/java/com/yamareviewer/domain/history/HistoryProjectorTest.java`, `src/test/java/com/yamareviewer/application/handler/HistoryLoaderTest.java`

**Interfaces:**
- Consumes: `KillReview`, `PhaseTimes`, `SupplySummary` (Task 3), `ReviewRepository` (Task 12), `Reviews` (Task 3).
- Produces: `KillSummary` (`@Value @Builder(toBuilder = true)`: `killId`, `startEpochMs`, `int killTicks` (-1 when the phases are hidden), `endReason`, `status`, `Long cost`, `Double p3Accuracy`, `Integer defenceDrained`, `Double specLandedShare`; the last three null until Part 3); `Trend(Double last10Mean, Double previous10Mean)`; `HistoryView(key, kills newest first, averageKillTicks, averageP3Accuracy, averageCost, fastestKillTicks, bestP3Accuracy, killTicksTrend, p3AccuracyTrend, costTrend, defenceDrainedTrend)`; `HistoryIndex(Map<HistoryKey, HistoryView>)` with `view(key)`; `HistoryProjector.summarize(KillReview)` and `index(List<KillReview>, int historySize)`; port `ReviewPublisher { publish(KillReview, HistoryIndex); showHistory(HistoryIndex) }`; `HistoryLoader(ExecutorService, ReviewRepository, ReviewPublisher, IntSupplier historySize)` with `load()`. Kill-time statistics use `YAMA_DIED` kills with known ticks only.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/history/HistoryProjectorTest.java`:

```java
package com.yamareviewer.domain.history;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.PriceMode;
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.SupplySummary;
import com.yamareviewer.testing.Reviews;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class HistoryProjectorTest
{
	private static final HistoryKey SOLO_NONE = new HistoryKey(Mode.SOLO, Contract.NONE);

	private static KillReview kill(String id, long start, int ticks, long cost, EndReason endReason)
	{
		return Reviews.builder(id, start, Mode.SOLO, Contract.NONE)
			.endReason(endReason)
			.phases(Section.ok(new PhaseTimes(List.of(new PhaseSpan(Phase.P1, 0, ticks)), ticks)))
			.supplies(Section.ok(new SupplySummary(List.of(), cost, PriceMode.GRAND_EXCHANGE)))
			.build();
	}

	private static List<String> ids(HistoryView view)
	{
		return view.getKills().stream().map(KillSummary::getKillId).collect(toList());
	}

	@Test
	public void summarizeTakesTicksCostAndStatus()
	{
		KillSummary summary = HistoryProjector.summarize(Reviews.sample());

		assertEquals(KillSummary.builder().killId("kill-1").startEpochMs(1_700_000_000_000L).killTicks(420).endReason(EndReason.YAMA_DIED)
			.status(ReviewStatus.COMPLETE).cost(318_450L).p3Accuracy(null).defenceDrained(null).specLandedShare(null).build(), summary);
	}

	@Test
	public void hiddenPhasesOrSuppliesGiveUnknownTicksAndCost()
	{
		KillReview review = Reviews.builder("k", 1L, Mode.SOLO, Contract.NONE)
			.phases(Section.hidden(HiddenReason.HEALTH_CHECK_FAILED))
			.supplies(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.build();

		KillSummary summary = HistoryProjector.summarize(review);

		assertEquals(-1, summary.getKillTicks());
		assertNull(summary.getCost());
	}

	@Test
	public void indexGroupsByModeAndContractNewestFirstAndLimits()
	{
		List<KillReview> reviews = List.of(
			kill("a", 1_000L, 400, 100, EndReason.YAMA_DIED),
			kill("c", 3_000L, 400, 100, EndReason.YAMA_DIED),
			kill("b", 2_000L, 400, 100, EndReason.YAMA_DIED),
			Reviews.builder("bb", 500L, Mode.SOLO, Contract.BLOODIED_BLOWS).build(),
			Reviews.builder("h", 100L, Mode.DUO_HOST, Contract.NONE).build());

		HistoryIndex index = HistoryProjector.index(reviews, 2);

		assertEquals(List.of("c", "b"), ids(index.view(SOLO_NONE).get()));
		assertEquals(List.of("bb"), ids(index.view(new HistoryKey(Mode.SOLO, Contract.BLOODIED_BLOWS)).get()));
		assertEquals(List.of("h"), ids(index.view(new HistoryKey(Mode.DUO_HOST, Contract.NONE)).get()));
		assertEquals(Optional.empty(), index.view(new HistoryKey(Mode.DUO_JOINER, Contract.NONE)));
		assertEquals(SOLO_NONE, index.view(SOLO_NONE).get().getKey());
	}

	@Test
	public void averagesAndPersonalBests()
	{
		HistoryView view = HistoryProjector.index(List.of(
			kill("a", 1_000L, 420, 100, EndReason.YAMA_DIED),
			kill("b", 2_000L, 400, 200, EndReason.YAMA_DIED),
			kill("c", 3_000L, 500, 300, EndReason.YAMA_DIED)), 50).view(SOLO_NONE).get();

		assertEquals(440.0, view.getAverageKillTicks(), 0.001);
		assertEquals(Integer.valueOf(400), view.getFastestKillTicks());
		assertEquals(200.0, view.getAverageCost(), 0.001);
		assertNull(view.getAverageP3Accuracy());
		assertNull(view.getBestP3Accuracy());
		assertNull(view.getKillTicksTrend().getPrevious10Mean());
		assertEquals(440.0, view.getKillTicksTrend().getLast10Mean(), 0.001);
		assertEquals(new Trend(null, null), view.getP3AccuracyTrend());
		assertEquals(new Trend(null, null), view.getDefenceDrainedTrend());
	}

	@Test
	public void deathsAndUnknownTicksAreLeftOutOfTheTimeStatistics()
	{
		KillReview unknownTicks = Reviews.builder("u", 3_000L, Mode.SOLO, Contract.NONE).phases(Section.hidden(HiddenReason.ERROR)).build();

		HistoryView view = HistoryProjector.index(List.of(
			kill("a", 1_000L, 400, 100, EndReason.YAMA_DIED),
			kill("d", 2_000L, 300, 100, EndReason.PLAYER_DIED),
			unknownTicks), 50).view(SOLO_NONE).get();

		assertEquals(List.of("u", "d", "a"), ids(view));
		assertEquals(400.0, view.getAverageKillTicks(), 0.001);
		assertEquals(Integer.valueOf(400), view.getFastestKillTicks());
		assertEquals(3, view.getKills().size());
	}

	@Test
	public void noFinishedKillsMeansNoTimeStatistics()
	{
		HistoryView view = HistoryProjector.index(List.of(kill("d", 2_000L, 300, 100, EndReason.PLAYER_DIED)), 50).view(SOLO_NONE).get();

		assertNull(view.getAverageKillTicks());
		assertNull(view.getFastestKillTicks());
		assertEquals(new Trend(null, null), view.getKillTicksTrend());
		assertEquals(100.0, view.getAverageCost(), 0.001);
	}

	@Test
	public void trendsCompareTheLastTenKillsWithTheTenBefore()
	{
		List<KillReview> reviews = new ArrayList<>();
		for (int i = 0; i < 25; i++)
		{
			reviews.add(kill("k" + i, 1_000L * (25 - i), 400 + i, 1_000 + i, EndReason.YAMA_DIED));
		}

		HistoryView view = HistoryProjector.index(reviews, 50).view(SOLO_NONE).get();

		assertEquals(new Trend(404.5, 414.5), view.getKillTicksTrend());
		assertEquals(new Trend(1_004.5, 1_014.5), view.getCostTrend());
		assertEquals(25, view.getKills().size());
	}
}
```

`src/test/java/com/yamareviewer/application/handler/HistoryLoaderTest.java`:

```java
package com.yamareviewer.application.handler;

import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryKey;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.testing.Reviews;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Test;

public class HistoryLoaderTest
{
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "yama-reviewer-test"));
	private final FakeReviews reviews = new FakeReviews();
	private final FakePublisher publisher = new FakePublisher();

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private void drain() throws Exception
	{
		executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
	}

	@Test
	public void publishesTheHistoryOnTheExecutor() throws Exception
	{
		reviews.stored = List.of(
			Reviews.builder("b", 2_000L, Mode.SOLO, Contract.NONE).build(),
			Reviews.builder("a", 1_000L, Mode.SOLO, Contract.NONE).build());

		new HistoryLoader(executor, reviews, publisher, () -> 50).load();
		drain();

		assertEquals(1, publisher.shown.size());
		assertEquals(2, publisher.shown.get(0).view(new HistoryKey(Mode.SOLO, Contract.NONE)).get().getKills().size());
		assertEquals("yama-reviewer-test", publisher.thread);
	}

	@Test
	public void aStorageFailureIsLoggedNotThrown() throws Exception
	{
		reviews.fail = true;

		new HistoryLoader(executor, reviews, publisher, () -> 50).load();
		drain();

		assertTrue(publisher.shown.isEmpty());
	}

	@Test
	public void aStoppedExecutorIsLoggedNotThrown()
	{
		executor.shutdownNow();

		new HistoryLoader(executor, reviews, publisher, () -> 50).load();

		assertTrue(publisher.shown.isEmpty());
	}

	private static final class FakeReviews implements ReviewRepository
	{
		private volatile List<KillReview> stored = List.of();
		private volatile boolean fail;

		@Override
		public void save(KillReview review)
		{
		}

		@Override
		public List<KillReview> loadAll() throws IOException
		{
			if (fail)
			{
				throw new IOException("disk gone");
			}
			return stored;
		}

		@Override
		public void prune(int keepPerKey)
		{
		}
	}

	private static final class FakePublisher implements ReviewPublisher
	{
		private final List<HistoryIndex> shown = new CopyOnWriteArrayList<>();
		private volatile String thread;

		@Override
		public void publish(KillReview review, HistoryIndex history)
		{
			throw new AssertionError("the loader never publishes a review");
		}

		@Override
		public void showHistory(HistoryIndex history)
		{
			thread = Thread.currentThread().getName();
			shown.add(history);
		}
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.history.*' --tests 'com.yamareviewer.application.handler.HistoryLoaderTest'`
Expected: FAIL — `cannot find symbol` for `KillSummary`, `HistoryProjector`, `HistoryLoader`, `ReviewPublisher`.

- [ ] **Step 3: Write the history types**

`src/main/java/com/yamareviewer/domain/history/KillSummary.java`:

```java
package com.yamareviewer.domain.history;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.review.ReviewStatus;
import lombok.Builder;
import lombok.Value;

/** One kill in a history list. Fields filled by Part 3 are null until then. */
@Value
@Builder(toBuilder = true)
public class KillSummary
{
	String killId;
	long startEpochMs;
	/** The end tick of the fight; -1 when the phases section is hidden. */
	int killTicks;
	EndReason endReason;
	ReviewStatus status;
	/** Null when supplies are hidden. */
	Long cost;
	/** Part 3: blocked / scored attacks. */
	Double p3Accuracy;
	/** Part 3. */
	Integer defenceDrained;
	/** Part 3. */
	Double specLandedShare;
}
```

`src/main/java/com/yamareviewer/domain/history/Trend.java`:

```java
package com.yamareviewer.domain.history;

import lombok.Value;

/** The mean of the last 10 kills against the mean of the 10 before; null when there are no values. The UI draws the arrow. */
@Value
public class Trend
{
	Double last10Mean;
	Double previous10Mean;
}
```

`src/main/java/com/yamareviewer/domain/history/HistoryView.java`:

```java
package com.yamareviewer.domain.history;

import java.util.List;
import lombok.Value;

/** The last historySize kills of one mode and contract with their statistics (spec 7.3). */
@Value
public class HistoryView
{
	HistoryKey key;
	/** Newest first. */
	List<KillSummary> kills;
	Double averageKillTicks;
	Double averageP3Accuracy;
	Double averageCost;
	Integer fastestKillTicks;
	Double bestP3Accuracy;
	Trend killTicksTrend;
	Trend p3AccuracyTrend;
	Trend costTrend;
	Trend defenceDrainedTrend;
}
```

`src/main/java/com/yamareviewer/domain/history/HistoryIndex.java`:

```java
package com.yamareviewer.domain.history;

import java.util.Map;
import java.util.Optional;
import lombok.Value;

@Value
public class HistoryIndex
{
	Map<HistoryKey, HistoryView> views;

	public Optional<HistoryView> view(HistoryKey key)
	{
		return Optional.ofNullable(views.get(key));
	}
}
```

`src/main/java/com/yamareviewer/domain/history/HistoryProjector.java`:

```java
package com.yamareviewer.domain.history;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.SupplySummary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/** Derives the history of spec 7.3 from stored reviews. Pure. */
public final class HistoryProjector
{
	static final int TREND_WINDOW = 10;

	private HistoryProjector()
	{
	}

	/** Part 3 fills the Part 3 fields of KillSummary here. */
	public static KillSummary summarize(KillReview review)
	{
		return KillSummary.builder()
			.killId(review.getKillId())
			.startEpochMs(review.getStartEpochMs())
			.killTicks(review.getPhases().asOptional().map(PhaseTimes::getTotalTicks).orElse(-1))
			.endReason(review.getEndReason())
			.status(review.getStatus())
			.cost(review.getSupplies().asOptional().map(SupplySummary::getTotalCost).orElse(null))
			.p3Accuracy(null)
			.defenceDrained(null)
			.specLandedShare(null)
			.build();
	}

	public static HistoryIndex index(List<KillReview> reviews, int historySize)
	{
		List<KillReview> newestFirst = new ArrayList<>(reviews);
		newestFirst.sort(Comparator.comparingLong(KillReview::getStartEpochMs).reversed());
		Map<HistoryKey, List<KillSummary>> byKey = new LinkedHashMap<>();
		for (KillReview review : newestFirst)
		{
			List<KillSummary> kills = byKey.computeIfAbsent(new HistoryKey(review.getMode(), review.getContract()), key -> new ArrayList<>());
			if (kills.size() < historySize)
			{
				kills.add(summarize(review));
			}
		}
		Map<HistoryKey, HistoryView> views = new LinkedHashMap<>();
		byKey.forEach((key, kills) -> views.put(key, view(key, kills)));
		return new HistoryIndex(Collections.unmodifiableMap(views));
	}

	static HistoryView view(HistoryKey key, List<KillSummary> kills)
	{
		List<Double> ticks = kills.stream()
			.filter(kill -> kill.getEndReason() == EndReason.YAMA_DIED && kill.getKillTicks() >= 0)
			.map(kill -> (double) kill.getKillTicks())
			.collect(Collectors.toList());
		List<Double> accuracy = kills.stream().map(KillSummary::getP3Accuracy).filter(value -> value != null).collect(Collectors.toList());
		List<Double> cost = kills.stream().map(KillSummary::getCost).filter(value -> value != null).map(Long::doubleValue).collect(Collectors.toList());
		List<Double> drained = kills.stream().map(KillSummary::getDefenceDrained).filter(value -> value != null).map(Integer::doubleValue).collect(Collectors.toList());
		return new HistoryView(
			key,
			List.copyOf(kills),
			mean(ticks),
			mean(accuracy),
			mean(cost),
			ticks.isEmpty() ? null : (int) Math.round(ticks.stream().mapToDouble(Double::doubleValue).min().getAsDouble()),
			accuracy.isEmpty() ? null : accuracy.stream().mapToDouble(Double::doubleValue).max().getAsDouble(),
			trend(ticks),
			trend(accuracy),
			trend(cost),
			trend(drained));
	}

	/** Values newest first: the mean of the first TREND_WINDOW against the mean of the next TREND_WINDOW. */
	static Trend trend(List<Double> newestFirst)
	{
		List<Double> last = newestFirst.subList(0, Math.min(TREND_WINDOW, newestFirst.size()));
		List<Double> previous = newestFirst.subList(last.size(), Math.min(2 * TREND_WINDOW, newestFirst.size()));
		return new Trend(mean(last), mean(previous));
	}

	static Double mean(List<Double> values)
	{
		OptionalDouble mean = values.stream().mapToDouble(Double::doubleValue).average();
		return mean.isPresent() ? mean.getAsDouble() : null;
	}
}
```

- [ ] **Step 4: Write the publisher port and `HistoryLoader`**

`src/main/java/com/yamareviewer/application/port/ReviewPublisher.java`:

```java
package com.yamareviewer.application.port;

import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.review.KillReview;

/** The only way a review reaches the user. Called on the plugin executor, never on the client thread. */
public interface ReviewPublisher
{
	void publish(KillReview review, HistoryIndex history);

	void showHistory(HistoryIndex history);
}
```

`src/main/java/com/yamareviewer/application/handler/HistoryLoader.java`:

```java
package com.yamareviewer.application.handler;

import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.history.HistoryProjector;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;

/** Builds the history from the stored reviews on the executor at start-up and shows it in the panel (spec 7.5). */
@Slf4j
public final class HistoryLoader
{
	private final ExecutorService executor;
	private final ReviewRepository reviews;
	private final ReviewPublisher publisher;
	private final IntSupplier historySize;

	public HistoryLoader(ExecutorService executor, ReviewRepository reviews, ReviewPublisher publisher, IntSupplier historySize)
	{
		this.executor = executor;
		this.reviews = reviews;
		this.publisher = publisher;
		this.historySize = historySize;
	}

	public void load()
	{
		try
		{
			executor.execute(this::loadNow);
		}
		catch (RejectedExecutionException e)
		{
			log.warn("Plugin stopped before the history could be loaded");
		}
	}

	private void loadNow()
	{
		try
		{
			publisher.showHistory(HistoryProjector.index(reviews.loadAll(), Math.max(1, historySize.getAsInt())));
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not load the review history", e);
		}
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.history.*' --tests 'com.yamareviewer.application.handler.HistoryLoaderTest'`
Expected: PASS (10 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/history src/main/java/com/yamareviewer/application/port/ReviewPublisher.java src/main/java/com/yamareviewer/application/handler/HistoryLoader.java src/test/java/com/yamareviewer/domain/history src/test/java/com/yamareviewer/application/handler/HistoryLoaderTest.java
git commit -m "feat: derive per-mode-and-contract history with averages, bests and trends"
```

---
### Task 14: Text: ReviewFormatter, ChatLines, ClipboardExport, HistoryFormatter and the replay tool

**Files:**
- Create in `src/main/java/com/yamareviewer/domain/text/`: `Formats.java`, `ViewSection.java`, `ReviewView.java`, `ReviewFormatter.java`, `ChatLineOptions.java`, `ChatLines.java`, `ClipboardExport.java`, `HistoryFormatter.java`
- Create: `src/test/java/com/yamareviewer/tools/Replay.java`
- Modify: `build.gradle` (add the `replay` task, bump `version` to `0.2.0`)
- Test: `src/test/java/com/yamareviewer/domain/text/FormatsTest.java`, `ReviewFormatterTest.java`, `ChatLinesTest.java`, `ClipboardExportTest.java`, `HistoryFormatterTest.java`, `src/test/java/com/yamareviewer/tools/ReplayTest.java`

**Interfaces:**
- Consumes: `KillReview` and the result types (Task 3), `HistoryView`, `KillSummary`, `Trend` (Task 13), `Projections`, `KillReviewAssembler` (Task 12), `EventCodec` (Part 1 Task 5), `BuiltInIds` (Part 1 Task 3), `Reviews` (Task 3).
- Produces: `Formats.duration(int ticks)` ("4:12"), `gp(long)` ("318,450"), `compactGp(long)` ("318k", "1.25M"), `percent(double)`, `date(long epochMs)`, `trend(Trend, boolean lowerIsBetter)` ("↓ improving", "↑ declining", "→ steady", ""); `ViewSection(title, lines, hiddenReason)`; `ReviewView(headline, status, sections)`; `ReviewFormatter.format(KillReview)` plus public helpers `headline`, `modeAndContract`, `status`, `section(title, Section, Function)` (Part 3 adds its sections in `format`); `ChatLineOptions(phaseLine, prayerLine, flareLine, specLine, deathRecap)`; `ChatLines.lines(KillReview, ChatLineOptions)` (Part 3 fills lines 2 and 4); `ClipboardExport.text(KillReview)`; `HistoryFormatter.summaryLines(HistoryView)`, `killLine(KillSummary)`; `Replay.review(KillLog, IdRegistry)`, `Replay.read(Path)`, `Replay.text(ReviewView)`, `./gradlew replay --args="<raw log> [--json]"`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/text/FormatsTest.java`:

```java
package com.yamareviewer.domain.text;

import com.yamareviewer.domain.history.Trend;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FormatsTest
{
	@Test
	public void durationsRoundTicksToSeconds()
	{
		assertEquals("4:12", Formats.duration(420));
		assertEquals("1:05", Formats.duration(108));
		assertEquals("0:00", Formats.duration(0));
		assertEquals("10:00", Formats.duration(1000));
	}

	@Test
	public void goldFormats()
	{
		assertEquals("318,450", Formats.gp(318_450));
		assertEquals("450", Formats.compactGp(450));
		assertEquals("318k", Formats.compactGp(318_450));
		assertEquals("1M", Formats.compactGp(1_000_000));
		assertEquals("1.25M", Formats.compactGp(1_250_000));
		assertEquals("2.5M", Formats.compactGp(2_500_000));
		assertEquals("89%", Formats.percent(0.894));
	}

	@Test
	public void trendsArrowsUseATwoPercentBand()
	{
		assertEquals("↓ improving", Formats.trend(new Trend(250.0, 260.0), true));
		assertEquals("↑ declining", Formats.trend(new Trend(270.0, 260.0), true));
		assertEquals("→ steady", Formats.trend(new Trend(261.0, 260.0), true));
		assertEquals("↑ improving", Formats.trend(new Trend(0.9, 0.8), false));
		assertEquals("", Formats.trend(new Trend(0.9, null), false));
		assertEquals("", Formats.trend(null, false));
	}

	@Test
	public void datesHaveMinutePrecision()
	{
		assertEquals(16, Formats.date(1_700_000_000_000L).length());
	}
}
```

`src/test/java/com/yamareviewer/domain/text/ReviewFormatterTest.java`:

```java
package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.RecapHit;
import com.yamareviewer.domain.review.RecapTick;
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Reviews;
import java.util.List;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class ReviewFormatterTest
{
	private static ViewSection section(ReviewView view, String title)
	{
		return view.getSections().stream().filter(section -> section.getTitle().equals(title)).findFirst().get();
	}

	@Test
	public void headlineStatusAndSectionOrder()
	{
		ReviewView view = ReviewFormatter.format(Reviews.builder("k", 1L, Mode.DUO_HOST, Contract.BLOODIED_BLOWS).build());

		assertEquals("Yama (duo host, Bloodied Blows) 4:12", view.getHeadline());
		assertEquals("Complete", view.getStatus());
		assertEquals(List.of("Phases", "Damage taken", "Flares", "Supplies"),
			view.getSections().stream().map(ViewSection::getTitle).collect(toList()));
	}

	@Test
	public void phaseDamageFlareAndSupplyLines()
	{
		ReviewView view = ReviewFormatter.format(Reviews.builder("k", 1L, Mode.DUO_HOST, Contract.NONE).build());

		assertEquals(List.of("P1 1:05 (108 ticks)", "Judge 1 0:19 (32 ticks)", "P2 1:10 (117 ticks)", "Judge 2 0:14 (23 ticks)",
			"P3 1:24 (140 ticks)", "Total 4:12 (420 ticks)"), section(view, "Phases").getLines());
		assertEquals(List.of("You: 212", "  P1 22 · P3 190", "  Melee 22", "  Flare explosions 190",
			"Partner: 188", "  P3 188", "  Other 188", "Shared mechanics: 190"), section(view, "Damage taken").getLines());
		assertEquals(List.of("Spawned 6, killed 5, exploded 1", "Yama healed 30", "Wave 1 (summoned): 2 flares, 30 ticks",
			"Wave 2 (summoned): 4 flares, 30 ticks"), section(view, "Flares").getLines());
		assertEquals(List.of("Shark x2: 1,600", "Super restore 6 doses: 316,850", "Total: 318,450 (Grand Exchange)"),
			section(view, "Supplies").getLines());
		assertNull(section(view, "Supplies").getHiddenReason());
	}

	@Test
	public void soloReviewsHaveNoPartnerLines()
	{
		ReviewView view = ReviewFormatter.format(Reviews.sample());

		assertEquals(List.of("You: 212", "  P1 22 · P3 190", "  Melee 22", "  Flare explosions 190", "Shared mechanics: 190"),
			section(view, "Damage taken").getLines());
		assertEquals("Yama (solo) 4:12", view.getHeadline());
	}

	@Test
	public void hiddenSectionsShowTheirReasonAndMakeTheStatusIncomplete()
	{
		KillReview review = Reviews.sample().toBuilder()
			.flares(Section.hidden(HiddenReason.IDS_NOT_CAPTURED))
			.phases(Section.hidden(HiddenReason.ERROR))
			.build()
			.withRecomputedStatus();

		ReviewView view = ReviewFormatter.format(review);

		assertEquals("Incomplete", view.getStatus());
		assertEquals("Yama (solo) n/a", view.getHeadline());
		assertEquals("IDs not captured", section(view, "Flares").getHiddenReason());
		assertEquals(List.of(), section(view, "Flares").getLines());
		assertEquals("Error while reviewing", section(view, "Phases").getHiddenReason());
	}

	@Test
	public void aDeathPutsTheRecapFirstWithItsTicks()
	{
		KillReview review = Reviews.builder("k", 1L, Mode.SOLO, Contract.FORFEIT_BREATH)
			.endReason(EndReason.PLAYER_DIED)
			.deathRecap(Section.ok(new DeathRecap(List.of(
				new RecapTick(419, List.of(new RecapHit(DamageSource.MELEE, 22)), 12, 5, 3, "you", List.of("Shark")),
				new RecapTick(420, List.of(), 0, 5, 0, "partner", List.of())), true)))
			.build();

		ReviewView view = ReviewFormatter.format(review);

		assertEquals("Yama (solo, Forfeit Breath) died in P3 at 4:12", view.getHeadline());
		assertEquals("Death recap", view.getSections().get(0).getTitle());
		assertEquals(List.of(
			"Tick 419: HP 12, prayer 5, run 3%, Yama targets you, hit 22 (Melee), used Shark",
			"Tick 420: HP 0, prayer 5, run 0%, Yama targets partner",
			"Died out of run energy"), view.getSections().get(0).getLines());
	}

	@Test
	public void aLeftKillSaysWhereItStopped()
	{
		KillReview review = Reviews.sample().toBuilder().endReason(EndReason.LEFT).status(ReviewStatus.COMPLETE).build();

		assertEquals("Yama (solo) left in P3 at 4:12", ReviewFormatter.format(review).getHeadline());
	}
}
```

`src/test/java/com/yamareviewer/domain/text/ChatLinesTest.java`:

```java
package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.RecapHit;
import com.yamareviewer.domain.review.RecapTick;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Reviews;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ChatLinesTest
{
	private static final ChatLineOptions ALL = new ChatLineOptions(true, true, true, true, true);

	@Test
	public void theFourLinesOfSpec72()
	{
		KillReview review = Reviews.builder("k", 1L, Mode.DUO_HOST, Contract.BLOODIED_BLOWS).build();

		assertEquals(List.of(
			"Yama (duo host, Bloodied Blows) 4:12. P1 1:05, P2 1:10, P3 1:24.",
			"P3 prayers n/a. Crash lines n/a. Waves n/a.",
			"Flares 5/6 killed. Damage taken 212 (partner 188).",
			"Specs: n/a. Supplies 318k."), ChatLines.lines(review, ALL));
	}

	@Test
	public void soloAndNoContract()
	{
		List<String> lines = ChatLines.lines(Reviews.sample(), ALL);

		assertEquals("Yama (solo) 4:12. P1 1:05, P2 1:10, P3 1:24.", lines.get(0));
		assertEquals("Flares 5/6 killed. Damage taken 212.", lines.get(2));
	}

	@Test
	public void unknownContractIsNamedAsSuch()
	{
		KillReview review = Reviews.builder("k", 1L, Mode.SOLO, Contract.UNKNOWN_CONTRACT).build();

		assertEquals("Yama (solo, unknown contract) 4:12. P1 1:05, P2 1:10, P3 1:24.", ChatLines.lines(review, ALL).get(0));
	}

	@Test
	public void eachLineCanBeSwitchedOff()
	{
		KillReview review = Reviews.sample();

		assertEquals(List.of("Specs: n/a. Supplies 318k."), ChatLines.lines(review, new ChatLineOptions(false, false, false, true, false)));
		assertEquals(List.of(), ChatLines.lines(review, new ChatLineOptions(false, false, false, false, true)));
	}

	@Test
	public void hiddenValuesPrintAsNotAvailable()
	{
		KillReview review = Reviews.sample().toBuilder()
			.phases(Section.hidden(HiddenReason.HEALTH_CHECK_FAILED))
			.flares(Section.hidden(HiddenReason.IDS_NOT_CAPTURED))
			.damage(Section.hidden(HiddenReason.ERROR))
			.supplies(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.build();

		assertEquals(List.of(
			"Yama (solo) n/a.",
			"P3 prayers n/a. Crash lines n/a. Waves n/a.",
			"Flares n/a. Damage taken n/a.",
			"Specs: n/a. Supplies n/a."), ChatLines.lines(review, ALL));
	}

	@Test
	public void theDeathRecapBlockFollowsWhenEnabledAndPresent()
	{
		KillReview review = Reviews.sample().toBuilder()
			.endReason(EndReason.PLAYER_DIED)
			.deathRecap(Section.ok(new DeathRecap(List.of(
				new RecapTick(420, List.of(new RecapHit(DamageSource.FLARE, 45)), 0, 5, null, "you", List.of())), false)))
			.build();

		List<String> lines = ChatLines.lines(review, ALL);

		assertEquals("Yama (solo) died in P3 at 4:12. P1 1:05, P2 1:10, P3 1:24.", lines.get(0));
		assertEquals(List.of("Death recap:", "Tick 420: HP 0, prayer 5, Yama targets you, hit 45 (Flare explosions)"), lines.subList(4, 6));
		assertEquals(4, ChatLines.lines(review, new ChatLineOptions(true, true, true, true, false)).size());
		assertEquals(4, ChatLines.lines(Reviews.sample(), ALL).size());
	}
}
```

`src/test/java/com/yamareviewer/domain/text/ClipboardExportTest.java`:

```java
package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.RecapTick;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Reviews;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ClipboardExportTest
{
	@Test
	public void chatLinesThenSuppliesThenTheRecap()
	{
		KillReview review = Reviews.sample().toBuilder()
			.endReason(EndReason.PLAYER_DIED)
			.deathRecap(Section.ok(new DeathRecap(List.of(new RecapTick(420, List.of(), 0, 5, null, "you", List.of())), false)))
			.build();

		String text = ClipboardExport.text(review);

		assertEquals(String.join("\n",
			"Yama (solo) died in P3 at 4:12. P1 1:05, P2 1:10, P3 1:24.",
			"P3 prayers n/a. Crash lines n/a. Waves n/a.",
			"Flares 5/6 killed. Damage taken 212.",
			"Specs: n/a. Supplies 318k.",
			"",
			"Supplies",
			"Shark x2: 1,600",
			"Super restore 6 doses: 316,850",
			"Total: 318,450 (Grand Exchange)",
			"",
			"Death recap",
			"Tick 420: HP 0, prayer 5, Yama targets you"), text);
	}

	@Test
	public void noRecapBlockWithoutADeathAndNoNames()
	{
		String text = ClipboardExport.text(Reviews.sample());

		assertFalse(text.contains("Death recap"));
		assertTrue(text.endsWith("Total: 318,450 (Grand Exchange)"));
		assertFalse(text.contains("Me"));
		assertFalse(text.contains("Buddy"));
	}
}
```

`src/test/java/com/yamareviewer/domain/text/HistoryFormatterTest.java`:

```java
package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.history.HistoryKey;
import com.yamareviewer.domain.history.HistoryView;
import com.yamareviewer.domain.history.KillSummary;
import com.yamareviewer.domain.history.Trend;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.ReviewStatus;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HistoryFormatterTest
{
	private static KillSummary kill(String id, int ticks, EndReason endReason, ReviewStatus status, Long cost)
	{
		return KillSummary.builder().killId(id).startEpochMs(1_700_000_000_000L).killTicks(ticks).endReason(endReason).status(status).cost(cost).build();
	}

	@Test
	public void summaryLinesWithStatisticsAndTrends()
	{
		HistoryView view = new HistoryView(new HistoryKey(Mode.SOLO, Contract.NONE),
			List.of(kill("a", 420, EndReason.YAMA_DIED, ReviewStatus.COMPLETE, 318_450L), kill("b", 400, EndReason.YAMA_DIED, ReviewStatus.COMPLETE, 200_000L)),
			410.0, null, 259_225.0, 400, null,
			new Trend(410.0, 430.0), new Trend(null, null), new Trend(259_225.0, 260_000.0), new Trend(null, null));

		assertEquals(List.of(
			"2 kills",
			"Kill time: avg 4:06, best 4:00 ↓ improving",
			"P3 accuracy: n/a",
			"Cost per kill: avg 259k → steady",
			"Defence drained: n/a"), HistoryFormatter.summaryLines(view));
	}

	@Test
	public void anEmptyViewSaysSo()
	{
		HistoryView view = new HistoryView(new HistoryKey(Mode.SOLO, Contract.NONE), List.of(), null, null, null, null, null,
			new Trend(null, null), new Trend(null, null), new Trend(null, null), new Trend(null, null));

		assertEquals(List.of("No kills yet"), HistoryFormatter.summaryLines(view));
	}

	@Test
	public void killLinesShowTimeOutcomeAndCost()
	{
		assertTrue(HistoryFormatter.killLine(kill("a", 420, EndReason.YAMA_DIED, ReviewStatus.COMPLETE, 318_450L)).endsWith(" · 4:12 · complete · 318k"));
		assertTrue(HistoryFormatter.killLine(kill("b", 420, EndReason.YAMA_DIED, ReviewStatus.INCOMPLETE, null)).endsWith(" · 4:12 · incomplete"));
		assertTrue(HistoryFormatter.killLine(kill("c", 300, EndReason.PLAYER_DIED, ReviewStatus.COMPLETE, 1_000L)).endsWith(" · 3:00 · died · 1k"));
		assertTrue(HistoryFormatter.killLine(kill("d", -1, EndReason.LEFT, ReviewStatus.COMPLETE, null)).endsWith(" · n/a · left"));
	}
}
```

`src/test/java/com/yamareviewer/tools/ReplayTest.java`:

```java
package com.yamareviewer.tools;

import com.google.gson.Gson;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ReviewFormatter;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ReplayTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static KillLog kill()
	{
		return KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, TestIds.id(Role.JUDGE))
			.ticks(30).npcDespawns(Actor.JUDGE, TestIds.id(Role.JUDGE), true)
			.ticks(20).hitsplatOn(Actor.SELF, 9).end(EndReason.YAMA_DIED);
	}

	@Test
	public void reviewsAKillAndRendersIt()
	{
		KillReview review = Replay.review(kill(), TestIds.registry());
		String text = Replay.text(ReviewFormatter.format(review));

		assertEquals(Mode.SOLO, review.getMode());
		assertTrue(text, text.startsWith("Yama (solo) 1:00"));
		assertTrue(text, text.contains("Complete"));
		assertTrue(text, text.contains("Phases"));
		assertTrue(text, text.contains("You: 9"));
	}

	@Test
	public void readsARawLogFile() throws IOException
	{
		KillLog kill = kill();
		EventCodec codec = new EventCodec(new Gson());
		StringBuilder lines = new StringBuilder(codec.encodeHeader(kill.getHeader())).append('\n');
		for (DomainEvent event : kill.getEvents())
		{
			lines.append(codec.encode(event)).append('\n');
		}
		lines.append("{\"type\":\"from-the-future\",\"data\":{}}\n");
		Path file = folder.newFile("0000000000001-test-kill.jsonl.gz").toPath();
		try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file)))
		{
			out.write(lines.toString().getBytes(StandardCharsets.UTF_8));
		}

		KillLog read = Replay.read(file);

		assertEquals(kill.getHeader(), read.getHeader());
		assertEquals(kill.getEvents(), read.getEvents());
		assertEquals(1, read.getSkippedEvents());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.text.*' --tests 'com.yamareviewer.tools.ReplayTest'`
Expected: FAIL — `cannot find symbol` for `Formats`, `ReviewFormatter`, `ChatLines`, `ClipboardExport`, `HistoryFormatter`, `Replay`.

- [ ] **Step 3: Write the formatting helpers and view models**

`src/main/java/com/yamareviewer/domain/text/Formats.java`:

```java
package com.yamareviewer.domain.text;

import com.yamareviewer.domain.history.Trend;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Number, time and trend formatting shared by the text renderers and the panel. */
public final class Formats
{
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
	private static final double STEADY_BAND = 0.02;

	private Formats()
	{
	}

	/** Ticks as m:ss, one tick being 0.6 seconds. */
	public static String duration(int ticks)
	{
		long seconds = Math.round(ticks * 0.6);
		return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	public static String gp(long amount)
	{
		return String.format(Locale.ROOT, "%,d", amount);
	}

	/** 450, 318k, 1.25M. */
	public static String compactGp(long amount)
	{
		if (amount < 1_000)
		{
			return Long.toString(amount);
		}
		if (amount < 1_000_000)
		{
			return Math.round(amount / 1_000.0) + "k";
		}
		return stripZeros(String.format(Locale.ROOT, "%.2f", amount / 1_000_000.0)) + "M";
	}

	public static String percent(double share)
	{
		return Math.round(share * 100) + "%";
	}

	public static String date(long epochMs)
	{
		return DATE.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs));
	}

	/** An arrow and a word comparing the last ten kills with the ten before; "" without two means. */
	public static String trend(Trend trend, boolean lowerIsBetter)
	{
		if (trend == null || trend.getLast10Mean() == null || trend.getPrevious10Mean() == null)
		{
			return "";
		}
		double last = trend.getLast10Mean();
		double previous = trend.getPrevious10Mean();
		if (Math.abs(last - previous) <= Math.abs(previous) * STEADY_BAND)
		{
			return "→ steady";
		}
		boolean lower = last < previous;
		return (lower ? "↓ " : "↑ ") + (lower == lowerIsBetter ? "improving" : "declining");
	}

	private static String stripZeros(String decimal)
	{
		String result = decimal;
		while (result.contains(".") && (result.endsWith("0") || result.endsWith(".")))
		{
			result = result.substring(0, result.length() - 1);
		}
		return result;
	}
}
```

`src/main/java/com/yamareviewer/domain/text/ViewSection.java`:

```java
package com.yamareviewer.domain.text;

import java.util.List;
import lombok.Value;

/** One rendered section; hiddenReason is null when the section is shown. */
@Value
public class ViewSection
{
	String title;
	List<String> lines;
	String hiddenReason;
}
```

`src/main/java/com/yamareviewer/domain/text/ReviewView.java`:

```java
package com.yamareviewer.domain.text;

import java.util.List;
import lombok.Value;

/** Everything the panel shows for one review; plain strings only. */
@Value
public class ReviewView
{
	String headline;
	String status;
	List<ViewSection> sections;
}
```

`src/main/java/com/yamareviewer/domain/text/ChatLineOptions.java`:

```java
package com.yamareviewer.domain.text;

import lombok.Value;

/** Which chat lines to print (spec 7.2, config keys chat*). */
@Value
public class ChatLineOptions
{
	boolean phaseLine;
	boolean prayerLine;
	boolean flareLine;
	boolean specLine;
	boolean deathRecap;
}
```

- [ ] **Step 4: Write `ReviewFormatter`**

`src/main/java/com/yamareviewer/domain/text/ReviewFormatter.java`:

```java
package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.FlareSummary;
import com.yamareviewer.domain.review.FlareWave;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.PriceMode;
import com.yamareviewer.domain.review.RecapHit;
import com.yamareviewer.domain.review.RecapTick;
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.SupplyLine;
import com.yamareviewer.domain.review.SupplySummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Renders a KillReview to plain strings for the panel (spec 7.3). Part 3 adds its sections in format(). */
public final class ReviewFormatter
{
	private ReviewFormatter()
	{
	}

	public static ReviewView format(KillReview review)
	{
		List<ViewSection> sections = new ArrayList<>();
		if (review.getEndReason() == EndReason.PLAYER_DIED || review.getDeathRecap().isOk())
		{
			sections.add(section("Death recap", review.getDeathRecap(), ReviewFormatter::deathRecapLines));
		}
		sections.add(section("Phases", review.getPhases(), ReviewFormatter::phaseLines));
		sections.add(section("Damage taken", review.getDamage(), damage -> damageLines(damage, review.getMode())));
		sections.add(section("Flares", review.getFlares(), ReviewFormatter::flareLines));
		sections.add(section("Supplies", review.getSupplies(), ReviewFormatter::supplyLines));
		return new ReviewView(headline(review), status(review), List.copyOf(sections));
	}

	/** "Yama (solo, Bloodied Blows) 4:12", "… died in P3 at 3:10", "… left in P2 at 2:00"; "n/a" without phases. */
	public static String headline(KillReview review)
	{
		String who = "Yama (" + modeAndContract(review) + ")";
		Optional<PhaseTimes> phases = review.getPhases().asOptional();
		switch (review.getEndReason())
		{
			case YAMA_DIED:
				return who + " " + phases.map(times -> Formats.duration(times.getTotalTicks())).orElse("n/a");
			case PLAYER_DIED:
				return who + " died" + phases.map(ReviewFormatter::whereAndWhen).orElse("");
			default:
				return who + " left" + phases.map(ReviewFormatter::whereAndWhen).orElse("");
		}
	}

	/** "solo", "duo host, Bloodied Blows", "solo, unknown contract". */
	public static String modeAndContract(KillReview review)
	{
		String mode = review.getMode().label();
		Contract contract = review.getContract();
		if (!contract.isContract())
		{
			return mode;
		}
		return mode + ", " + (contract == Contract.UNKNOWN_CONTRACT ? "unknown contract" : contract.shortName());
	}

	public static String status(KillReview review)
	{
		return review.getStatus() == ReviewStatus.COMPLETE ? "Complete" : "Incomplete";
	}

	public static <T> ViewSection section(String title, Section<T> section, Function<T, List<String>> lines)
	{
		if (section.isOk())
		{
			return new ViewSection(title, List.copyOf(lines.apply(section.value())), null);
		}
		return new ViewSection(title, List.of(), section.hiddenReason().get().label());
	}

	static List<String> phaseLines(PhaseTimes times)
	{
		List<String> lines = new ArrayList<>();
		for (PhaseSpan span : times.getSpans())
		{
			lines.add(span.getPhase().label() + " " + Formats.duration(span.ticks()) + " (" + span.ticks() + " ticks)");
		}
		lines.add("Total " + Formats.duration(times.getTotalTicks()) + " (" + times.getTotalTicks() + " ticks)");
		return lines;
	}

	static List<String> damageLines(DamageSummary damage, Mode mode)
	{
		List<String> lines = new ArrayList<>(playerLines("You", Actor.SELF, damage));
		if (mode != Mode.SOLO)
		{
			lines.addAll(playerLines("Partner", Actor.PARTNER, damage));
		}
		lines.add("Shared mechanics: " + damage.shared());
		return lines;
	}

	private static List<String> playerLines(String label, Actor player, DamageSummary damage)
	{
		List<String> lines = new ArrayList<>();
		lines.add(label + ": " + damage.total(player));
		List<String> perPhase = new ArrayList<>();
		for (Phase phase : Phase.values())
		{
			int amount = damage.total(player, phase);
			if (amount > 0)
			{
				perPhase.add(phase.label() + " " + amount);
			}
		}
		if (!perPhase.isEmpty())
		{
			lines.add("  " + String.join(" · ", perPhase));
		}
		for (Map.Entry<DamageSource, Integer> entry : damage.bySource(player).entrySet())
		{
			lines.add("  " + entry.getKey().label() + " " + entry.getValue());
		}
		return lines;
	}

	static List<String> flareLines(FlareSummary flares)
	{
		List<String> lines = new ArrayList<>();
		lines.add("Spawned " + flares.spawned() + ", killed " + flares.killed() + ", exploded " + flares.exploded());
		lines.add("Yama healed " + flares.getYamaHealing());
		if (flares.purgingStaffKills() > 0)
		{
			lines.add(flares.purgingStaffKills() + " killed with the purging staff");
		}
		int number = 1;
		for (FlareWave wave : flares.getWaves())
		{
			lines.add("Wave " + number++ + " (" + wave.cause().name().toLowerCase(Locale.ROOT) + "): " + wave.getFlares().size()
				+ (wave.getFlares().size() == 1 ? " flare, " : " flares, ") + wave.ticks() + " ticks");
		}
		return lines;
	}

	static List<String> supplyLines(SupplySummary supplies)
	{
		List<String> lines = new ArrayList<>();
		if (supplies.getLines().isEmpty())
		{
			lines.add("Nothing used");
		}
		for (SupplyLine line : supplies.getLines())
		{
			String quantity = "doses".equals(line.getUnit()) ? " " + line.getQuantity() + " doses" : " x" + line.getQuantity();
			lines.add(line.getName() + quantity + ": " + Formats.gp(line.getCost()) + (line.isNoEffect() ? " (no effect under this contract)" : ""));
		}
		lines.add("Total: " + Formats.gp(supplies.getTotalCost()) + " ("
			+ (supplies.getPriceMode() == PriceMode.GRAND_EXCHANGE ? "Grand Exchange" : "high alchemy") + ")");
		return lines;
	}

	static List<String> deathRecapLines(DeathRecap recap)
	{
		List<String> lines = recap.getTicks().stream().map(ReviewFormatter::recapTickLine).collect(Collectors.toList());
		if (recap.isOutOfRunEnergy())
		{
			lines.add("Died out of run energy");
		}
		return lines;
	}

	static String recapTickLine(RecapTick tick)
	{
		StringBuilder line = new StringBuilder("Tick ").append(tick.getTick())
			.append(": HP ").append(tick.getHitpoints() < 0 ? "?" : String.valueOf(tick.getHitpoints()))
			.append(", prayer ").append(tick.getPrayerPoints() < 0 ? "?" : String.valueOf(tick.getPrayerPoints()));
		if (tick.getRunEnergy() != null)
		{
			line.append(", run ").append(tick.getRunEnergy()).append('%');
		}
		line.append(", Yama targets ").append(tick.getYamaTarget());
		for (RecapHit hit : tick.getHits())
		{
			line.append(", hit ").append(hit.getAmount()).append(" (").append(hit.getSource().label()).append(')');
		}
		for (String item : tick.getConsumed())
		{
			line.append(", used ").append(item);
		}
		return line.toString();
	}

	private static String whereAndWhen(PhaseTimes times)
	{
		return times.last().map(span -> " in " + span.getPhase().label()).orElse("") + " at " + Formats.duration(times.getTotalTicks());
	}
}
```

- [ ] **Step 5: Write `ChatLines`, `ClipboardExport` and `HistoryFormatter`**

`src/main/java/com/yamareviewer/domain/text/ChatLines.java`:

```java
package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.FlareSummary;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.SupplySummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** The chat summary of spec 7.2: up to four lines plus the recap block; hidden values print "n/a". Part 3 fills lines 2 and 4. */
public final class ChatLines
{
	static final String NOT_AVAILABLE = "n/a";

	private ChatLines()
	{
	}

	public static List<String> lines(KillReview review, ChatLineOptions options)
	{
		List<String> lines = new ArrayList<>();
		if (options.isPhaseLine())
		{
			lines.add(phaseLine(review));
		}
		if (options.isPrayerLine())
		{
			lines.add(prayerLine(review));
		}
		if (options.isFlareLine())
		{
			lines.add(flareLine(review));
		}
		if (options.isSpecLine())
		{
			lines.add(specLine(review));
		}
		if (options.isDeathRecap())
		{
			Optional<DeathRecap> recap = review.getDeathRecap().asOptional();
			if (recap.isPresent())
			{
				lines.add("Death recap:");
				lines.addAll(ReviewFormatter.deathRecapLines(recap.get()));
			}
		}
		return lines;
	}

	/** "Yama (solo, Bloodied Blows) 4:12. P1 1:05, P2 1:10, P3 1:24." */
	static String phaseLine(KillReview review)
	{
		Optional<PhaseTimes> phases = review.getPhases().asOptional();
		String reached = phases.map(times -> times.getSpans().stream()
			.filter(span -> !span.getPhase().isJudge())
			.map(span -> span.getPhase().label() + " " + Formats.duration(span.ticks()))
			.collect(Collectors.joining(", "))).orElse("");
		return ReviewFormatter.headline(review) + "." + (reached.isEmpty() ? "" : " " + reached + ".");
	}

	/** Part 3 fills this with the P3 prayer, crash line and wave results. */
	static String prayerLine(KillReview review)
	{
		return "P3 prayers " + NOT_AVAILABLE + ". Crash lines " + NOT_AVAILABLE + ". Waves " + NOT_AVAILABLE + ".";
	}

	/** "Flares 5/6 killed. Damage taken 212 (partner 188)." */
	static String flareLine(KillReview review)
	{
		String flares = review.getFlares().asOptional().map(summary -> summary.killed() + "/" + summary.spawned() + " killed").orElse(NOT_AVAILABLE);
		String damage = review.getDamage().asOptional().map(summary -> damageTaken(summary, review.getMode())).orElse(NOT_AVAILABLE);
		return "Flares " + flares + ". Damage taken " + damage + ".";
	}

	/** Part 3 fills the specs; "Specs: n/a. Supplies 318k." */
	static String specLine(KillReview review)
	{
		String supplies = review.getSupplies().asOptional().map(summary -> Formats.compactGp(summary.getTotalCost())).orElse(NOT_AVAILABLE);
		return "Specs: " + NOT_AVAILABLE + ". Supplies " + supplies + ".";
	}

	private static String damageTaken(DamageSummary damage, Mode mode)
	{
		String self = String.valueOf(damage.total(Actor.SELF));
		return mode == Mode.SOLO ? self : self + " (partner " + damage.total(Actor.PARTNER) + ")";
	}
}
```

`src/main/java/com/yamareviewer/domain/text/ClipboardExport.java`:

```java
package com.yamareviewer.domain.text;

import com.yamareviewer.domain.review.KillReview;
import java.util.List;

/** Plain text of spec 7.4: the chat lines, supplies and cost, the death recap if any. Never a player name. Part 3 adds the spec efficiency. */
public final class ClipboardExport
{
	private static final List<String> COPIED_SECTIONS = List.of("Supplies", "Death recap");

	private ClipboardExport()
	{
	}

	public static String text(KillReview review)
	{
		StringBuilder text = new StringBuilder(String.join("\n", ChatLines.lines(review, new ChatLineOptions(true, true, true, true, false))));
		List<ViewSection> sections = ReviewFormatter.format(review).getSections();
		// Copied in COPIED_SECTIONS order (spec 7.4), not the panel's display order.
		for (String title : COPIED_SECTIONS)
		{
			ViewSection section = sections.stream().filter(s -> s.getTitle().equals(title)).findFirst().orElse(null);
			if (section == null)
			{
				continue;
			}
			text.append("\n\n").append(section.getTitle()).append('\n');
			if (section.getHiddenReason() != null)
			{
				text.append(ChatLines.NOT_AVAILABLE).append(" (").append(section.getHiddenReason()).append(')');
			}
			else
			{
				text.append(String.join("\n", section.getLines()));
			}
		}
		return text.toString();
	}
}
```

`src/main/java/com/yamareviewer/domain/text/HistoryFormatter.java`:

```java
package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.history.HistoryView;
import com.yamareviewer.domain.history.KillSummary;
import com.yamareviewer.domain.history.Trend;
import com.yamareviewer.domain.review.ReviewStatus;
import java.util.List;

/** Renders a HistoryView to strings, so the panel does no arithmetic (spec 7.3). */
public final class HistoryFormatter
{
	private HistoryFormatter()
	{
	}

	public static List<String> summaryLines(HistoryView view)
	{
		int kills = view.getKills().size();
		if (kills == 0)
		{
			return List.of("No kills yet");
		}
		String time = view.getAverageKillTicks() == null ? ChatLines.NOT_AVAILABLE
			: "avg " + Formats.duration((int) Math.round(view.getAverageKillTicks())) + ", best " + Formats.duration(view.getFastestKillTicks())
				+ arrow(view.getKillTicksTrend(), true);
		String accuracy = view.getAverageP3Accuracy() == null ? ChatLines.NOT_AVAILABLE
			: "avg " + Formats.percent(view.getAverageP3Accuracy()) + ", best " + Formats.percent(view.getBestP3Accuracy())
				+ arrow(view.getP3AccuracyTrend(), false);
		String cost = view.getAverageCost() == null ? ChatLines.NOT_AVAILABLE
			: "avg " + Formats.compactGp(Math.round(view.getAverageCost())) + arrow(view.getCostTrend(), true);
		Trend drained = view.getDefenceDrainedTrend();
		String defence = drained.getLast10Mean() == null ? ChatLines.NOT_AVAILABLE
			: "recent avg " + Math.round(drained.getLast10Mean()) + arrow(drained, false);
		return List.of(
			kills + (kills == 1 ? " kill" : " kills"),
			"Kill time: " + time,
			"P3 accuracy: " + accuracy,
			"Cost per kill: " + cost,
			"Defence drained: " + defence);
	}

	/** "2026-09-24 21:03 · 4:12 · complete · 318k" */
	public static String killLine(KillSummary kill)
	{
		String time = kill.getKillTicks() < 0 ? ChatLines.NOT_AVAILABLE : Formats.duration(kill.getKillTicks());
		String outcome;
		if (kill.getEndReason() == EndReason.PLAYER_DIED)
		{
			outcome = "died";
		}
		else if (kill.getEndReason() == EndReason.LEFT)
		{
			outcome = "left";
		}
		else
		{
			outcome = kill.getStatus() == ReviewStatus.COMPLETE ? "complete" : "incomplete";
		}
		String cost = kill.getCost() == null ? "" : " · " + Formats.compactGp(kill.getCost());
		return Formats.date(kill.getStartEpochMs()) + " · " + time + " · " + outcome + cost;
	}

	private static String arrow(Trend trend, boolean lowerIsBetter)
	{
		String text = Formats.trend(trend, lowerIsBetter);
		return text.isEmpty() ? "" : " " + text;
	}
}
```

- [ ] **Step 6: Write the replay tool and its Gradle task**

`src/test/java/com/yamareviewer/tools/Replay.java`:

```java
package com.yamareviewer.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.yamareviewer.adapter.ids.BuiltInIds;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.KillReviewAssembler;
import com.yamareviewer.domain.projection.Projections;
import com.yamareviewer.domain.projection.ReviewBuilder;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ReviewFormatter;
import com.yamareviewer.domain.text.ReviewView;
import com.yamareviewer.domain.text.ViewSection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Development tool: reviews a raw log with the built-in IDs and default rules and prints the review as
 * text, or as JSON with --json (the expected-review fixtures of Part 3's golden tests are made this way).
 */
public final class Replay
{
	private Replay()
	{
	}

	public static void main(String[] args) throws IOException
	{
		if (args.length < 1 || args.length > 2 || (args.length == 2 && !args[1].equals("--json")))
		{
			System.err.println("Usage: ./gradlew replay --args=\"<path to raw/*.jsonl.gz> [--json]\"");
			System.exit(1);
		}
		KillReview review = review(read(Path.of(args[0])), BuiltInIds.registry());
		if (args.length == 2)
		{
			System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(review));
		}
		else
		{
			System.out.print(text(ReviewFormatter.format(review)));
		}
	}

	public static KillLog read(Path file) throws IOException
	{
		String text;
		try (InputStream in = new GZIPInputStream(Files.newInputStream(file)))
		{
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		EventCodec codec = new EventCodec(new Gson());
		String[] lines = text.split("\n");
		KillHeader header = codec.decodeHeader(lines[0]);
		List<DomainEvent> events = new ArrayList<>();
		int skipped = 0;
		for (int i = 1; i < lines.length; i++)
		{
			if (lines[i].isBlank())
			{
				continue;
			}
			Optional<DomainEvent> event = codec.decode(lines[i]);
			if (event.isPresent())
			{
				events.add(event.get());
			}
			else
			{
				skipped++;
			}
		}
		return KillLog.of(header, events, skipped);
	}

	public static KillReview review(KillLog log, IdRegistry ids)
	{
		ReviewBuilder builder = new ReviewBuilder(Projections.standard(), ids, Rules.DEFAULT);
		return KillReviewAssembler.assemble(log, builder.run(log, ReviewSettings.DEFAULT));
	}

	public static String text(ReviewView view)
	{
		StringBuilder out = new StringBuilder(view.getHeadline()).append('\n').append(view.getStatus()).append('\n');
		for (ViewSection section : view.getSections())
		{
			out.append('\n').append(section.getTitle()).append('\n');
			if (section.getHiddenReason() != null)
			{
				out.append("  hidden: ").append(section.getHiddenReason()).append('\n');
			}
			for (String line : section.getLines())
			{
				out.append("  ").append(line).append('\n');
			}
		}
		return out.toString();
	}
}
```

Append to `build.gradle` (after the `captureSummary` task) and change `version = '0.1.0'` to `version = '0.2.0'`:

```groovy
tasks.register('replay', JavaExec) {
	classpath = sourceSets.test.runtimeClasspath
	mainClass = 'com.yamareviewer.tools.Replay'
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.text.*' --tests 'com.yamareviewer.tools.ReplayTest'`
Expected: PASS (21 tests).

- [ ] **Step 8: Commit**

```bash
git add build.gradle src/main/java/com/yamareviewer/domain/text src/test/java/com/yamareviewer/domain/text src/test/java/com/yamareviewer/tools/Replay.java src/test/java/com/yamareviewer/tools/ReplayTest.java
git commit -m "feat: render reviews as panel text, chat lines and clipboard text; add the replay tool"
```

---
### Task 15: KillEndedHandler replacement and the chat publisher

**Files:**
- Replace: `src/main/java/com/yamareviewer/application/handler/KillEndedHandler.java` (Part 1 Task 6)
- Create: `src/main/java/com/yamareviewer/adapter/publish/CompositeReviewPublisher.java`, `ChatReviewPublisher.java`, `ChatReviewPublisherFactory.java`
- Replace: `src/test/java/com/yamareviewer/application/handler/KillEndedHandlerTest.java`
- Test: `src/test/java/com/yamareviewer/adapter/publish/CompositeReviewPublisherTest.java`, `ChatReviewPublisherTest.java`

**Interfaces:**
- Consumes: `LogRepository`, `KillEndedListener` (Part 1), `ReviewRepository` (Task 12), `ReviewPublisher`, `HistoryProjector` (Task 13), `ReviewBuilder`, `KillReviewAssembler`, `ReviewSettings` (Tasks 5, 12), `ChatLines`, `ChatLineOptions` (Task 14).
- Produces: `KillEndedHandler(ExecutorService, LogRepository, ReviewRepository, ReviewBuilder, ReviewPublisher, BooleanSupplier active, IntSupplier rawLogsKept, IntSupplier historySize, Supplier<ReviewSettings>)` keeping `killEnded`, `pending()` and package-private `handle(KillLog)` (Part 4 inserts the health checks between `builder.run` and `KillReviewAssembler.assemble`); `CompositeReviewPublisher(List<ReviewPublisher>)`; `ChatReviewPublisher(ChatMessageManager, Supplier<ChatLineOptions>)`; `ChatReviewPublisherFactory` (Guice-injectable, `create(Supplier<ChatLineOptions>)`), which keeps `ChatMessageManager` out of the root package as `ArchitectureTest.onlyPublishTalksToChat` demands.

- [ ] **Step 1: Write the failing tests**

Replace `src/test/java/com/yamareviewer/application/handler/KillEndedHandlerTest.java`:

```java
package com.yamareviewer.application.handler;

import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryKey;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.projection.ReviewBuilder;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Test;

public class KillEndedHandlerTest
{
	private final FakeLogs logs = new FakeLogs();
	private final FakeReviews reviews = new FakeReviews();
	private final FakePublisher publisher = new FakePublisher();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "yama-reviewer-test"));

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private KillEndedHandler handler(boolean active)
	{
		ReviewBuilder builder = new ReviewBuilder(List.of(), TestIds.registry(), Rules.DEFAULT);
		return new KillEndedHandler(executor, logs, reviews, builder, publisher, () -> active, () -> 20, () -> 50, () -> ReviewSettings.DEFAULT);
	}

	private static KillLog kill()
	{
		return KillLogBuilder.kill().ticks(2).end(EndReason.YAMA_DIED);
	}

	@Test
	public void storesTheLogAndTheReviewThenPublishesOnTheExecutor() throws Exception
	{
		KillEndedHandler handler = handler(true);

		handler.killEnded(kill());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertEquals(List.of("save test-kill on yama-reviewer-test", "prune 20"), logs.calls);
		assertEquals(List.of("save test-kill", "prune 50", "loadAll"), reviews.calls);
		assertEquals(1, publisher.published.size());
		KillReview review = publisher.published.get(0);
		assertEquals("test-kill", review.getKillId());
		assertEquals(Mode.SOLO, review.getMode());
		assertEquals(ReviewStatus.COMPLETE, review.getStatus());
		assertEquals(1, publisher.histories.get(0).view(new HistoryKey(Mode.SOLO, Contract.NONE)).get().getKills().size());
		assertEquals("yama-reviewer-test", publisher.thread);
	}

	@Test
	public void nothingIsPublishedWhenThePluginIsInactive() throws Exception
	{
		KillEndedHandler handler = handler(false);

		handler.killEnded(kill());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertTrue(publisher.published.isEmpty());
		assertEquals(1, reviews.saved.size());
	}

	@Test
	public void storageFailuresStillPublishTheReview() throws Exception
	{
		logs.fail = true;
		reviews.failSave = true;
		KillEndedHandler handler = handler(true);

		handler.killEnded(kill());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertEquals(1, publisher.published.size());
	}

	@Test
	public void aHistoryLoadFailureFallsBackToThisKill() throws Exception
	{
		reviews.failLoad = true;
		KillEndedHandler handler = handler(true);

		handler.killEnded(kill());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertEquals(1, publisher.histories.get(0).view(new HistoryKey(Mode.SOLO, Contract.NONE)).get().getKills().size());
	}

	@Test
	public void aPublisherFailureDoesNotEscape() throws Exception
	{
		publisher.fail = true;
		KillEndedHandler handler = handler(true);

		handler.killEnded(kill());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertEquals(1, reviews.saved.size());
	}

	@Test
	public void aStoppedExecutorIsLoggedNotThrown()
	{
		executor.shutdownNow();
		KillEndedHandler handler = handler(true);

		handler.killEnded(kill());

		assertTrue(handler.pending().isDone());
		assertTrue(publisher.published.isEmpty());
	}

	private static final class FakeLogs implements LogRepository
	{
		private final List<String> calls = new CopyOnWriteArrayList<>();
		private volatile boolean fail;

		@Override
		public void save(KillLog kill) throws IOException
		{
			if (fail)
			{
				throw new IOException("disk full");
			}
			calls.add("save " + kill.getHeader().getKillId() + " on " + Thread.currentThread().getName());
		}

		@Override
		public List<KillLog> loadAll()
		{
			return List.of();
		}

		@Override
		public void prune(int keep)
		{
			calls.add("prune " + keep);
		}
	}

	private static final class FakeReviews implements ReviewRepository
	{
		private final List<String> calls = new CopyOnWriteArrayList<>();
		private final List<KillReview> saved = new CopyOnWriteArrayList<>();
		private volatile boolean failSave;
		private volatile boolean failLoad;

		@Override
		public void save(KillReview review) throws IOException
		{
			if (failSave)
			{
				throw new IOException("disk full");
			}
			calls.add("save " + review.getKillId());
			saved.add(review);
		}

		@Override
		public List<KillReview> loadAll() throws IOException
		{
			calls.add("loadAll");
			if (failLoad)
			{
				throw new IOException("disk gone");
			}
			return List.copyOf(saved);
		}

		@Override
		public void prune(int keepPerKey)
		{
			calls.add("prune " + keepPerKey);
		}
	}

	private static final class FakePublisher implements ReviewPublisher
	{
		private final List<KillReview> published = new CopyOnWriteArrayList<>();
		private final List<HistoryIndex> histories = new CopyOnWriteArrayList<>();
		private volatile String thread;
		private volatile boolean fail;

		@Override
		public void publish(KillReview review, HistoryIndex history)
		{
			if (fail)
			{
				throw new IllegalStateException("chat is gone");
			}
			thread = Thread.currentThread().getName();
			published.add(review);
			histories.add(history);
		}

		@Override
		public void showHistory(HistoryIndex history)
		{
			histories.add(history);
		}
	}
}
```

`src/test/java/com/yamareviewer/adapter/publish/CompositeReviewPublisherTest.java`:

```java
package com.yamareviewer.adapter.publish;

import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryProjector;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.testing.Reviews;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class CompositeReviewPublisherTest
{
	private final List<String> calls = new ArrayList<>();

	private ReviewPublisher publisher(String name, boolean fail)
	{
		return new ReviewPublisher()
		{
			@Override
			public void publish(KillReview review, HistoryIndex history)
			{
				calls.add(name + " publish " + review.getKillId());
				if (fail)
				{
					throw new IllegalStateException(name);
				}
			}

			@Override
			public void showHistory(HistoryIndex history)
			{
				calls.add(name + " history");
				if (fail)
				{
					throw new IllegalStateException(name);
				}
			}
		};
	}

	@Test
	public void forwardsToEveryPublisherEvenWhenOneFails()
	{
		CompositeReviewPublisher composite = new CompositeReviewPublisher(List.of(publisher("chat", true), publisher("panel", false)));
		HistoryIndex history = HistoryProjector.index(List.of(), 50);

		composite.publish(Reviews.sample(), history);
		composite.showHistory(history);

		assertEquals(List.of("chat publish kill-1", "panel publish kill-1", "chat history", "panel history"), calls);
	}
}
```

`src/test/java/com/yamareviewer/adapter/publish/ChatReviewPublisherTest.java`:

```java
package com.yamareviewer.adapter.publish;

import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryProjector;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ChatLineOptions;
import com.yamareviewer.domain.text.ChatLines;
import com.yamareviewer.testing.Reviews;
import java.util.List;
import static java.util.stream.Collectors.toList;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class ChatReviewPublisherTest
{
	private final ChatMessageManager chat = mock(ChatMessageManager.class);
	private final HistoryIndex history = HistoryProjector.index(List.of(), 50);
	private ChatLineOptions options = new ChatLineOptions(true, true, true, true, false);
	private final ChatReviewPublisher publisher = new ChatReviewPublisher(chat, () -> options);

	@Test
	public void queuesOneGameMessagePerEnabledLine()
	{
		KillReview review = Reviews.sample();

		publisher.publish(review, history);

		ArgumentCaptor<QueuedMessage> captor = ArgumentCaptor.forClass(QueuedMessage.class);
		verify(chat, times(4)).queue(captor.capture());
		assertTrue(captor.getAllValues().stream().allMatch(message -> message.getType() == ChatMessageType.GAMEMESSAGE));
		assertEquals(ChatLines.lines(review, options), captor.getAllValues().stream().map(QueuedMessage::getRuneLiteFormattedMessage).collect(toList()));
	}

	@Test
	public void readsTheOptionsAtPublishTime()
	{
		options = new ChatLineOptions(true, false, false, false, false);

		publisher.publish(Reviews.sample(), history);

		verify(chat, times(1)).queue(org.mockito.ArgumentMatchers.any(QueuedMessage.class));
	}

	@Test
	public void historyIsNotChatted()
	{
		publisher.showHistory(history);

		verifyNoInteractions(chat);
	}

	@Test
	public void theFactoryBuildsAPublisher()
	{
		ChatReviewPublisher built = new ChatReviewPublisherFactory(chat).create(() -> options);

		built.publish(Reviews.sample(), history);

		verify(chat, times(4)).queue(org.mockito.ArgumentMatchers.any(QueuedMessage.class));
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.application.handler.KillEndedHandlerTest' --tests 'com.yamareviewer.adapter.publish.*'`
Expected: FAIL — the Part 1 `KillEndedHandler` has no nine-argument constructor; `cannot find symbol` for `CompositeReviewPublisher`, `ChatReviewPublisher`, `ChatReviewPublisherFactory`.

- [ ] **Step 3: Replace `KillEndedHandler`**

`src/main/java/com/yamareviewer/application/handler/KillEndedHandler.java`:

```java
package com.yamareviewer.application.handler;

import com.yamareviewer.application.command.KillEndedListener;
import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryProjector;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.KillReviewAssembler;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.ReviewBuilder;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.review.KillReview;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Everything that happens after a kill, on the plugin's own executor (spec 4.3): store the raw log, build
 * the review, store it, rebuild the history, publish if the plugin is still active. Storage failures are
 * logged and the review is still published (spec 11). Part 4 runs the health checks between the build and
 * the assembly.
 */
@Slf4j
public final class KillEndedHandler implements KillEndedListener
{
	private final ExecutorService executor;
	private final LogRepository logs;
	private final ReviewRepository reviews;
	private final ReviewBuilder builder;
	private final ReviewPublisher publisher;
	private final BooleanSupplier active;
	private final IntSupplier rawLogsKept;
	private final IntSupplier historySize;
	private final Supplier<ReviewSettings> settings;
	private volatile Future<?> pending = CompletableFuture.completedFuture(null);

	public KillEndedHandler(ExecutorService executor, LogRepository logs, ReviewRepository reviews,
		ReviewBuilder builder, ReviewPublisher publisher, BooleanSupplier active,
		IntSupplier rawLogsKept, IntSupplier historySize, Supplier<ReviewSettings> settings)
	{
		this.executor = executor;
		this.logs = logs;
		this.reviews = reviews;
		this.builder = builder;
		this.publisher = publisher;
		this.active = active;
		this.rawLogsKept = rawLogsKept;
		this.historySize = historySize;
		this.settings = settings;
	}

	@Override
	public void killEnded(KillLog kill)
	{
		try
		{
			pending = executor.submit(() -> handle(kill));
		}
		catch (RejectedExecutionException e)
		{
			log.warn("Plugin stopped before kill {} could be stored", kill.getHeader().getKillId());
		}
	}

	/** The latest submitted work, so client shutdown can wait for it. */
	public Future<?> pending()
	{
		return pending;
	}

	void handle(KillLog kill)
	{
		String killId = kill.getHeader().getKillId();
		try
		{
			logs.save(kill);
			logs.prune(Math.max(1, rawLogsKept.getAsInt()));
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not store raw log {}", killId, e);
		}

		KillReview review;
		try
		{
			ProjectionContext context = builder.run(kill, settings.get());
			review = KillReviewAssembler.assemble(kill, context);
		}
		catch (RuntimeException e)
		{
			log.error("Could not review kill {}", killId, e);
			return;
		}

		int keep = Math.max(1, historySize.getAsInt());
		try
		{
			reviews.save(review);
			reviews.prune(keep);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not store review {}", killId, e);
		}

		HistoryIndex history;
		try
		{
			history = HistoryProjector.index(reviews.loadAll(), keep);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not load the review history; showing this kill only", e);
			history = HistoryProjector.index(List.of(review), keep);
		}

		if (!active.getAsBoolean())
		{
			log.debug("Plugin inactive; review {} not published", killId);
			return;
		}
		try
		{
			publisher.publish(review, history);
		}
		catch (RuntimeException e)
		{
			log.warn("Could not publish review {}", killId, e);
		}
	}
}
```

- [ ] **Step 4: Write the publishers**

`src/main/java/com/yamareviewer/adapter/publish/CompositeReviewPublisher.java`:

```java
package com.yamareviewer.adapter.publish;

import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.review.KillReview;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** Fans out to the chat and panel publishers; one failing publisher never stops the others. */
@Slf4j
public final class CompositeReviewPublisher implements ReviewPublisher
{
	private final List<ReviewPublisher> publishers;

	public CompositeReviewPublisher(List<ReviewPublisher> publishers)
	{
		this.publishers = List.copyOf(publishers);
	}

	@Override
	public void publish(KillReview review, HistoryIndex history)
	{
		for (ReviewPublisher publisher : publishers)
		{
			try
			{
				publisher.publish(review, history);
			}
			catch (RuntimeException e)
			{
				log.warn("Publisher {} failed", publisher.getClass().getSimpleName(), e);
			}
		}
	}

	@Override
	public void showHistory(HistoryIndex history)
	{
		for (ReviewPublisher publisher : publishers)
		{
			try
			{
				publisher.showHistory(history);
			}
			catch (RuntimeException e)
			{
				log.warn("Publisher {} failed", publisher.getClass().getSimpleName(), e);
			}
		}
	}
}
```

`src/main/java/com/yamareviewer/adapter/publish/ChatReviewPublisher.java`:

```java
package com.yamareviewer.adapter.publish;

import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ChatLineOptions;
import com.yamareviewer.domain.text.ChatLines;
import java.util.function.Supplier;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

/** The chat summary of spec 7.2. ChatMessageManager.queue is thread-safe, so this runs on the plugin executor. */
public final class ChatReviewPublisher implements ReviewPublisher
{
	private final ChatMessageManager chatMessageManager;
	private final Supplier<ChatLineOptions> options;

	public ChatReviewPublisher(ChatMessageManager chatMessageManager, Supplier<ChatLineOptions> options)
	{
		this.chatMessageManager = chatMessageManager;
		this.options = options;
	}

	@Override
	public void publish(KillReview review, HistoryIndex history)
	{
		for (String line : ChatLines.lines(review, options.get()))
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.runeLiteFormattedMessage(line)
				.build());
		}
	}

	@Override
	public void showHistory(HistoryIndex history)
	{
		// The history is only shown in the panel.
	}
}
```

`src/main/java/com/yamareviewer/adapter/publish/ChatReviewPublisherFactory.java`:

```java
package com.yamareviewer.adapter.publish;

import com.yamareviewer.domain.text.ChatLineOptions;
import java.util.function.Supplier;
import javax.inject.Inject;
import net.runelite.client.chat.ChatMessageManager;

/**
 * Injected into the plugin so that only this package depends on ChatMessageManager
 * (ArchitectureTest.onlyPublishTalksToChat); the plugin never sees the chat manager itself.
 */
public final class ChatReviewPublisherFactory
{
	private final ChatMessageManager chatMessageManager;

	@Inject
	public ChatReviewPublisherFactory(ChatMessageManager chatMessageManager)
	{
		this.chatMessageManager = chatMessageManager;
	}

	public ChatReviewPublisher create(Supplier<ChatLineOptions> options)
	{
		return new ChatReviewPublisher(chatMessageManager, options);
	}
}
```

- [ ] **Step 5: Keep the plugin compiling until Task 17 rewires it**

`YamaReviewerPlugin.startUp()` (Part 1 Task 10) still calls the old three-argument constructor, so the main source set would not compile. Replace that one line

```java
		KillEndedHandler handler = new KillEndedHandler(executor, logs, config::rawLogsKept);
```

with this stopgap (replaced entirely in Task 17; `active` is `() -> false`, so nothing is published by this intermediate build):

```java
		KillEndedHandler handler = new KillEndedHandler(executor, logs, new GsonReviewRepository(new FilepathFileStore(getPluginDirectory()), gson),
			new ReviewBuilder(Projections.standard(), ids, Rules.DEFAULT), new CompositeReviewPublisher(List.of()), () -> false,
			config::rawLogsKept, () -> 50, () -> ReviewSettings.DEFAULT);
```

and add the imports `com.yamareviewer.adapter.persistence.GsonReviewRepository`, `com.yamareviewer.adapter.publish.CompositeReviewPublisher`, `com.yamareviewer.domain.ids.Rules`, `com.yamareviewer.domain.projection.Projections`, `com.yamareviewer.domain.projection.ReviewBuilder`, `com.yamareviewer.domain.projection.ReviewSettings` and `java.util.List` to the plugin.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.application.handler.KillEndedHandlerTest' --tests 'com.yamareviewer.adapter.publish.*'`
Expected: PASS (11 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yamareviewer/YamaReviewerPlugin.java src/main/java/com/yamareviewer/application/handler/KillEndedHandler.java src/main/java/com/yamareviewer/adapter/publish src/test/java/com/yamareviewer/application/handler/KillEndedHandlerTest.java src/test/java/com/yamareviewer/adapter/publish
git commit -m "feat: build, store and publish the review when a kill ends"
```

---
### Task 16: The side panel and its publisher

**Files:**
- Create in `src/main/java/com/yamareviewer/adapter/ui/`: `TextLine.java`, `ReviewTab.java`, `HistoryTab.java`, `ReviewPanel.java`, `PanelIcon.java`, `SystemClipboard.java`, `KillReviewOpener.java`
- Create: `src/main/java/com/yamareviewer/adapter/publish/PanelReviewPublisher.java`
- Test: `src/test/java/com/yamareviewer/adapter/ui/ReviewPanelTest.java`, `PanelIconTest.java`, `KillReviewOpenerTest.java`, `src/test/java/com/yamareviewer/adapter/publish/PanelReviewPublisherTest.java`

**Interfaces:**
- Consumes: `ReviewView`, `ViewSection`, `ReviewFormatter`, `ClipboardExport`, `HistoryFormatter` (Task 14), `HistoryIndex`, `HistoryKey`, `HistoryView` (Task 13), `ReviewRepository` (Task 12), `Mode`, `Contract` (Task 1), RuneLite `PluginPanel`, `ColorScheme`.
- Produces: `ReviewPanel(Consumer<String> clipboard)` extending `PluginPanel` with `show(ReviewView, String clipboardText, HistoryIndex)`, `showHistory(HistoryIndex)`, `showKill(Mode, ReviewView, String clipboardText)` (all hop to the Swing thread themselves), `onKillSelected(Consumer<String> killId)`, `footer()` (a `JPanel` reserved for Part 4's "Report a problem"), package-private `lastKillTab()` and `historyTab(Mode)` for tests; `PanelIcon.create()` (a 16×16 Java2D icon, no binary resource); `SystemClipboard.copy(String)`; `KillReviewOpener(ExecutorService, ReviewRepository, ReviewPanel)` with `open(String killId)`; `PanelReviewPublisher(ReviewPanel)`. Swing code renders strings only; every number is formatted in `domain.text`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/adapter/ui/ReviewPanelTest.java`:

```java
package com.yamareviewer.adapter.ui;

import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryProjector;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.text.ReviewFormatter;
import com.yamareviewer.domain.text.ReviewView;
import com.yamareviewer.testing.Reviews;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class ReviewPanelTest
{
	private final List<String> copied = new ArrayList<>();
	private final List<String> opened = new ArrayList<>();
	private ReviewPanel panel;

	@Before
	public void setUp() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> panel = new ReviewPanel(copied::add));
		panel.onKillSelected(opened::add);
	}

	private static void flush() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
		});
	}

	private static ReviewView view()
	{
		return ReviewFormatter.format(Reviews.sample().toBuilder().flares(Section.hidden(HiddenReason.IDS_NOT_CAPTURED)).build());
	}

	private static boolean contains(List<String> texts, String needle)
	{
		return texts.stream().anyMatch(text -> text.contains(needle));
	}

	@Test
	public void showsTheLastKillWithItsStatusAndCopiesItsText() throws Exception
	{
		assertFalse(panel.lastKillTab().copyButton().isEnabled());

		panel.show(view(), "clipboard text", new HistoryIndex(Map.of()));
		flush();

		List<String> texts = panel.lastKillTab().texts();
		assertTrue(texts.toString(), contains(texts, "Yama (solo) 4:12"));
		assertTrue(texts.toString(), contains(texts, "Complete"));
		assertTrue(texts.toString(), contains(texts, "Hidden: IDs not captured"));
		assertTrue(texts.toString(), contains(texts, "Shark x2: 1,600"));
		SwingUtilities.invokeAndWait(() -> panel.lastKillTab().copyButton().doClick());
		assertEquals(List.of("clipboard text"), copied);
	}

	@Test
	public void modeTabsFilterByContractAndOpenASelectedKill() throws Exception
	{
		KillReview none = Reviews.builder("k1", 1_000L, Mode.SOLO, Contract.NONE).build();
		KillReview bloodied = Reviews.builder("k2", 2_000L, Mode.SOLO, Contract.BLOODIED_BLOWS).build();
		KillReview host = Reviews.builder("k3", 3_000L, Mode.DUO_HOST, Contract.NONE).build();

		panel.showHistory(HistoryProjector.index(List.of(none, bloodied, host), 50));
		flush();

		HistoryTab solo = panel.historyTab(Mode.SOLO);
		assertEquals(List.of("k1"), solo.killIds());
		assertTrue(contains(solo.texts(), "1 kill"));
		assertEquals(List.of("k3"), panel.historyTab(Mode.DUO_HOST).killIds());
		assertTrue(contains(panel.historyTab(Mode.DUO_JOINER).texts(), "No kills yet"));

		SwingUtilities.invokeAndWait(() -> solo.contractFilter().setSelectedItem(Contract.BLOODIED_BLOWS.displayName()));
		flush();
		assertEquals(List.of("k2"), solo.killIds());

		SwingUtilities.invokeAndWait(() -> solo.killButtons().get(0).doClick());
		assertEquals(List.of("k2"), opened);
	}

	@Test
	public void aPastKillRendersInsideItsModeTab() throws Exception
	{
		panel.showKill(Mode.DUO_HOST, view(), "past text");
		flush();

		HistoryTab tab = panel.historyTab(Mode.DUO_HOST);
		assertTrue(contains(tab.texts(), "Yama (solo) 4:12"));
		SwingUtilities.invokeAndWait(() -> tab.selectedKill().copyButton().doClick());
		assertEquals(List.of("past text"), copied);
	}

	@Test
	public void theFooterIsEmptyUntilPart4()
	{
		assertEquals(0, panel.footer().getComponentCount());
	}
}
```

`src/test/java/com/yamareviewer/adapter/ui/PanelIconTest.java`:

```java
package com.yamareviewer.adapter.ui;

import java.awt.image.BufferedImage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PanelIconTest
{
	@Test
	public void drawsASixteenPixelIconWithTransparentCornersAndAnOpaqueStem()
	{
		BufferedImage icon = PanelIcon.create();

		assertEquals(16, icon.getWidth());
		assertEquals(16, icon.getHeight());
		assertEquals(0, icon.getRGB(0, 15) >>> 24);
		assertTrue((icon.getRGB(8, 11) >>> 24) > 0);
	}
}
```

`src/test/java/com/yamareviewer/adapter/ui/KillReviewOpenerTest.java`:

```java
package com.yamareviewer.adapter.ui;

import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ClipboardExport;
import com.yamareviewer.domain.text.ReviewFormatter;
import com.yamareviewer.testing.Reviews;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class KillReviewOpenerTest
{
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "yama-reviewer-test"));
	private final ReviewPanel panel = mock(ReviewPanel.class);
	private final KillReview a = Reviews.builder("a", 1_000L, Mode.SOLO, Contract.NONE).build();
	private final KillReview b = Reviews.builder("b", 2_000L, Mode.DUO_HOST, Contract.NONE).build();
	private final ReviewRepository reviews = new ReviewRepository()
	{
		@Override
		public void save(KillReview review)
		{
		}

		@Override
		public List<KillReview> loadAll() throws IOException
		{
			return List.of(b, a);
		}

		@Override
		public void prune(int keepPerKey)
		{
		}
	};
	private final KillReviewOpener opener = new KillReviewOpener(executor, reviews, panel);

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private void drain() throws Exception
	{
		executor.submit(() ->
		{
		}).get(5, TimeUnit.SECONDS);
	}

	@Test
	public void loadsTheReviewOnTheExecutorAndShowsItInItsModeTab() throws Exception
	{
		opener.open("b");
		drain();

		verify(panel).showKill(Mode.DUO_HOST, ReviewFormatter.format(b), ClipboardExport.text(b));
	}

	@Test
	public void anUnknownKillShowsNothing() throws Exception
	{
		opener.open("zzz");
		drain();

		verifyNoInteractions(panel);
	}

	@Test
	public void aStoppedExecutorIsLoggedNotThrown()
	{
		executor.shutdownNow();

		opener.open("a");

		verifyNoInteractions(panel);
	}
}
```

`src/test/java/com/yamareviewer/adapter/publish/PanelReviewPublisherTest.java`:

```java
package com.yamareviewer.adapter.publish;

import com.yamareviewer.adapter.ui.ReviewPanel;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryProjector;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ClipboardExport;
import com.yamareviewer.domain.text.ReviewFormatter;
import com.yamareviewer.testing.Reviews;
import java.util.List;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.ArgumentMatchers.eq;

public class PanelReviewPublisherTest
{
	private final ReviewPanel panel = mock(ReviewPanel.class);
	private final PanelReviewPublisher publisher = new PanelReviewPublisher(panel);

	@Test
	public void handsViewModelsToThePanel()
	{
		KillReview review = Reviews.sample();
		HistoryIndex history = HistoryProjector.index(List.of(review), 50);

		publisher.publish(review, history);
		publisher.showHistory(history);

		verify(panel).show(eq(ReviewFormatter.format(review)), eq(ClipboardExport.text(review)), same(history));
		verify(panel).showHistory(same(history));
		verifyNoMoreInteractions(panel);
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.ui.*' --tests 'com.yamareviewer.adapter.publish.PanelReviewPublisherTest'`
Expected: FAIL — `cannot find symbol` for `ReviewPanel`, `HistoryTab`, `PanelIcon`, `KillReviewOpener`, `PanelReviewPublisher`.

- [ ] **Step 3: Write the text lines and the two tab components**

`src/main/java/com/yamareviewer/adapter/ui/TextLine.java`:

```java
package com.yamareviewer.adapter.ui;

import java.awt.Color;
import java.awt.Font;
import javax.swing.JLabel;
import net.runelite.client.ui.ColorScheme;

/** Wrapping labels for the fixed-width panel; the plain text is kept as a client property for tests. */
final class TextLine
{
	static final String PLAIN_TEXT = "yama-reviewer.plain-text";
	private static final int WRAP_WIDTH_PX = 195;

	private TextLine()
	{
	}

	static JLabel label(String text)
	{
		JLabel label = new JLabel("<html><body style='width:" + WRAP_WIDTH_PX + "px'>" + escape(text) + "</body></html>");
		label.putClientProperty(PLAIN_TEXT, text);
		label.setForeground(ColorScheme.TEXT_COLOR);
		return label;
	}

	static JLabel title(String text)
	{
		JLabel label = label(text);
		label.setFont(label.getFont().deriveFont(Font.BOLD));
		label.setForeground(Color.WHITE);
		return label;
	}

	static JLabel muted(String text)
	{
		JLabel label = label(text);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return label;
	}

	static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
```

`src/main/java/com/yamareviewer/adapter/ui/ReviewTab.java`:

```java
package com.yamareviewer.adapter.ui;

import com.yamareviewer.domain.text.ReviewView;
import com.yamareviewer.domain.text.ViewSection;
import java.awt.Color;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;

/** One review: headline, status badge, Copy button, then the sections. Renders a ReviewView and nothing else. */
final class ReviewTab extends JPanel
{
	private final Consumer<String> clipboard;
	private final JButton copy = new JButton("Copy");
	private String clipboardText;

	ReviewTab(Consumer<String> clipboard, String emptyText)
	{
		this.clipboard = clipboard;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		copy.setEnabled(false);
		copy.addActionListener(event ->
		{
			if (clipboardText != null)
			{
				clipboard.accept(clipboardText);
			}
		});
		removeAll();
		add(TextLine.muted(emptyText));
	}

	void render(ReviewView view, String text)
	{
		clipboardText = text;
		copy.setEnabled(true);
		removeAll();
		add(TextLine.title(view.getHeadline()));
		JLabel status = TextLine.label(view.getStatus());
		status.setOpaque(true);
		status.setForeground(Color.BLACK);
		status.setBackground("Complete".equals(view.getStatus()) ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_INPROGRESS_COLOR);
		add(status);
		add(copy);
		for (ViewSection section : view.getSections())
		{
			add(Box.createVerticalStrut(8));
			add(TextLine.title(section.getTitle()));
			if (section.getHiddenReason() != null)
			{
				add(TextLine.muted("Hidden: " + section.getHiddenReason()));
			}
			for (String line : section.getLines())
			{
				add(TextLine.label(line));
			}
		}
		revalidate();
		repaint();
	}

	JButton copyButton()
	{
		return copy;
	}

	List<String> texts()
	{
		List<String> texts = new ArrayList<>();
		for (Component component : getComponents())
		{
			if (component instanceof JLabel)
			{
				Object plain = ((JLabel) component).getClientProperty(TextLine.PLAIN_TEXT);
				if (plain != null)
				{
					texts.add(plain.toString());
				}
			}
		}
		return texts;
	}
}
```

`src/main/java/com/yamareviewer/adapter/ui/HistoryTab.java`:

```java
package com.yamareviewer.adapter.ui;

import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryKey;
import com.yamareviewer.domain.history.HistoryView;
import com.yamareviewer.domain.history.KillSummary;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.text.HistoryFormatter;
import com.yamareviewer.domain.text.ReviewView;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;

/** One mode: a contract filter, the statistics of that mode and contract, the kill list, and the selected kill's review. */
final class HistoryTab extends JPanel
{
	private static final List<Contract> FILTER = filterOrder();

	private final Mode mode;
	private final Consumer<String> opener;
	private final JComboBox<String> filter = new JComboBox<>(FILTER.stream().map(Contract::displayName).toArray(String[]::new));
	private final JPanel summary = column();
	private final JPanel kills = column();
	private final List<JButton> killButtons = new ArrayList<>();
	private final ReviewTab selected;
	private HistoryIndex index = new HistoryIndex(Map.of());

	HistoryTab(Mode mode, Consumer<String> clipboard, Consumer<String> opener)
	{
		this.mode = mode;
		this.opener = opener;
		this.selected = new ReviewTab(clipboard, "Select a kill above to see its review");
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		filter.addActionListener(event -> renderKills());
		add(TextLine.muted("Contract"));
		add(filter);
		add(summary);
		add(Box.createVerticalStrut(8));
		add(kills);
		add(Box.createVerticalStrut(8));
		add(selected);
		renderKills();
	}

	void render(HistoryIndex history)
	{
		index = history;
		renderKills();
	}

	void renderKill(ReviewView view, String clipboardText)
	{
		selected.render(view, clipboardText);
	}

	JComboBox<String> contractFilter()
	{
		return filter;
	}

	List<JButton> killButtons()
	{
		return List.copyOf(killButtons);
	}

	List<String> killIds()
	{
		return killButtons.stream().map(button -> button.getClientProperty(KILL_ID).toString()).collect(Collectors.toList());
	}

	ReviewTab selectedKill()
	{
		return selected;
	}

	List<String> texts()
	{
		List<String> texts = new ArrayList<>();
		for (Component component : summary.getComponents())
		{
			if (component instanceof JLabel)
			{
				texts.add(((JLabel) component).getClientProperty(TextLine.PLAIN_TEXT).toString());
			}
		}
		texts.addAll(selected.texts());
		return texts;
	}

	private static final String KILL_ID = "yama-reviewer.kill-id";

	private void renderKills()
	{
		Contract contract = FILTER.get(Math.max(0, filter.getSelectedIndex()));
		Optional<HistoryView> view = index.view(new HistoryKey(mode, contract));
		summary.removeAll();
		kills.removeAll();
		killButtons.clear();
		List<String> lines = view.map(HistoryFormatter::summaryLines).orElse(List.of("No kills yet"));
		for (String line : lines)
		{
			summary.add(TextLine.label(line));
		}
		if (view.isPresent())
		{
			for (KillSummary kill : view.get().getKills())
			{
				JButton button = new JButton(HistoryFormatter.killLine(kill));
				button.setHorizontalAlignment(SwingConstants.LEFT);
				button.putClientProperty(KILL_ID, kill.getKillId());
				button.addActionListener(event -> opener.accept(kill.getKillId()));
				killButtons.add(button);
				kills.add(button);
			}
		}
		revalidate();
		repaint();
	}

	private static JPanel column()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		return panel;
	}

	/** No contract first, then the contracts of spec 6.3.1 in order, then the unknown contract. */
	private static List<Contract> filterOrder()
	{
		List<Contract> order = new ArrayList<>();
		order.add(Contract.NONE);
		for (Contract contract : Contract.values())
		{
			if (contract.shortName() != null)
			{
				order.add(contract);
			}
		}
		order.add(Contract.UNKNOWN_CONTRACT);
		return List.copyOf(order);
	}
}
```

- [ ] **Step 4: Write `ReviewPanel`, the icon, the clipboard and the opener**

`src/main/java/com/yamareviewer/adapter/ui/ReviewPanel.java`:

```java
package com.yamareviewer.adapter.ui;

import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.text.ReviewView;
import java.awt.BorderLayout;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * The side panel of spec 7.3: tabs Last kill, Solo, Duo host, Duo joiner; a contract filter per mode tab;
 * a Copy button on every review; a footer reserved for Part 4's "Report a problem". It renders only
 * ReviewView and HistoryView, and every public update hops to the Swing thread itself.
 */
public class ReviewPanel extends PluginPanel
{
	private final JTabbedPane tabs = new JTabbedPane();
	private final ReviewTab lastKill;
	private final Map<Mode, HistoryTab> modeTabs = new EnumMap<>(Mode.class);
	private final JPanel footer = new JPanel(new BorderLayout());
	private Consumer<String> killOpener = killId ->
	{
	};

	public ReviewPanel(Consumer<String> clipboard)
	{
		super(false);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		lastKill = new ReviewTab(clipboard, "No kill reviewed yet");
		tabs.addTab("Last kill", scroll(lastKill));
		for (Mode mode : Mode.values())
		{
			HistoryTab tab = new HistoryTab(mode, clipboard, killId -> killOpener.accept(killId));
			modeTabs.put(mode, tab);
			tabs.addTab(tabTitle(mode), scroll(tab));
		}
		footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(tabs, BorderLayout.CENTER);
		add(footer, BorderLayout.SOUTH);
	}

	/** Called with a kill id when the user selects a kill in a history tab. */
	public void onKillSelected(Consumer<String> opener)
	{
		killOpener = opener;
	}

	/** Part 4 puts "Report a problem" here. */
	public JPanel footer()
	{
		return footer;
	}

	public void show(ReviewView view, String clipboardText, HistoryIndex history)
	{
		SwingUtilities.invokeLater(() ->
		{
			lastKill.render(view, clipboardText);
			renderHistory(history);
			tabs.setSelectedIndex(0);
		});
	}

	public void showHistory(HistoryIndex history)
	{
		SwingUtilities.invokeLater(() -> renderHistory(history));
	}

	/** A past kill opened from a history tab, rendered inside that mode's tab. */
	public void showKill(Mode mode, ReviewView view, String clipboardText)
	{
		SwingUtilities.invokeLater(() -> modeTabs.get(mode).renderKill(view, clipboardText));
	}

	ReviewTab lastKillTab()
	{
		return lastKill;
	}

	HistoryTab historyTab(Mode mode)
	{
		return modeTabs.get(mode);
	}

	private void renderHistory(HistoryIndex history)
	{
		modeTabs.values().forEach(tab -> tab.render(history));
	}

	private static JScrollPane scroll(JComponent content)
	{
		JScrollPane pane = new JScrollPane(content);
		pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		pane.setBorder(null);
		pane.getVerticalScrollBar().setUnitIncrement(16);
		return pane;
	}

	private static String tabTitle(Mode mode)
	{
		String label = mode.label();
		return label.substring(0, 1).toUpperCase(Locale.ROOT) + label.substring(1);
	}
}
```

`src/main/java/com/yamareviewer/adapter/ui/PanelIcon.java`:

```java
package com.yamareviewer.adapter.ui;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.ColorScheme;

/** The navigation button icon, drawn at runtime with Java2D (no binary resource): an orange "Y" of two horns and a stem. */
public final class PanelIcon
{
	static final int SIZE = 16;

	private PanelIcon()
	{
	}

	public static BufferedImage create()
	{
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setColor(ColorScheme.BRAND_ORANGE);
			graphics.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			graphics.draw(new Line2D.Float(3, 2, 8, 8));
			graphics.draw(new Line2D.Float(13, 2, 8, 8));
			graphics.draw(new Line2D.Float(8, 8, 8, 14));
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}
}
```

`src/main/java/com/yamareviewer/adapter/ui/SystemClipboard.java`:

```java
package com.yamareviewer.adapter.ui;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import lombok.extern.slf4j.Slf4j;

/** The Copy button's target. */
@Slf4j
public final class SystemClipboard
{
	private SystemClipboard()
	{
	}

	public static void copy(String text)
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
		}
		catch (HeadlessException | IllegalStateException e)
		{
			log.warn("Could not copy the review to the clipboard", e);
		}
	}
}
```

`src/main/java/com/yamareviewer/adapter/ui/KillReviewOpener.java`:

```java
package com.yamareviewer.adapter.ui;

import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ClipboardExport;
import com.yamareviewer.domain.text.ReviewFormatter;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;

/** Loads a past kill's review on the executor and hands its view model to the panel (spec 7.3, "selecting a kill"). */
@Slf4j
public final class KillReviewOpener
{
	private final ExecutorService executor;
	private final ReviewRepository reviews;
	private final ReviewPanel panel;

	public KillReviewOpener(ExecutorService executor, ReviewRepository reviews, ReviewPanel panel)
	{
		this.executor = executor;
		this.reviews = reviews;
		this.panel = panel;
	}

	public void open(String killId)
	{
		try
		{
			executor.execute(() -> openNow(killId));
		}
		catch (RejectedExecutionException e)
		{
			log.warn("Plugin stopped before kill {} could be opened", killId);
		}
	}

	private void openNow(String killId)
	{
		try
		{
			Optional<KillReview> review = reviews.loadAll().stream().filter(candidate -> candidate.getKillId().equals(killId)).findFirst();
			review.ifPresent(found -> panel.showKill(found.getMode(), ReviewFormatter.format(found), ClipboardExport.text(found)));
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not open review {}", killId, e);
		}
	}
}
```

- [ ] **Step 5: Write `PanelReviewPublisher`**

`src/main/java/com/yamareviewer/adapter/publish/PanelReviewPublisher.java`:

```java
package com.yamareviewer.adapter.publish;

import com.yamareviewer.adapter.ui.ReviewPanel;
import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ClipboardExport;
import com.yamareviewer.domain.text.ReviewFormatter;

/** Turns the review into immutable view models on the executor; the panel hops to the Swing thread itself. */
public final class PanelReviewPublisher implements ReviewPublisher
{
	private final ReviewPanel panel;

	public PanelReviewPublisher(ReviewPanel panel)
	{
		this.panel = panel;
	}

	@Override
	public void publish(KillReview review, HistoryIndex history)
	{
		panel.show(ReviewFormatter.format(review), ClipboardExport.text(review), history);
	}

	@Override
	public void showHistory(HistoryIndex history)
	{
		panel.showHistory(history);
	}
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.ui.*' --tests 'com.yamareviewer.adapter.publish.PanelReviewPublisherTest'`
Expected: PASS (9 tests). The Swing tests build components without a display; if the JVM reports `HeadlessException`, run them with `-Djava.awt.headless=true` (Gradle's default on servers) and never create a `JFrame` in a test.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yamareviewer/adapter/ui src/main/java/com/yamareviewer/adapter/publish/PanelReviewPublisher.java src/test/java/com/yamareviewer/adapter/ui src/test/java/com/yamareviewer/adapter/publish/PanelReviewPublisherTest.java
git commit -m "feat: add the review side panel with history tabs and copy"
```

---
### Task 17: Config, plugin wiring and the silence replay test

**Files:**
- Create: `src/main/java/com/yamareviewer/ModeOverride.java`
- Replace: `src/main/java/com/yamareviewer/YamaReviewerConfig.java`, `src/main/java/com/yamareviewer/YamaReviewerPlugin.java`
- Test: `src/test/java/com/yamareviewer/SilenceReplayTest.java`

**Interfaces:**
- Consumes: everything from Tasks 12–16 and Part 1's recording classes.
- Produces: `ModeOverride { AUTO, SOLO, DUO_HOST, DUO_JOINER }` with `mode()` (null for AUTO); the config keys of spec 10 (`chatPhaseLine`, `chatPrayerLine`, `chatFlareLine`, `chatSpecLine`, `chatDeathRecap`, `priceSource`, `modeOverride`, `historySize`, `rawLogsKept`, `captureMode`); the wired plugin with the navigation button, the `active` flag cleared first thing in `shutDown`, and the history loaded at start-up.

- [ ] **Step 1: Write the failing silence test**

`src/test/java/com/yamareviewer/SilenceReplayTest.java`:

```java
package com.yamareviewer;

import com.google.gson.Gson;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.adapter.persistence.GsonLogRepository;
import com.yamareviewer.adapter.persistence.GsonReviewRepository;
import com.yamareviewer.adapter.persistence.InMemoryFileStore;
import com.yamareviewer.adapter.recording.ActorResolver;
import com.yamareviewer.adapter.recording.EventTranslator;
import com.yamareviewer.adapter.recording.GameEventListener;
import com.yamareviewer.adapter.recording.PositionReader;
import com.yamareviewer.adapter.recording.TickSampler;
import com.yamareviewer.application.command.KillSession;
import com.yamareviewer.application.handler.KillEndedHandler;
import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.projection.Projections;
import com.yamareviewer.domain.projection.ReviewBuilder;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.testing.TestIds;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.game.NpcUtil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Spec 4.4: a synthetic fight through GameEventListener publishes nothing until the kill has ended, then exactly one review. */
public class SilenceReplayTest
{
	private final Client client = mock(Client.class);
	private final Player self = mock(Player.class);
	private final NPC yama = mock(NPC.class);
	private final PositionReader positions = mock(PositionReader.class);
	private final TickSampler sampler = mock(TickSampler.class);
	private final NpcUtil npcUtil = mock(NpcUtil.class);
	private final IdRegistry ids = TestIds.registry();
	private final InMemoryFileStore files = new InMemoryFileStore();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "yama-reviewer-test"));
	private final RecordingPublisher publisher = new RecordingPublisher();
	private KillEndedHandler handler;
	private GameEventListener listener;

	@Before
	public void setUp()
	{
		when(client.getLocalPlayer()).thenReturn(self);
		when(self.getName()).thenReturn("Me");
		when(positions.regionId(any())).thenReturn(TestIds.id(Role.YAMAS_DOMAIN));
		when(yama.getId()).thenReturn(TestIds.id(Role.YAMA));
		when(yama.getAnimation()).thenReturn(TestIds.id(Role.YAMA_STANDARD_ATTACK));
		when(sampler.sample(anyInt(), any(), any())).thenAnswer(invocation ->
			new TickState(invocation.getArgument(0), Set.of(), 99, 99, 100, 100, -1, null, null, null));

		Gson gson = new Gson();
		LogRepository logs = new GsonLogRepository(files, new EventCodec(gson));
		ReviewRepository reviews = new GsonReviewRepository(files, gson);
		ReviewBuilder builder = new ReviewBuilder(Projections.standard(), ids, Rules.DEFAULT);
		handler = new KillEndedHandler(executor, logs, reviews, builder, publisher, () -> true, () -> 20, () -> 50, () -> ReviewSettings.DEFAULT);
		KillSession session = new KillSession(handler, (tick, kind) -> Optional.of(new SuppliesSnapshot(tick, kind, List.of())),
			Clock.systemUTC(), () -> "kill-1", "0.2.0", ids.fingerprint(), () -> false);
		ActorResolver actors = new ActorResolver(client, ids, session::partnerName);
		EventTranslator translator = new EventTranslator(ids, actors, session::currentTick, () -> false, itemId -> "item", positions);
		listener = new GameEventListener(client, ids, session, translator, sampler, positions, npcUtil, handler::pending);
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private void drain() throws Exception
	{
		executor.submit(() ->
		{
		}).get(5, TimeUnit.SECONDS);
	}

	private HitsplatApplied hitOnSelf(int amount)
	{
		Hitsplat hitsplat = mock(Hitsplat.class);
		when(hitsplat.getHitsplatType()).thenReturn(HitsplatID.DAMAGE_ME);
		when(hitsplat.getAmount()).thenReturn(amount);
		when(hitsplat.isMine()).thenReturn(false);
		HitsplatApplied event = new HitsplatApplied();
		event.setActor(self);
		event.setHitsplat(hitsplat);
		return event;
	}

	private AnimationChanged yamaAttacks()
	{
		AnimationChanged event = new AnimationChanged();
		event.setActor(yama);
		return event;
	}

	@Test
	public void nothingIsPublishedBeforeTheKillEndsAndExactlyOneReviewAfter() throws Exception
	{
		listener.onNpcSpawned(new NpcSpawned(yama));
		for (int tick = 0; tick < 40; tick++)
		{
			if (tick % 8 == 5)
			{
				listener.onAnimationChanged(yamaAttacks());
			}
			if (tick % 10 == 7)
			{
				listener.onHitsplatApplied(hitOnSelf(12));
			}
			listener.onGameTick(new GameTick());
		}
		drain();

		assertEquals(0, publisher.published.size());
		assertEquals(0, publisher.histories.size());
		assertTrue(files.paths().isEmpty());

		when(npcUtil.isDying(yama)).thenReturn(true);
		listener.onNpcDespawned(new NpcDespawned(yama));
		listener.onGameTick(new GameTick());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertEquals(1, publisher.published.size());
		KillReview review = publisher.published.get(0);
		assertEquals("kill-1", review.getKillId());
		assertEquals(EndReason.YAMA_DIED, review.getEndReason());
		assertEquals(Mode.SOLO, review.getMode());
		assertEquals(48, review.getDamage().value().total(Actor.SELF));
		assertEquals(1, publisher.histories.size());
		assertTrue(files.paths().stream().anyMatch(path -> path.startsWith("raw/")));
		assertTrue(files.paths().stream().anyMatch(path -> path.startsWith("reviews/SOLO/")));
	}

	private static final class RecordingPublisher implements ReviewPublisher
	{
		private final List<KillReview> published = new CopyOnWriteArrayList<>();
		private final List<HistoryIndex> histories = new CopyOnWriteArrayList<>();

		@Override
		public void publish(KillReview review, HistoryIndex history)
		{
			published.add(review);
			histories.add(history);
		}

		@Override
		public void showHistory(HistoryIndex history)
		{
			histories.add(history);
		}
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.SilenceReplayTest'`
Expected: PASS (1 test). This is the end-to-end guard of spec 4.4 over parts that already exist (the Part 1 listener, the Task 15 handler, the standard projections); it is written here, before the wiring, so that the wiring below is checked against the same objects the test drives. If it fails, the review pipeline publishes during a fight or not at all: fix that before wiring the plugin.

- [ ] **Step 3: Write `ModeOverride` and replace the config**

`src/main/java/com/yamareviewer/ModeOverride.java`:

```java
package com.yamareviewer;

import com.yamareviewer.domain.model.Mode;

/** The modeOverride config value (spec 10). */
public enum ModeOverride
{
	AUTO(null),
	SOLO(Mode.SOLO),
	DUO_HOST(Mode.DUO_HOST),
	DUO_JOINER(Mode.DUO_JOINER);

	private final Mode mode;

	ModeOverride(Mode mode)
	{
		this.mode = mode;
	}

	/** Null for AUTO: detect the mode. */
	public Mode mode()
	{
		return mode;
	}
}
```

`src/main/java/com/yamareviewer/YamaReviewerConfig.java`:

```java
package com.yamareviewer;

import com.yamareviewer.domain.review.PriceMode;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(YamaReviewerConfig.GROUP)
public interface YamaReviewerConfig extends Config
{
	String GROUP = "yamareviewer";

	@ConfigSection(
		name = "Chat summary",
		description = "Which lines of the after-kill summary are printed in the chat",
		position = 10
	)
	String chat = "chat";

	@ConfigSection(
		name = "Review",
		description = "How kills are reviewed and kept",
		position = 20
	)
	String review = "review";

	@ConfigSection(
		name = "Development",
		description = "Settings used while capturing logging kills",
		position = 100,
		closedByDefault = true
	)
	String development = "development";

	@ConfigItem(
		keyName = "chatPhaseLine",
		name = "Phase times",
		description = "Kill time and the time of each phase",
		position = 11,
		section = chat
	)
	default boolean chatPhaseLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatPrayerLine",
		name = "P3 prayers, crashes and waves",
		description = "Prayer accuracy, crash lines and waves dodged",
		position = 12,
		section = chat
	)
	default boolean chatPrayerLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatFlareLine",
		name = "Flares and damage",
		description = "Flares killed and damage taken",
		position = 13,
		section = chat
	)
	default boolean chatFlareLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatSpecLine",
		name = "Specs and supplies",
		description = "Special attacks, drains and supply cost",
		position = 14,
		section = chat
	)
	default boolean chatSpecLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatDeathRecap",
		name = "Death recap",
		description = "After a death, the last ten ticks tick by tick",
		position = 15,
		section = chat
	)
	default boolean chatDeathRecap()
	{
		return false;
	}

	@ConfigItem(
		keyName = "priceSource",
		name = "Supply prices",
		description = "Price supplies at Grand Exchange or high alchemy value",
		position = 21,
		section = review
	)
	default PriceMode priceSource()
	{
		return PriceMode.GRAND_EXCHANGE;
	}

	@ConfigItem(
		keyName = "modeOverride",
		name = "Mode",
		description = "Detect solo, duo host or duo joiner automatically, or force one",
		position = 22,
		section = review
	)
	default ModeOverride modeOverride()
	{
		return ModeOverride.AUTO;
	}

	@Range(min = 10, max = 500)
	@ConfigItem(
		keyName = "historySize",
		name = "History size",
		description = "How many kills are kept per mode and contract",
		position = 23,
		section = review
	)
	default int historySize()
	{
		return 50;
	}

	@Range(min = 1, max = 200)
	@ConfigItem(
		keyName = "rawLogsKept",
		name = "Raw logs kept",
		description = "How many recent kills keep their raw event log, so their reviews can be recalculated",
		position = 24,
		section = review
	)
	default int rawLogsKept()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "captureMode",
		name = "Capture mode",
		description = "Also record other actors and every animated object, for confirming IDs after a game update",
		position = 101,
		section = development
	)
	default boolean captureMode()
	{
		return false;
	}
}
```

- [ ] **Step 4: Replace the plugin**

`src/main/java/com/yamareviewer/YamaReviewerPlugin.java`:

```java
package com.yamareviewer;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.yamareviewer.adapter.ids.BuiltInIds;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.adapter.persistence.FileStore;
import com.yamareviewer.adapter.persistence.FilepathFileStore;
import com.yamareviewer.adapter.persistence.GsonLogRepository;
import com.yamareviewer.adapter.persistence.GsonReviewRepository;
import com.yamareviewer.adapter.publish.ChatReviewPublisherFactory;
import com.yamareviewer.adapter.publish.CompositeReviewPublisher;
import com.yamareviewer.adapter.publish.PanelReviewPublisher;
import com.yamareviewer.adapter.recording.ActorResolver;
import com.yamareviewer.adapter.recording.EventTranslator;
import com.yamareviewer.adapter.recording.GameEventListener;
import com.yamareviewer.adapter.recording.ItemLookup;
import com.yamareviewer.adapter.recording.ItemManagerLookup;
import com.yamareviewer.adapter.recording.PositionReader;
import com.yamareviewer.adapter.recording.SnapshotReader;
import com.yamareviewer.adapter.recording.TickSampler;
import com.yamareviewer.adapter.ui.KillReviewOpener;
import com.yamareviewer.adapter.ui.PanelIcon;
import com.yamareviewer.adapter.ui.ReviewPanel;
import com.yamareviewer.adapter.ui.SystemClipboard;
import com.yamareviewer.application.command.KillSession;
import com.yamareviewer.application.handler.HistoryLoader;
import com.yamareviewer.application.handler.KillEndedHandler;
import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.projection.Projections;
import com.yamareviewer.domain.projection.ReviewBuilder;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.text.ChatLineOptions;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.NpcUtil;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

/** Composition root: builds the object graph, registers the listener and attaches the panel. No game logic lives here. */
@Slf4j
@PluginDescriptor(
	name = "Yama Reviewer",
	description = "Records Yama kills and reviews them after the kill ends. Silent during the fight.",
	tags = {"yama", "pvm", "review", "prayer", "duo"},
	internalName = "yama-reviewer"
)
public class YamaReviewerPlugin extends Plugin
{
	static final String VERSION = "0.2.0";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private YamaReviewerConfig config;

	@Inject
	private Gson gson;

	@Inject
	private ItemManager itemManager;

	@Inject
	private NpcUtil npcUtil;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ChatReviewPublisherFactory chatPublishers;

	private final AtomicBoolean active = new AtomicBoolean();
	private ExecutorService executor;
	private KillSession session;
	private GameEventListener listener;
	private NavigationButton navigationButton;

	@Provides
	YamaReviewerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(YamaReviewerConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		active.set(true);
		executor = Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "yama-reviewer");
			thread.setDaemon(true);
			return thread;
		});
		IdRegistry ids = BuiltInIds.registry();
		FileStore files = new FilepathFileStore(getPluginDirectory());
		LogRepository logs = new GsonLogRepository(files, new EventCodec(gson));
		ReviewRepository reviews = new GsonReviewRepository(files, gson);
		ReviewBuilder builder = new ReviewBuilder(Projections.standard(), ids, Rules.DEFAULT);

		ReviewPanel panel = new ReviewPanel(SystemClipboard::copy);
		KillReviewOpener opener = new KillReviewOpener(executor, reviews, panel);
		panel.onKillSelected(opener::open);
		ReviewPublisher publisher = new CompositeReviewPublisher(List.of(
			chatPublishers.create(this::chatLineOptions),
			new PanelReviewPublisher(panel)));
		KillEndedHandler handler = new KillEndedHandler(executor, logs, reviews, builder, publisher, active::get,
			config::rawLogsKept, config::historySize, this::reviewSettings);

		ItemLookup items = new ItemManagerLookup(itemManager);
		session = new KillSession(handler, new SnapshotReader(client, items), Clock.systemUTC(),
			() -> UUID.randomUUID().toString(), VERSION, ids.fingerprint(), config::captureMode);
		PositionReader positions = new PositionReader(client);
		ActorResolver actors = new ActorResolver(client, ids, session::partnerName);
		EventTranslator translator = new EventTranslator(ids, actors, session::currentTick, config::captureMode, items::name, positions);
		listener = new GameEventListener(client, ids, session, translator, new TickSampler(client, actors, positions),
			positions, npcUtil, handler::pending);

		navigationButton = NavigationButton.builder()
			.tooltip("Yama Reviewer")
			.icon(PanelIcon.create())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		eventBus.register(listener);
		new HistoryLoader(executor, reviews, publisher, config::historySize).load();
		log.debug("Yama Reviewer started");
	}

	@Override
	protected void shutDown()
	{
		active.set(false);
		clientToolbar.removeNavigation(navigationButton);
		eventBus.unregister(listener);
		KillSession ending = session;
		ExecutorService stopping = executor;
		clientThread.invoke(() ->
		{
			ending.leave();
			stopping.shutdown();
		});
		log.debug("Yama Reviewer stopped");
	}

	private ChatLineOptions chatLineOptions()
	{
		return new ChatLineOptions(config.chatPhaseLine(), config.chatPrayerLine(), config.chatFlareLine(), config.chatSpecLine(),
			config.chatDeathRecap());
	}

	private ReviewSettings reviewSettings()
	{
		return new ReviewSettings(config.priceSource(), config.modeOverride().mode());
	}
}
```

`ChatReviewPublisherFactory` is created by Guice through its `@Inject` constructor (a just-in-time binding), so the plugin never references `ChatMessageManager`. `clientToolbar.addNavigation` and `removeNavigation` hop to the Swing thread themselves. `active` is cleared before anything else in `shutDown`, so a review finishing on the executor afterwards is stored but not published.

- [ ] **Step 5: Run the whole test suite, including the architecture rules**

Run: `./gradlew test`
Expected: PASS (every test of Parts 1 and 2, including `ArchitectureTest`'s 9 rules). If `onlyPublishTalksToChat` fails, a class outside `adapter.publish` references `ChatMessageManager`; if `onlyUiAndRootUseSwing` fails, a class outside `adapter.ui` and the root package imports Swing or `net.runelite.client.ui`; fix the dependency, never the rule.

- [ ] **Step 6: Check in game (the user does this; never automate game input)**

Run `./gradlew run`, log in following https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts, and enable **Yama Reviewer**.

Check:
1. The sidebar shows the orange "Y" button; the panel has the tabs Last kill, Solo, Duo host, Duo joiner and says "No kill reviewed yet".
2. Do a solo kill. During the fight nothing appears in chat or in the panel (open it and watch: it must not change).
3. When Yama dies, four chat lines appear (phase times, `P3 prayers n/a…`, flares and damage, `Specs: n/a. Supplies …`) and the Last kill tab shows the review with a green "Complete" badge (or orange "Incomplete" with the hidden sections named).
4. Copy puts the review text on the clipboard; paste it somewhere: no player names.
5. The Solo tab lists the kill; selecting it shows its review below the list; the contract filter switches to an empty list for a contract.
6. `~/.runelite/plugin-data/yama-reviewer/reviews/SOLO/` holds one `.json` file; `./gradlew replay --args="<the raw log>"` prints the same review.
7. Die once: the chat shows the phase line with "died in …", and with **Death recap** enabled in the config the recap block follows; the panel shows the recap on top.
8. Disable the plugin mid-fight: nothing is printed; a raw log ending in `LEFT` is written.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yamareviewer/ModeOverride.java src/main/java/com/yamareviewer/YamaReviewerConfig.java src/main/java/com/yamareviewer/YamaReviewerPlugin.java src/test/java/com/yamareviewer/SilenceReplayTest.java
git commit -m "feat: wire the review pipeline, the side panel and the chat summary into the plugin"
```

---
## Self-Review

**Spec coverage (spec 13, part 2):**

- 4.3–4.5 flow, threading, lifecycle: `KillEndedHandler` does save → build → save review → history → publish on the executor, checks `active` (Task 15); `startUp` only builds objects and submits `HistoryLoader.load()`; `shutDown` clears `active` first (Task 17); publishers hop to Swing inside `ReviewPanel` (Task 16) and use the thread-safe `ChatMessageManager.queue` (Task 15). Failure isolation per projection: `ReviewBuilder` (Task 5).
- 4.4 silence: `SilenceReplayTest` drives `GameEventListener` and asserts zero publishes before the end and exactly one after (Task 17); the ArchUnit rules stay unchanged and `ChatReviewPublisherFactory` keeps `ChatMessageManager` inside `adapter.publish` (Task 15).
- 5.3 scene reloads: Judge and flare despawn/respawn pairs inside `LOADING` are ignored (Tasks 6, 8).
- 5.4 mode rules including override, the no-entry-choice east/west fallback and "seen before the first Judge ends" (Task 7).
- 6.1 ordered projections, `Section` reasons, review status rule, prices from snapshots (Tasks 1, 3, 5, 12).
- 6.2 phases: all four signals, earliest wins, Judge end, early end, P3 at `FightEnded` (Task 6).
- 6.3 contracts: detection order (widget text, item within the first ticks, 7-tick P1 cycle → `UNKNOWN_CONTRACT`, else `NONE`), `ContractRules` with every 6.3.1 value, history key per mode and contract (Tasks 2, 7, 13).
- 6.8 flares: waves within 3 ticks, summoned/melee cause, exploded via animation, heal graphic or heal hitsplat, killed via death animation or dying despawn, purging staff kills (only when the role is captured), Yama's healing, per-wave ticks (Task 8).
- 6.10 rules 2 and 5–10 with their windows, per phase/player/source totals and shared mechanics; rules 1, 3, 4 have marked insertion points for Part 3 (Task 9).
- 6.11 supplies: decreases only, dose grouping and pricing, price source, `LEFT` without `END` → `NOT_APPLICABLE`, Familiar "no effect" (Task 10).
- 6.12 death recap: 10 ticks including the death tick, sources, HP/prayer, run energy under Forfeit Breath, target, consumed items (Task 11).
- 7.1 publishing only while active, 7.2 chat lines with `n/a`, the contract in line 1, the recap block (Tasks 14, 15), 7.3 panel tabs, contract filter, status badge, recap on top, hidden reasons, history statistics with bests and trend arrows, selecting a kill, Copy (Tasks 14, 16), 7.4 clipboard text without names (Task 14), 7.5 review storage with `reviewSchemaVersion`, pruning per mode and contract, history built at start-up and after each kill (Tasks 12, 13).
- 10 config keys and defaults (Task 17). 11 error handling: corrupt review files renamed, write failures still publish, exceptions caught per projection, `LEFT` on disable (Tasks 5, 12, 15, 17). 12 test DSL, silence test and the `replay` tool (Tasks 4, 14, 17).
- Not in Part 2 by design: spec 7.5's rebuild of reviews of another schema version from their raw logs (the `HistoryLoader` signature has no `LogRepository`; with `SCHEMA_VERSION = 1` no such file can exist yet), spec 8 health checks and spec 9 reports (Part 4), and the sections of 6.4–6.7 and 6.9 (Part 3).

**Placeholder scan:** no "TBD", "TODO", "similar to Task", or "add error handling" anywhere; every step that touches code shows the whole file, and the Task 15 stopgap shows the exact line it replaces.

**Type consistency:** `Section.ok/hidden/isOk/value/asOptional/hiddenReason/map`, `SectionKey.of`, `Sections.*`, `ProjectionContext.ids/rules/settings/contractRules/section/value/put`, `ReviewBuilder.run(KillLog, ReviewSettings)`, `KillReviewAssembler.assemble`, `Projections.standard()`, `KillReview.builder()…allSections()/withRecomputedStatus()`, `ReviewRepository.save/loadAll/prune`, `ReviewPublisher.publish/showHistory`, `HistoryProjector.summarize/index`, `HistoryIndex.view`, `ReviewFormatter.format/headline/section`, `ChatLines.lines`, `ClipboardExport.text`, `HistoryFormatter.summaryLines/killLine`, `Formats.*`, `ReviewPanel.show/showHistory/showKill/onKillSelected/footer`, `KillReviewOpener.open`, `KillEndedHandler(9 args).killEnded/pending/handle`, `HistoryLoader.load`, `TestIds.registry/registryWithout/id/text`, `KillLogBuilder.*` and `Reviews.builder/sample` are used with the same names and signatures in every task that consumes them (checked task by task against the **Interfaces** blocks).

**Review Focus:** each of the five lines names its test, and each test is in the task listed: `aPlayerSeenAfterTheFirstJudgeEndedIsNotAPartner` (Task 7), `aSceneReloadInsideAJudgePhaseIsIgnored` (Task 6) and `aSceneReloadDoesNotRespawnAFlare` (Task 8), `endOnlyPotionVariantsCountAsRemainingDoses` (Task 10), `otherSchemaVersionsAreSkippedButKept` and `corruptFileIsRenamedAndSkipped` (Task 12) with `aLeftKillWithoutAnEndSnapshotHidesSupplies` (Task 10), `nothingIsPublishedWhenThePluginIsInactive` (Task 15) and `hiddenValuesPrintAsNotAvailable` (Task 14).
