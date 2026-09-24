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
