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
