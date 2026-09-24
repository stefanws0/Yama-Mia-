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
