# Yama Reviewer Part 4: Health Checks and Reports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After every kill, eight health checks decide whether the review's numbers can be trusted; a failed check hides the sections it protects, writes a report with candidate IDs, and the panel's "Report a problem" footer turns the latest report into a prefilled GitHub issue.

**Architecture:** `domain.health` holds the `HealthCheck` contract, `CheckResult`, the runner and the eight checks of spec section 8 with their shared evidence helpers; `domain.diagnosis` holds `Candidate`, `CandidateFinder` and the `ProblemReport` value; `domain.text` holds `ReportWriter` and `IssueLink`. `KillEndedHandler` runs the checks between `ReviewBuilder.run` and `KillReviewAssembler.assemble`, so a failed check hides sections in the `ProjectionContext` before the review exists, and hands failures to `ProblemReporter`, which writes `reports/<timestamp>.txt` through the `ReportRepository` port. `GameEventListener` reports an unknown NPC named "Yama" through a callback; `ReportProblemFooter` (adapter.ui) copies the latest report and opens the issue URL with `LinkBrowser`. Self-healing is report-only: nothing is repaired automatically.

**Tech Stack:** Java 11 (release target), RuneLite client API (`latest.release`), Lombok, JUnit 4.12, Mockito 5, ArchUnit 1.3, the Part 2 `KillLogBuilder`/`TestIds` test DSL, the Part 2 and Part 3 domain types.

**Spec:** `docs/superpowers/specs/2026-09-24-yama-reviewer-design.md`, sections 2 (rules 6 and 7), 4.5, 5.3 (recording self-check), 6.1 (review status), 6.3.1 (per-contract review rules), 8, 9, 11, 12 and 13 (part 4).

## Assumed fields of Parts 2 and 3 (reconcile before Task 2)

The names below are used by this plan but are not fixed by the interface brief. Before Task 2, open the real Part 2 and Part 3 classes; where a name differs, change the Part 4 code to the real name. Never change a Part 2 or Part 3 field to match this plan.

1. `com.yamareviewer.domain.projection.Sections.ATTACKS` is a `SectionKey<AttackTimeline>` and `Sections.CRASHES` a `SectionKey<CrashSummary>`; `Sections.PRAYER_REVIEW`, `Sections.OPENER`, `Sections.TICK_LOG` and `Sections.SPECS` exist (their value types don't matter here: they are only hidden).
2. `com.yamareviewer.domain.review.Attack` is a Lombok `@Value` whose getters are `getCastTick()` (int), `getPhase()` (`Phase`), `getStyle()` (`Style`, null when neither cast nor impact graphic was seen), `getTarget()` (`Actor`), `getLandingTick()` (`Integer`, null when nothing landed) and `getDamage()` (`Integer`, null when no hitsplat landed); an attack with an unknown style is still listed. `AttackTimeline.getAttacks()` returns the attacks in cast order.
3. `com.yamareviewer.domain.review.CrashSummary.getLines()` returns `List<CrashLine>`; `CrashLine.getTick()` is the tick the line's fireballs appeared and `CrashLine.getPlayer()` the `Actor` (SELF or PARTNER) nearest its centre fireball.
4. `KillLogBuilder.end(reason)` adds no END supplies snapshot; the fixture of Task 2 adds `supplies(SnapshotKind.END)` itself.
5. `KillReviewAssembler.assemble` returns a review whose status is already computed; Part 4 calls `withRecomputedStatus()` again after setting `failedChecks`, which is harmless.
6. `ReviewFormatter.format` collects its `ViewSection`s in a local mutable list before constructing the `ReviewView` (Task 10 says what to do when it doesn't).
7. `ReviewPanel` reserved a footer container (the brief: "footer reserved for Part 4's Report a problem"); Task 12 calls it `footerSlot` and says how to add one when Part 2 left no container. `ReviewPanel.show(ReviewView, String, HistoryIndex)` is where each new review arrives.
8. The Part 2 `YamaReviewerPlugin.startUp` has locals `files` (the `FilepathFileStore`), `ids`, `executor`, `panel` (the `ReviewPanel`) and the `KillEndedHandler` and `GameEventListener` constructor calls of the brief; Task 12 shows what to append to them.
9. The Part 2 `Replay` tool (test sources) calls `builder.run(...)` and then `KillReviewAssembler.assemble(...)`.
10. Part 2's `KillEndedHandlerTest` and the silence replay test construct `KillEndedHandler` and `GameEventListener` with the brief's constructors; Tasks 10 and 11 append arguments to those calls.

Everything else this plan uses from Parts 2 and 3 is binding in the brief: `Section<T>`, `HiddenReason`, `ProjectionContext`, `ReviewBuilder`, `Projections.standard()`, `KillReviewAssembler`, `KillReview` (`toBuilder()`, `withRecomputedStatus()`), `PhaseTimes`/`PhaseSpan`, `DamageSummary`, `ContractRules`, `Contract`, `Mode`, `Phase`, `Style`, `ReviewSettings`, `ReviewRepository`, `ReviewPublisher`, `HistoryProjector`, `ReviewFormatter`/`ReviewView`/`ViewSection`, `KillLogBuilder`, `TestIds`.

## Global Constraints

- Build matches the RuneLite example-plugin template; `options.release.set(11)`; RuneLite code style: tabs, braces on their own line.
- Root package `com.yamareviewer`; `@PluginDescriptor(internalName = "yama-reviewer")`; config group `yamareviewer`.
- Nothing is drawn, printed or played during a fight; reports are written after the kill on the plugin executor, never on the client thread.
- The plugin makes no network calls. "Report a problem" opens the user's browser through `LinkBrowser`; nothing is sent automatically (spec 2, rule 6).
- Reports and the issue text never contain player names: actors appear as `SELF`, `PARTNER` and `OTHER` (spec 2, rule 7).
- Self-healing is report-only in v1: no ID is changed, no override is written (spec 9).
- All file I/O goes through the `FileStore` port backed by RuneLite's `Filepath`; never `java.io.File` or `java.nio.file.Files` in main code.
- No reflection in main code; `log.debug` for per-event logging, `log.info` only for the one-time "health checks failed" line.
- `domain` imports nothing from `application`, `adapter`, `net.runelite`, `com.google`, Swing or AWT. `application` imports nothing from `adapter`, `net.runelite`, `com.google`, Swing or AWT. Only `adapter.ui` touches the clipboard (`java.awt.datatransfer`) and `LinkBrowser`.
- Checks are skipped for sections already hidden as `IDS_NOT_CAPTURED`, `NOT_APPLICABLE` or `CONTRACT`; a failed check hides its sections with `HEALTH_CHECK_FAILED` before `KillReviewAssembler.assemble` runs and makes the review `INCOMPLETE` (spec 8, 6.1).
- Contract kills use `ContractRules`: `BlockedDamage` and `GraphicVsBehaviour` are skipped when `blockedMaxHit` is none, `Alternation` under random attack styles (Shard Acquisition), `CrashSets` reports the contract's expected P3 set count (spec 8, 6.3.1).
- The report holds plugin, RuneLite and game versions, failed checks with their numbers, candidates with counts and tick offsets, and at most 200 event lines around the first failure; the issue body holds the summary only, under 6,000 characters (spec 9).
- Exceptions inside checks, the candidate finder and report writing are caught per unit and logged; a failed report write never stops the review from being published (spec 4.5, 11).
- ArchUnit rules of Part 1 keep passing; Task 12 adds one more.

## Review Focus

1. A healthy kill with several genuine prayer mistakes (wrong prayer, big hits) must not fail `GraphicVsBehaviour`: a big hit while praying the other style is evidence that agrees with the graphic, not a contradiction (`GraphicVsBehaviourCheckTest.realPrayerMistakesAreNotContradictions`, Task 5).
2. A duo kill where Yama alternates globally but each player's own sequence repeats styles must pass `Alternation`; the check fails only when neither the global nor the per-target view alternates (`AlternationCheckTest.globalAlternationIsEnoughInDuo`, Task 4).
3. Dying or leaving before P3 is a normal outcome, not a broken recording: `PhaseOrder` passes on a prefix, the P3 checks report "no evidence" instead of failing, and no report is written (`PhaseOrderCheckTest.deathBeforeP3IsNotAFailure` and `P3AttackCountCheckTest.noP3MeansNoEvidence`, Tasks 3 and 4; `KillEndedHandlerChecksTest.anEarlyDeathWritesNoReport`, Task 10).
4. A dragon claws spec (any weapon without a role) drops spec energy with no `SPEC_*` animation and must not fail `SpecsMatched`; a P3 fought in melee range has no crash lines at all and must not fail `CrashSets` (`SpecsMatchedCheckTest.otherWeaponSpecsAreNotChecked`, `CrashSetsCheckTest.noCrashLinesIsNoEvidence`, Task 6).
5. Yama sitting on his throne (`NpcID.YAMA_THRONE_OCCUPIED`, also named "Yama") spawns every time the player is armed and must not trigger the recording self-check, while a renumbered combat Yama must (`GameEventListenerTest.theSittingYamaIsNotReported` and `anUnknownNpcNamedYamaWhileArmedIsReported`, Task 11).

---

## File Structure

```
src/main/java/com/yamareviewer/
  ProjectLinks.java                          the repository URL (root package)
  YamaReviewerPlugin.java                    MODIFIED: wires reports, checks, the self-check and the footer
  domain/health/
    HealthCheck.java, CheckResult.java, CheckOutcome.java, HealthCheckRunner.java, HealthChecks.java
    TickStateIndex.java, PrayerCheckTick.java, PrayedAttack.java, PrayedAttacks.java, SpecDrop.java, SpecEnergyDrops.java
    PhaseOrderCheck.java, P3AttackCountCheck.java, AlternationCheck.java, BlockedDamageCheck.java,
    GraphicVsBehaviourCheck.java, CrashSetsCheck.java, SpecsMatchedCheck.java, SuppliesSnapshotCheck.java
  domain/diagnosis/
    Candidate.java, CandidateFinder.java, Versions.java, CheckCandidates.java, ProblemReport.java
  domain/text/
    ReportWriter.java, EventLine.java, IssueLink.java
    ReviewFormatter.java                     MODIFIED: shows the failed checks first
  domain/review/KillReview.java              MODIFIED: failedChecks
  domain/ids/Role.java                       MODIFIED: YAMA_SITTING
  adapter/ids/BuiltInIds.java                MODIFIED: YAMA_SITTING = NpcID.YAMA_THRONE_OCCUPIED
  application/port/ReportRepository.java
  application/handler/ProblemReporter.java
  application/handler/KillEndedHandler.java  REPLACED: checks between run and assemble, report on failure
  adapter/persistence/FileReportRepository.java
  adapter/recording/GameEventListener.java   MODIFIED: unknown-Yama callback
  adapter/ui/ReportProblemFooter.java
  adapter/ui/ReviewPanel.java                MODIFIED: footer slot, refresh on show and activate
src/test/java/com/yamareviewer/
  testing/Fights.java                        synthetic fights that drive the real projections
  testing/FightsTest.java
  domain/health/*Test.java, domain/diagnosis/CandidateFinderTest.java, domain/text/{ReportWriterTest,EventLineTest,IssueLinkTest,ReviewFormatterFailedChecksTest}.java
  domain/review/KillReviewFailedChecksTest.java
  application/handler/{ProblemReporterTest,KillEndedHandlerChecksTest}.java
  adapter/persistence/FileReportRepositoryTest.java
  adapter/recording/GameEventListenerTest.java   MODIFIED
  adapter/ui/ReportProblemFooterTest.java
  ArchitectureTest.java                      MODIFIED: one more rule
  tools/Replay.java                          MODIFIED: prints the check outcomes
```

---

### Task 1: Health check contract and runner

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/health/HealthCheck.java`, `CheckResult.java`, `CheckOutcome.java`, `HealthCheckRunner.java`
- Test: `src/test/java/com/yamareviewer/domain/health/CheckResultTest.java`, `HealthCheckRunnerTest.java`

**Interfaces:**
- Consumes: `ProjectionContext`, `Section`, `SectionKey`, `Sections`, `HiddenReason`, `ContractRules` (Part 2); `Role`, `KillLog` (Part 1).
- Produces: `HealthCheck { String name(); Set<Role> reportRoles(); List<SectionKey<?>> hides(); boolean applies(ContractRules rules); CheckResult check(KillLog log, ProjectionContext context); }`; `CheckResult.pass()`, `CheckResult.fail(String detail)`, `CheckResult.fail(String detail, Integer tick)`, `CheckResult.skipped(String reason)` with `passed()`, `skipped()`, `detail()`, `tick()` (`OptionalInt`); `CheckOutcome(HealthCheck check, CheckResult result)` with `name()`, `failed()`, `skipped()`, `getCheck()`, `getResult()`, static `failedNames(List<CheckOutcome>)` and `anyFailed(List<CheckOutcome>)`; `HealthCheckRunner.apply(KillLog, ProjectionContext, List<HealthCheck>)` returning `List<CheckOutcome>` after hiding the failed checks' sections in the context.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/health/CheckResultTest.java`:

```java
package com.yamareviewer.domain.health;

import java.util.OptionalInt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CheckResultTest
{
	@Test
	public void passHasNoDetailAndNoTick()
	{
		CheckResult pass = CheckResult.pass();

		assertTrue(pass.passed());
		assertFalse(pass.skipped());
		assertEquals("", pass.detail());
		assertEquals(OptionalInt.empty(), pass.tick());
	}

	@Test
	public void failCarriesTheNumbersAndTheFirstFailureTick()
	{
		CheckResult fail = CheckResult.fail("3 of 17 attacks", 240);

		assertFalse(fail.passed());
		assertFalse(fail.skipped());
		assertEquals("3 of 17 attacks", fail.detail());
		assertEquals(OptionalInt.of(240), fail.tick());
		assertEquals(OptionalInt.empty(), CheckResult.fail("no tick").tick());
	}

	@Test
	public void skippedCountsAsPassed()
	{
		CheckResult skipped = CheckResult.skipped("not applicable under Contract of Shard Acquisition");

		assertTrue(skipped.passed());
		assertTrue(skipped.skipped());
		assertEquals("not applicable under Contract of Shard Acquisition", skipped.detail());
	}

	@Test
	public void resultsAreValues()
	{
		assertEquals(CheckResult.fail("x", 1), CheckResult.fail("x", 1));
		assertNotEquals(CheckResult.fail("x", 1), CheckResult.fail("x", 2));
		assertEquals(CheckResult.pass(), CheckResult.pass());
		assertNotEquals(CheckResult.pass(), CheckResult.skipped("x"));
	}
}
```

`src/test/java/com/yamareviewer/domain/health/HealthCheckRunnerTest.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HealthCheckRunnerTest
{
	private final KillLog log = KillLogBuilder.kill().ticks(3).end(EndReason.YAMA_DIED);
	private final ProjectionContext context = new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT);

	private static Section<PhaseTimes> phases()
	{
		return Section.ok(new PhaseTimes(List.of(), 0));
	}

	private static Section<DamageSummary> damage()
	{
		return Section.ok(new DamageSummary(List.of()));
	}

	@Test
	public void aFailedCheckHidesItsSectionsWithHealthCheckFailed()
	{
		context.put(Sections.PHASES, phases());
		context.put(Sections.DAMAGE, damage());
		FakeCheck check = new FakeCheck("Fake", List.of(Sections.PHASES, Sections.DAMAGE), true, CheckResult.fail("2 of 3", 7));

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, List.of(check));

		assertEquals(Optional.of(HiddenReason.HEALTH_CHECK_FAILED), context.section(Sections.PHASES).hiddenReason());
		assertEquals(Optional.of(HiddenReason.HEALTH_CHECK_FAILED), context.section(Sections.DAMAGE).hiddenReason());
		assertEquals(1, outcomes.size());
		assertTrue(outcomes.get(0).failed());
		assertEquals("Fake", outcomes.get(0).name());
		assertEquals(CheckResult.fail("2 of 3", 7), outcomes.get(0).getResult());
	}

	@Test
	public void passingChecksChangeNothing()
	{
		context.put(Sections.PHASES, phases());
		FakeCheck check = new FakeCheck("Fake", List.of(Sections.PHASES), true, CheckResult.pass());

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, List.of(check));

		assertTrue(context.section(Sections.PHASES).isOk());
		assertFalse(outcomes.get(0).failed());
		assertEquals(1, check.calls);
	}

	@Test
	public void sectionsHiddenForOtherReasonsAreLeftAlone()
	{
		context.put(Sections.PHASES, Section.hidden(HiddenReason.ERROR));
		context.put(Sections.DAMAGE, damage());
		FakeCheck check = new FakeCheck("Fake", List.of(Sections.PHASES, Sections.DAMAGE), true, CheckResult.fail("bad"));

		HealthCheckRunner.apply(log, context, List.of(check));

		assertEquals(Optional.of(HiddenReason.ERROR), context.section(Sections.PHASES).hiddenReason());
		assertEquals(Optional.of(HiddenReason.HEALTH_CHECK_FAILED), context.section(Sections.DAMAGE).hiddenReason());
	}

	@Test
	public void aCheckIsSkippedWhenEverySectionItProtectsIsHiddenForIdsApplicabilityOrContract()
	{
		context.put(Sections.PHASES, Section.hidden(HiddenReason.IDS_NOT_CAPTURED));
		context.put(Sections.DAMAGE, Section.hidden(HiddenReason.CONTRACT));
		FakeCheck check = new FakeCheck("Fake", List.of(Sections.PHASES, Sections.DAMAGE, Sections.FLARES), true, CheckResult.fail("bad"));

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, List.of(check));

		assertEquals(0, check.calls);
		assertTrue(outcomes.get(0).skipped());
		assertFalse(outcomes.get(0).failed());
		assertTrue(outcomes.get(0).getResult().detail(), outcomes.get(0).getResult().detail().contains("already hidden"));
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), context.section(Sections.FLARES).hiddenReason());
	}

	@Test
	public void aCheckStillRunsWhenAnEarlierCheckHidItsSections()
	{
		context.put(Sections.PHASES, Section.hidden(HiddenReason.HEALTH_CHECK_FAILED));
		FakeCheck check = new FakeCheck("Fake", List.of(Sections.PHASES), true, CheckResult.fail("bad"));

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, List.of(check));

		assertEquals(1, check.calls);
		assertTrue(outcomes.get(0).failed());
	}

	@Test
	public void aCheckThatDoesNotApplyUnderTheContractIsSkipped()
	{
		context.put(Sections.PHASES, phases());
		FakeCheck check = new FakeCheck("Fake", List.of(Sections.PHASES), false, CheckResult.fail("bad"));

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, List.of(check));

		assertEquals(0, check.calls);
		assertTrue(outcomes.get(0).skipped());
		assertTrue(outcomes.get(0).getResult().detail(), outcomes.get(0).getResult().detail().contains("not applicable under No contract"));
		assertTrue(context.section(Sections.PHASES).isOk());
	}

	@Test
	public void anExceptionIsSkippedAndTheOtherChecksStillRun()
	{
		context.put(Sections.PHASES, phases());
		FakeCheck broken = new FakeCheck("Broken", List.of(Sections.PHASES), true, null);
		FakeCheck fine = new FakeCheck("Fine", List.of(Sections.PHASES), true, CheckResult.pass());

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, List.of(broken, fine));

		assertEquals(2, outcomes.size());
		assertTrue(outcomes.get(0).skipped());
		assertTrue(outcomes.get(0).getResult().detail(), outcomes.get(0).getResult().detail().contains("error"));
		assertEquals(1, fine.calls);
		assertTrue(context.section(Sections.PHASES).isOk());
	}

	@Test
	public void failedNamesKeepTheCheckOrder()
	{
		List<CheckOutcome> outcomes = List.of(
			new CheckOutcome(new FakeCheck("A", List.of(), true, CheckResult.fail("a")), CheckResult.fail("a")),
			new CheckOutcome(new FakeCheck("B", List.of(), true, CheckResult.pass()), CheckResult.pass()),
			new CheckOutcome(new FakeCheck("C", List.of(), true, CheckResult.fail("c")), CheckResult.fail("c")));

		assertEquals(List.of("A", "C"), CheckOutcome.failedNames(outcomes));
		assertTrue(CheckOutcome.anyFailed(outcomes));
		assertFalse(CheckOutcome.anyFailed(outcomes.subList(1, 2)));
	}

	private static final class FakeCheck implements HealthCheck
	{
		private final String name;
		private final List<SectionKey<?>> hides;
		private final boolean applies;
		/** Null makes check() throw. */
		private final CheckResult result;
		private int calls;

		private FakeCheck(String name, List<SectionKey<?>> hides, boolean applies, CheckResult result)
		{
			this.name = name;
			this.hides = hides;
			this.applies = applies;
			this.result = result;
		}

		@Override
		public String name()
		{
			return name;
		}

		@Override
		public Set<Role> reportRoles()
		{
			return Set.of(Role.JUDGE);
		}

		@Override
		public List<SectionKey<?>> hides()
		{
			return hides;
		}

		@Override
		public boolean applies(ContractRules rules)
		{
			return applies;
		}

		@Override
		public CheckResult check(KillLog log, ProjectionContext context)
		{
			calls++;
			if (result == null)
			{
				throw new IllegalStateException("boom");
			}
			return result;
		}
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.health.*'`
Expected: FAIL — `cannot find symbol` for `CheckResult`, `HealthCheck`, `CheckOutcome`, `HealthCheckRunner`.

- [ ] **Step 3: Write the contract**

`src/main/java/com/yamareviewer/domain/health/HealthCheck.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import java.util.List;
import java.util.Set;

/**
 * One check of spec section 8. A check needs a minimum of evidence before it can fail; without it, it
 * returns a skipped result. It reads the log and the context and never changes either.
 */
public interface HealthCheck
{
	/** The name of the spec 8 table, for example "P3AttackCount". */
	String name();

	/** The roles a report should look at when this check fails. */
	Set<Role> reportRoles();

	/** The sections hidden with HEALTH_CHECK_FAILED when this check fails. */
	List<SectionKey<?>> hides();

	/** False when the contract makes the check meaningless (spec 8); the runner then skips it. */
	boolean applies(ContractRules rules);

	CheckResult check(KillLog log, ProjectionContext context);
}
```

`src/main/java/com/yamareviewer/domain/health/CheckResult.java`:

```java
package com.yamareviewer.domain.health;

import java.util.Objects;
import java.util.OptionalInt;

/** Passed, failed with its numbers, or skipped for lack of evidence or applicability (skipped counts as passed). */
public final class CheckResult
{
	private final boolean passed;
	private final boolean skipped;
	private final String detail;
	private final Integer tick;

	private CheckResult(boolean passed, boolean skipped, String detail, Integer tick)
	{
		this.passed = passed;
		this.skipped = skipped;
		this.detail = detail;
		this.tick = tick;
	}

	public static CheckResult pass()
	{
		return new CheckResult(true, false, "", null);
	}

	public static CheckResult fail(String detail)
	{
		return fail(detail, null);
	}

	/** @param tick the tick of the first evidence of the failure, which centres the report's event excerpt; may be null */
	public static CheckResult fail(String detail, Integer tick)
	{
		return new CheckResult(false, false, Objects.requireNonNull(detail), tick);
	}

	public static CheckResult skipped(String reason)
	{
		return new CheckResult(true, true, Objects.requireNonNull(reason), null);
	}

	public boolean passed()
	{
		return passed;
	}

	public boolean skipped()
	{
		return skipped;
	}

	/** The numbers behind a failure, the reason for a skip, empty for a pass. */
	public String detail()
	{
		return detail;
	}

	public OptionalInt tick()
	{
		return tick == null ? OptionalInt.empty() : OptionalInt.of(tick);
	}

	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof CheckResult))
		{
			return false;
		}
		CheckResult that = (CheckResult) other;
		return passed == that.passed && skipped == that.skipped && detail.equals(that.detail) && Objects.equals(tick, that.tick);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(passed, skipped, detail, tick);
	}

	@Override
	public String toString()
	{
		if (skipped)
		{
			return "skipped: " + detail;
		}
		return passed ? "passed" : "failed: " + detail + (tick == null ? "" : " (tick " + tick + ")");
	}
}
```

`src/main/java/com/yamareviewer/domain/health/CheckOutcome.java`:

```java
package com.yamareviewer.domain.health;

import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;

/** What one check said about one kill. */
@Value
public class CheckOutcome
{
	HealthCheck check;
	CheckResult result;

	public String name()
	{
		return check.name();
	}

	public boolean failed()
	{
		return !result.passed();
	}

	public boolean skipped()
	{
		return result.skipped();
	}

	/** Names of the failed checks, in check order. */
	public static List<String> failedNames(List<CheckOutcome> outcomes)
	{
		return outcomes.stream().filter(CheckOutcome::failed).map(CheckOutcome::name).collect(Collectors.toList());
	}

	public static boolean anyFailed(List<CheckOutcome> outcomes)
	{
		return outcomes.stream().anyMatch(CheckOutcome::failed);
	}
}
```

`src/main/java/com/yamareviewer/domain/health/HealthCheckRunner.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the checks after the projections and before the review is assembled. A failed check hides the
 * sections it protects in the context with HEALTH_CHECK_FAILED, so the assembled review never shows them.
 * A check is skipped when the contract says so, when every section it protects is already hidden as
 * IDS_NOT_CAPTURED, NOT_APPLICABLE or CONTRACT (spec 8), or when it throws (spec 4.5).
 */
@Slf4j
public final class HealthCheckRunner
{
	private static final Set<HiddenReason> ALREADY_HIDDEN = EnumSet.of(
		HiddenReason.IDS_NOT_CAPTURED, HiddenReason.NOT_APPLICABLE, HiddenReason.CONTRACT);

	private HealthCheckRunner()
	{
	}

	public static List<CheckOutcome> apply(KillLog log, ProjectionContext context, List<HealthCheck> checks)
	{
		List<CheckOutcome> outcomes = new ArrayList<>();
		for (HealthCheck check : checks)
		{
			CheckResult result = run(check, log, context);
			if (!result.passed())
			{
				for (SectionKey<?> key : check.hides())
				{
					hide(context, key);
				}
			}
			outcomes.add(new CheckOutcome(check, result));
		}
		return List.copyOf(outcomes);
	}

	private static CheckResult run(HealthCheck check, KillLog log, ProjectionContext context)
	{
		try
		{
			if (!check.applies(context.contractRules()))
			{
				Contract contract = context.value(Sections.CONTRACT).orElse(Contract.NONE);
				return CheckResult.skipped("not applicable under " + contract.displayName());
			}
			if (nothingLeftToProtect(check, context))
			{
				return CheckResult.skipped("every section it protects is already hidden");
			}
			return check.check(log, context);
		}
		catch (RuntimeException e)
		{
			log.warn("Health check {} failed with an error and was skipped", check.name(), e);
			return CheckResult.skipped("error: " + e);
		}
	}

	private static boolean nothingLeftToProtect(HealthCheck check, ProjectionContext context)
	{
		if (check.hides().isEmpty())
		{
			return false;
		}
		for (SectionKey<?> key : check.hides())
		{
			Optional<HiddenReason> reason = context.section(key).hiddenReason();
			if (reason.isEmpty() || !ALREADY_HIDDEN.contains(reason.get()))
			{
				return false;
			}
		}
		return true;
	}

	/** Only a shown section is hidden; a section hidden for another reason keeps that reason. */
	private static <T> void hide(ProjectionContext context, SectionKey<T> key)
	{
		Section<T> current = context.section(key);
		if (current.isOk())
		{
			context.put(key, Section.hidden(HiddenReason.HEALTH_CHECK_FAILED));
		}
	}
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.health.*'`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/health src/test/java/com/yamareviewer/domain/health
git commit -m "feat: add the health check contract and the runner that hides failed sections"
```

---

### Task 2: Synthetic fights and the shared evidence helpers

**Files:**
- Create: `src/test/java/com/yamareviewer/testing/Fights.java`
- Create: `src/main/java/com/yamareviewer/domain/health/TickStateIndex.java`, `PrayerCheckTick.java`, `PrayedAttack.java`, `PrayedAttacks.java`, `SpecDrop.java`, `SpecEnergyDrops.java`
- Test: `src/test/java/com/yamareviewer/testing/FightsTest.java`, `src/test/java/com/yamareviewer/domain/health/TickStateIndexTest.java`, `SpecEnergyDropsTest.java`, `PrayedAttacksTest.java`

**Interfaces:**
- Consumes: `KillLogBuilder`, `TestIds` (Part 2 test support); `ReviewBuilder`, `Projections.standard()`, `ReviewSettings`, `Sections` (Part 2/3); `Attack`, `AttackTimeline` (Part 3, assumed fields 1–3); `Rules`, `PrayerCheck`, `TickState`, `Role`, `IdRegistry` (Part 1).
- Produces: `Fights` (solo/duo fixtures with `soloUntilP3()`, `duoUntilP3()`, `untilP3`, `judgePhase`, `attack`, `blockedAttacks`, `crashLine`, `crashSet`, `emberlightSpec`, `finish`, `healthySolo()`, `context`, `castGraphic`, `impactGraphic`, `alternating`, `correct`, `wrong`, constants `CYCLE`, `P3_START`, `SELF_X`, `SELF_Y`, `PARTNER_X`, `PARTNER_Y`); `TickStateIndex.of(KillLog)` with `at(int)` and `prayersAt(int)`; `PrayerCheckTick.of(Attack, Rules)` returning `OptionalInt`; `PrayedAttack(Attack attack, int checkTick, Set<ProtectionPrayer> prayers)` with `damage()`, `correctPrayer()`, `prayedCorrectly()`, `prayedAgainstMagicOrRanged()`, static `correctPrayer(Style)`; `PrayedAttacks.onSelf(List<Attack>, KillLog, Rules, Phase phaseOrNull)`; `SpecDrop(tick, energyBefore, energyAfter, weaponId, weaponRole, matched)` with `unmatchedKnownWeapon()`; `SpecEnergyDrops.find(KillLog, IdRegistry)`, `SpecEnergyDrops.SPEC_ROLES`, `SpecEnergyDrops.WEAPON_ROLES`.

- [ ] **Step 1: Write the fixture**

`src/test/java/com/yamareviewer/testing/Fights.java`:

```java
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
 * Synthetic kills that drive the Part 2 and Part 3 projections the way a recorded fight does (spec 6.2, 6.4,
 * 6.5, 6.6 and 6.9), with TestIds. Every helper leaves the builder's cursor on the tick after what it added.
 * P1 attacks are never 7 ticks apart here, because that would make ContractProjection see a contract (spec 6.3).
 */
public final class Fights
{
	/** The P3 attack cycle in ticks (spec 3). */
	public static final int CYCLE = 7;
	/** The first P3 tick of a kill built with soloUntilP3() or duoUntilP3(). */
	public static final int P3_START = 120;
	public static final int SELF_X = 3200;
	public static final int SELF_Y = 3200;
	public static final int PARTNER_X = 3210;
	public static final int PARTNER_Y = 3210;

	private Fights()
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
		return kill.npcSpawns(Actor.JUDGE, TestIds.id(Role.JUDGE))
			.ticks(20)
			.npcDespawns(Actor.JUDGE, TestIds.id(Role.JUDGE), true);
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
			.yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK))
			.graphicOn(Actor.YAMA, castGraphic(style))
			.ticks(2)
			.graphicOn(target, impactGraphic(style))
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
		int fireball = TestIds.id(Role.CRASH_FIREBALL);
		return kill.groundGraphic(fireball, x - 1, y).groundGraphic(fireball, x, y).groundGraphic(fireball, x + 1, y);
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
			.animates(Actor.SELF, TestIds.id(Role.SPEC_EMBERLIGHT)).myHitOn(Actor.YAMA, 40).spec(energyBefore - 25).endTick()
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
```

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/yamareviewer/testing/FightsTest.java` pins what the health-check tests assume about how Parts 2 and 3 read a synthetic fight. If it fails, adjust `Fights` (never the projections) until it passes; the spec rules it follows are 6.2, 6.4, 6.6 and 6.9.

```java
package com.yamareviewer.testing;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import java.util.List;
import java.util.Optional;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Pins the assumptions the health-check tests make about how Parts 2 and 3 read a synthetic fight. */
public class FightsTest
{
	@Test
	public void aHealthySoloKillProjectsAllPhasesAttacksCrashesAndSpecs()
	{
		ProjectionContext context = Fights.context(Fights.healthySolo());

		PhaseTimes phases = context.value(Sections.PHASES).orElseThrow();
		assertEquals(List.of(Phase.P1, Phase.JUDGE_1, Phase.P2, Phase.JUDGE_2, Phase.P3),
			phases.getSpans().stream().map(PhaseSpan::getPhase).collect(toList()));
		assertEquals(Fights.P3_START, phases.span(Phase.P3).orElseThrow().getStartTick());

		List<Attack> attacks = context.value(Sections.ATTACKS).orElseThrow().getAttacks();
		assertEquals(20, attacks.stream().filter(attack -> attack.getPhase() == Phase.P3).count());
		Attack first = attacks.get(0);
		assertEquals(Fights.P3_START, first.getCastTick());
		assertEquals(Style.MAGIC, first.getStyle());
		assertEquals(Actor.SELF, first.getTarget());
		assertEquals(Integer.valueOf(Fights.P3_START + 2), first.getLandingTick());
		assertEquals(Integer.valueOf(2), first.getDamage());
		assertEquals(Style.RANGED, attacks.get(1).getStyle());

		assertEquals(3, context.value(Sections.CRASHES).orElseThrow().getLines().size());
		assertTrue(context.section(Sections.SPECS).isOk());
		assertTrue(context.section(Sections.PRAYER_REVIEW).isOk());
		assertTrue(context.section(Sections.SUPPLIES).isOk());
		assertEquals(Optional.of(Mode.SOLO), context.value(Sections.MODE));
	}

	@Test
	public void aDuoKillHasAPartnerAndAttacksOnBoth()
	{
		var kill = Fights.duoUntilP3();
		Fights.attack(kill, Style.MAGIC, Actor.SELF, 2, Fights.correct(Style.MAGIC));
		Fights.attack(kill, Style.RANGED, Actor.PARTNER, 2);
		ProjectionContext context = Fights.context(Fights.finish(kill, EndReason.YAMA_DIED));

		assertEquals(Optional.of(Mode.DUO_HOST), context.value(Sections.MODE));
		List<Attack> attacks = context.value(Sections.ATTACKS).orElseThrow().getAttacks();
		assertEquals(Actor.SELF, attacks.get(0).getTarget());
		assertEquals(Actor.PARTNER, attacks.get(1).getTarget());
	}
}
```

`src/test/java/com/yamareviewer/domain/health/TickStateIndexTest.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.testing.KillLogBuilder;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TickStateIndexTest
{
	@Test
	public void findsTheStateOfATickAndTreatsMissingTicksAsNoPrayers()
	{
		KillLog log = KillLogBuilder.kill().prayers(ProtectionPrayer.MAGIC).endTick().prayers().endTick().end(EndReason.LEFT);

		TickStateIndex index = TickStateIndex.of(log);

		assertEquals(Set.of(ProtectionPrayer.MAGIC), index.prayersAt(0));
		assertEquals(Set.of(), index.prayersAt(1));
		assertEquals(Optional.empty(), index.at(7));
		assertEquals(Set.of(), index.prayersAt(7));
	}
}
```

`src/test/java/com/yamareviewer/domain/health/SpecEnergyDropsTest.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SpecEnergyDropsTest
{
	private static final int EMBERLIGHT = TestIds.id(Role.WEAPON_EMBERLIGHT);
	private static final int EMBERLIGHT_SPEC = TestIds.id(Role.SPEC_EMBERLIGHT);

	private static List<SpecDrop> drops(KillLog log)
	{
		return SpecEnergyDrops.find(log, TestIds.registry());
	}

	@Test
	public void aDropWithTheWeaponsAnimationIsMatched()
	{
		KillLog log = KillLogBuilder.kill().weapon(EMBERLIGHT).spec(100).endTick()
			.animates(Actor.SELF, EMBERLIGHT_SPEC).spec(75).endTick()
			.end(EndReason.LEFT);

		List<SpecDrop> drops = drops(log);

		assertEquals(1, drops.size());
		assertEquals(new SpecDrop(1, 100, 75, EMBERLIGHT, Role.WEAPON_EMBERLIGHT, true), drops.get(0));
		assertFalse(drops.get(0).unmatchedKnownWeapon());
	}

	@Test
	public void aDropWithoutAnimationIsUnmatched()
	{
		KillLog log = KillLogBuilder.kill().weapon(EMBERLIGHT).spec(100).endTick().spec(75).endTick().end(EndReason.LEFT);

		List<SpecDrop> drops = drops(log);

		assertEquals(1, drops.size());
		assertTrue(drops.get(0).unmatchedKnownWeapon());
	}

	@Test
	public void theAnimationOneTickEarlierCounts()
	{
		KillLog log = KillLogBuilder.kill().weapon(EMBERLIGHT).spec(100).animates(Actor.SELF, EMBERLIGHT_SPEC).endTick()
			.spec(75).endTick().end(EndReason.LEFT);

		assertTrue(drops(log).get(0).isMatched());
	}

	@Test
	public void unknownWeaponsAndRegenerationAreNoEvidence()
	{
		KillLog log = KillLogBuilder.kill().weapon(4151).spec(100).endTick().spec(50).endTick().spec(60).endTick().end(EndReason.LEFT);

		List<SpecDrop> drops = drops(log);

		assertEquals(1, drops.size());
		assertNull(drops.get(0).getWeaponRole());
		assertEquals(4151, drops.get(0).getWeaponId());
		assertFalse(drops.get(0).unmatchedKnownWeapon());
	}

	@Test
	public void everyWeaponRoleIsASpecWeaponAndEverySpecRoleIsAnAnimation()
	{
		Set<Role> weapons = Arrays.stream(Role.values())
			.filter(role -> role.kind() == RoleKind.ITEM && !Role.CONTRACT_ITEMS.contains(role))
			.collect(Collectors.toSet());
		Set<Role> specs = Arrays.stream(Role.values())
			.filter(role -> role.kind() == RoleKind.ANIMATION && role.name().startsWith("SPEC_"))
			.collect(Collectors.toSet());

		assertEquals(weapons, SpecEnergyDrops.WEAPON_ROLES);
		assertEquals(specs, SpecEnergyDrops.SPEC_ROLES);
	}
}
```

`src/test/java/com/yamareviewer/domain/health/PrayedAttacksTest.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.ids.PrayerCheck;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PrayedAttacksTest
{
	private static List<Attack> attacks(KillLog log, Rules rules)
	{
		return Fights.context(log, rules).value(Sections.ATTACKS).orElseThrow().getAttacks();
	}

	@Test
	public void readsSelfPrayersAtTheCastTickAndLeavesOtherTargetsOut()
	{
		KillLogBuilder kill = Fights.duoUntilP3();
		Fights.attack(kill, Style.MAGIC, Actor.SELF, 2, ProtectionPrayer.MAGIC);
		Fights.attack(kill, Style.RANGED, Actor.SELF, 9, ProtectionPrayer.MELEE);
		Fights.attack(kill, Style.MAGIC, Actor.PARTNER, 9);
		KillLog log = Fights.finish(kill, EndReason.YAMA_DIED);

		List<PrayedAttack> prayed = PrayedAttacks.onSelf(attacks(log, Rules.DEFAULT), log, Rules.DEFAULT, Phase.P3);

		assertEquals(2, prayed.size());
		assertEquals(Fights.P3_START, prayed.get(0).getCheckTick());
		assertTrue(prayed.get(0).prayedCorrectly());
		assertTrue(prayed.get(0).prayedAgainstMagicOrRanged());
		assertEquals(2, prayed.get(0).damage());
		assertEquals(ProtectionPrayer.MISSILES, prayed.get(1).correctPrayer());
		assertFalse(prayed.get(1).prayedCorrectly());
		assertFalse(prayed.get(1).prayedAgainstMagicOrRanged());
		assertEquals(9, prayed.get(1).damage());
	}

	@Test
	public void hitsplatModeUsesTheLandingTickPlusTheOffset()
	{
		Rules rules = Rules.DEFAULT.toBuilder().prayerCheck(PrayerCheck.HITSPLAT).prayerCheckOffset(1).build();
		KillLog log = Fights.finish(Fights.blockedAttacks(Fights.soloUntilP3(), 2), EndReason.YAMA_DIED);

		List<PrayedAttack> prayed = PrayedAttacks.onSelf(attacks(log, rules), log, rules, null);

		assertEquals(2, prayed.size());
		assertEquals(Fights.P3_START + 3, prayed.get(0).getCheckTick());
	}
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.testing.FightsTest' --tests 'com.yamareviewer.domain.health.*'`
Expected: FAIL — `cannot find symbol` for `TickStateIndex`, `SpecEnergyDrops`, `SpecDrop`, `PrayedAttacks`, `PrayedAttack`.

- [ ] **Step 4: Write the helpers**

`src/main/java/com/yamareviewer/domain/health/TickStateIndex.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.model.KillLog;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The TickState of each tick of a kill (the last one recorded for a tick wins). */
public final class TickStateIndex
{
	private final Map<Integer, TickState> byTick;

	private TickStateIndex(Map<Integer, TickState> byTick)
	{
		this.byTick = byTick;
	}

	public static TickStateIndex of(KillLog log)
	{
		Map<Integer, TickState> byTick = new HashMap<>();
		for (TickState state : log.eventsOf(TickState.class))
		{
			byTick.put(state.getTick(), state);
		}
		return new TickStateIndex(byTick);
	}

	public Optional<TickState> at(int tick)
	{
		return Optional.ofNullable(byTick.get(tick));
	}

	/** SELF's protection prayers at the end of the tick; none when the tick has no state. */
	public Set<ProtectionPrayer> prayersAt(int tick)
	{
		return at(tick).map(TickState::getPrayers).orElse(Set.of());
	}
}
```

`src/main/java/com/yamareviewer/domain/health/PrayerCheckTick.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.ids.PrayerCheck;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.review.Attack;
import java.util.OptionalInt;

/** The prayer-check tick L of spec 6.5: the cast tick, or the landing tick, plus the configured offset. */
public final class PrayerCheckTick
{
	private PrayerCheckTick()
	{
	}

	/** Empty when the check is on the hitsplat and the attack never landed. */
	public static OptionalInt of(Attack attack, Rules rules)
	{
		if (rules.getPrayerCheck() == PrayerCheck.HITSPLAT)
		{
			Integer landing = attack.getLandingTick();
			return landing == null ? OptionalInt.empty() : OptionalInt.of(landing + rules.getPrayerCheckOffset());
		}
		return OptionalInt.of(attack.getCastTick() + rules.getPrayerCheckOffset());
	}
}
```

`src/main/java/com/yamareviewer/domain/health/PrayedAttack.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.Attack;
import java.util.Set;
import lombok.Value;

/** An attack aimed at SELF with a known style and damage, and SELF's protection prayers at its prayer-check tick. */
@Value
public class PrayedAttack
{
	Attack attack;
	int checkTick;
	Set<ProtectionPrayer> prayers;

	public int damage()
	{
		return attack.getDamage();
	}

	/** The prayer that blocks the attack's graphic style (spec 6.5: Magic for magic, Missiles for ranged). */
	public ProtectionPrayer correctPrayer()
	{
		return correctPrayer(attack.getStyle());
	}

	/** Spec 6.5 rule 1: the correct prayer was active at the check tick. */
	public boolean prayedCorrectly()
	{
		return prayers.contains(correctPrayer());
	}

	/** Protect from Magic or Missiles was on, so a big hit says something about magic versus ranged; Protect from Melee says nothing. */
	public boolean prayedAgainstMagicOrRanged()
	{
		return prayers.contains(ProtectionPrayer.MAGIC) || prayers.contains(ProtectionPrayer.MISSILES);
	}

	public static ProtectionPrayer correctPrayer(Style style)
	{
		return style == Style.MAGIC ? ProtectionPrayer.MAGIC : ProtectionPrayer.MISSILES;
	}
}
```

`src/main/java/com/yamareviewer/domain/health/PrayedAttacks.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.Attack;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Attacks with behaviour evidence (spec 6.4): aimed at SELF, style and damage known, with SELF's prayers at the check tick. */
public final class PrayedAttacks
{
	private PrayedAttacks()
	{
	}

	/** @param phase only attacks of this phase, or every phase when null */
	public static List<PrayedAttack> onSelf(List<Attack> attacks, KillLog log, Rules rules, Phase phase)
	{
		TickStateIndex states = TickStateIndex.of(log);
		List<PrayedAttack> result = new ArrayList<>();
		for (Attack attack : attacks)
		{
			if (!Actor.SELF.equals(attack.getTarget()) || attack.getStyle() == null || attack.getDamage() == null)
			{
				continue;
			}
			if (phase != null && attack.getPhase() != phase)
			{
				continue;
			}
			OptionalInt tick = PrayerCheckTick.of(attack, rules);
			if (tick.isEmpty())
			{
				continue;
			}
			Optional<TickState> state = states.at(tick.getAsInt());
			if (state.isPresent())
			{
				result.add(new PrayedAttack(attack, tick.getAsInt(), state.get().getPrayers()));
			}
		}
		return List.copyOf(result);
	}
}
```

`src/main/java/com/yamareviewer/domain/health/SpecDrop.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.ids.Role;
import lombok.Value;

/** Spec energy going down between two consecutive TickStates (spec 6.9). */
@Value
public class SpecDrop
{
	int tick;
	int energyBefore;
	int energyAfter;
	int weaponId;
	/** The WEAPON_* role of the wielded weapon; null for any other weapon (an OTHER_WEAPON spec). */
	Role weaponRole;
	/** A SPEC_* animation played on SELF on this tick or the tick before. */
	boolean matched;

	/** The SpecsMatched failure condition: a known spec weapon, but no spec animation. */
	public boolean unmatchedKnownWeapon()
	{
		return weaponRole != null && !matched;
	}
}
```

`src/main/java/com/yamareviewer/domain/health/SpecEnergyDrops.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import com.yamareviewer.domain.model.KillLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Finds every spec-energy drop of SELF and whether a SPEC_* animation explains it (spec 6.9). */
public final class SpecEnergyDrops
{
	public static final Set<Role> SPEC_ROLES = Collections.unmodifiableSet(EnumSet.of(
		Role.SPEC_EMBERLIGHT, Role.SPEC_ELDER_MAUL, Role.SPEC_DRAGON_WARHAMMER, Role.SPEC_BANDOS_GODSWORD,
		Role.SPEC_ACCURSED_SCEPTRE, Role.SPEC_EYE_OF_AYAK, Role.SPEC_SOULFLAME_HORN, Role.SPEC_PURGING_STAFF,
		Role.SPEC_SARADOMIN_GODSWORD));

	public static final Set<Role> WEAPON_ROLES = Collections.unmodifiableSet(EnumSet.of(
		Role.WEAPON_EMBERLIGHT, Role.WEAPON_ELDER_MAUL, Role.WEAPON_DRAGON_WARHAMMER, Role.WEAPON_BANDOS_GODSWORD,
		Role.WEAPON_ACCURSED_SCEPTRE, Role.WEAPON_EYE_OF_AYAK, Role.WEAPON_SOULFLAME_HORN, Role.WEAPON_PURGING_STAFF,
		Role.WEAPON_SARADOMIN_GODSWORD));

	private SpecEnergyDrops()
	{
	}

	public static List<SpecDrop> find(KillLog log, IdRegistry ids)
	{
		Set<Integer> specAnimationTicks = new HashSet<>();
		for (AnimationObserved animation : log.eventsOf(AnimationObserved.class))
		{
			if (Actor.SELF.equals(animation.getActor())
				&& ids.roleOf(RoleKind.ANIMATION, animation.getAnimationId()).filter(SPEC_ROLES::contains).isPresent())
			{
				specAnimationTicks.add(animation.getTick());
			}
		}
		List<TickState> states = log.eventsOf(TickState.class);
		List<SpecDrop> drops = new ArrayList<>();
		for (int i = 1; i < states.size(); i++)
		{
			TickState before = states.get(i - 1);
			TickState after = states.get(i);
			if (after.getSpecEnergy() >= before.getSpecEnergy())
			{
				continue;
			}
			int weaponId = after.getWeaponId() != -1 ? after.getWeaponId() : before.getWeaponId();
			Role weaponRole = weaponRole(ids, after.getWeaponId()).or(() -> weaponRole(ids, before.getWeaponId())).orElse(null);
			boolean matched = specAnimationTicks.contains(after.getTick()) || specAnimationTicks.contains(after.getTick() - 1);
			drops.add(new SpecDrop(after.getTick(), before.getSpecEnergy(), after.getSpecEnergy(), weaponId, weaponRole, matched));
		}
		return List.copyOf(drops);
	}

	private static Optional<Role> weaponRole(IdRegistry ids, int itemId)
	{
		return itemId < 0 ? Optional.empty() : ids.roleOf(RoleKind.ITEM, itemId).filter(WEAPON_ROLES::contains);
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.testing.FightsTest' --tests 'com.yamareviewer.domain.health.*'`
Expected: PASS (22 tests: 12 from Task 1, 2 in `FightsTest`, 1 in `TickStateIndexTest`, 5 in `SpecEnergyDropsTest`, 2 in `PrayedAttacksTest`). If `FightsTest` fails, fix `Fights` against the real projections (assumed fields 1–4) before going on: every later task builds on it.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/health src/test/java/com/yamareviewer/domain/health src/test/java/com/yamareviewer/testing
git commit -m "feat: add synthetic fights and the evidence helpers the health checks share"
```

---
