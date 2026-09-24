package com.yamareviewer.domain.review;

import com.yamareviewer.domain.model.Style;
import lombok.Value;

/** The P3 opener (spec 6.5): the glyph majority against Yama's first P3 attack, and your first outcome. */
@Value
public class Opener
{
	GlyphCount glyphs;
	/** Null on a glyph tie. */
	Style expectedStyle;
	/** Yama's first P3 attack on anyone; null when there was none. */
	Attack firstAttack;
	/** Null when there is no expectation, no first attack, or its style is unknown. */
	Boolean asExpected;
	/** The outcome of your first P3 attack; null when none was scored. */
	AttackResult firstOwnResult;
}
