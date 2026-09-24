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

### Task 3: PhaseOrderCheck and SuppliesSnapshotCheck

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/health/PhaseOrderCheck.java`, `SuppliesSnapshotCheck.java`
- Test: `src/test/java/com/yamareviewer/domain/health/PhaseOrderCheckTest.java`, `SuppliesSnapshotCheckTest.java`

**Interfaces:**
- Consumes: `HealthCheck`, `CheckResult` (Task 1); `Fights` (Task 2); `PhaseTimes`, `PhaseSpan`, `Sections.PHASES` (Part 2); `SuppliesSnapshot`, `SnapshotKind`, `EndReason` (Part 1).
- Produces: `PhaseOrderCheck` (name "PhaseOrder", hides PHASES, DAMAGE, PRAYER_REVIEW, OPENER, TICK_LOG; roles JUDGE, PHASE_VARBIT, PHASE_TRANSITION_TEXT, PHASE_TRANSITION_GRAPHIC); `SuppliesSnapshotCheck` (name "SuppliesSnapshot", hides SUPPLIES, no roles). Both have public no-arg constructors.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/health/PhaseOrderCheckTest.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PhaseOrderCheckTest
{
	private final PhaseOrderCheck check = new PhaseOrderCheck();

	private CheckResult run(KillLog log)
	{
		return check.check(log, Fights.context(log));
	}

	@Test
	public void aFullKillPasses()
	{
		assertEquals(CheckResult.pass(), run(Fights.healthySolo()));
	}

	@Test
	public void deathBeforeP3IsNotAFailure()
	{
		KillLogBuilder kill = Fights.solo().ticks(40);
		Fights.judgePhase(kill).ticks(10);

		assertEquals(CheckResult.pass(), run(Fights.finish(kill, EndReason.PLAYER_DIED)));
	}

	@Test
	public void leavingInP1IsNotAFailure()
	{
		assertEquals(CheckResult.pass(), run(Fights.solo().ticks(30).build()));
	}

	@Test
	public void aKillWhereYamaDiedWithoutAllPhasesFails()
	{
		KillLog log = Fights.finish(Fights.solo().ticks(50), EndReason.YAMA_DIED);

		CheckResult result = run(log);

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("phases seen: P1;"));
		assertTrue(result.detail(), result.detail().contains("expected P1, JUDGE_1, P2, JUDGE_2, P3"));
		assertEquals(OptionalInt.of(50), result.tick());
	}

	@Test
	public void phasesOutOfOrderFail()
	{
		KillLog log = Fights.solo().ticks(80).build();
		ProjectionContext context = new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT);
		context.put(Sections.PHASES, Section.ok(new PhaseTimes(
			List.of(new PhaseSpan(Phase.P1, 0, 40), new PhaseSpan(Phase.P2, 40, 80)), 80)));

		CheckResult result = check.check(log, context);

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("phases seen: P1, P2;"));
		assertEquals(OptionalInt.of(40), result.tick());
	}

	@Test
	public void skippedWhenPhasesAreNotAvailable()
	{
		ProjectionContext context = new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT);

		assertTrue(check.check(Fights.solo().build(), context).skipped());
	}

	@Test
	public void appliesUnderEveryContractAndHidesTheSectionsOfSpec8()
	{
		for (Contract contract : Contract.values())
		{
			assertTrue(contract.name(), check.applies(ContractRules.of(contract, Rules.DEFAULT)));
		}
		assertEquals("PhaseOrder", check.name());
		assertEquals(List.of(Sections.PHASES, Sections.DAMAGE, Sections.PRAYER_REVIEW, Sections.OPENER, Sections.TICK_LOG), check.hides());
		assertEquals(Set.of(Role.JUDGE, Role.PHASE_VARBIT, Role.PHASE_TRANSITION_TEXT, Role.PHASE_TRANSITION_GRAPHIC), check.reportRoles());
	}
}
```

`src/test/java/com/yamareviewer/domain/health/SuppliesSnapshotCheckTest.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SuppliesSnapshotCheckTest
{
	private final SuppliesSnapshotCheck check = new SuppliesSnapshotCheck();
	private final ProjectionContext context = new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT);

	@Test
	public void bothSnapshotsPass()
	{
		assertEquals(CheckResult.pass(), check.check(Fights.healthySolo(), context));
	}

	@Test
	public void aMissingEndSnapshotFails()
	{
		CheckResult result = check.check(Fights.solo().ticks(5).end(EndReason.YAMA_DIED), context);

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("END"));
		assertEquals(OptionalInt.of(5), result.tick());
	}

	@Test
	public void aMissingStartSnapshotFails()
	{
		KillHeader header = new KillHeader("k", 1L, 2L, "0.1.0", KillLog.SCHEMA_VERSION, "f00d", false);
		KillLog log = KillLog.of(header, List.of(
			new FightStarted(0, "Me", new Position(3200, 3200, 0)),
			new SuppliesSnapshot(4, SnapshotKind.END, List.of()),
			new FightEnded(4, EndReason.PLAYER_DIED)), 0);

		CheckResult result = check.check(log, context);

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("START"));
		assertEquals(OptionalInt.of(0), result.tick());
	}

	@Test
	public void leftKillsAreNotChecked()
	{
		CheckResult result = check.check(Fights.solo().ticks(5).end(EndReason.LEFT), context);

		assertTrue(result.passed());
		assertTrue(result.skipped());
	}

	@Test
	public void hidesSuppliesAndNamesNoRoles()
	{
		assertEquals("SuppliesSnapshot", check.name());
		assertEquals(List.of(Sections.SUPPLIES), check.hides());
		assertEquals(Set.of(), check.reportRoles());
		assertTrue(check.applies(ContractRules.of(Contract.FAMILIAR_ACQUISITION, Rules.DEFAULT)));
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.health.PhaseOrderCheckTest' --tests 'com.yamareviewer.domain.health.SuppliesSnapshotCheckTest'`
Expected: FAIL — `cannot find symbol` for `PhaseOrderCheck`, `SuppliesSnapshotCheck`.

- [ ] **Step 3: Write the checks**

`src/main/java/com/yamareviewer/domain/health/PhaseOrderCheck.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spec 8: a kill that ended with Yama's death must have P1, J1, P2, J2 and P3 in that order; any other kill
 * must have a prefix of that order.
 */
public final class PhaseOrderCheck implements HealthCheck
{
	static final List<Phase> ORDER = List.of(Phase.P1, Phase.JUDGE_1, Phase.P2, Phase.JUDGE_2, Phase.P3);

	@Override
	public String name()
	{
		return "PhaseOrder";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return Set.of(Role.JUDGE, Role.PHASE_VARBIT, Role.PHASE_TRANSITION_TEXT, Role.PHASE_TRANSITION_GRAPHIC);
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.PHASES, Sections.DAMAGE, Sections.PRAYER_REVIEW, Sections.OPENER, Sections.TICK_LOG);
	}

	@Override
	public boolean applies(ContractRules rules)
	{
		return true;
	}

	@Override
	public CheckResult check(KillLog log, ProjectionContext context)
	{
		Optional<PhaseTimes> phases = context.value(Sections.PHASES);
		if (phases.isEmpty())
		{
			return CheckResult.skipped("phases not available");
		}
		List<PhaseSpan> spans = phases.get().getSpans();
		List<Phase> reached = spans.stream().map(PhaseSpan::getPhase).collect(Collectors.toList());
		boolean complete = log.endReason() == EndReason.YAMA_DIED;
		boolean prefix = reached.size() <= ORDER.size() && ORDER.subList(0, reached.size()).equals(reached);
		if (prefix && (!complete || reached.size() == ORDER.size()))
		{
			return CheckResult.pass();
		}
		return CheckResult.fail(String.format("phases seen: %s; expected %s%s", names(reached), names(ORDER),
			complete ? " for a kill that ended with Yama's death" : " or a prefix of it"), firstDeviation(spans, prefix));
	}

	/** The start of the first span out of order, or the end of the last span when a phase is missing after it. */
	private static int firstDeviation(List<PhaseSpan> spans, boolean prefix)
	{
		if (prefix)
		{
			return spans.isEmpty() ? 0 : spans.get(spans.size() - 1).getEndTick();
		}
		for (int i = 0; i < spans.size(); i++)
		{
			if (i >= ORDER.size() || spans.get(i).getPhase() != ORDER.get(i))
			{
				return spans.get(i).getStartTick();
			}
		}
		return 0;
	}

	private static String names(List<Phase> phases)
	{
		return phases.isEmpty() ? "none" : phases.stream().map(Phase::name).collect(Collectors.joining(", "));
	}
}
```

`src/main/java/com/yamareviewer/domain/health/SuppliesSnapshotCheck.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Spec 8: START and END snapshots must both be in the log; a LEFT kill is not checked. */
public final class SuppliesSnapshotCheck implements HealthCheck
{
	@Override
	public String name()
	{
		return "SuppliesSnapshot";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return Set.of();
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.SUPPLIES);
	}

	@Override
	public boolean applies(ContractRules rules)
	{
		return true;
	}

	@Override
	public CheckResult check(KillLog log, ProjectionContext context)
	{
		if (log.endReason() == EndReason.LEFT)
		{
			return CheckResult.skipped("not checked for a kill that ended with LEFT");
		}
		Set<SnapshotKind> kinds = EnumSet.noneOf(SnapshotKind.class);
		for (SuppliesSnapshot snapshot : log.eventsOf(SuppliesSnapshot.class))
		{
			kinds.add(snapshot.getKind());
		}
		if (!kinds.contains(SnapshotKind.START))
		{
			return CheckResult.fail("no START supplies snapshot in the log", 0);
		}
		if (!kinds.contains(SnapshotKind.END))
		{
			return CheckResult.fail("no END supplies snapshot in the log", log.lastTick());
		}
		return CheckResult.pass();
	}
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.health.PhaseOrderCheckTest' --tests 'com.yamareviewer.domain.health.SuppliesSnapshotCheckTest'`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/health src/test/java/com/yamareviewer/domain/health
git commit -m "feat: add the PhaseOrder and SuppliesSnapshot health checks"
```

---

### Task 4: P3AttackCountCheck and AlternationCheck

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/health/P3AttackCountCheck.java`, `AlternationCheck.java`
- Test: `src/test/java/com/yamareviewer/domain/health/P3AttackCountCheckTest.java`, `AlternationCheckTest.java`

**Interfaces:**
- Consumes: Tasks 1–2; `Sections.ATTACKS`, `AttackTimeline`, `Attack` (Part 3, assumed fields 1–2); `Rules.getP3AttackCycle()`, `getMinAttackCountRatio()`, `getMinCycleGapRatio()`, `getMinAlternationRatio()`; `ContractRules.isRandomAttackStyles()`.
- Produces: `P3AttackCountCheck` (name "P3AttackCount"; hides PRAYER_REVIEW, OPENER, TICK_LOG; roles YAMA_STANDARD_ATTACK, YAMA_CAST_MAGIC, YAMA_CAST_RANGED; constants `MIN_CYCLES = 4`, `MIN_GAPS = 3`); `AlternationCheck` (name "Alternation"; hides PRAYER_REVIEW, OPENER; roles YAMA_CAST_*, IMPACT_*; constant `MIN_PAIRS = 5`; skipped under random attack styles).

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/health/P3AttackCountCheckTest.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class P3AttackCountCheckTest
{
	private final P3AttackCountCheck check = new P3AttackCountCheck();

	private CheckResult run(KillLog log)
	{
		return check.check(log, Fights.context(log));
	}

	@Test
	public void aHealthyKillPasses()
	{
		assertEquals(CheckResult.pass(), run(Fights.healthySolo()));
	}

	@Test
	public void tooFewAttacksFail()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		Fights.blockedAttacks(kill, 3).ticks(150);

		CheckResult result = run(Fights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("3 standard attacks in 171 P3 ticks"));
		assertEquals(OptionalInt.of(Fights.P3_START), result.tick());
	}

	@Test
	public void irregularGapsFail()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		for (int i = 0; i < 12; i++)
		{
			Style style = Fights.alternating(i);
			Fights.attack(kill, style, Actor.SELF, 2, Fights.correct(style)).ticks(3);
		}

		CheckResult result = run(Fights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("0 of 11 gaps"));
		assertEquals(OptionalInt.of(Fights.P3_START + 10), result.tick());
	}

	@Test
	public void noP3MeansNoEvidence()
	{
		KillLogBuilder kill = Fights.solo().ticks(40);
		Fights.judgePhase(kill).ticks(10);

		CheckResult result = run(Fights.finish(kill, EndReason.PLAYER_DIED));

		assertTrue(result.passed());
		assertTrue(result.skipped());
	}

	@Test
	public void aShortP3IsNoEvidence()
	{
		KillLogBuilder kill = Fights.soloUntilP3().ticks(10);

		assertTrue(run(Fights.finish(kill, EndReason.PLAYER_DIED)).skipped());
	}

	@Test
	public void namesHidesAndRoles()
	{
		assertEquals("P3AttackCount", check.name());
		assertEquals(List.of(Sections.PRAYER_REVIEW, Sections.OPENER, Sections.TICK_LOG), check.hides());
		assertEquals(Set.of(Role.YAMA_STANDARD_ATTACK, Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED), check.reportRoles());
		assertTrue(check.applies(ContractRules.of(Contract.SHARD_ACQUISITION, Rules.DEFAULT)));
	}
}
```

`src/test/java/com/yamareviewer/domain/health/AlternationCheckTest.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AlternationCheckTest
{
	private final AlternationCheck check = new AlternationCheck();

	private CheckResult run(KillLog log)
	{
		return check.check(log, Fights.context(log));
	}

	private static Actor alternatingTarget(int index)
	{
		return index % 2 == 0 ? Actor.SELF : Actor.PARTNER;
	}

	@Test
	public void aHealthyKillPasses()
	{
		assertEquals(CheckResult.pass(), run(Fights.healthySolo()));
	}

	@Test
	public void globalAlternationIsEnoughInDuo()
	{
		KillLogBuilder kill = Fights.duoUntilP3();
		for (int i = 0; i < 12; i++)
		{
			Style style = Fights.alternating(i);
			Fights.attack(kill, style, alternatingTarget(i), 2, Fights.correct(style));
		}

		assertEquals(CheckResult.pass(), run(Fights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void perTargetAlternationIsEnough()
	{
		KillLogBuilder kill = Fights.duoUntilP3();
		for (int i = 0; i < 12; i++)
		{
			Style style = (i / 2) % 2 == 0 ? Style.MAGIC : Style.RANGED;
			Fights.attack(kill, style, alternatingTarget(i), 2, Fights.correct(style));
		}

		assertEquals(CheckResult.pass(), run(Fights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void noAlternationAnywhereFails()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		for (int i = 0; i < 12; i++)
		{
			Style style = i % 4 == 3 ? Style.RANGED : Style.MAGIC;
			Fights.attack(kill, style, Actor.SELF, 2, Fights.correct(style));
		}

		CheckResult result = run(Fights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("5 of 11 pairs"));
		assertEquals(OptionalInt.of(Fights.P3_START + Fights.CYCLE), result.tick());
	}

	@Test
	public void attacksWithAnUnknownStyleAreLeftOut()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		for (int i = 0; i < 12; i++)
		{
			if (i == 2)
			{
				kill.yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).ticks(Fights.CYCLE);
				continue;
			}
			Style style = Fights.alternating(i);
			Fights.attack(kill, style, Actor.SELF, 2, Fights.correct(style));
		}

		assertEquals(CheckResult.pass(), run(Fights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void tooFewPairsAreNoEvidence()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		for (int i = 0; i < 4; i++)
		{
			Fights.attack(kill, Style.MAGIC, Actor.SELF, 2, Fights.correct(Style.MAGIC));
		}

		assertTrue(run(Fights.finish(kill, EndReason.YAMA_DIED)).skipped());
	}

	@Test
	public void skippedUnderShardAcquisitionAndHidesPrayerReviewAndOpener()
	{
		assertFalse(check.applies(ContractRules.of(Contract.SHARD_ACQUISITION, Rules.DEFAULT)));
		assertTrue(check.applies(ContractRules.of(Contract.CATALYST_ACQUISITION, Rules.DEFAULT)));
		assertTrue(check.applies(ContractRules.of(Contract.NONE, Rules.DEFAULT)));
		assertEquals("Alternation", check.name());
		assertEquals(List.of(Sections.PRAYER_REVIEW, Sections.OPENER), check.hides());
		assertEquals(Set.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED, Role.IMPACT_MAGIC, Role.IMPACT_RANGED), check.reportRoles());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.health.P3AttackCountCheckTest' --tests 'com.yamareviewer.domain.health.AlternationCheckTest'`
Expected: FAIL — `cannot find symbol` for `P3AttackCountCheck`, `AlternationCheck`.

- [ ] **Step 3: Write the checks**

`src/main/java/com/yamareviewer/domain/health/P3AttackCountCheck.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.PhaseSpan;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spec 8: fewer P3 attacks than minAttackCountRatio × P3 ticks ÷ cycle, or fewer than minCycleGapRatio of the
 * gaps between them being multiples of the cycle, means the attack animation or cast graphics are not recognised.
 * The P3 cycle is 7 ticks under every contract (spec 3, 6.3), so Rules.p3AttackCycle is the contract cycle too.
 */
public final class P3AttackCountCheck implements HealthCheck
{
	/** P3 must span at least this many cycles before the count rule can fail. */
	static final int MIN_CYCLES = 4;
	/** At least this many gaps before the gap rule can fail. */
	static final int MIN_GAPS = 3;

	@Override
	public String name()
	{
		return "P3AttackCount";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return Set.of(Role.YAMA_STANDARD_ATTACK, Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED);
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.PRAYER_REVIEW, Sections.OPENER, Sections.TICK_LOG);
	}

	@Override
	public boolean applies(ContractRules rules)
	{
		return true;
	}

	@Override
	public CheckResult check(KillLog log, ProjectionContext context)
	{
		Optional<PhaseSpan> p3 = context.value(Sections.PHASES).flatMap(phases -> phases.span(Phase.P3));
		if (p3.isEmpty())
		{
			return CheckResult.skipped("no P3 in this kill");
		}
		Optional<AttackTimeline> timeline = context.value(Sections.ATTACKS);
		if (timeline.isEmpty())
		{
			return CheckResult.skipped("attacks not available");
		}
		int cycle = context.rules().getP3AttackCycle();
		int ticks = p3.get().ticks();
		if (ticks < MIN_CYCLES * cycle)
		{
			return CheckResult.skipped(String.format("P3 lasted %d ticks, fewer than %d cycles of %d", ticks, MIN_CYCLES, cycle));
		}
		List<Integer> casts = timeline.get().getAttacks().stream()
			.filter(attack -> attack.getPhase() == Phase.P3)
			.map(Attack::getCastTick)
			.sorted()
			.collect(Collectors.toList());
		double ratio = context.rules().getMinAttackCountRatio();
		double minimum = ratio * ticks / cycle;
		if (casts.size() < minimum)
		{
			return CheckResult.fail(String.format("%d standard attacks in %d P3 ticks; at least %.1f expected (%.0f%% of %d/%d)",
				casts.size(), ticks, minimum, ratio * 100, ticks, cycle), p3.get().getStartTick());
		}
		int gaps = casts.size() - 1;
		if (gaps >= MIN_GAPS)
		{
			int multiples = 0;
			Integer firstOdd = null;
			for (int i = 1; i < casts.size(); i++)
			{
				int gap = casts.get(i) - casts.get(i - 1);
				if (gap % cycle == 0)
				{
					multiples++;
				}
				else if (firstOdd == null)
				{
					firstOdd = casts.get(i);
				}
			}
			double gapRatio = context.rules().getMinCycleGapRatio();
			if (multiples < gapRatio * gaps)
			{
				return CheckResult.fail(String.format("%d of %d gaps between P3 attacks are multiples of %d; at least %.0f%% expected",
					multiples, gaps, cycle, gapRatio * 100), firstOdd);
			}
		}
		return CheckResult.pass();
	}
}
```

`src/main/java/com/yamareviewer/domain/health/AlternationCheck.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackTimeline;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spec 8: P3 attacks must alternate style. Spec 6.4 says alternation is global, so the check passes when the
 * global sequence alternates; it also passes when each target's own sequence alternates, and fails only when
 * neither view reaches minAlternationRatio. Skipped under random attack styles (Shard Acquisition).
 */
public final class AlternationCheck implements HealthCheck
{
	/** At least this many pairs of consecutive attacks with known styles before the check can fail. */
	static final int MIN_PAIRS = 5;

	@Override
	public String name()
	{
		return "Alternation";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return Set.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED, Role.IMPACT_MAGIC, Role.IMPACT_RANGED);
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.PRAYER_REVIEW, Sections.OPENER);
	}

	@Override
	public boolean applies(ContractRules rules)
	{
		return !rules.isRandomAttackStyles();
	}

	@Override
	public CheckResult check(KillLog log, ProjectionContext context)
	{
		Optional<AttackTimeline> timeline = context.value(Sections.ATTACKS);
		if (timeline.isEmpty())
		{
			return CheckResult.skipped("attacks not available");
		}
		List<Attack> p3 = timeline.get().getAttacks().stream()
			.filter(attack -> attack.getPhase() == Phase.P3)
			.sorted(Comparator.comparingInt(Attack::getCastTick))
			.collect(Collectors.toList());
		Pairs global = pairs(p3);
		if (global.total < MIN_PAIRS)
		{
			return CheckResult.skipped(String.format("only %d pairs of consecutive P3 attacks with a known style; %d needed", global.total, MIN_PAIRS));
		}
		Pairs perTarget = new Pairs();
		for (Actor target : List.of(Actor.SELF, Actor.PARTNER))
		{
			perTarget.add(pairs(p3.stream().filter(attack -> target.equals(attack.getTarget())).collect(Collectors.toList())));
		}
		double minimum = context.rules().getMinAlternationRatio();
		boolean globalOk = global.ratio() >= minimum;
		boolean perTargetOk = perTarget.total > 0 && perTarget.ratio() >= minimum;
		if (globalOk || perTargetOk)
		{
			return CheckResult.pass();
		}
		return CheckResult.fail(String.format(
			"consecutive P3 attacks alternate style in %d of %d pairs (%.0f%%) over the whole sequence and %d of %d per target; at least %.0f%% expected",
			global.alternating, global.total, global.ratio() * 100, perTarget.alternating, perTarget.total, minimum * 100), global.firstMismatch);
	}

	/** Pairs of adjacent attacks whose styles are both known. */
	private static Pairs pairs(List<Attack> attacks)
	{
		Pairs pairs = new Pairs();
		for (int i = 1; i < attacks.size(); i++)
		{
			Attack previous = attacks.get(i - 1);
			Attack current = attacks.get(i);
			if (previous.getStyle() == null || current.getStyle() == null)
			{
				continue;
			}
			pairs.total++;
			if (previous.getStyle() != current.getStyle())
			{
				pairs.alternating++;
			}
			else if (pairs.firstMismatch == null)
			{
				pairs.firstMismatch = current.getCastTick();
			}
		}
		return pairs;
	}

	private static final class Pairs
	{
		private int total;
		private int alternating;
		private Integer firstMismatch;

		private double ratio()
		{
			return total == 0 ? 1.0 : (double) alternating / total;
		}

		private void add(Pairs other)
		{
			total += other.total;
			alternating += other.alternating;
			if (firstMismatch == null)
			{
				firstMismatch = other.firstMismatch;
			}
		}
	}
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.health.P3AttackCountCheckTest' --tests 'com.yamareviewer.domain.health.AlternationCheckTest'`
Expected: PASS (13 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/health src/test/java/com/yamareviewer/domain/health
git commit -m "feat: add the P3AttackCount and Alternation health checks"
```

---

### Task 5: BlockedDamageCheck and GraphicVsBehaviourCheck

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/health/BlockedDamageCheck.java`, `GraphicVsBehaviourCheck.java`
- Test: `src/test/java/com/yamareviewer/domain/health/BlockedDamageCheckTest.java`, `GraphicVsBehaviourCheckTest.java`

**Interfaces:**
- Consumes: `PrayedAttacks`, `PrayedAttack` (Task 2); `ContractRules.getBlockedMaxHit()` (Integer, null when none); `Rules.getPrayerCheck()`, `getPrayerCheckOffset()`.
- Produces: `BlockedDamageCheck` (name "BlockedDamage"; hides PRAYER_REVIEW; roles IMPACT_MAGIC, IMPACT_RANGED); `GraphicVsBehaviourCheck` (name "GraphicVsBehaviour"; hides PRAYER_REVIEW, OPENER; roles YAMA_CAST_*, IMPACT_*; constants `MIN_CONTRADICTIONS = 3`, `MIN_SHARE = 0.25`). Both apply only when `blockedMaxHit` is not null.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/health/BlockedDamageCheckTest.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.PrayerCheck;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class BlockedDamageCheckTest
{
	private final BlockedDamageCheck check = new BlockedDamageCheck();

	private CheckResult run(KillLog log)
	{
		return check.check(log, Fights.context(log));
	}

	@Test
	public void blockedHitsWithinTheMaxPass()
	{
		assertEquals(CheckResult.pass(), run(Fights.healthySolo()));
	}

	@Test
	public void twoHardBlockedHitsFail()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		Fights.blockedAttacks(kill, 4);
		Fights.attack(kill, Style.MAGIC, Actor.SELF, 12, Fights.correct(Style.MAGIC));
		Fights.blockedAttacks(kill, 3);
		Fights.attack(kill, Style.RANGED, Actor.SELF, 15, Fights.correct(Style.RANGED));
		Fights.blockedAttacks(kill, 2);

		CheckResult result = run(Fights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("2 of 11"));
		assertEquals(OptionalInt.of(Fights.P3_START + 4 * Fights.CYCLE), result.tick());
	}

	@Test
	public void oneHardBlockedHitIsTolerated()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		Fights.blockedAttacks(kill, 4);
		Fights.attack(kill, Style.MAGIC, Actor.SELF, 12, Fights.correct(Style.MAGIC));
		Fights.blockedAttacks(kill, 5);

		assertEquals(CheckResult.pass(), run(Fights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void wrongPrayerHitsAreNotBlockedAttacks()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		for (int i = 0; i < 6; i++)
		{
			Style style = Fights.alternating(i);
			Fights.attack(kill, style, Actor.SELF, 20, Fights.wrong(style));
		}

		assertEquals(CheckResult.pass(), run(Fights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void hitsplatModeChecksTheLandingTick()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		for (int i = 0; i < 3; i++)
		{
			Style style = Fights.alternating(i);
			kill.prayers().yamaTarget(Actor.SELF)
				.yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).graphicOn(Actor.YAMA, Fights.castGraphic(style))
				.ticks(2)
				.prayers(Fights.correct(style)).graphicOn(Actor.SELF, Fights.impactGraphic(style)).hitsplatOn(Actor.SELF, 12)
				.ticks(Fights.CYCLE - 2);
		}
		KillLog log = Fights.finish(kill, EndReason.YAMA_DIED);
		Rules hitsplat = Rules.DEFAULT.toBuilder().prayerCheck(PrayerCheck.HITSPLAT).build();

		assertEquals(CheckResult.pass(), check.check(log, Fights.context(log)));
		assertFalse(check.check(log, Fights.context(log, hitsplat)).passed());
	}

	@Test
	public void skippedWhenBlockedMaxHitIsNoneAndHidesThePrayerReview()
	{
		assertFalse(check.applies(ContractRules.of(Contract.SENSORY_CLOUDING, Rules.DEFAULT)));
		assertFalse(check.applies(ContractRules.of(Contract.BLOODIED_BLOWS, Rules.DEFAULT)));
		assertFalse(check.applies(ContractRules.of(Contract.FAMILIAR_ACQUISITION, Rules.DEFAULT)));
		assertTrue(check.applies(ContractRules.of(Contract.NONE, Rules.DEFAULT)));
		assertTrue(check.applies(ContractRules.of(Contract.DIVINE_SEVERANCE, Rules.DEFAULT)));
		assertEquals("BlockedDamage", check.name());
		assertEquals(List.of(Sections.PRAYER_REVIEW), check.hides());
		assertEquals(Set.of(Role.IMPACT_MAGIC, Role.IMPACT_RANGED), check.reportRoles());
	}
}
```

`src/test/java/com/yamareviewer/domain/health/GraphicVsBehaviourCheckTest.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GraphicVsBehaviourCheckTest
{
	private final GraphicVsBehaviourCheck check = new GraphicVsBehaviourCheck();

	private CheckResult run(KillLog log)
	{
		return check.check(log, Fights.context(log));
	}

	/** {@code count} alternating attacks on SELF hitting for {@code damage}, praying the graphic's style or the other one. */
	private static KillLogBuilder attacks(KillLogBuilder kill, int count, int damage, boolean prayGraphicStyle)
	{
		for (int i = 0; i < count; i++)
		{
			Style style = Fights.alternating(i);
			Fights.attack(kill, style, Actor.SELF, damage, prayGraphicStyle ? Fights.correct(style) : Fights.wrong(style));
		}
		return kill;
	}

	@Test
	public void aHealthyKillPasses()
	{
		assertEquals(CheckResult.pass(), run(Fights.healthySolo()));
	}

	@Test
	public void realPrayerMistakesAreNotContradictions()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		Fights.blockedAttacks(kill, 7);
		attacks(kill, 5, 20, false);

		assertEquals(CheckResult.pass(), run(Fights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void swappedGraphicsFail()
	{
		KillLogBuilder kill = attacks(Fights.soloUntilP3(), 12, 20, true);

		CheckResult result = run(Fights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("12 of 12"));
		assertEquals(OptionalInt.of(Fights.P3_START), result.tick());
	}

	@Test
	public void fewerThanThreeContradictionsPass()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		attacks(kill, 2, 20, true);
		attacks(kill, 4, 20, false);

		assertEquals(CheckResult.pass(), run(Fights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void threeContradictionsAmongManyPass()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		attacks(kill, 3, 20, true);
		attacks(kill, 17, 20, false);

		assertEquals(CheckResult.pass(), run(Fights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void threeOfEightFail()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		attacks(kill, 3, 20, true);
		attacks(kill, 5, 20, false);
		Fights.blockedAttacks(kill, 4);

		CheckResult result = run(Fights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("3 of 8"));
	}

	@Test
	public void evidenceFromEveryPhaseCounts()
	{
		KillLogBuilder kill = Fights.solo();
		for (int i = 0; i < 3; i++)
		{
			Style style = Fights.alternating(i);
			Fights.attack(kill, style, Actor.SELF, 20, Fights.correct(style)).ticks(1);
		}
		Fights.untilP3(kill);
		attacks(kill, 5, 20, false);

		CheckResult result = run(Fights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("3 of 8"));
		assertEquals(OptionalInt.of(0), result.tick());
	}

	@Test
	public void skippedWhenBlockedMaxHitIsNoneAndHidesPrayerReviewAndOpener()
	{
		assertFalse(check.applies(ContractRules.of(Contract.SENSORY_CLOUDING, Rules.DEFAULT)));
		assertTrue(check.applies(ContractRules.of(Contract.NONE, Rules.DEFAULT)));
		assertEquals("GraphicVsBehaviour", check.name());
		assertEquals(List.of(Sections.PRAYER_REVIEW, Sections.OPENER), check.hides());
		assertEquals(Set.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED, Role.IMPACT_MAGIC, Role.IMPACT_RANGED), check.reportRoles());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.health.BlockedDamageCheckTest' --tests 'com.yamareviewer.domain.health.GraphicVsBehaviourCheckTest'`
Expected: FAIL — `cannot find symbol` for `BlockedDamageCheck`, `GraphicVsBehaviourCheck`.

- [ ] **Step 3: Write the checks**

`src/main/java/com/yamareviewer/domain/health/BlockedDamageCheck.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.AttackTimeline;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Spec 8: more than one P3 attack blocked by the right prayer (spec 6.5 rule 1) still hit for more than
 * blockedMaxHit. Either the impact graphics are swapped or the prayer-check tick is off.
 */
public final class BlockedDamageCheck implements HealthCheck
{
	@Override
	public String name()
	{
		return "BlockedDamage";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return Set.of(Role.IMPACT_MAGIC, Role.IMPACT_RANGED);
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.PRAYER_REVIEW);
	}

	@Override
	public boolean applies(ContractRules rules)
	{
		return rules.getBlockedMaxHit() != null;
	}

	@Override
	public CheckResult check(KillLog log, ProjectionContext context)
	{
		Optional<AttackTimeline> timeline = context.value(Sections.ATTACKS);
		if (timeline.isEmpty())
		{
			return CheckResult.skipped("attacks not available");
		}
		Integer max = context.contractRules().getBlockedMaxHit();
		if (max == null)
		{
			return CheckResult.skipped("blockedMaxHit is not defined under this contract");
		}
		Rules rules = context.rules();
		int blocked = 0;
		int hard = 0;
		Integer firstTick = null;
		for (PrayedAttack attack : PrayedAttacks.onSelf(timeline.get().getAttacks(), log, rules, Phase.P3))
		{
			if (!attack.prayedCorrectly())
			{
				continue;
			}
			blocked++;
			if (attack.damage() > max)
			{
				hard++;
				if (firstTick == null)
				{
					firstTick = attack.getAttack().getCastTick();
				}
			}
		}
		if (hard > 1)
		{
			return CheckResult.fail(String.format(
				"%d of %d P3 attacks blocked by the right prayer still hit for more than %d; the impact graphics may be swapped, or the prayer-check tick (%s, offset %d) may be off",
				hard, blocked, max, rules.getPrayerCheck(), rules.getPrayerCheckOffset()), firstTick);
		}
		return CheckResult.pass();
	}
}
```

`src/main/java/com/yamareviewer/domain/health/GraphicVsBehaviourCheck.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.AttackTimeline;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Spec 8 with the behaviour evidence of spec 6.4: an attack has evidence when SELF prayed against magic or
 * ranged at the check tick and still took more than blockedMaxHit (so the style was not the prayed one). The
 * evidence contradicts the graphic when the prayed style is the graphic's style. At least three
 * contradictions making up at least a quarter of the evidence means the cast or impact graphics are wrong.
 */
public final class GraphicVsBehaviourCheck implements HealthCheck
{
	static final int MIN_CONTRADICTIONS = 3;
	static final double MIN_SHARE = 0.25;

	@Override
	public String name()
	{
		return "GraphicVsBehaviour";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return Set.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED, Role.IMPACT_MAGIC, Role.IMPACT_RANGED);
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.PRAYER_REVIEW, Sections.OPENER);
	}

	@Override
	public boolean applies(ContractRules rules)
	{
		return rules.getBlockedMaxHit() != null;
	}

	@Override
	public CheckResult check(KillLog log, ProjectionContext context)
	{
		Optional<AttackTimeline> timeline = context.value(Sections.ATTACKS);
		if (timeline.isEmpty())
		{
			return CheckResult.skipped("attacks not available");
		}
		Integer max = context.contractRules().getBlockedMaxHit();
		if (max == null)
		{
			return CheckResult.skipped("blockedMaxHit is not defined under this contract");
		}
		int evidence = 0;
		int contradictions = 0;
		Integer firstTick = null;
		for (PrayedAttack attack : PrayedAttacks.onSelf(timeline.get().getAttacks(), log, context.rules(), null))
		{
			if (attack.damage() <= max || !attack.prayedAgainstMagicOrRanged())
			{
				continue;
			}
			evidence++;
			if (attack.prayedCorrectly())
			{
				contradictions++;
				if (firstTick == null)
				{
					firstTick = attack.getAttack().getCastTick();
				}
			}
		}
		if (contradictions >= MIN_CONTRADICTIONS && contradictions >= MIN_SHARE * evidence)
		{
			return CheckResult.fail(String.format(
				"%d of %d attacks with behaviour evidence contradict their graphic: SELF prayed against the graphic's style at the prayer-check tick and still took more than %d",
				contradictions, evidence, max), firstTick);
		}
		return CheckResult.pass();
	}
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.health.BlockedDamageCheckTest' --tests 'com.yamareviewer.domain.health.GraphicVsBehaviourCheckTest'`
Expected: PASS (14 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/health src/test/java/com/yamareviewer/domain/health
git commit -m "feat: add the BlockedDamage and GraphicVsBehaviour health checks"
```

---

### Task 6: CrashSetsCheck, SpecsMatchedCheck and the standard list

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/health/CrashSetsCheck.java`, `SpecsMatchedCheck.java`, `HealthChecks.java`
- Test: `src/test/java/com/yamareviewer/domain/health/CrashSetsCheckTest.java`, `SpecsMatchedCheckTest.java`, `HealthChecksTest.java`

**Interfaces:**
- Consumes: `Sections.CRASHES`, `CrashSummary.getLines()`, `CrashLine.getTick()`, `CrashLine.getPlayer()` (Part 3, assumed field 3); `SpecEnergyDrops`, `SpecDrop` (Task 2); `Rules.getCrashSetGap()`; `ContractRules.getP3CrashSets()`.
- Produces: `CrashSetsCheck` (name "CrashSets"; hides CRASHES; roles CRASH_FIREBALL, CRASH_IMPACT; constant `LINES_PER_PLAYER = 3`); `SpecsMatchedCheck` (name "SpecsMatched"; hides SPECS; roles `SpecEnergyDrops.SPEC_ROLES`); `HealthChecks.standard()` returning the eight checks in spec-table order.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/health/CrashSetsCheckTest.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CrashSetsCheckTest
{
	private final CrashSetsCheck check = new CrashSetsCheck();

	private CheckResult run(KillLog log)
	{
		return check.check(log, Fights.context(log));
	}

	/** Two lines two ticks apart, then idle: an incomplete set that takes one attack cycle. */
	private static KillLogBuilder twoLineSet(KillLogBuilder kill, int x, int y)
	{
		Fights.crashLine(kill, x, y).ticks(2);
		Fights.crashLine(kill, x, y).ticks(Fights.CYCLE - 2);
		return kill;
	}

	@Test
	public void completeSetsPass()
	{
		assertEquals(CheckResult.pass(), run(Fights.healthySolo()));
	}

	@Test
	public void noCrashLinesIsNoEvidence()
	{
		KillLog log = Fights.finish(Fights.blockedAttacks(Fights.soloUntilP3(), 8), EndReason.YAMA_DIED);

		assertEquals(CheckResult.pass(), run(log));
	}

	@Test
	public void anIncompleteSetFails()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		Fights.blockedAttacks(kill, 3);
		twoLineSet(kill, Fights.SELF_X, Fights.SELF_Y);
		Fights.blockedAttacks(kill, 3);
		Fights.crashSet(kill, Fights.SELF_X, Fights.SELF_Y);
		Fights.blockedAttacks(kill, 3);

		CheckResult result = run(Fights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("2 lines for SELF"));
		assertTrue(result.detail(), result.detail().contains("expects 3 sets in P3"));
		assertEquals(OptionalInt.of(Fights.P3_START + 3 * Fights.CYCLE), result.tick());
	}

	@Test
	public void theLastSetMayBeIncomplete()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		Fights.blockedAttacks(kill, 3);
		Fights.crashSet(kill, Fights.SELF_X, Fights.SELF_Y);
		Fights.blockedAttacks(kill, 3);
		twoLineSet(kill, Fights.SELF_X, Fights.SELF_Y);
		Fights.blockedAttacks(kill, 3);

		assertEquals(CheckResult.pass(), run(Fights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void aSetWithTooManyLinesFails()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		Fights.blockedAttacks(kill, 3);
		for (int line = 0; line < 4; line++)
		{
			Fights.crashLine(kill, Fights.SELF_X, Fights.SELF_Y).ticks(2);
		}
		kill.ticks(Fights.CYCLE - 1);
		Fights.blockedAttacks(kill, 3);
		Fights.crashSet(kill, Fights.SELF_X, Fights.SELF_Y);
		Fights.blockedAttacks(kill, 3);

		CheckResult result = run(Fights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("4 lines for SELF"));
	}

	@Test
	public void linesAreCountedPerPlayer()
	{
		// Relies on Part 3 giving each line to the player nearest its centre fireball (spec 6.6).
		KillLogBuilder kill = Fights.duoUntilP3();
		Fights.blockedAttacks(kill, 3);
		for (int line = 0; line < 3; line++)
		{
			Fights.crashLine(kill, Fights.SELF_X, Fights.SELF_Y);
			Fights.crashLine(kill, Fights.PARTNER_X, Fights.PARTNER_Y).ticks(2);
		}
		kill.ticks(1);
		Fights.blockedAttacks(kill, 3);
		for (int line = 0; line < 3; line++)
		{
			Fights.crashLine(kill, Fights.SELF_X, Fights.SELF_Y);
			if (line < 2)
			{
				Fights.crashLine(kill, Fights.PARTNER_X, Fights.PARTNER_Y);
			}
			kill.ticks(2);
		}
		kill.ticks(1);
		Fights.blockedAttacks(kill, 3);
		Fights.crashSet(kill, Fights.SELF_X, Fights.SELF_Y);
		Fights.blockedAttacks(kill, 3);

		CheckResult result = run(Fights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("2 lines for PARTNER"));
	}

	@Test
	public void skippedWhenCrashesAreNotAvailableAndAppliesUnderEveryContract()
	{
		ProjectionContext context = new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT);

		assertTrue(check.check(Fights.solo().build(), context).skipped());
		for (Contract contract : Contract.values())
		{
			assertTrue(contract.name(), check.applies(ContractRules.of(contract, Rules.DEFAULT)));
		}
		assertEquals("CrashSets", check.name());
		assertEquals(List.of(Sections.CRASHES), check.hides());
		assertEquals(Set.of(Role.CRASH_FIREBALL, Role.CRASH_IMPACT), check.reportRoles());
	}
}
```

`src/test/java/com/yamareviewer/domain/health/SpecsMatchedCheckTest.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.OptionalInt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SpecsMatchedCheckTest
{
	private static final int EMBERLIGHT = TestIds.id(Role.WEAPON_EMBERLIGHT);

	private final SpecsMatchedCheck check = new SpecsMatchedCheck();

	private CheckResult run(KillLog log)
	{
		return check.check(log, Fights.context(log));
	}

	@Test
	public void matchedSpecsPass()
	{
		assertEquals(CheckResult.pass(), run(Fights.healthySolo()));
	}

	@Test
	public void anEnergyDropWithoutAnimationFails()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		Fights.blockedAttacks(kill, 3);
		kill.weapon(EMBERLIGHT).spec(100).endTick().spec(75).ticks(Fights.CYCLE - 1);
		Fights.blockedAttacks(kill, 3);

		CheckResult result = run(Fights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("WEAPON_EMBERLIGHT"));
		assertTrue(result.detail(), result.detail().contains("100% -> 75%"));
		assertEquals(OptionalInt.of(Fights.P3_START + 3 * Fights.CYCLE + 1), result.tick());
	}

	@Test
	public void otherWeaponSpecsAreNotChecked()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		Fights.blockedAttacks(kill, 3);
		kill.weapon(4151).spec(100).endTick().spec(75).ticks(Fights.CYCLE - 1);
		Fights.blockedAttacks(kill, 3);

		assertEquals(CheckResult.pass(), run(Fights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void theAnimationMayComeOneTickEarlier()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		Fights.blockedAttacks(kill, 3);
		kill.weapon(EMBERLIGHT).spec(100).animates(Actor.SELF, TestIds.id(Role.SPEC_EMBERLIGHT)).endTick().spec(75).ticks(Fights.CYCLE - 1);
		Fights.blockedAttacks(kill, 3);

		assertEquals(CheckResult.pass(), run(Fights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void noWeaponIsNotChecked()
	{
		KillLogBuilder kill = Fights.soloUntilP3();
		Fights.blockedAttacks(kill, 3);
		kill.weapon(-1).spec(100).endTick().spec(75).ticks(Fights.CYCLE - 1);
		Fights.blockedAttacks(kill, 3);

		assertEquals(CheckResult.pass(), run(Fights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void hidesSpecsAndNamesEverySpecRole()
	{
		assertEquals("SpecsMatched", check.name());
		assertEquals(List.of(Sections.SPECS), check.hides());
		assertEquals(SpecEnergyDrops.SPEC_ROLES, check.reportRoles());
		for (Contract contract : Contract.values())
		{
			assertTrue(contract.name(), check.applies(ContractRules.of(contract, Rules.DEFAULT)));
		}
	}
}
```

`src/test/java/com/yamareviewer/domain/health/HealthChecksTest.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HealthChecksTest
{
	private static List<String> names(List<CheckOutcome> outcomes)
	{
		return outcomes.stream().map(CheckOutcome::name).collect(Collectors.toList());
	}

	@Test
	public void theEightChecksOfSpec8InTableOrder()
	{
		assertEquals(List.of("PhaseOrder", "P3AttackCount", "Alternation", "BlockedDamage", "GraphicVsBehaviour",
			"CrashSets", "SpecsMatched", "SuppliesSnapshot"),
			HealthChecks.standard().stream().map(HealthCheck::name).collect(Collectors.toList()));
	}

	@Test
	public void everyStandardCheckRunsAndPassesOnAHealthyKill()
	{
		KillLog log = Fights.healthySolo();
		ProjectionContext context = Fights.context(log);

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, HealthChecks.standard());

		assertEquals(List.of(), CheckOutcome.failedNames(outcomes));
		assertEquals(List.of(), outcomes.stream().filter(CheckOutcome::skipped).map(CheckOutcome::name).collect(Collectors.toList()));
		assertTrue(context.section(Sections.PRAYER_REVIEW).isOk());
	}

	@Test
	public void contractChecksAreSkippedUnderSensoryClouding()
	{
		KillLogBuilder kill = Fights.solo().widgetText(TestIds.id(Role.CONTRACT_NAME_WIDGET), "Contract of Sensory Clouding");
		Fights.untilP3(kill);
		Fights.blockedAttacks(kill, 20);
		KillLog log = Fights.finish(kill, EndReason.YAMA_DIED);
		ProjectionContext context = Fights.context(log);
		assertEquals(Optional.of(Contract.SENSORY_CLOUDING), context.value(Sections.CONTRACT));

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, HealthChecks.standard());

		assertEquals(List.of(), CheckOutcome.failedNames(outcomes));
		assertEquals(List.of("BlockedDamage", "GraphicVsBehaviour"),
			outcomes.stream().filter(CheckOutcome::skipped).map(CheckOutcome::name).collect(Collectors.toList()));
		assertTrue(outcomes.get(3).getResult().detail(), outcomes.get(3).getResult().detail().contains("not applicable under Contract of Sensory Clouding"));
		assertEquals(8, names(outcomes).size());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.health.CrashSetsCheckTest' --tests 'com.yamareviewer.domain.health.SpecsMatchedCheckTest' --tests 'com.yamareviewer.domain.health.HealthChecksTest'`
Expected: FAIL — `cannot find symbol` for `CrashSetsCheck`, `SpecsMatchedCheck`, `HealthChecks`.

- [ ] **Step 3: Write the checks and the list**

`src/main/java/com/yamareviewer/domain/health/CrashSetsCheck.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.CrashLine;
import com.yamareviewer.domain.review.CrashSummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spec 8: crash lines are grouped into sets by time across players (a new set starts after a gap longer than
 * crashSetGap); every set except the last must have exactly three lines for each player that has lines in it.
 * The last set may be cut short by the end of the fight. No lines at all is no evidence.
 */
public final class CrashSetsCheck implements HealthCheck
{
	static final int LINES_PER_PLAYER = 3;

	@Override
	public String name()
	{
		return "CrashSets";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return Set.of(Role.CRASH_FIREBALL, Role.CRASH_IMPACT);
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.CRASHES);
	}

	@Override
	public boolean applies(ContractRules rules)
	{
		return true;
	}

	@Override
	public CheckResult check(KillLog log, ProjectionContext context)
	{
		Optional<CrashSummary> crashes = context.value(Sections.CRASHES);
		if (crashes.isEmpty())
		{
			return CheckResult.skipped("crash lines not available");
		}
		List<CrashLine> lines = crashes.get().getLines().stream()
			.sorted(Comparator.comparingInt(CrashLine::getTick))
			.collect(Collectors.toList());
		if (lines.isEmpty())
		{
			return CheckResult.pass();
		}
		List<List<CrashLine>> sets = sets(lines, context.rules().getCrashSetGap());
		for (int i = 0; i < sets.size() - 1; i++)
		{
			List<CrashLine> set = sets.get(i);
			Map<Actor, Integer> perPlayer = new LinkedHashMap<>();
			for (CrashLine line : set)
			{
				perPlayer.merge(line.getPlayer(), 1, Integer::sum);
			}
			for (Map.Entry<Actor, Integer> entry : perPlayer.entrySet())
			{
				if (entry.getValue() != LINES_PER_PLAYER)
				{
					int first = set.get(0).getTick();
					int last = set.get(set.size() - 1).getTick();
					return CheckResult.fail(String.format(
						"crash set %d of %d (ticks %d-%d) has %d lines for %s; %d per player expected (this contract expects %d sets in P3)",
						i + 1, sets.size(), first, last, entry.getValue(), entry.getKey().getKind(), LINES_PER_PLAYER,
						context.contractRules().getP3CrashSets()), first);
				}
			}
		}
		return CheckResult.pass();
	}

	private static List<List<CrashLine>> sets(List<CrashLine> sorted, int gap)
	{
		List<List<CrashLine>> sets = new ArrayList<>();
		List<CrashLine> current = new ArrayList<>();
		for (CrashLine line : sorted)
		{
			if (!current.isEmpty() && line.getTick() - current.get(current.size() - 1).getTick() > gap)
			{
				sets.add(current);
				current = new ArrayList<>();
			}
			current.add(line);
		}
		sets.add(current);
		return sets;
	}
}
```

`src/main/java/com/yamareviewer/domain/health/SpecsMatchedCheck.java`:

```java
package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Spec 8: a spec-energy drop while a known spec weapon is wielded must have a SPEC_* animation on SELF (spec 6.9). */
public final class SpecsMatchedCheck implements HealthCheck
{
	@Override
	public String name()
	{
		return "SpecsMatched";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return SpecEnergyDrops.SPEC_ROLES;
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.SPECS);
	}

	@Override
	public boolean applies(ContractRules rules)
	{
		return true;
	}

	@Override
	public CheckResult check(KillLog log, ProjectionContext context)
	{
		List<SpecDrop> drops = SpecEnergyDrops.find(log, context.ids());
		List<SpecDrop> unmatched = drops.stream().filter(SpecDrop::unmatchedKnownWeapon).collect(Collectors.toList());
		if (unmatched.isEmpty())
		{
			return CheckResult.pass();
		}
		SpecDrop first = unmatched.get(0);
		return CheckResult.fail(String.format(
			"spec energy dropped %d%% -> %d%% at tick %d with %s (item %d) wielded, but no SPEC_* animation played on SELF at tick %d or %d (%d of %d drops unmatched)",
			first.getEnergyBefore(), first.getEnergyAfter(), first.getTick(), first.getWeaponRole(), first.getWeaponId(),
			first.getTick() - 1, first.getTick(), unmatched.size(), drops.size()), first.getTick());
	}
}
```

`src/main/java/com/yamareviewer/domain/health/HealthChecks.java`:

```java
package com.yamareviewer.domain.health;

import java.util.List;

/** The checks of spec section 8, in table order. Adding a check means adding a class here. */
public final class HealthChecks
{
	private HealthChecks()
	{
	}

	public static List<HealthCheck> standard()
	{
		return List.of(
			new PhaseOrderCheck(),
			new P3AttackCountCheck(),
			new AlternationCheck(),
			new BlockedDamageCheck(),
			new GraphicVsBehaviourCheck(),
			new CrashSetsCheck(),
			new SpecsMatchedCheck(),
			new SuppliesSnapshotCheck());
	}
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.health.*'`
Expected: PASS (67 tests: everything in `domain.health` so far). `linesAreCountedPerPlayer` depends on Part 3 attributing lines to the nearest player; if only that test fails, check `CrashesProjection` against spec 6.6 before touching the check.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/health src/test/java/com/yamareviewer/domain/health
git commit -m "feat: add the CrashSets and SpecsMatched health checks and the standard list"
```

---

### Task 7: Candidate finder

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/diagnosis/Candidate.java`, `CandidateFinder.java`
- Test: `src/test/java/com/yamareviewer/domain/diagnosis/CandidateFinderTest.java`

**Interfaces:**
- Consumes: `IdRegistry.roleOf(RoleKind, int)`, `matchesText`, `Role`, `RoleKind`, the events (Part 1); `SpecEnergyDrops` (Task 2).
- Produces: `Candidate(Role role, int id, String text, String where, int count, int firstTick, int lastTick, double score, String evidence)` (Lombok `@Value`; `text` is null for an id candidate, `id` is 0 for a text candidate); `CandidateFinder.find(KillLog, IdRegistry, Set<Role>)` returning candidates grouped by role in `Role` order, best fit first within a role. Reads the log only; changes nothing.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/domain/diagnosis/CandidateFinderTest.java`:

```java
package com.yamareviewer.domain.diagnosis;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CandidateFinderTest
{
	private static final int UNKNOWN_CAST = 900_001;
	private static final int DISTRACTOR_GRAPHIC = 900_002;
	private static final int UNKNOWN_FIREBALL = 900_010;
	private static final int DISTRACTOR_GROUND = 900_011;
	private static final int UNKNOWN_SPEC = 900_020;
	private static final int DISTRACTOR_ANIMATION = 900_021;

	private static List<Candidate> find(KillLog log, Set<Role> roles)
	{
		return CandidateFinder.find(log, TestIds.registry(), roles);
	}

	@Test
	public void aRenumberedCastGraphicRanksFirst()
	{
		KillLogBuilder kill = KillLogBuilder.kill();
		for (int i = 0; i < 10; i++)
		{
			kill.yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).graphicOn(Actor.YAMA, UNKNOWN_CAST)
				.ticks(2).hitsplatOn(Actor.SELF, 5).ticks(5);
		}
		kill.graphicOn(Actor.YAMA, DISTRACTOR_GRAPHIC).ticks(3).graphicOn(Actor.YAMA, DISTRACTOR_GRAPHIC).ticks(1);

		List<Candidate> candidates = find(kill.end(EndReason.YAMA_DIED), Set.of(Role.YAMA_CAST_MAGIC));

		assertEquals(2, candidates.size());
		Candidate best = candidates.get(0);
		assertEquals(UNKNOWN_CAST, best.getId());
		assertEquals(Role.YAMA_CAST_MAGIC, best.getRole());
		assertEquals("YAMA", best.getWhere());
		assertEquals(10, best.getCount());
		assertEquals(0, best.getFirstTick());
		assertEquals(63, best.getLastTick());
		assertTrue(best.getScore() > candidates.get(1).getScore());
		assertTrue(best.getEvidence(), best.getEvidence().contains("gaps of 7 or 8 ticks: 9 of 9"));
		assertTrue(best.getEvidence(), best.getEvidence().contains("player hitsplat 1-3 ticks after: 10 of 10"));
		assertEquals(DISTRACTOR_GRAPHIC, candidates.get(1).getId());
	}

	@Test
	public void knownIdsAreNeverCandidates()
	{
		assertEquals(List.of(), find(Fights.healthySolo(), Set.of(Role.YAMA_STANDARD_ATTACK, Role.YAMA_CAST_MAGIC, Role.IMPACT_MAGIC,
			Role.CRASH_FIREBALL, Role.SPEC_EMBERLIGHT, Role.JUDGE)));
	}

	@Test
	public void crashFireballTripletsRankFirst()
	{
		KillLogBuilder kill = KillLogBuilder.kill();
		for (int set = 0; set < 3; set++)
		{
			kill.groundGraphic(UNKNOWN_FIREBALL, 3199, 3200).groundGraphic(UNKNOWN_FIREBALL, 3200, 3200)
				.groundGraphic(UNKNOWN_FIREBALL, 3201, 3200).ticks(2);
			kill.groundGraphic(DISTRACTOR_GROUND, 3205, 3205).ticks(3);
		}

		List<Candidate> candidates = find(kill.end(EndReason.YAMA_DIED), Set.of(Role.CRASH_FIREBALL));

		assertEquals(UNKNOWN_FIREBALL, candidates.get(0).getId());
		assertEquals("ground", candidates.get(0).getWhere());
		assertEquals(9, candidates.get(0).getCount());
		assertTrue(candidates.get(0).getEvidence(), candidates.get(0).getEvidence().contains("same-tick groups of three: 3 of 3 groups"));
		assertEquals(DISTRACTOR_GROUND, candidates.get(1).getId());
		assertEquals(0.0, candidates.get(1).getScore(), 0.0);
	}

	@Test
	public void aSpecAnimationAtTheUnmatchedEnergyDropRanksFirst()
	{
		KillLogBuilder kill = KillLogBuilder.kill().weapon(TestIds.id(Role.WEAPON_EMBERLIGHT)).spec(100);
		kill.animates(Actor.SELF, DISTRACTOR_ANIMATION).ticks(3);
		kill.animates(Actor.SELF, UNKNOWN_SPEC).spec(75).ticks(4);
		kill.animates(Actor.SELF, DISTRACTOR_ANIMATION).ticks(3);
		kill.animates(Actor.SELF, UNKNOWN_SPEC).spec(50).ticks(4);
		kill.animates(Actor.SELF, DISTRACTOR_ANIMATION).ticks(1);

		List<Candidate> candidates = find(kill.end(EndReason.YAMA_DIED), Set.of(Role.SPEC_EMBERLIGHT));

		assertEquals(UNKNOWN_SPEC, candidates.get(0).getId());
		assertEquals("SELF", candidates.get(0).getWhere());
		assertTrue(candidates.get(0).getEvidence(), candidates.get(0).getEvidence().contains("at an unmatched spec-energy drop: 2 of 2"));
		assertEquals(DISTRACTOR_ANIMATION, candidates.get(1).getId());
	}

	@Test
	public void unknownNpcSpawnsAreCandidatesForNpcRoles()
	{
		KillLog log = KillLogBuilder.kill().npcSpawns(Actor.other("npc 900030"), 900_030).ticks(2)
			.npcSpawns(Actor.other("npc 900030"), 900_030).end(EndReason.LEFT);

		List<Candidate> candidates = find(log, Set.of(Role.JUDGE));

		assertEquals(1, candidates.size());
		assertEquals(900_030, candidates.get(0).getId());
		assertEquals(2, candidates.get(0).getCount());
		assertEquals("OTHER", candidates.get(0).getWhere());
	}

	@Test
	public void unknownOverheadTextsFromYamaAreCandidates()
	{
		KillLog log = KillLogBuilder.kill().overhead(Actor.YAMA, "Kneel.").ticks(1).overhead(Actor.YAMA, "Enough.").end(EndReason.LEFT);

		List<Candidate> candidates = find(log, Set.of(Role.PHASE_TRANSITION_TEXT));

		assertEquals(1, candidates.size());
		assertEquals("Kneel.", candidates.get(0).getText());
		assertEquals(0, candidates.get(0).getId());
		assertEquals("YAMA", candidates.get(0).getWhere());
	}

	@Test
	public void candidatesAreGroupedByRoleInRoleOrder()
	{
		KillLog log = KillLogBuilder.kill().graphicOn(Actor.YAMA, UNKNOWN_CAST).graphicOn(Actor.SELF, DISTRACTOR_GRAPHIC).end(EndReason.LEFT);

		List<Candidate> candidates = find(log, Set.of(Role.IMPACT_MAGIC, Role.YAMA_CAST_MAGIC));

		assertEquals(List.of(Role.YAMA_CAST_MAGIC, Role.IMPACT_MAGIC),
			List.of(candidates.get(0).getRole(), candidates.get(1).getRole()));
		assertEquals(UNKNOWN_CAST, candidates.get(0).getId());
		assertEquals(DISTRACTOR_GRAPHIC, candidates.get(1).getId());
		assertNull(candidates.get(0).getText());
	}

	@Test
	public void kindsThatAreNotRecordedWithoutARoleGiveNothing()
	{
		KillLog log = KillLogBuilder.kill().varbit(900_040, 1).end(EndReason.LEFT);

		assertEquals(List.of(), find(log, Set.of(Role.PHASE_VARBIT)));
		assertEquals(List.of(), find(log, Set.of()));
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.domain.diagnosis.CandidateFinderTest'`
Expected: FAIL — `cannot find symbol` for `Candidate`, `CandidateFinder`.

- [ ] **Step 3: Write the finder**

`src/main/java/com/yamareviewer/domain/diagnosis/Candidate.java`:

```java
package com.yamareviewer.domain.diagnosis;

import com.yamareviewer.domain.ids.Role;
import lombok.Value;

/** An id (or overhead text) the registry doesn't know that could be the given role after a game update. */
@Value
public class Candidate
{
	Role role;
	/** The unknown id; 0 for a text candidate. */
	int id;
	/** The unknown overhead text; null for an id candidate. */
	String text;
	/** Where it was seen: YAMA, SELF, PARTNER, JUDGE, FLARE, NPC, OTHER or ground. */
	String where;
	int count;
	int firstTick;
	int lastTick;
	/** 0 to 1: how well the occurrences fit the role's pattern (spec 9). */
	double score;
	/** The counts and tick offsets behind the score. */
	String evidence;
}
```

`src/main/java/com/yamareviewer/domain/diagnosis/CandidateFinder.java`:

```java
package com.yamareviewer.domain.diagnosis;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ActorKind;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.health.SpecDrop;
import com.yamareviewer.domain.health.SpecEnergyDrops;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import com.yamareviewer.domain.model.KillLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.Value;

/**
 * Lists ids in a kill's log that the registry doesn't know, of the kinds and on the actors of the given roles,
 * ranked by how well they fit each role's pattern (spec 9): a period near 7 ticks and a lead before hitsplats
 * for attack animations and graphics, a hitsplat right after for impact graphics, same-tick groups of three
 * for crash fireballs, the tick of an unmatched spec-energy drop for spec animations. It reads the log and
 * changes nothing. Kinds that the recorder only keeps for ids with a role (varbits, items, widgets, objects,
 * messages) can't have candidates; unknown NPCs are only in the log in capture mode.
 */
public final class CandidateFinder
{
	/** Ticks after an attack animation or cast graphic within which a player's hitsplat supports it. */
	static final int ATTACK_LEAD = 3;
	/** Ticks after an impact graphic within which a hitsplat on the same actor supports it. */
	static final int IMPACT_LEAD = 2;
	private static final Comparator<Candidate> BEST_FIRST = Comparator.comparingDouble(Candidate::getScore).reversed()
		.thenComparing(Comparator.comparingInt(Candidate::getCount).reversed())
		.thenComparingInt(Candidate::getId)
		.thenComparing(Candidate::getText, Comparator.nullsFirst(Comparator.naturalOrder()));

	private enum Pattern
	{
		PERIODIC_ATTACK,
		HITSPLAT_LEAD,
		TRIPLETS,
		SPEC_DROP,
		COUNT
	}

	private CandidateFinder()
	{
	}

	public static List<Candidate> find(KillLog log, IdRegistry ids, Set<Role> roles)
	{
		if (roles.isEmpty())
		{
			return List.of();
		}
		EnumSet<Role> ordered = EnumSet.noneOf(Role.class);
		ordered.addAll(roles);
		Evidence evidence = new Evidence(log, ids);
		List<Candidate> result = new ArrayList<>();
		for (Role role : ordered)
		{
			List<Candidate> ofRole = new ArrayList<>();
			for (Map.Entry<Integer, List<Occurrence>> entry : evidence.unknownIds(role).entrySet())
			{
				ofRole.add(idCandidate(role, entry.getKey(), entry.getValue(), evidence));
			}
			for (Map.Entry<String, List<Occurrence>> entry : evidence.unknownTexts(role).entrySet())
			{
				List<Occurrence> seen = entry.getValue();
				ofRole.add(new Candidate(role, 0, entry.getKey(), seen.get(0).where, seen.size(),
					seen.get(0).tick, seen.get(seen.size() - 1).tick, 0.0, "seen " + seen.size() + " times"));
			}
			ofRole.sort(BEST_FIRST);
			result.addAll(ofRole);
		}
		return List.copyOf(result);
	}

	private static Candidate idCandidate(Role role, int id, List<Occurrence> seen, Evidence evidence)
	{
		Fit fit;
		switch (patternOf(role))
		{
			case PERIODIC_ATTACK:
				fit = periodicAttack(seen, evidence);
				break;
			case HITSPLAT_LEAD:
				fit = hitsplatLead(seen, evidence);
				break;
			case TRIPLETS:
				fit = triplets(seen);
				break;
			case SPEC_DROP:
				fit = specDrop(seen, evidence);
				break;
			default:
				fit = new Fit(0.0, "seen " + seen.size() + " times");
		}
		return new Candidate(role, id, null, seen.get(0).where, seen.size(), seen.get(0).tick,
			seen.get(seen.size() - 1).tick, fit.score, fit.evidence);
	}

	private static Pattern patternOf(Role role)
	{
		switch (role)
		{
			case YAMA_STANDARD_ATTACK:
			case YAMA_CAST_MAGIC:
			case YAMA_CAST_RANGED:
				return Pattern.PERIODIC_ATTACK;
			case IMPACT_MAGIC:
			case IMPACT_RANGED:
			case CRASH_IMPACT:
			case FLARE_HIT:
				return Pattern.HITSPLAT_LEAD;
			case CRASH_FIREBALL:
				return Pattern.TRIPLETS;
			default:
				return SpecEnergyDrops.SPEC_ROLES.contains(role) ? Pattern.SPEC_DROP : Pattern.COUNT;
		}
	}

	/** The actors an animation or graphic of this role appears on. */
	private static Set<ActorKind> actorsOf(Role role)
	{
		switch (role)
		{
			case YAMA_STANDARD_ATTACK:
			case YAMA_MELEE:
			case YAMA_FLARE_SUMMON:
			case SHADOW_STOMP:
			case YAMA_CAST_MAGIC:
			case YAMA_CAST_RANGED:
			case PHASE_TRANSITION_GRAPHIC:
			case FLARE_HEAL:
				return EnumSet.of(ActorKind.YAMA);
			case FLARE_EXPLODE:
			case FLARE_DEATH:
				return EnumSet.of(ActorKind.FLARE);
			case CRASH_FIREBALL:
				return EnumSet.noneOf(ActorKind.class);
			default:
				return EnumSet.of(ActorKind.SELF, ActorKind.PARTNER);
		}
	}

	/** Graphics of this role also appear as ground graphics. */
	private static boolean onTheGround(Role role)
	{
		switch (role)
		{
			case CRASH_FIREBALL:
			case SHADOW_WAVE:
			case FIRE_STREAK:
			case FIRE_ATTACK:
			case METEOR_STRIKE:
			case SHADOW_POOL:
				return true;
			default:
				return false;
		}
	}

	private static Fit periodicAttack(List<Occurrence> seen, Evidence evidence)
	{
		int gaps = 0;
		int periodic = 0;
		for (int i = 1; i < seen.size(); i++)
		{
			int gap = seen.get(i).tick - seen.get(i - 1).tick;
			if (gap > 0)
			{
				gaps++;
				if (gap % 7 == 0 || gap % 8 == 0)
				{
					periodic++;
				}
			}
		}
		int led = 0;
		for (Occurrence occurrence : seen)
		{
			if (evidence.playerHitsplatBetween(occurrence.tick + 1, occurrence.tick + ATTACK_LEAD))
			{
				led++;
			}
		}
		double periodicShare = gaps == 0 ? 0.0 : (double) periodic / gaps;
		double leadShare = (double) led / seen.size();
		return new Fit((periodicShare + leadShare) / 2, String.format(
			"gaps of 7 or 8 ticks: %d of %d, player hitsplat 1-%d ticks after: %d of %d", periodic, gaps, ATTACK_LEAD, led, seen.size()));
	}

	private static Fit hitsplatLead(List<Occurrence> seen, Evidence evidence)
	{
		int led = 0;
		Map<Integer, Integer> leads = new TreeMap<>();
		for (Occurrence occurrence : seen)
		{
			OptionalInt lead = evidence.hitsplatLeadOn(occurrence.where, occurrence.tick, IMPACT_LEAD);
			if (lead.isPresent())
			{
				led++;
				leads.merge(lead.getAsInt(), 1, Integer::sum);
			}
		}
		String typical = leads.entrySet().stream()
			.max(Map.Entry.comparingByValue())
			.map(entry -> entry.getKey() + " ticks")
			.orElse("none");
		return new Fit((double) led / seen.size(), String.format(
			"hitsplat on the same actor within %d ticks: %d of %d (typical lead %s)", IMPACT_LEAD, led, seen.size(), typical));
	}

	private static Fit triplets(List<Occurrence> seen)
	{
		Map<Integer, Integer> perTick = new TreeMap<>();
		for (Occurrence occurrence : seen)
		{
			perTick.merge(occurrence.tick, 1, Integer::sum);
		}
		int triples = 0;
		int inTriples = 0;
		for (int size : perTick.values())
		{
			if (size % 3 == 0)
			{
				triples++;
				inTriples += size;
			}
		}
		return new Fit((double) inTriples / seen.size(), String.format(
			"same-tick groups of three: %d of %d groups", triples, perTick.size()));
	}

	private static Fit specDrop(List<Occurrence> seen, Evidence evidence)
	{
		int at = 0;
		for (Occurrence occurrence : seen)
		{
			if (evidence.unmatchedDropTicks.contains(occurrence.tick) || evidence.unmatchedDropTicks.contains(occurrence.tick + 1))
			{
				at++;
			}
		}
		return new Fit((double) at / seen.size(), String.format("at an unmatched spec-energy drop: %d of %d", at, seen.size()));
	}

	static String where(Actor actor)
	{
		return actor.getKind() == ActorKind.OTHER ? "OTHER" : actor.getKind().name();
	}

	@Value
	private static class Fit
	{
		double score;
		String evidence;
	}

	@Value
	private static class Occurrence
	{
		int tick;
		String where;
	}

	/** The log, indexed once per find(). */
	private static final class Evidence
	{
		private final KillLog log;
		private final IdRegistry ids;
		private final NavigableSet<Integer> playerHitsplatTicks = new TreeSet<>();
		private final Map<String, NavigableSet<Integer>> hitsplatTicksByActor = new HashMap<>();
		private final Set<Integer> unmatchedDropTicks = new HashSet<>();

		private Evidence(KillLog log, IdRegistry ids)
		{
			this.log = log;
			this.ids = ids;
			for (HitsplatObserved hitsplat : log.eventsOf(HitsplatObserved.class))
			{
				if (hitsplat.getKind() != HitsplatKind.DAMAGE && hitsplat.getKind() != HitsplatKind.BLOCK)
				{
					continue;
				}
				if (hitsplat.getTarget().isPlayer())
				{
					playerHitsplatTicks.add(hitsplat.getTick());
				}
				hitsplatTicksByActor.computeIfAbsent(where(hitsplat.getTarget()), key -> new TreeSet<>()).add(hitsplat.getTick());
			}
			for (SpecDrop drop : SpecEnergyDrops.find(log, ids))
			{
				if (drop.unmatchedKnownWeapon())
				{
					unmatchedDropTicks.add(drop.getTick());
				}
			}
		}

		private boolean playerHitsplatBetween(int from, int to)
		{
			Integer next = playerHitsplatTicks.ceiling(from);
			return next != null && next <= to;
		}

		private OptionalInt hitsplatLeadOn(String where, int tick, int window)
		{
			NavigableSet<Integer> ticks = hitsplatTicksByActor.get(where);
			if (ticks == null)
			{
				return OptionalInt.empty();
			}
			Integer next = ticks.ceiling(tick);
			return next != null && next <= tick + window ? OptionalInt.of(next - tick) : OptionalInt.empty();
		}

		/** Unknown ids of the role's kind on the role's actors, each with its occurrences in log order. */
		private Map<Integer, List<Occurrence>> unknownIds(Role role)
		{
			Map<Integer, List<Occurrence>> result = new TreeMap<>();
			switch (role.kind())
			{
				case ANIMATION:
					for (AnimationObserved animation : log.eventsOf(AnimationObserved.class))
					{
						if (actorsOf(role).contains(animation.getActor().getKind()) && unknown(RoleKind.ANIMATION, animation.getAnimationId()))
						{
							add(result, animation.getAnimationId(), animation.getTick(), where(animation.getActor()));
						}
					}
					break;
				case GRAPHIC:
					for (GraphicObserved graphic : log.eventsOf(GraphicObserved.class))
					{
						if (actorsOf(role).contains(graphic.getActor().getKind()) && unknown(RoleKind.GRAPHIC, graphic.getGraphicId()))
						{
							add(result, graphic.getGraphicId(), graphic.getTick(), where(graphic.getActor()));
						}
					}
					if (onTheGround(role))
					{
						for (GroundGraphicObserved graphic : log.eventsOf(GroundGraphicObserved.class))
						{
							if (unknown(RoleKind.GRAPHIC, graphic.getGraphicId()))
							{
								add(result, graphic.getGraphicId(), graphic.getTick(), "ground");
							}
						}
					}
					break;
				case NPC:
					for (NpcSpawnObserved spawn : log.eventsOf(NpcSpawnObserved.class))
					{
						if (unknown(RoleKind.NPC, spawn.getNpcId()))
						{
							add(result, spawn.getNpcId(), spawn.getTick(), where(spawn.getActor()));
						}
					}
					break;
				default:
					break;
			}
			return result;
		}

		/** Overhead texts from Yama that match no overhead role. */
		private Map<String, List<Occurrence>> unknownTexts(Role role)
		{
			Map<String, List<Occurrence>> result = new TreeMap<>();
			if (role.kind() != RoleKind.OVERHEAD)
			{
				return result;
			}
			for (OverheadTextObserved overhead : log.eventsOf(OverheadTextObserved.class))
			{
				if (overhead.getActor().getKind() != ActorKind.YAMA || overhead.getText() == null || knownText(overhead.getText()))
				{
					continue;
				}
				result.computeIfAbsent(overhead.getText(), key -> new ArrayList<>()).add(new Occurrence(overhead.getTick(), where(overhead.getActor())));
			}
			return result;
		}

		private boolean knownText(String text)
		{
			for (Role role : Role.values())
			{
				if (role.kind() == RoleKind.OVERHEAD && ids.matchesText(role, text))
				{
					return true;
				}
			}
			return false;
		}

		private boolean unknown(RoleKind kind, int id)
		{
			return ids.roleOf(kind, id).isEmpty();
		}

		private static void add(Map<Integer, List<Occurrence>> result, int id, int tick, String where)
		{
			result.computeIfAbsent(id, key -> new ArrayList<>()).add(new Occurrence(tick, where));
		}
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.domain.diagnosis.CandidateFinderTest'`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/diagnosis src/test/java/com/yamareviewer/domain/diagnosis
git commit -m "feat: rank unknown ids as candidates for the roles a failed check names"
```

---

### Task 8: Report text and the issue link

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/diagnosis/Versions.java`, `CheckCandidates.java`, `ProblemReport.java`
- Create: `src/main/java/com/yamareviewer/domain/text/ReportWriter.java`, `EventLine.java`, `IssueLink.java`
- Test: `src/test/java/com/yamareviewer/domain/text/ReportWriterTest.java`, `EventLineTest.java`, `IssueLinkTest.java`

**Interfaces:**
- Consumes: `CheckOutcome` (Task 1), `Candidate` (Task 7), the events, `EventType`, `KillLog`, `Mode`, `Contract`, `SectionKey.name()`.
- Produces: `Versions(String plugin, String runeLite, String game)`; `CheckCandidates(String checkName, Set<Role> roles, List<Candidate> candidates)`; `ProblemReport` (`@Value`: headline, versions, log (nullable), mode (nullable), contract (nullable), outcomes, candidates, notes) with `ProblemReport.forFailedChecks(Versions, KillLog, Mode, Contract, List<CheckOutcome>, List<CheckCandidates>)` and `ProblemReport.forUnknownYama(Versions, Set<Integer> unknownIds, Set<Integer> knownIds)`; `ReportWriter.write(ProblemReport)`, `ReportWriter.summary(String report)`, constants `TITLE_PREFIX`, `EVENTS_HEADER`, `EXCERPT_LINES = 200`; `EventLine.of(DomainEvent, String partnerName)` (package-private); `IssueLink.url(String repositoryUrl, String report)` with `MAX_BODY_CHARS = 6_000`, `MAX_URL_CHARS = 8_000`, `ATTACH_NOTE`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/text/ReportWriterTest.java`:

```java
package com.yamareviewer.domain.text;

import com.yamareviewer.domain.diagnosis.Candidate;
import com.yamareviewer.domain.diagnosis.CheckCandidates;
import com.yamareviewer.domain.diagnosis.ProblemReport;
import com.yamareviewer.domain.diagnosis.Versions;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.PlayerLeft;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.health.AlternationCheck;
import com.yamareviewer.domain.health.CheckOutcome;
import com.yamareviewer.domain.health.CheckResult;
import com.yamareviewer.domain.health.PhaseOrderCheck;
import com.yamareviewer.domain.health.SuppliesSnapshotCheck;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ReportWriterTest
{
	private static final Versions VERSIONS = new Versions("0.1.0", "1.11.5", "232");

	private static ProblemReport failedPhaseOrder(KillLog log)
	{
		CheckOutcome failed = new CheckOutcome(new PhaseOrderCheck(),
			CheckResult.fail("phases seen: P1; expected P1, JUDGE_1, P2, JUDGE_2, P3 for a kill that ended with Yama's death", 50));
		CheckOutcome passed = new CheckOutcome(new SuppliesSnapshotCheck(), CheckResult.pass());
		CheckOutcome skipped = new CheckOutcome(new AlternationCheck(), CheckResult.skipped("not applicable under Contract of Shard Acquisition"));
		List<Candidate> candidates = List.of(
			new Candidate(Role.PHASE_TRANSITION_GRAPHIC, 3301, null, "YAMA", 2, 233, 540, 0.0, "seen 2 times"));
		return ProblemReport.forFailedChecks(VERSIONS, log, Mode.SOLO, Contract.NONE, List.of(failed, passed, skipped),
			List.of(new CheckCandidates("PhaseOrder", new PhaseOrderCheck().reportRoles(), candidates)));
	}

	@Test
	public void listsVersionsTheKillChecksCandidatesAndAnExcerpt()
	{
		KillLog log = Fights.solo().ticks(50).supplies(SnapshotKind.END).end(EndReason.YAMA_DIED);

		String report = ReportWriter.write(failedPhaseOrder(log));

		assertTrue(report, report.startsWith("Yama Reviewer report: health checks failed (PhaseOrder)\n"
			+ "Plugin 0.1.0, RuneLite 1.11.5, game revision 232\n"));
		assertTrue(report, report.contains(": ended YAMA_DIED at tick 50, "));
		assertTrue(report, report.contains(", mode SOLO, No contract, ids fingerprint "));
		assertTrue(report, report.contains("\nChecks\n  FAILED  PhaseOrder: phases seen: P1;"));
		assertTrue(report, report.contains(" (hides phases, damage, "));
		assertTrue(report, report.contains("\n  passed  SuppliesSnapshot\n"));
		assertTrue(report, report.contains("\n  skipped Alternation: not applicable under Contract of Shard Acquisition\n"));
		assertTrue(report, report.contains("\nCandidates (ids in this log that the plugin doesn't know, best fit first)\n"
			+ "  PhaseOrder: JUDGE, PHASE_TRANSITION_TEXT, PHASE_VARBIT, PHASE_TRANSITION_GRAPHIC\n"));
		assertTrue(report, report.contains("    JUDGE: none found (unknown NPCs are only recorded in capture mode)\n"));
		assertTrue(report, report.contains("    PHASE_VARBIT: none found (this kind is only recorded for ids that already have a role)\n"));
		assertTrue(report, report.contains("    PHASE_TRANSITION_GRAPHIC on YAMA: graphic 3301 x2, ticks 233-540, fit 0.00, seen 2 times\n"));
		assertTrue(report, report.contains("\nEvents (lines 1-"));
		assertTrue(report, report.contains(", around tick 50;"));
		assertTrue(report, report.contains("\n  0 entry TRAVEL\n  0 fight-start at (3200,3200,0)\n  0 supplies START "));
	}

	@Test
	public void theExcerptHasAtMost200EventsAroundTheFirstFailure()
	{
		KillLog log = Fights.solo().ticks(300).end(EndReason.YAMA_DIED);
		ProblemReport report = ProblemReport.forFailedChecks(VERSIONS, log, null, null,
			List.of(new CheckOutcome(new PhaseOrderCheck(), CheckResult.fail("x", 150))), List.of());

		String text = ReportWriter.write(report);

		List<String> lines = text.substring(text.indexOf("Events (")).lines().collect(Collectors.toList());
		assertEquals(201, lines.size());
		assertTrue(lines.get(0), lines.get(0).contains("lines 54-253 of 304 events, around tick 150"));
		assertTrue(lines.get(1), lines.get(1).startsWith("  50 tick "));
		assertTrue(lines.get(200), lines.get(200).startsWith("  249 tick "));
	}

	@Test
	public void neverContainsPlayerNames()
	{
		KillLogBuilder kill = Fights.solo().playerSeen("Zezima")
			.event(new HitsplatObserved(0, Actor.other("Stranger"), HitsplatKind.DAMAGE, 4, 1, false))
			.ticks(2)
			.event(new PlayerLeft(2, "Zezima"));

		String report = ReportWriter.write(failedPhaseOrder(kill.end(EndReason.LEFT)));

		assertFalse(report, report.contains("Zezima"));
		assertFalse(report, report.contains("Stranger"));
		assertTrue(report, report.contains("\n  0 fight-start at (3200,3200,0)\n"));
		assertTrue(report, report.contains("\n  0 player-seen PARTNER\n"));
		assertTrue(report, report.contains("\n  0 hitsplat OTHER DAMAGE 4 type=1 mine=false\n"));
		assertTrue(report, report.contains("\n  2 player-left PARTNER\n"));
	}

	@Test
	public void theSelfCheckReportListsTheIds()
	{
		String report = ReportWriter.write(ProblemReport.forUnknownYama(VERSIONS, Set.of(15001, 14999), Set.of(14176)));

		assertTrue(report, report.startsWith("Yama Reviewer report: unknown Yama NPC id [14999, 15001]\n"));
		assertTrue(report, report.contains("Built-in YAMA ids: [14176]\n"));
		assertFalse(report, report.contains("Checks"));
		assertFalse(report, report.contains("Events ("));
	}

	@Test
	public void summaryStopsBeforeTheEvents()
	{
		String report = ReportWriter.write(failedPhaseOrder(Fights.solo().ticks(50).end(EndReason.YAMA_DIED)));

		String summary = ReportWriter.summary(report);

		assertTrue(summary, summary.contains("\nChecks\n"));
		assertTrue(summary, summary.contains("\nCandidates ("));
		assertFalse(summary, summary.contains("Events ("));
		assertEquals(summary, ReportWriter.summary(summary));
	}
}
```

`src/test/java/com/yamareviewer/domain/text/EventLineTest.java`:

```java
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
```

`src/test/java/com/yamareviewer/domain/text/IssueLinkTest.java`:

```java
package com.yamareviewer.domain.text;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class IssueLinkTest
{
	private static final String REPOSITORY = "https://github.com/stefanws0/Yama-Mia-";

	private static String body(String url)
	{
		return URLDecoder.decode(url.substring(url.indexOf("&body=") + "&body=".length()), StandardCharsets.UTF_8);
	}

	@Test
	public void buildsAPrefilledIssueFromTheSummary()
	{
		String report = "Yama Reviewer report: health checks failed (PhaseOrder)\nPlugin 0.1.0\n\nChecks\n  FAILED  PhaseOrder: x\n\n"
			+ "Events (lines 1-2 of 2 events, around tick 0)\n  0 fight-start at (1,2,0)\n  1 fight-end LEFT\n";

		String url = IssueLink.url(REPOSITORY, report);

		assertTrue(url, url.startsWith(REPOSITORY + "/issues/new?title="
			+ URLEncoder.encode("health checks failed (PhaseOrder)", StandardCharsets.UTF_8) + "&body="));
		String body = body(url);
		assertTrue(body, body.startsWith("Yama Reviewer report: health checks failed (PhaseOrder)\nPlugin 0.1.0\n\nChecks\n  FAILED  PhaseOrder: x"));
		assertFalse(body, body.contains("Events ("));
		assertTrue(body, body.endsWith(IssueLink.ATTACH_NOTE));
	}

	@Test
	public void theBodyStaysUnder6000CharactersAndTheUrlUnder8000()
	{
		StringBuilder big = new StringBuilder("Yama Reviewer report: big\n");
		for (int i = 0; i < 400; i++)
		{
			big.append("  FAILED  Check").append(i).append(": some numbers ").append(i).append('\n');
		}

		String url = IssueLink.url(REPOSITORY, big.toString());

		String body = body(url);
		assertTrue(String.valueOf(body.length()), body.length() < IssueLink.MAX_BODY_CHARS);
		assertTrue(String.valueOf(url.length()), url.length() <= IssueLink.MAX_URL_CHARS);
		assertTrue(body, body.contains("[truncated]"));
		assertTrue(body, body.endsWith(IssueLink.ATTACH_NOTE));
	}

	@Test
	public void aFirstLineWithoutThePrefixIsTheTitleAsItIs()
	{
		String url = IssueLink.url(REPOSITORY, "Something odd\nmore\n");

		assertEquals(REPOSITORY + "/issues/new?title=Something+odd&body=" + URLEncoder.encode("Something odd\nmore" + IssueLink.ATTACH_NOTE, StandardCharsets.UTF_8), url);
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.text.ReportWriterTest' --tests 'com.yamareviewer.domain.text.EventLineTest' --tests 'com.yamareviewer.domain.text.IssueLinkTest'`
Expected: FAIL — `cannot find symbol` for `Versions`, `ProblemReport`, `CheckCandidates`, `ReportWriter`, `EventLine`, `IssueLink`.

- [ ] **Step 3: Write the report values**

`src/main/java/com/yamareviewer/domain/diagnosis/Versions.java`:

```java
package com.yamareviewer.domain.diagnosis;

import lombok.Value;

/** What was running when a report was written: plugin version, RuneLite version, game revision (spec 9). */
@Value
public class Versions
{
	String plugin;
	String runeLite;
	String game;
}
```

`src/main/java/com/yamareviewer/domain/diagnosis/CheckCandidates.java`:

```java
package com.yamareviewer.domain.diagnosis;

import com.yamareviewer.domain.ids.Role;
import java.util.List;
import java.util.Set;
import lombok.Value;

/** The candidates found for the report roles of one failed check. */
@Value
public class CheckCandidates
{
	String checkName;
	Set<Role> roles;
	List<Candidate> candidates;
}
```

`src/main/java/com/yamareviewer/domain/diagnosis/ProblemReport.java`:

```java
package com.yamareviewer.domain.diagnosis;

import com.yamareviewer.domain.health.CheckOutcome;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.Value;

/** Everything a report says, before it is turned into text (spec 9). */
@Value
public class ProblemReport
{
	/** One line, the issue title: "health checks failed (P3AttackCount)" or "unknown Yama NPC id [14999]". */
	String headline;
	Versions versions;
	/** The kill the failed checks came from; null for the recording self-check. */
	KillLog log;
	/** Null when unknown. */
	Mode mode;
	/** Null when unknown. */
	Contract contract;
	List<CheckOutcome> outcomes;
	List<CheckCandidates> candidates;
	/** Free lines printed after the kill line. */
	List<String> notes;

	public static ProblemReport forFailedChecks(Versions versions, KillLog log, Mode mode, Contract contract,
		List<CheckOutcome> outcomes, List<CheckCandidates> candidates)
	{
		String failed = String.join(", ", CheckOutcome.failedNames(outcomes));
		return new ProblemReport("health checks failed (" + failed + ")", versions, log, mode, contract,
			List.copyOf(outcomes), List.copyOf(candidates), List.of());
	}

	/** Spec 5.3 and 9: an NPC named "Yama" with an unknown id spawned while armed, so no kill can be recorded. */
	public static ProblemReport forUnknownYama(Versions versions, Set<Integer> unknownIds, Set<Integer> knownIds)
	{
		String unknown = new TreeSet<>(unknownIds).toString();
		List<String> notes = List.of(
			"An NPC named \"Yama\" spawned in Yama's Domain with an id this plugin doesn't know: " + unknown,
			"Built-in YAMA ids: " + new TreeSet<>(knownIds),
			"No kill can be recorded until BuiltInIds knows the new id; a RuneLite update that regenerates gameval may already fix it.");
		return new ProblemReport("unknown Yama NPC id " + unknown, versions, null, null, null, List.of(), List.of(), notes);
	}
}
```

- [ ] **Step 4: Write the writer, the event lines and the link**

`src/main/java/com/yamareviewer/domain/text/EventLine.java`:

```java
package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ActorKind;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EntryChosen;
import com.yamareviewer.domain.event.EventType;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.GameMessageObserved;
import com.yamareviewer.domain.event.GameStateObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
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
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.event.WidgetTextObserved;
import java.util.TreeSet;

/** One event as one report line: "<tick> <type name> <fields>". Player names never appear (spec 2, rule 7). */
final class EventLine
{
	private EventLine()
	{
	}

	static String of(DomainEvent event, String partnerName)
	{
		return event.getTick() + " " + EventType.of(event).typeName() + " " + fields(event, partnerName);
	}

	private static String fields(DomainEvent event, String partnerName)
	{
		if (event instanceof EntryChosen)
		{
			return ((EntryChosen) event).getChoice().name();
		}
		if (event instanceof FightStarted)
		{
			return "at " + position(((FightStarted) event).getSelfPosition());
		}
		if (event instanceof FightEnded)
		{
			return ((FightEnded) event).getReason().name();
		}
		if (event instanceof PlayerSeen)
		{
			return player(((PlayerSeen) event).getName(), partnerName);
		}
		if (event instanceof PlayerLeft)
		{
			return player(((PlayerLeft) event).getName(), partnerName);
		}
		if (event instanceof GameStateObserved)
		{
			return ((GameStateObserved) event).getState().name();
		}
		if (event instanceof NpcSpawnObserved)
		{
			NpcSpawnObserved e = (NpcSpawnObserved) event;
			return actor(e.getActor()) + " id=" + e.getNpcId() + " index=" + e.getNpcIndex();
		}
		if (event instanceof NpcDespawnObserved)
		{
			NpcDespawnObserved e = (NpcDespawnObserved) event;
			return actor(e.getActor()) + " id=" + e.getNpcId() + " index=" + e.getNpcIndex() + " dying=" + e.isDying();
		}
		if (event instanceof NpcChangedObserved)
		{
			NpcChangedObserved e = (NpcChangedObserved) event;
			return actor(e.getActor()) + " " + e.getOldId() + " -> " + e.getNewId();
		}
		if (event instanceof ObjectSpawnObserved)
		{
			ObjectSpawnObserved e = (ObjectSpawnObserved) event;
			return "id=" + e.getObjectId() + " at " + position(e.getPosition());
		}
		if (event instanceof ObjectDespawnObserved)
		{
			ObjectDespawnObserved e = (ObjectDespawnObserved) event;
			return "id=" + e.getObjectId() + " at " + position(e.getPosition());
		}
		if (event instanceof ObjectAnimationObserved)
		{
			ObjectAnimationObserved e = (ObjectAnimationObserved) event;
			return "id=" + e.getObjectId() + " at " + position(e.getPosition()) + " animation=" + e.getAnimationId();
		}
		if (event instanceof OverheadTextObserved)
		{
			OverheadTextObserved e = (OverheadTextObserved) event;
			return actor(e.getActor()) + " \"" + e.getText() + "\"";
		}
		if (event instanceof GameMessageObserved)
		{
			return "\"" + ((GameMessageObserved) event).getText() + "\"";
		}
		if (event instanceof VarbitObserved)
		{
			VarbitObserved e = (VarbitObserved) event;
			return e.getVarbitId() + "=" + e.getValue();
		}
		if (event instanceof WidgetTextObserved)
		{
			WidgetTextObserved e = (WidgetTextObserved) event;
			return e.getComponentId() + " \"" + e.getText() + "\"";
		}
		if (event instanceof AnimationObserved)
		{
			AnimationObserved e = (AnimationObserved) event;
			return actor(e.getActor()) + " " + e.getAnimationId();
		}
		if (event instanceof GraphicObserved)
		{
			GraphicObserved e = (GraphicObserved) event;
			return actor(e.getActor()) + " " + e.getGraphicId();
		}
		if (event instanceof GroundGraphicObserved)
		{
			GroundGraphicObserved e = (GroundGraphicObserved) event;
			return e.getGraphicId() + " at " + position(e.getPosition());
		}
		if (event instanceof ProjectileObserved)
		{
			ProjectileObserved e = (ProjectileObserved) event;
			return e.getProjectileId() + " -> " + (e.getTarget() == null ? "tile" : actor(e.getTarget())) + " lands " + e.getEndTick();
		}
		if (event instanceof HitsplatObserved)
		{
			HitsplatObserved e = (HitsplatObserved) event;
			return actor(e.getTarget()) + " " + e.getKind() + " " + e.getAmount() + " type=" + e.getRawType() + " mine=" + e.isMine();
		}
		if (event instanceof TickState)
		{
			TickState e = (TickState) event;
			return "prayers=" + new TreeSet<>(e.getPrayers()) + " hp=" + e.getHitpoints() + " prayer=" + e.getPrayerPoints()
				+ " spec=" + e.getSpecEnergy() + " run=" + e.getRunEnergy() + " weapon=" + e.getWeaponId()
				+ " target=" + (e.getYamaTarget() == null ? "-" : actor(e.getYamaTarget()))
				+ " self=" + position(e.getSelfPosition()) + " partner=" + position(e.getPartnerPosition());
		}
		if (event instanceof InventoryDelta)
		{
			InventoryDelta e = (InventoryDelta) event;
			return e.getItemId() + " \"" + e.getName() + "\" " + e.getChange();
		}
		if (event instanceof SuppliesSnapshot)
		{
			SuppliesSnapshot e = (SuppliesSnapshot) event;
			return e.getKind() + " " + e.getItems().size() + " items";
		}
		return "-";
	}

	static String actor(Actor actor)
	{
		if (actor == null)
		{
			return "-";
		}
		switch (actor.getKind())
		{
			case FLARE:
				return "FLARE(" + actor.getRef() + ")";
			case NPC:
				return "NPC(" + actor.getRef() + ")";
			case OTHER:
				return "OTHER";
			default:
				return actor.getKind().name();
		}
	}

	private static String player(String name, String partnerName)
	{
		return name != null && name.equals(partnerName) ? ActorKind.PARTNER.name() : ActorKind.OTHER.name();
	}

	private static String position(Position position)
	{
		return position == null ? "-" : "(" + position.getX() + "," + position.getY() + "," + position.getPlane() + ")";
	}
}
```

`src/main/java/com/yamareviewer/domain/text/ReportWriter.java`:

```java
package com.yamareviewer.domain.text;

import com.yamareviewer.domain.diagnosis.Candidate;
import com.yamareviewer.domain.diagnosis.CheckCandidates;
import com.yamareviewer.domain.diagnosis.ProblemReport;
import com.yamareviewer.domain.diagnosis.Versions;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.PlayerSeen;
import com.yamareviewer.domain.health.CheckOutcome;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.SectionKey;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

/** Writes a problem report as plain text (spec 9). Actors are SELF, PARTNER and OTHER; player names never appear. */
public final class ReportWriter
{
	public static final String TITLE_PREFIX = "Yama Reviewer report: ";
	public static final String EVENTS_HEADER = "Events (";
	public static final int EXCERPT_LINES = 200;
	static final int CANDIDATES_PER_ROLE = 10;

	private ReportWriter()
	{
	}

	public static String write(ProblemReport report)
	{
		StringBuilder out = new StringBuilder();
		out.append(TITLE_PREFIX).append(report.getHeadline()).append('\n');
		Versions versions = report.getVersions();
		out.append("Plugin ").append(versions.getPlugin())
			.append(", RuneLite ").append(versions.getRuneLite())
			.append(", game revision ").append(versions.getGame()).append('\n');
		KillLog log = report.getLog();
		if (log != null)
		{
			out.append(String.format("Kill %s: ended %s at tick %d, %d events (%d skipped), mode %s, %s, ids fingerprint %s, recorded by plugin %s",
				log.getHeader().getKillId(), log.endReason(), log.lastTick(), log.getEvents().size(), log.getSkippedEvents(),
				report.getMode() == null ? "unknown" : report.getMode().name(),
				report.getContract() == null ? "contract unknown" : report.getContract().displayName(),
				log.getHeader().getIdsFingerprint(), log.getHeader().getPluginVersion())).append('\n');
		}
		for (String note : report.getNotes())
		{
			out.append(note).append('\n');
		}
		if (!report.getOutcomes().isEmpty())
		{
			out.append("\nChecks\n");
			for (CheckOutcome outcome : report.getOutcomes())
			{
				out.append("  ").append(outcomeLine(outcome)).append('\n');
			}
		}
		if (!report.getCandidates().isEmpty())
		{
			out.append("\nCandidates (ids in this log that the plugin doesn't know, best fit first)\n");
			for (CheckCandidates candidates : report.getCandidates())
			{
				appendCandidates(out, candidates);
			}
		}
		if (log != null)
		{
			appendExcerpt(out, log, firstFailureTick(report.getOutcomes()));
		}
		return out.toString();
	}

	/** Everything before the event excerpt: what "Report a problem" puts in the issue. */
	public static String summary(String report)
	{
		int at = report.indexOf("\n" + EVENTS_HEADER);
		return at < 0 ? report : report.substring(0, at + 1);
	}

	private static String outcomeLine(CheckOutcome outcome)
	{
		if (outcome.failed())
		{
			String hides = outcome.getCheck().hides().stream().map(SectionKey::name).collect(Collectors.joining(", "));
			return "FAILED  " + outcome.name() + ": " + outcome.getResult().detail() + " (hides " + hides + ")";
		}
		if (outcome.skipped())
		{
			return "skipped " + outcome.name() + ": " + outcome.getResult().detail();
		}
		return "passed  " + outcome.name();
	}

	private static void appendCandidates(StringBuilder out, CheckCandidates candidates)
	{
		EnumSet<Role> roles = EnumSet.noneOf(Role.class);
		roles.addAll(candidates.getRoles());
		out.append("  ").append(candidates.getCheckName()).append(": ")
			.append(roles.isEmpty() ? "no report roles" : roles.stream().map(Role::name).collect(Collectors.joining(", ")))
			.append('\n');
		for (Role role : roles)
		{
			List<Candidate> ofRole = candidates.getCandidates().stream()
				.filter(candidate -> candidate.getRole() == role)
				.limit(CANDIDATES_PER_ROLE)
				.collect(Collectors.toList());
			if (ofRole.isEmpty())
			{
				out.append("    ").append(role.name()).append(": none found").append(noneFoundNote(role.kind())).append('\n');
				continue;
			}
			for (Candidate candidate : ofRole)
			{
				out.append("    ").append(candidateLine(candidate)).append('\n');
			}
		}
	}

	private static String noneFoundNote(RoleKind kind)
	{
		switch (kind)
		{
			case NPC:
				return " (unknown NPCs are only recorded in capture mode)";
			case ANIMATION:
			case GRAPHIC:
			case OVERHEAD:
				return "";
			default:
				return " (this kind is only recorded for ids that already have a role)";
		}
	}

	private static String candidateLine(Candidate candidate)
	{
		String what = candidate.getText() != null
			? "text \"" + candidate.getText() + "\""
			: candidate.getRole().kind().name().toLowerCase() + " " + candidate.getId();
		return String.format("%s on %s: %s x%d, ticks %d-%d, fit %.2f, %s", candidate.getRole().name(), candidate.getWhere(), what,
			candidate.getCount(), candidate.getFirstTick(), candidate.getLastTick(), candidate.getScore(), candidate.getEvidence());
	}

	private static int firstFailureTick(List<CheckOutcome> outcomes)
	{
		for (CheckOutcome outcome : outcomes)
		{
			if (outcome.failed() && outcome.getResult().tick().isPresent())
			{
				return outcome.getResult().tick().getAsInt();
			}
		}
		return 0;
	}

	private static void appendExcerpt(StringBuilder out, KillLog log, int aroundTick)
	{
		List<DomainEvent> events = log.getEvents();
		int centre = 0;
		while (centre < events.size() && events.get(centre).getTick() < aroundTick)
		{
			centre++;
		}
		int from = Math.max(0, Math.min(centre - EXCERPT_LINES / 2, events.size() - EXCERPT_LINES));
		int to = Math.min(events.size(), from + EXCERPT_LINES);
		String partner = log.eventsOf(PlayerSeen.class).stream().map(PlayerSeen::getName).findFirst().orElse(null);
		out.append('\n').append(EVENTS_HEADER)
			.append(String.format("lines %d-%d of %d events, around tick %d; actors are SELF, PARTNER, OTHER, YAMA, JUDGE, FLARE(index), NPC(id))",
				from + 1, to, events.size(), aroundTick))
			.append('\n');
		for (int i = from; i < to; i++)
		{
			out.append("  ").append(EventLine.of(events.get(i), partner)).append('\n');
		}
	}
}
```

`src/main/java/com/yamareviewer/domain/text/IssueLink.java`:

```java
package com.yamareviewer.domain.text;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The prefilled GitHub "new issue" URL for a report (spec 9): the summary only, under 6,000 characters, and a
 * request to attach the file. The whole URL is kept under 8,000 characters so browsers and GitHub accept it.
 */
public final class IssueLink
{
	public static final int MAX_BODY_CHARS = 6_000;
	static final int MAX_URL_CHARS = 8_000;
	static final String ATTACH_NOTE = "\n\nThe full report, including the event excerpt, was copied to your clipboard when you clicked "
		+ "\"Report a problem\": paste it here, or attach the newest file from .runelite/plugin-data/yama-reviewer/reports/.";
	private static final String TRUNCATED = "\n[truncated]";
	private static final int TRIM_STEP = 500;

	private IssueLink()
	{
	}

	public static String url(String repositoryUrl, String report)
	{
		String title = title(report);
		String summary = ReportWriter.summary(report).trim();
		int keep = Math.min(summary.length(), MAX_BODY_CHARS - 1 - ATTACH_NOTE.length() - TRUNCATED.length());
		String url = build(repositoryUrl, title, summary.substring(0, keep) + (keep < summary.length() ? TRUNCATED : "") + ATTACH_NOTE);
		while (url.length() > MAX_URL_CHARS && keep > 0)
		{
			keep = Math.max(0, keep - TRIM_STEP);
			url = build(repositoryUrl, title, summary.substring(0, keep) + TRUNCATED + ATTACH_NOTE);
		}
		return url;
	}

	/** The first line of the report without the "Yama Reviewer report: " prefix. */
	static String title(String report)
	{
		int end = report.indexOf('\n');
		String first = end < 0 ? report : report.substring(0, end);
		return first.startsWith(ReportWriter.TITLE_PREFIX) ? first.substring(ReportWriter.TITLE_PREFIX.length()) : first;
	}

	private static String build(String repositoryUrl, String title, String body)
	{
		return repositoryUrl + "/issues/new?title=" + encode(title) + "&body=" + encode(body);
	}

	private static String encode(String text)
	{
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.text.ReportWriterTest' --tests 'com.yamareviewer.domain.text.EventLineTest' --tests 'com.yamareviewer.domain.text.IssueLinkTest'`
Expected: PASS (10 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/diagnosis src/main/java/com/yamareviewer/domain/text src/test/java/com/yamareviewer/domain/text
git commit -m "feat: write problem reports without player names and build the issue link"
```

---

### Task 9: Report storage and the problem reporter

**Files:**
- Create: `src/main/java/com/yamareviewer/application/port/ReportRepository.java`
- Create: `src/main/java/com/yamareviewer/adapter/persistence/FileReportRepository.java`
- Create: `src/main/java/com/yamareviewer/application/handler/ProblemReporter.java`
- Test: `src/test/java/com/yamareviewer/adapter/persistence/FileReportRepositoryTest.java`, `src/test/java/com/yamareviewer/application/handler/ProblemReporterTest.java`

**Interfaces:**
- Consumes: `FileStore`, `InMemoryFileStore` (Part 1); `CandidateFinder`, `ProblemReport`, `ReportWriter`, `CheckOutcome` (Tasks 7–8); `ProjectionContext`, `Sections.MODE`, `Sections.CONTRACT`.
- Produces: `ReportRepository { void save(String fileName, String text) throws IOException; Optional<String> latest() throws IOException; }`; `FileReportRepository(FileStore)` writing `reports/<fileName>` and keeping the newest 50; `ProblemReporter(ReportRepository reports, IdRegistry ids, Supplier<Versions> versions, Clock clock)` with `Optional<String> reportFailedChecks(KillLog, ProjectionContext, List<CheckOutcome>)` and `Optional<String> reportUnknownYama(int npcId)` (both never throw; return the file name written), file names `yyyyMMdd-HHmmss-SSS.txt` in the clock's zone. Executor thread only.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/adapter/persistence/FileReportRepositoryTest.java`:

```java
package com.yamareviewer.adapter.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FileReportRepositoryTest
{
	private final InMemoryFileStore files = new InMemoryFileStore();
	private final FileReportRepository repository = new FileReportRepository(files);

	@Test
	public void savesUnderReportsAndReadsTheNewest() throws IOException
	{
		repository.save("20260924-153012-000.txt", "first");
		repository.save("20260924-160000-000.txt", "second");

		assertEquals(Optional.of("second"), repository.latest());
		assertEquals(List.of("20260924-153012-000.txt", "20260924-160000-000.txt"), files.list("reports"));
		assertEquals("first", new String(files.read("reports/20260924-153012-000.txt"), StandardCharsets.UTF_8));
	}

	@Test
	public void emptyWhenThereAreNoReports() throws IOException
	{
		assertEquals(Optional.empty(), repository.latest());
	}

	@Test
	public void keepsOnlyTheNewestReports() throws IOException
	{
		FileReportRepository small = new FileReportRepository(files, 2);

		small.save("20260924-100000-000.txt", "a");
		small.save("20260924-110000-000.txt", "b");
		small.save("20260924-120000-000.txt", "c");

		assertEquals(List.of("20260924-110000-000.txt", "20260924-120000-000.txt"), files.list("reports"));
		assertEquals(Optional.of("c"), small.latest());
	}

	@Test
	public void ignoresFilesThatAreNotReports() throws IOException
	{
		files.write("reports/notes.md", "x".getBytes(StandardCharsets.UTF_8));
		repository.save("20260924-100000-000.txt", "a");
		files.write("reports/zzz.tmp", "y".getBytes(StandardCharsets.UTF_8));

		assertEquals(Optional.of("a"), repository.latest());
	}
}
```

`src/test/java/com/yamareviewer/application/handler/ProblemReporterTest.java`:

```java
package com.yamareviewer.application.handler;

import com.yamareviewer.application.port.ReportRepository;
import com.yamareviewer.domain.diagnosis.Versions;
import com.yamareviewer.domain.health.CheckOutcome;
import com.yamareviewer.domain.health.CheckResult;
import com.yamareviewer.domain.health.P3AttackCountCheck;
import com.yamareviewer.domain.health.PhaseOrderCheck;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.TestIds;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ProblemReporterTest
{
	private final FakeReports reports = new FakeReports();
	private final Clock clock = Clock.fixed(Instant.parse("2026-09-24T15:30:12.345Z"), ZoneOffset.UTC);
	private final ProblemReporter reporter = new ProblemReporter(reports, TestIds.registry(),
		() -> new Versions("0.1.0", "1.11.5", "232"), clock);

	@Test
	public void writesATimestampedReportWithCandidatesForEachFailedCheck()
	{
		KillLog kill = Fights.healthySolo();
		ProjectionContext context = Fights.context(kill);
		List<CheckOutcome> outcomes = List.of(
			new CheckOutcome(new PhaseOrderCheck(), CheckResult.fail("phases seen: P1", 40)),
			new CheckOutcome(new P3AttackCountCheck(), CheckResult.pass()));

		Optional<String> written = reporter.reportFailedChecks(kill, context, outcomes);

		assertEquals(Optional.of("20260924-153012-345.txt"), written);
		String text = reports.saved.get("20260924-153012-345.txt");
		assertTrue(text, text.startsWith("Yama Reviewer report: health checks failed (PhaseOrder)\nPlugin 0.1.0, RuneLite 1.11.5, game revision 232\n"));
		assertTrue(text, text.contains(", mode SOLO, No contract, "));
		assertTrue(text, text.contains("\n  FAILED  PhaseOrder: phases seen: P1"));
		assertTrue(text, text.contains("\n  passed  P3AttackCount\n"));
		assertTrue(text, text.contains("\n  PhaseOrder: JUDGE, PHASE_TRANSITION_TEXT, PHASE_VARBIT, PHASE_TRANSITION_GRAPHIC\n"));
		assertTrue(text, text.contains("\nEvents (lines "));
	}

	@Test
	public void theSelfCheckReportsEachNewIdOnceListingAllSeen()
	{
		assertEquals(Optional.of("20260924-153012-345.txt"), reporter.reportUnknownYama(14999));
		assertEquals(Optional.empty(), reporter.reportUnknownYama(14999));
		assertEquals(Optional.of("20260924-153012-345.txt"), reporter.reportUnknownYama(15001));

		String text = reports.saved.get("20260924-153012-345.txt");
		assertTrue(text, text.startsWith("Yama Reviewer report: unknown Yama NPC id [14999, 15001]\n"));
		assertTrue(text, text.contains("Built-in YAMA ids: [" + TestIds.id(com.yamareviewer.domain.ids.Role.YAMA) + "]\n"));
		assertEquals(2, reports.writes);
	}

	@Test
	public void aWriteFailureIsSwallowed()
	{
		reports.fail = true;

		assertEquals(Optional.empty(), reporter.reportUnknownYama(14999));
		assertEquals(Optional.empty(), reporter.reportFailedChecks(Fights.healthySolo(), Fights.context(Fights.healthySolo()),
			List.of(new CheckOutcome(new PhaseOrderCheck(), CheckResult.fail("x")))));
	}

	private static final class FakeReports implements ReportRepository
	{
		private final Map<String, String> saved = new LinkedHashMap<>();
		private int writes;
		private boolean fail;

		@Override
		public void save(String fileName, String text) throws IOException
		{
			if (fail)
			{
				throw new IOException("disk full");
			}
			writes++;
			saved.put(fileName, text);
		}

		@Override
		public Optional<String> latest()
		{
			return saved.values().stream().reduce((first, second) -> second);
		}
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.persistence.FileReportRepositoryTest' --tests 'com.yamareviewer.application.handler.ProblemReporterTest'`
Expected: FAIL — `cannot find symbol` for `ReportRepository`, `FileReportRepository`, `ProblemReporter`.

- [ ] **Step 3: Write the port and the adapter**

`src/main/java/com/yamareviewer/application/port/ReportRepository.java`:

```java
package com.yamareviewer.application.port;

import java.io.IOException;
import java.util.Optional;

/** Stores problem reports (spec 9). File names sort chronologically. */
public interface ReportRepository
{
	void save(String fileName, String text) throws IOException;

	/** The text of the newest report, empty when there is none. */
	Optional<String> latest() throws IOException;
}
```

`src/main/java/com/yamareviewer/adapter/persistence/FileReportRepository.java`:

```java
package com.yamareviewer.adapter.persistence;

import com.yamareviewer.application.port.ReportRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** reports/<timestamp>.txt under the plugin directory; only the newest reports are kept. */
public final class FileReportRepository implements ReportRepository
{
	static final String DIR = "reports";
	static final String SUFFIX = ".txt";
	/** A broken id writes one report per kill; this keeps the folder small. */
	static final int KEPT = 50;

	private final FileStore files;
	private final int kept;

	public FileReportRepository(FileStore files)
	{
		this(files, KEPT);
	}

	FileReportRepository(FileStore files, int kept)
	{
		this.files = files;
		this.kept = kept;
	}

	@Override
	public void save(String fileName, String text) throws IOException
	{
		files.write(DIR + "/" + fileName, text.getBytes(StandardCharsets.UTF_8));
		List<String> names = reportNames();
		for (int i = 0; i < names.size() - kept; i++)
		{
			files.delete(DIR + "/" + names.get(i));
		}
	}

	@Override
	public Optional<String> latest() throws IOException
	{
		List<String> names = reportNames();
		if (names.isEmpty())
		{
			return Optional.empty();
		}
		return Optional.of(new String(files.read(DIR + "/" + names.get(names.size() - 1)), StandardCharsets.UTF_8));
	}

	/** Ascending, which is chronological for the timestamp names ProblemReporter uses. */
	private List<String> reportNames() throws IOException
	{
		return files.list(DIR).stream().filter(name -> name.endsWith(SUFFIX)).collect(Collectors.toList());
	}
}
```

- [ ] **Step 4: Write the reporter**

`src/main/java/com/yamareviewer/application/handler/ProblemReporter.java`:

```java
package com.yamareviewer.application.handler;

import com.yamareviewer.application.port.ReportRepository;
import com.yamareviewer.domain.diagnosis.Candidate;
import com.yamareviewer.domain.diagnosis.CandidateFinder;
import com.yamareviewer.domain.diagnosis.CheckCandidates;
import com.yamareviewer.domain.diagnosis.ProblemReport;
import com.yamareviewer.domain.diagnosis.Versions;
import com.yamareviewer.domain.health.CheckOutcome;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.text.ReportWriter;
import java.io.IOException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns failed checks and the recording self-check into report files (spec 9). Every method catches its own
 * exceptions (spec 4.5, 11) and returns the file name it wrote. Call it on the plugin executor only.
 */
@Slf4j
public final class ProblemReporter
{
	static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

	private final ReportRepository reports;
	private final IdRegistry ids;
	private final Supplier<Versions> versions;
	private final Clock clock;
	private final Set<Integer> unknownYamaIds = new LinkedHashSet<>();

	public ProblemReporter(ReportRepository reports, IdRegistry ids, Supplier<Versions> versions, Clock clock)
	{
		this.reports = reports;
		this.ids = ids;
		this.versions = versions;
		this.clock = clock;
	}

	public Optional<String> reportFailedChecks(KillLog log, ProjectionContext context, List<CheckOutcome> outcomes)
	{
		try
		{
			List<CheckCandidates> candidates = new ArrayList<>();
			for (CheckOutcome outcome : outcomes)
			{
				if (outcome.failed())
				{
					Set<Role> roles = outcome.getCheck().reportRoles();
					candidates.add(new CheckCandidates(outcome.name(), roles, candidatesFor(log, roles)));
				}
			}
			ProblemReport report = ProblemReport.forFailedChecks(versions.get(), log,
				context.value(Sections.MODE).orElse(null), context.value(Sections.CONTRACT).orElse(null), outcomes, candidates);
			return write(report);
		}
		catch (RuntimeException e)
		{
			log.warn("Could not build the report for kill {}", log.getHeader().getKillId(), e);
			return Optional.empty();
		}
	}

	/** Spec 5.3: an NPC named "Yama" with an unknown id spawned while armed. One report per new id since startup. */
	public Optional<String> reportUnknownYama(int npcId)
	{
		if (!unknownYamaIds.add(npcId))
		{
			return Optional.empty();
		}
		try
		{
			return write(ProblemReport.forUnknownYama(versions.get(), Set.copyOf(unknownYamaIds), ids.ids(Role.YAMA)));
		}
		catch (RuntimeException e)
		{
			log.warn("Could not build the unknown-Yama report", e);
			return Optional.empty();
		}
	}

	private List<Candidate> candidatesFor(KillLog log, Set<Role> roles)
	{
		try
		{
			return CandidateFinder.find(log, ids, roles);
		}
		catch (RuntimeException e)
		{
			log.warn("Candidate finder failed for {}", roles, e);
			return List.of();
		}
	}

	private Optional<String> write(ProblemReport report)
	{
		String name = FILE_TIMESTAMP.withZone(clock.getZone()).format(clock.instant()) + ".txt";
		try
		{
			reports.save(name, ReportWriter.write(report));
			return Optional.of(name);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not write report {}", name, e);
			return Optional.empty();
		}
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.persistence.FileReportRepositoryTest' --tests 'com.yamareviewer.application.handler.ProblemReporterTest'`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/application/port/ReportRepository.java src/main/java/com/yamareviewer/application/handler/ProblemReporter.java src/main/java/com/yamareviewer/adapter/persistence/FileReportRepository.java src/test/java/com/yamareviewer/adapter/persistence/FileReportRepositoryTest.java src/test/java/com/yamareviewer/application/handler/ProblemReporterTest.java
git commit -m "feat: store problem reports under reports/ and write them from failed checks"
```

---

### Task 10: Checks in the kill handler, failed checks in the review

**Files:**
- Modify: `src/main/java/com/yamareviewer/domain/review/KillReview.java` (add `failedChecks`)
- Replace: `src/main/java/com/yamareviewer/application/handler/KillEndedHandler.java`
- Modify: `src/main/java/com/yamareviewer/domain/text/ReviewFormatter.java` (failed checks section)
- Modify: `src/test/java/com/yamareviewer/application/handler/KillEndedHandlerTest.java` (Part 2), the Part 2 silence replay test, `src/test/java/com/yamareviewer/tools/Replay.java` (Part 2)
- Test: `src/test/java/com/yamareviewer/domain/review/KillReviewFailedChecksTest.java`, `src/test/java/com/yamareviewer/application/handler/KillEndedHandlerChecksTest.java`, `src/test/java/com/yamareviewer/domain/text/ReviewFormatterFailedChecksTest.java`

**Interfaces:**
- Consumes: `HealthCheckRunner`, `HealthChecks` (Tasks 1, 6); `ProblemReporter` (Task 9); Part 2 handler dependencies.
- Produces: `KillReview.getFailedChecks()` (never null); `KillEndedHandler(ExecutorService executor, LogRepository logs, ReviewRepository reviews, ReviewBuilder builder, ReviewPublisher publisher, BooleanSupplier active, IntSupplier rawLogsKept, IntSupplier historySize, Supplier<ReviewSettings> settings, List<HealthCheck> checks, ProblemReporter reporter)`, the Part 2 constructor with two arguments appended; `ReviewFormatter` shows a "Health checks failed" section first when any check failed.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/review/KillReviewFailedChecksTest.java`:

```java
package com.yamareviewer.domain.review;

import com.google.gson.Gson;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class KillReviewFailedChecksTest
{
	@Test
	public void failedChecksNeverReadNull()
	{
		assertEquals(List.of(), KillReview.builder().build().getFailedChecks());
		assertEquals(List.of("PhaseOrder"), KillReview.builder().failedChecks(List.of("PhaseOrder")).build().getFailedChecks());
	}

	@Test
	public void reviewsStoredBeforePart4LoadWithNoFailedChecks()
	{
		KillReview review = new Gson().fromJson("{\"reviewSchemaVersion\":1,\"killId\":\"k\"}", KillReview.class);

		assertEquals(List.of(), review.getFailedChecks());
	}
}
```

`src/test/java/com/yamareviewer/application/handler/KillEndedHandlerChecksTest.java`:

```java
package com.yamareviewer.application.handler;

import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.application.port.ReportRepository;
import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.diagnosis.Versions;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.health.CheckResult;
import com.yamareviewer.domain.health.HealthCheck;
import com.yamareviewer.domain.health.HealthChecks;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.Projections;
import com.yamareviewer.domain.projection.ReviewBuilder;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Test;

public class KillEndedHandlerChecksTest
{
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final FakeLogs logs = new FakeLogs();
	private final FakeReviews reviews = new FakeReviews();
	private final FakePublisher publisher = new FakePublisher();
	private final FakeReports reports = new FakeReports();

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private KillEndedHandler handler(List<HealthCheck> checks)
	{
		ReviewBuilder builder = new ReviewBuilder(Projections.standard(), TestIds.registry(), Rules.DEFAULT);
		ProblemReporter reporter = new ProblemReporter(reports, TestIds.registry(), () -> new Versions("0.1.0", "1.11.5", "232"),
			Clock.fixed(Instant.parse("2026-09-24T15:30:12.345Z"), ZoneOffset.UTC));
		return new KillEndedHandler(executor, logs, reviews, builder, publisher, () -> true, () -> 20, () -> 50,
			() -> ReviewSettings.DEFAULT, checks, reporter);
	}

	@Test
	public void aHealthyKillIsCompleteAndWritesNoReport()
	{
		handler(HealthChecks.standard()).handle(Fights.healthySolo());

		KillReview review = publisher.published.get(0);
		assertEquals(ReviewStatus.COMPLETE, review.getStatus());
		assertEquals(List.of(), review.getFailedChecks());
		assertTrue(reports.saved.isEmpty());
		assertEquals(1, reviews.saved.size());
	}

	@Test
	public void aFailedCheckHidesItsSectionsNamesItselfAndWritesAReport()
	{
		handler(List.of(new AlwaysFailing())).handle(Fights.healthySolo());

		KillReview review = publisher.published.get(0);
		assertEquals(Optional.of(HiddenReason.HEALTH_CHECK_FAILED), review.getPhases().hiddenReason());
		assertEquals(List.of("AlwaysFailing"), review.getFailedChecks());
		assertEquals(ReviewStatus.INCOMPLETE, review.getStatus());
		assertEquals(List.of("AlwaysFailing"), reviews.saved.get(0).getFailedChecks());
		assertEquals(1, reports.saved.size());
		String text = reports.saved.values().iterator().next();
		assertTrue(text, text.contains("health checks failed (AlwaysFailing)"));
		assertTrue(text, text.contains("Plugin 0.1.0, RuneLite 1.11.5, game revision 232"));
	}

	@Test
	public void anEarlyDeathWritesNoReport()
	{
		KillLogBuilder kill = Fights.solo().ticks(40);
		Fights.judgePhase(kill).ticks(10);

		handler(HealthChecks.standard()).handle(Fights.finish(kill, EndReason.PLAYER_DIED));

		assertEquals(List.of(), publisher.published.get(0).getFailedChecks());
		assertEquals(ReviewStatus.COMPLETE, publisher.published.get(0).getStatus());
		assertTrue(reports.saved.isEmpty());
	}

	@Test
	public void aReportWriteFailureStillPublishesTheReview()
	{
		reports.fail = true;

		handler(List.of(new AlwaysFailing())).handle(Fights.healthySolo());

		assertEquals(1, publisher.published.size());
		assertEquals(ReviewStatus.INCOMPLETE, publisher.published.get(0).getStatus());
	}

	private static final class AlwaysFailing implements HealthCheck
	{
		@Override
		public String name()
		{
			return "AlwaysFailing";
		}

		@Override
		public Set<Role> reportRoles()
		{
			return Set.of(Role.JUDGE);
		}

		@Override
		public List<SectionKey<?>> hides()
		{
			return List.of(Sections.PHASES);
		}

		@Override
		public boolean applies(ContractRules rules)
		{
			return true;
		}

		@Override
		public CheckResult check(KillLog log, ProjectionContext context)
		{
			return CheckResult.fail("always", 0);
		}
	}

	private static final class FakeLogs implements LogRepository
	{
		@Override
		public void save(KillLog kill)
		{
		}

		@Override
		public List<KillLog> loadAll()
		{
			return List.of();
		}

		@Override
		public void prune(int keep)
		{
		}
	}

	private static final class FakeReviews implements ReviewRepository
	{
		private final List<KillReview> saved = new ArrayList<>();

		@Override
		public void save(KillReview review)
		{
			saved.add(review);
		}

		@Override
		public List<KillReview> loadAll()
		{
			return List.copyOf(saved);
		}

		@Override
		public void prune(int keepPerKey)
		{
		}
	}

	private static final class FakePublisher implements ReviewPublisher
	{
		private final List<KillReview> published = new ArrayList<>();

		@Override
		public void publish(KillReview review, HistoryIndex history)
		{
			published.add(review);
		}

		@Override
		public void showHistory(HistoryIndex history)
		{
		}
	}

	private static final class FakeReports implements ReportRepository
	{
		private final Map<String, String> saved = new LinkedHashMap<>();
		private boolean fail;

		@Override
		public void save(String fileName, String text) throws IOException
		{
			if (fail)
			{
				throw new IOException("disk full");
			}
			saved.put(fileName, text);
		}

		@Override
		public Optional<String> latest()
		{
			return saved.values().stream().reduce((first, second) -> second);
		}
	}
}
```

`src/test/java/com/yamareviewer/domain/text/ReviewFormatterFailedChecksTest.java`:

```java
package com.yamareviewer.domain.text;

import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.KillReviewAssembler;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.testing.Fights;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ReviewFormatterFailedChecksTest
{
	private static KillReview review(List<String> failedChecks)
	{
		KillLog log = Fights.healthySolo();
		return KillReviewAssembler.assemble(log, Fights.context(log)).toBuilder().failedChecks(failedChecks).build();
	}

	@Test
	public void failedChecksComeFirst()
	{
		ReviewView view = ReviewFormatter.format(review(List.of("PhaseOrder", "Alternation")));

		ViewSection first = view.getSections().get(0);
		assertEquals("Health checks failed", first.getTitle());
		assertNull(first.getHiddenReason());
		assertTrue(first.getLines().toString(), first.getLines().contains("- PhaseOrder"));
		assertTrue(first.getLines().toString(), first.getLines().contains("- Alternation"));
		assertTrue(first.getLines().toString(), first.getLines().get(first.getLines().size() - 1).contains("Report a problem"));
	}

	@Test
	public void noFailedChecksAddNoSection()
	{
		ReviewView view = ReviewFormatter.format(review(List.of()));

		assertNotEquals("Health checks failed", view.getSections().get(0).getTitle());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.review.KillReviewFailedChecksTest' --tests 'com.yamareviewer.application.handler.KillEndedHandlerChecksTest' --tests 'com.yamareviewer.domain.text.ReviewFormatterFailedChecksTest'`
Expected: FAIL — `cannot find symbol: method failedChecks(...)` and the eleven-argument `KillEndedHandler` constructor.

- [ ] **Step 3: Add `failedChecks` to `KillReview`**

In `src/main/java/com/yamareviewer/domain/review/KillReview.java`, after the last `Section<...>` field (Part 3's `specs`), add:

```java
	/** Names of the health checks that failed (Part 4); empty for reviews stored before Part 4. */
	List<String> failedChecks;

	public List<String> getFailedChecks()
	{
		return failedChecks == null ? List.of() : failedChecks;
	}
```

`java.util.List` is already imported (for `allSections()`). Leave `allSections()` and `withRecomputedStatus()` as they are: the hidden sections already make the review `INCOMPLETE`.

- [ ] **Step 4: Replace `KillEndedHandler`**

Replace the whole of `src/main/java/com/yamareviewer/application/handler/KillEndedHandler.java` with:

```java
package com.yamareviewer.application.handler;

import com.yamareviewer.application.command.KillEndedListener;
import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.health.CheckOutcome;
import com.yamareviewer.domain.health.HealthCheck;
import com.yamareviewer.domain.health.HealthCheckRunner;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Everything that happens after a kill, on the plugin's own executor (spec 4.3): store the raw log, build the
 * review, run the health checks, write a report when one fails, store the review, rebuild the history and
 * publish if the plugin is still active. Storage failures are logged and the review is still published (spec 11).
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
	private final List<HealthCheck> checks;
	private final ProblemReporter reporter;
	private volatile Future<?> pending = CompletableFuture.completedFuture(null);

	public KillEndedHandler(ExecutorService executor, LogRepository logs, ReviewRepository reviews, ReviewBuilder builder,
		ReviewPublisher publisher, BooleanSupplier active, IntSupplier rawLogsKept, IntSupplier historySize,
		Supplier<ReviewSettings> settings, List<HealthCheck> checks, ProblemReporter reporter)
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
		this.checks = checks;
		this.reporter = reporter;
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
			List<CheckOutcome> outcomes = HealthCheckRunner.apply(kill, context, checks);
			review = KillReviewAssembler.assemble(kill, context).toBuilder()
				.failedChecks(CheckOutcome.failedNames(outcomes))
				.build()
				.withRecomputedStatus();
			if (CheckOutcome.anyFailed(outcomes))
			{
				Optional<String> report = reporter.reportFailedChecks(kill, context, outcomes);
				log.info("Health checks failed for kill {}: {}; report {}", killId, review.getFailedChecks(), report.orElse("not written"));
			}
		}
		catch (RuntimeException e)
		{
			log.warn("Could not review kill {}", killId, e);
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
		HistoryIndex index;
		try
		{
			index = HistoryProjector.index(reviews.loadAll(), keep);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not load the history; showing this kill only", e);
			index = HistoryProjector.index(List.of(review), keep);
		}
		if (active.getAsBoolean())
		{
			try
			{
				publisher.publish(review, index);
			}
			catch (RuntimeException e)
			{
				log.warn("Could not publish review {}", killId, e);
			}
		}
	}
}
```

Keep any private helper Part 2 added to this class only if a Part 2 test still calls it; the flow above is the whole contract of the brief.

- [ ] **Step 5: Update the Part 2 call sites**

In `src/test/java/com/yamareviewer/application/handler/KillEndedHandlerTest.java` (Part 2) and in the Part 2 silence replay test, every `new KillEndedHandler(executor, logs, reviews, builder, publisher, active, rawLogsKept, historySize, settings)` gets two arguments appended: `, List.of(), new ProblemReporter(fakeReports, TestIds.registry(), () -> new Versions("0.1.0", "test", "0"), Clock.systemUTC())`, where `fakeReports` is a `ReportRepository` that stores nothing:

```java
	private static final ReportRepository NO_REPORTS = new ReportRepository()
	{
		@Override
		public void save(String fileName, String text)
		{
		}

		@Override
		public Optional<String> latest()
		{
			return Optional.empty();
		}
	};
```

Add the imports `com.yamareviewer.application.port.ReportRepository`, `com.yamareviewer.domain.diagnosis.Versions`, `com.yamareviewer.testing.TestIds`, `java.time.Clock`, `java.util.List`, `java.util.Optional` where they are missing.

In `src/test/java/com/yamareviewer/tools/Replay.java` (Part 2), directly after the line that calls `builder.run(...)` (assumed field 9) insert:

```java
		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, HealthChecks.standard());
```

and after the review is printed, print the outcomes:

```java
		System.out.println();
		System.out.println("Health checks");
		for (CheckOutcome outcome : outcomes)
		{
			System.out.println("  " + outcome.name() + ": " + outcome.getResult());
		}
```

with the imports `com.yamareviewer.domain.health.CheckOutcome`, `com.yamareviewer.domain.health.HealthCheckRunner`, `com.yamareviewer.domain.health.HealthChecks`. The review `Replay` assembles is unchanged, so the Part 3 golden fixtures are unaffected.

- [ ] **Step 6: Show the failed checks in `ReviewFormatter`**

In `src/main/java/com/yamareviewer/domain/text/ReviewFormatter.java`, add this method:

```java
	/** Part 4: why numbers are missing, shown before every other section. */
	static ViewSection failedChecksSection(KillReview review)
	{
		List<String> lines = new ArrayList<>();
		lines.add("Some numbers are hidden because these checks failed:");
		for (String name : review.getFailedChecks())
		{
			lines.add("- " + name);
		}
		lines.add("Use \"Report a problem\" at the bottom of the panel to send the report.");
		return new ViewSection("Health checks failed", List.copyOf(lines), null);
	}
```

and, in `format(KillReview review)`, right before the `ReviewView` is constructed, insert:

```java
		if (!review.getFailedChecks().isEmpty())
		{
			sections.add(0, failedChecksSection(review));
		}
```

If Part 2 built `sections` with `List.of(...)` (immutable), change that line to `List<ViewSection> sections = new ArrayList<>(List.of(...));` and pass `List.copyOf(sections)` to the `ReviewView`. Add `import java.util.ArrayList;` if missing.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.review.*' --tests 'com.yamareviewer.application.handler.*' --tests 'com.yamareviewer.domain.text.*' --tests 'com.yamareviewer.tools.*'`
Expected: PASS (Part 2's tests plus 2 in `KillReviewFailedChecksTest`, 4 in `KillEndedHandlerChecksTest`, 2 in `ReviewFormatterFailedChecksTest`).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/review/KillReview.java src/main/java/com/yamareviewer/application/handler/KillEndedHandler.java src/main/java/com/yamareviewer/domain/text/ReviewFormatter.java src/test/java/com/yamareviewer
git commit -m "feat: run the health checks after every kill, hide failed sections and report failures"
```

---

### Task 11: Recording self-check for an unknown Yama

**Files:**
- Modify: `src/main/java/com/yamareviewer/domain/ids/Role.java` (add `YAMA_SITTING`)
- Modify: `src/main/java/com/yamareviewer/adapter/ids/BuiltInIds.java` (map it)
- Modify: `src/main/java/com/yamareviewer/adapter/recording/GameEventListener.java` (callback)
- Modify: `src/test/java/com/yamareviewer/adapter/recording/GameEventListenerTest.java` (Part 1), the Part 2 silence replay test
- Test: `src/test/java/com/yamareviewer/adapter/ids/BuiltInIdsTest.java` (one test added)

**Interfaces:**
- Consumes: `KillSession.state()`, `SessionState.ARMED`, `IdRegistry.roleOf`, `Text.removeTags` (Part 1); `NpcID.YAMA_THRONE_OCCUPIED` (gameval).
- Produces: `Role.YAMA_SITTING` (kind NPC): Yama on his throne, who is also named "Yama" but is not a fight; `GameEventListener(Client, IdRegistry, KillSession, EventTranslator, TickSampler, PositionReader, NpcUtil, Supplier<Future<?>> pendingWrite, Consumer<Integer> unknownYama)`, the Part 1 constructor with the callback appended. The callback is called on the client thread, once per unknown id per login, when an NPC named "Yama" spawns (or changes into) an id with no NPC role while the session is armed.

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/yamareviewer/adapter/recording/GameEventListenerTest.java` (Part 1) make these edits:

Add the field next to `ended`:

```java
	private final List<Integer> unknownYamaIds = new ArrayList<>();
```

In the `ids` registry map add the entry `Role.YAMA_SITTING, Set.of(124)` (the map becomes `Map.of(Role.YAMA, Set.of(100), Role.YAMAS_DOMAIN, Set.of(6045), Role.VOICE_OF_YAMA, Set.of(200), Role.CONTRACT_ITEM_BLOODIED_BLOWS, Set.of(700), Role.YAMA_SITTING, Set.of(124))`).

In `setUp`, append the argument `unknownYamaIds::add` to the `new GameEventListener(...)` call, after `() -> CompletableFuture.completedFuture(null)`.

Add the helper and the tests:

```java
	private static NPC named(int id, String name)
	{
		NPC npc = mock(NPC.class);
		when(npc.getId()).thenReturn(id);
		when(npc.getName()).thenReturn(name);
		return npc;
	}

	@Test
	public void anUnknownNpcNamedYamaWhileArmedIsReported()
	{
		listener.onGameTick(new GameTick());

		listener.onNpcSpawned(new NpcSpawned(named(999, "<col=ff0000>Yama</col>")));

		assertEquals(List.of(999), unknownYamaIds);
		assertFalse(session.isFighting());
	}

	@Test
	public void theSittingYamaIsNotReported()
	{
		listener.onGameTick(new GameTick());

		listener.onNpcSpawned(new NpcSpawned(named(124, "Yama")));

		assertTrue(unknownYamaIds.isEmpty());
	}

	@Test
	public void otherUnknownNpcsAreNotReported()
	{
		listener.onGameTick(new GameTick());

		listener.onNpcSpawned(new NpcSpawned(named(999, "Judge of Yama")));
		listener.onNpcSpawned(new NpcSpawned(named(998, null)));

		assertTrue(unknownYamaIds.isEmpty());
	}

	@Test
	public void anUnknownYamaOutsideTheDomainOrDuringAFightIsNotReported()
	{
		when(positions.regionId(any())).thenReturn(1234);
		listener.onNpcSpawned(new NpcSpawned(named(999, "Yama")));
		assertTrue(unknownYamaIds.isEmpty());

		when(positions.regionId(any())).thenReturn(6045);
		listener.onNpcSpawned(new NpcSpawned(yama));
		listener.onNpcSpawned(new NpcSpawned(named(999, "Yama")));
		assertTrue(unknownYamaIds.isEmpty());
	}

	@Test
	public void eachUnknownIdIsReportedOncePerLogin()
	{
		listener.onGameTick(new GameTick());
		listener.onNpcSpawned(new NpcSpawned(named(999, "Yama")));
		listener.onNpcSpawned(new NpcSpawned(named(999, "Yama")));
		assertEquals(List.of(999), unknownYamaIds);

		GameStateChanged loggedOut = new GameStateChanged();
		loggedOut.setGameState(GameState.LOGIN_SCREEN);
		listener.onGameStateChanged(loggedOut);
		listener.onGameTick(new GameTick());
		listener.onNpcSpawned(new NpcSpawned(named(999, "Yama")));

		assertEquals(List.of(999, 999), unknownYamaIds);
	}
```

Add the imports `java.util.ArrayList` and `static org.junit.Assert.assertFalse` if they are missing (`assertTrue`, `assertEquals`, `any`, `mock`, `when`, `NPC`, `GameTick`, `GameStateChanged`, `GameState`, `NpcSpawned` are already imported by Part 1).

In `src/test/java/com/yamareviewer/adapter/ids/BuiltInIdsTest.java` add:

```java
	@Test
	public void theSittingYamaIsKnownSoTheSelfCheckIgnoresHim()
	{
		assertEquals(Set.of(net.runelite.api.gameval.NpcID.YAMA_THRONE_OCCUPIED), ids.ids(Role.YAMA_SITTING));
	}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.recording.GameEventListenerTest' --tests 'com.yamareviewer.adapter.ids.BuiltInIdsTest'`
Expected: FAIL — `cannot find symbol: variable YAMA_SITTING` and no nine-argument `GameEventListener` constructor.

- [ ] **Step 3: Add the role and its id**

In `src/main/java/com/yamareviewer/domain/ids/Role.java`, directly after `YAMA(RoleKind.NPC),` add:

```java
	/** Yama on his throne before and after a fight: also named "Yama", never a fight (spec 5.3 self-check). */
	YAMA_SITTING(RoleKind.NPC),
```

In `src/main/java/com/yamareviewer/adapter/ids/BuiltInIds.java`, directly after `ids.put(Role.YAMA, Set.of(NpcID.YAMA));` add:

```java
		ids.put(Role.YAMA_SITTING, Set.of(NpcID.YAMA_THRONE_OCCUPIED));
```

`ActorResolver` now resolves the sitting Yama to `Actor.npc(124)`, which the recorder only keeps while a fight runs; that is harmless.

- [ ] **Step 4: Add the callback to `GameEventListener`**

In `src/main/java/com/yamareviewer/adapter/recording/GameEventListener.java` (Part 1):

Add the imports `java.util.HashSet` and `java.util.function.Consumer`.

Add the constant and fields after `private NPC yama;`:

```java
	/** The NPC name that marks Yama in any form (the combat NPC and the one on the throne). */
	private static final String YAMA_NAME = "Yama";

	private final Consumer<Integer> unknownYama;
	private final Set<Integer> reportedYamaIds = new HashSet<>();
```

Replace the constructor with:

```java
	public GameEventListener(Client client, IdRegistry ids, KillSession session, EventTranslator translator,
		TickSampler sampler, PositionReader positions, NpcUtil npcUtil, Supplier<Future<?>> pendingWrite,
		Consumer<Integer> unknownYama)
	{
		this.client = client;
		this.ids = ids;
		this.session = session;
		this.translator = translator;
		this.sampler = sampler;
		this.positions = positions;
		this.npcUtil = npcUtil;
		this.pendingWrite = pendingWrite;
		this.unknownYama = unknownYama;
	}
```

Replace `onNpcSpawned` and `onNpcChanged` with:

```java
	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		if (ids.is(Role.YAMA, npc.getId()))
		{
			yama = npc;
			startFightIfArmed();
		}
		else
		{
			selfCheck(npc);
		}
		record(translator.npcSpawned(npc));
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		if (!ids.is(Role.YAMA, event.getNpc().getId()))
		{
			selfCheck(event.getNpc());
		}
		record(translator.npcChanged(event));
	}
```

In `onGameStateChanged`, inside the `if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)` block, add `reportedYamaIds.clear();`.

Add the private method:

```java
	/**
	 * Spec 5.3: an NPC named "Yama" with an id that has no NPC role, while armed, means the combat Yama was
	 * renumbered and no kill can be recorded. Reported once per id per login; the report is written elsewhere.
	 */
	private void selfCheck(NPC npc)
	{
		String name = npc.getName();
		if (name == null || !YAMA_NAME.equals(Text.removeTags(name)) || ids.roleOf(RoleKind.NPC, npc.getId()).isPresent())
		{
			return;
		}
		session.updateRegion(inYamasDomain());
		if (session.state() == SessionState.ARMED && reportedYamaIds.add(npc.getId()))
		{
			log.debug("Unknown NPC named Yama with id {} while armed", npc.getId());
			unknownYama.accept(npc.getId());
		}
	}
```

Add the imports `com.yamareviewer.domain.ids.RoleKind` and `lombok.extern.slf4j.Slf4j`, and annotate the class with `@Slf4j`.

In the Part 2 silence replay test, append `id -> { }` to its `new GameEventListener(...)` call.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.recording.*' --tests 'com.yamareviewer.adapter.ids.*'`
Expected: PASS (Part 1's recording and id tests plus 5 new listener tests and 1 new id test).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/ids/Role.java src/main/java/com/yamareviewer/adapter/ids/BuiltInIds.java src/main/java/com/yamareviewer/adapter/recording/GameEventListener.java src/test/java/com/yamareviewer/adapter
git commit -m "feat: report an unknown NPC named Yama while armed, ignoring the one on the throne"
```

---

### Task 12: "Report a problem" and the plugin wiring

**Files:**
- Create: `src/main/java/com/yamareviewer/ProjectLinks.java`
- Create: `src/main/java/com/yamareviewer/adapter/ui/ReportProblemFooter.java`
- Modify: `src/main/java/com/yamareviewer/adapter/ui/ReviewPanel.java` (Part 2: footer slot, refresh on show and activate)
- Modify: `src/main/java/com/yamareviewer/YamaReviewerPlugin.java` (Part 2 wiring)
- Modify: `src/test/java/com/yamareviewer/ArchitectureTest.java` (one rule)
- Test: `src/test/java/com/yamareviewer/adapter/ui/ReportProblemFooterTest.java`

**Interfaces:**
- Consumes: `ReportRepository` (Task 9), `IssueLink` (Task 8), `ProblemReporter`, `HealthChecks`, `FileReportRepository`, `Versions`; RuneLite `LinkBrowser.browse(String)`, `RuneLiteProperties.getVersion()`, `Client.getRevision()`, `ColorScheme`, `PluginPanel.onActivate()`.
- Produces: `ProjectLinks.REPOSITORY_URL`; `ReportProblemFooter(ExecutorService executor, ReportRepository reports, String repositoryUrl)` (a `JPanel`) with `refresh()` (safe from any thread) and package-private `isReportAvailable()`; `ReviewPanel.attachFooter(ReportProblemFooter)`; the wired plugin.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/adapter/ui/ReportProblemFooterTest.java`:

```java
package com.yamareviewer.adapter.ui;

import com.yamareviewer.application.port.ReportRepository;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Test;

public class ReportProblemFooterTest
{
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final FakeReports reports = new FakeReports();
	private final ReportProblemFooter footer = new ReportProblemFooter(executor, reports, "https://github.com/stefanws0/Yama-Mia-");

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private void refreshAndWait() throws Exception
	{
		footer.refresh();
		executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
		SwingUtilities.invokeAndWait(() -> { });
	}

	@Test
	public void theButtonIsEnabledOnlyWhenAReportExists() throws Exception
	{
		assertFalse(footer.isReportAvailable());

		refreshAndWait();
		assertFalse(footer.isReportAvailable());
		assertEquals("No report yet", footer.hintText());

		reports.latest = Optional.of("Yama Reviewer report: x\n");
		refreshAndWait();
		assertTrue(footer.isReportAvailable());
		assertEquals("Latest report ready to send", footer.hintText());
	}

	@Test
	public void aStorageErrorDisablesTheButton() throws Exception
	{
		reports.latest = Optional.of("Yama Reviewer report: x\n");
		refreshAndWait();
		reports.fail = true;

		refreshAndWait();

		assertFalse(footer.isReportAvailable());
	}

	private static final class FakeReports implements ReportRepository
	{
		private volatile Optional<String> latest = Optional.empty();
		private volatile boolean fail;

		@Override
		public void save(String fileName, String text)
		{
		}

		@Override
		public Optional<String> latest() throws IOException
		{
			if (fail)
			{
				throw new IOException("unreadable");
			}
			return latest;
		}
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.ui.ReportProblemFooterTest'`
Expected: FAIL — `cannot find symbol: class ReportProblemFooter`.

- [ ] **Step 3: Write the link constant and the footer**

`src/main/java/com/yamareviewer/ProjectLinks.java`:

```java
package com.yamareviewer;

/** Where "Report a problem" sends the user (spec 9). The only place that knows the repository. */
public final class ProjectLinks
{
	public static final String REPOSITORY_URL = "https://github.com/stefanws0/Yama-Mia-";

	private ProjectLinks()
	{
	}
}
```

`src/main/java/com/yamareviewer/adapter/ui/ReportProblemFooter.java`:

```java
package com.yamareviewer.adapter.ui;

import com.yamareviewer.application.port.ReportRepository;
import com.yamareviewer.domain.text.IssueLink;
import java.awt.BorderLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;

/**
 * The panel footer of spec 7.3 and 9: enabled when a report exists; on click it copies the latest report to the
 * clipboard and opens a prefilled GitHub issue with the summary. Files are read on the plugin executor, never
 * on the Swing thread; nothing is sent anywhere by the plugin itself.
 */
@Slf4j
public class ReportProblemFooter extends JPanel
{
	static final String NO_REPORT = "No report yet";
	static final String REPORT_READY = "Latest report ready to send";
	static final String REPORT_COPIED = "Report copied to the clipboard; the issue opens in your browser";

	private final ExecutorService executor;
	private final ReportRepository reports;
	private final String repositoryUrl;
	private final JButton button = new JButton("Report a problem");
	private final JLabel hint = new JLabel(NO_REPORT);

	public ReportProblemFooter(ExecutorService executor, ReportRepository reports, String repositoryUrl)
	{
		this.executor = executor;
		this.reports = reports;
		this.repositoryUrl = repositoryUrl;
		setLayout(new BorderLayout(0, 4));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(8, 0, 0, 0));
		button.setEnabled(false);
		button.setFocusPainted(false);
		button.setToolTipText("Copies the latest report to the clipboard and opens a GitHub issue with its summary");
		button.addActionListener(event -> report());
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hint.setFont(hint.getFont().deriveFont(11f));
		add(button, BorderLayout.NORTH);
		add(hint, BorderLayout.SOUTH);
	}

	/** Finds out on the executor whether a report exists and enables the button on the Swing thread. Safe from any thread. */
	public void refresh()
	{
		submit(() ->
		{
			boolean present = reports.latest().isPresent();
			SwingUtilities.invokeLater(() -> setAvailable(present, present ? REPORT_READY : NO_REPORT));
		});
	}

	boolean isReportAvailable()
	{
		return button.isEnabled();
	}

	String hintText()
	{
		return hint.getText();
	}

	private void report()
	{
		button.setEnabled(false);
		submit(() ->
		{
			Optional<String> report = reports.latest();
			SwingUtilities.invokeLater(() ->
			{
				if (report.isEmpty())
				{
					setAvailable(false, NO_REPORT);
					return;
				}
				copyToClipboard(report.get());
				LinkBrowser.browse(IssueLink.url(repositoryUrl, report.get()));
				setAvailable(true, REPORT_COPIED);
			});
		});
	}

	private void submit(FileTask task)
	{
		try
		{
			executor.submit(() ->
			{
				try
				{
					task.run();
				}
				catch (IOException | RuntimeException e)
				{
					log.warn("Could not read the latest report", e);
					SwingUtilities.invokeLater(() -> setAvailable(false, NO_REPORT));
				}
			});
		}
		catch (RejectedExecutionException e)
		{
			log.debug("Plugin stopped; report footer not refreshed");
		}
	}

	private void setAvailable(boolean available, String text)
	{
		button.setEnabled(available);
		hint.setText(text);
	}

	private static void copyToClipboard(String text)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	@FunctionalInterface
	private interface FileTask
	{
		void run() throws IOException;
	}
}
```

- [ ] **Step 4: Put the footer into `ReviewPanel`**

In `src/main/java/com/yamareviewer/adapter/ui/ReviewPanel.java` (Part 2). Part 2 reserved a footer container (assumed field 7); the code below calls it `footerSlot`: use the name Part 2 chose (`grep -n footer src/main/java/com/yamareviewer/adapter/ui/ReviewPanel.java` shows it). If Part 2 reserved no container, add the field `private final JPanel footerSlot = new JPanel(new BorderLayout());` with `footerSlot.setBackground(ColorScheme.DARK_GRAY_COLOR);` and add it last in the constructor: `add(footerSlot, BorderLayout.SOUTH)` when the panel's layout is a `BorderLayout`, plain `add(footerSlot)` when it is the `PluginPanel` default `DynamicGridLayout`.

Add the field:

```java
	private ReportProblemFooter footer;
```

Add the method:

```java
	/** Part 4: the "Report a problem" footer. Called once by the plugin, on the Swing thread, before the panel is shown. */
	public void attachFooter(ReportProblemFooter footer)
	{
		this.footer = footer;
		footerSlot.removeAll();
		footerSlot.add(footer, BorderLayout.CENTER);
		footerSlot.revalidate();
		footer.refresh();
	}

	@Override
	public void onActivate()
	{
		if (footer != null)
		{
			footer.refresh();
		}
	}
```

At the end of `show(ReviewView view, String clipboardText, HistoryIndex history)` (outside its `invokeLater` block; `refresh()` is thread-safe) add:

```java
		if (footer != null)
		{
			footer.refresh();
		}
```

Add `import java.awt.BorderLayout;` if it is missing. `ArchitectureTest.onlyUiAndRootUseSwing` allows all of this in `adapter.ui`.

- [ ] **Step 5: Wire the plugin**

In `src/main/java/com/yamareviewer/YamaReviewerPlugin.java` (Part 2), add the imports:

```java
import com.yamareviewer.adapter.persistence.FileReportRepository;
import com.yamareviewer.adapter.ui.ReportProblemFooter;
import com.yamareviewer.application.handler.ProblemReporter;
import com.yamareviewer.application.port.ReportRepository;
import com.yamareviewer.domain.diagnosis.Versions;
import com.yamareviewer.domain.health.HealthChecks;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import net.runelite.client.RuneLiteProperties;
```

Add the field:

```java
	private ProblemReporter reporter;
```

In `startUp`, after the `FileStore` local (`files`, assumed field 8; if Part 2 still constructs `new FilepathFileStore(getPluginDirectory())` inline, extract it into `FileStore files = new FilepathFileStore(getPluginDirectory());` first) and before the `KillEndedHandler` is built, add:

```java
		ReportRepository reports = new FileReportRepository(files);
		reporter = new ProblemReporter(reports, ids,
			() -> new Versions(VERSION, Objects.toString(RuneLiteProperties.getVersion(), "dev"), String.valueOf(client.getRevision())),
			Clock.systemDefaultZone());
```

Append `, HealthChecks.standard(), reporter` to the `new KillEndedHandler(...)` arguments, and `, this::reportUnknownYama` to the `new GameEventListener(...)` arguments.

After the `ReviewPanel` (`panel`) is constructed, add:

```java
		panel.attachFooter(new ReportProblemFooter(executor, reports, ProjectLinks.REPOSITORY_URL));
```

Add the method:

```java
	/** Client thread → executor: the recording self-check of spec 5.3 writes its report off the client thread. */
	private void reportUnknownYama(int npcId)
	{
		try
		{
			executor.submit(() -> reporter.reportUnknownYama(npcId));
		}
		catch (RejectedExecutionException e)
		{
			log.debug("Plugin stopped before the unknown-Yama report could be written");
		}
	}
```

`Client.getRevision()` returns the client's build number, a constant read once per report; `RuneLiteProperties.getVersion()` is null in a development client, hence "dev".

- [ ] **Step 6: Add the architecture rule**

In `src/test/java/com/yamareviewer/ArchitectureTest.java` add:

```java
	@ArchTest
	public static final ArchRule onlyUiOpensLinksAndTheClipboard = noClasses()
		.that().resideOutsideOfPackage("com.yamareviewer.adapter.ui..")
		.should().dependOnClassesThat().haveFullyQualifiedName("net.runelite.client.util.LinkBrowser")
		.orShould().dependOnClassesThat().resideInAPackage("java.awt.datatransfer..");
```

- [ ] **Step 7: Run the whole suite**

Run: `./gradlew test`
Expected: PASS, including `ArchitectureTest` (10 rules) and `ReportProblemFooterTest` (2 tests). If `onlyUiOpensLinksAndTheClipboard` fails, move the offending call into `adapter.ui`; never weaken the rule.

- [ ] **Step 8: Check in game (the user does this; never automate game input)**

Run `./gradlew run`, log in following https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts, and enable **Yama Reviewer**.

1. Open the side panel: the footer shows "Report a problem" disabled with "No report yet".
2. Create a report by hand: save a text file `~/.runelite/plugin-data/yama-reviewer/reports/20260101-000000-000.txt` whose first line is `Yama Reviewer report: manual test` and second line `Plugin test`. Close and reopen the panel: the button is enabled with "Latest report ready to send".
3. Click it: the browser opens a new-issue page on the repository with title "manual test" and the two lines plus the attach note in the body; pasting into a text editor gives the file's content. Nothing happens in the game.
4. Do one solo kill. With correct IDs no report is written and the review shows no "Health checks failed" section; if one appears, the report in `reports/` names the check, and "Report a problem" sends it.
5. Walk out of the domain and back in: no report appears (the sitting Yama is ignored).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/yamareviewer/ProjectLinks.java src/main/java/com/yamareviewer/YamaReviewerPlugin.java src/main/java/com/yamareviewer/adapter/ui src/test/java/com/yamareviewer/adapter/ui src/test/java/com/yamareviewer/ArchitectureTest.java
git commit -m "feat: add the Report a problem footer and wire checks, reports and the self-check"
```

---

## Self-Review

**Spec coverage (spec 13, part 4):** the eight checks of section 8 with their evidence minimums, hidden sections, report roles and contract rules (Tasks 3–6, `applies` per 6.3.1); checks skipped for sections hidden as IDS_NOT_CAPTURED, NOT_APPLICABLE or CONTRACT, and failures hidden before assembly (Task 1); the review goes INCOMPLETE and a report is written (Task 10, `withRecomputedStatus`, `ProblemReporter`); candidate finding by pattern, changing nothing (Task 7); report contents, `reports/<timestamp>.txt` on the executor, actors never names (Tasks 8–9); the recording self-check of 5.3 with the NPC ids seen (Task 11); "Report a problem" copying the latest report and opening a prefilled issue with the summary under 6,000 characters through `LinkBrowser` (Task 12); rules 6 and 7 of section 2 (no network, no names: `ReportWriterTest.neverContainsPlayerNames`, `EventLineTest`); 4.5 and 11 failure isolation per unit (runner, reporter, handler, footer). Not in scope by the spec: overrides and automatic repair (v1.1).

**Placeholders:** none. Where a Part 2 or Part 3 name is not in the brief the plan names its assumption in the list at the top and says which real name to substitute; every code step is complete.

**Type consistency:** `CheckResult.pass()/fail(String)/fail(String, Integer)/skipped(String)` with `passed()/skipped()/detail()/tick()` are used identically in Tasks 1–12; `CheckOutcome(HealthCheck, CheckResult)` with `name()/failed()/skipped()/getCheck()/getResult()/failedNames/anyFailed` in Tasks 1, 8, 9, 10; `HealthCheckRunner.apply(KillLog, ProjectionContext, List<HealthCheck>)` in Tasks 1, 6, 10; `Fights` helpers in Tasks 2–10; `PrayedAttacks.onSelf(List<Attack>, KillLog, Rules, Phase)` in Tasks 2 and 5; `SpecEnergyDrops.find/SPEC_ROLES/WEAPON_ROLES` and `SpecDrop.unmatchedKnownWeapon()` in Tasks 2, 6, 7; `Candidate` field order `(role, id, text, where, count, firstTick, lastTick, score, evidence)` in Tasks 7 and 8; `ProblemReport.forFailedChecks(Versions, KillLog, Mode, Contract, List<CheckOutcome>, List<CheckCandidates>)` and `forUnknownYama(Versions, Set<Integer>, Set<Integer>)` in Tasks 8 and 9; `ReportRepository.save(String, String)/latest()` in Tasks 9, 10, 12; `ProblemReporter(ReportRepository, IdRegistry, Supplier<Versions>, Clock)` with `reportFailedChecks/reportUnknownYama` in Tasks 9, 10, 12; the eleven-argument `KillEndedHandler` in Tasks 10 and 12; the nine-argument `GameEventListener` in Tasks 11 and 12; `ReportProblemFooter(ExecutorService, ReportRepository, String)` with `refresh()` in Task 12.

**Review Focus:** each of the five lines is pinned by a named test in its owning task (Tasks 5, 4, 3/4/10, 6 and 11).
