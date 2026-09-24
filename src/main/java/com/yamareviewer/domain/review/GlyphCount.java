package com.yamareviewer.domain.review;

import com.yamareviewer.domain.model.Style;
import java.util.Optional;
import lombok.Value;

/** Glyphs conjured in P2: the majority colour sets the expected P3 opener (spec 6.5). */
@Value
public class GlyphCount
{
	int fire;
	int shadow;

	/** Fire majority: magic opener; shadow majority: ranged; tie: no expectation. */
	public Optional<Style> expectedOpener()
	{
		if (fire > shadow)
		{
			return Optional.of(Style.MAGIC);
		}
		if (shadow > fire)
		{
			return Optional.of(Style.RANGED);
		}
		return Optional.empty();
	}
}
