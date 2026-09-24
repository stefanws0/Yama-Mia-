package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.ObjectAnimationObserved;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.GlyphCount;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import com.yamareviewer.testing.TestIds;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class GlyphsProjectionTest
{
	private final GlyphsProjection projection = new GlyphsProjection();

	@Test
	public void keyAndRoles()
	{
		assertEquals(Sections.GLYPHS, projection.key());
		assertEquals(Set.of(Role.GLYPH_FIRE, Role.GLYPH_SHADOW), projection.requiredRoles());
	}

	@Test
	public void countsGlyphsConjuredInP2()
	{
		KillLog log = Fights.throughToP3(3, 1).ticks(10).end(EndReason.YAMA_DIED);

		assertEquals(Section.ok(new GlyphCount(3, 1)), projection.project(log, TestContext.withPhases(50)));
	}

	@Test
	public void glyphsOutsideP2AreIgnored()
	{
		KillLog log = Fights.throughToP3().ticks(5).glyph(Role.GLYPH_FIRE, 3200, 3200).ticks(5).end(EndReason.YAMA_DIED);

		assertEquals(Section.ok(new GlyphCount(0, 0)), projection.project(log, TestContext.withPhases(50)));
	}

	@Test
	public void aGlyphThatSpawnsAndAnimatesOnTheSameTickCountsOnceButALaterAnimationCountsAgain()
	{
		int fire = TestIds.id(Role.GLYPH_FIRE);
		Position tile = new Position(3190, 3210, 0);
		KillLog log = Fights.throughToP3(1, 0)
			.event(new ObjectAnimationObserved(Fights.GLYPH_TICK, fire, tile, 12169))
			.event(new ObjectAnimationObserved(Fights.GLYPH_TICK + 4, fire, tile, 12169))
			.ticks(10)
			.end(EndReason.YAMA_DIED);

		assertEquals(Section.ok(new GlyphCount(2, 0)), projection.project(log, TestContext.withPhases(50)));
	}

	@Test
	public void hiddenPhasesHideTheGlyphsWithTheSameReason()
	{
		KillLog log = Fights.throughToP3(3, 1).ticks(10).end(EndReason.YAMA_DIED);
		ProjectionContext context = TestContext.empty();
		context.put(Sections.PHASES, Section.<PhaseTimes>hidden(HiddenReason.IDS_NOT_CAPTURED));

		assertEquals(Optional.of(HiddenReason.IDS_NOT_CAPTURED), projection.project(log, context).hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), projection.project(log, TestContext.empty()).hiddenReason());
	}
}
