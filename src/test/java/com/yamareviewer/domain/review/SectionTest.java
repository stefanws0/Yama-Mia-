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
