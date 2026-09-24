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
