package com.yamareviewer.domain.text;

import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.testing.P3Reviews;
import java.util.List;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** The Part 2 entry points carry the Part 3 text. */
public class P3TextIntegrationTest
{
	private final KillReview sample = P3Reviews.sample();
	private final ChatLineOptions allLines = new ChatLineOptions(true, true, true, true, false);

	@Test
	public void chatLinesTwoAndFour()
	{
		List<String> lines = ChatLines.lines(sample, allLines);

		assertEquals(4, lines.size());
		assertEquals("P3 prayers 2/3 blocked (1 too early). Crash lines 2/3 dodged. Waves 1/2.", lines.get(1));
		assertTrue(lines.get(3), lines.get(3).startsWith("Specs: Def 225→147 in P1 (1/1 Elder maul). Supplies"));
	}

	@Test
	public void hiddenValuesPrintNotAvailable()
	{
		List<String> lines = ChatLines.lines(P3Reviews.allHidden(), allLines);

		assertEquals("P3 prayers n/a. Crash lines n/a. Waves n/a.", lines.get(1));
		assertTrue(lines.get(3), lines.get(3).startsWith("Specs: n/a. Supplies"));
	}

	@Test
	public void theFormatterShowsTheSixSectionsInTheSpecsOrder()
	{
		List<String> titles = ReviewFormatter.format(sample).getSections().stream().map(ViewSection::getTitle).collect(Collectors.toList());

		List<String> p3Titles = titles.stream()
			.filter(title -> List.of("Opener", "P3 prayers", "Crash lines", "Waves", "Specs and drains", "P3 tick log").contains(title))
			.collect(Collectors.toList());
		assertEquals(List.of("Opener", "P3 prayers", "Crash lines", "Waves", "Specs and drains", "P3 tick log"), p3Titles);
		assertEquals("P3 tick log", titles.get(titles.size() - 1));
		assertTrue(titles.indexOf("Waves") < titles.indexOf("Specs and drains"));
	}

	@Test
	public void theFormatterShowsAHiddenReasonInOneLine()
	{
		ViewSection opener = ReviewFormatter.format(P3Reviews.allHidden()).getSections().stream()
			.filter(section -> section.getTitle().equals("Opener")).findFirst().orElseThrow(() -> new AssertionError("no opener"));

		assertNotNull(opener.getHiddenReason());
		assertTrue(opener.getLines().isEmpty());
	}

	@Test
	public void theClipboardTextCarriesTheChatLinesAndTheSpecEfficiency()
	{
		String text = ClipboardExport.text(sample);

		assertTrue(text, text.contains("P3 prayers 2/3 blocked (1 too early). Crash lines 2/3 dodged. Waves 1/2."));
		assertTrue(text, text.contains("Elder maul: 1/1 landed, 60 avg damage"));
		assertTrue(text, text.contains("Defence 225→147 (lowest, in P1), 78 of 80 drained (modelled)."));
	}
}
