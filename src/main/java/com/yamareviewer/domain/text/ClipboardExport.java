package com.yamareviewer.domain.text;

import com.yamareviewer.domain.review.KillReview;
import java.util.List;

/** Plain text of spec 7.4: the chat lines, supplies and cost, the death recap if any. Never a player name. Part 3 adds the spec efficiency. */
public final class ClipboardExport
{
	private static final List<String> COPIED_SECTIONS = List.of("Specs and drains", "Supplies", "Death recap");

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
