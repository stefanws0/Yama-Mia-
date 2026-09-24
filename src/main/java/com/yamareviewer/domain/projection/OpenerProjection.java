package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackResult;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.GlyphCount;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Opener;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.PrayerReview;
import com.yamareviewer.domain.review.Section;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The P3 opener (spec 6.5): the glyph majority of P2 against the style of Yama's first P3 attack on anyone,
 * plus the outcome of your first P3 attack. Hidden under a contract with random attack styles.
 */
public final class OpenerProjection implements Projection<Opener>
{
	@Override
	public SectionKey<Opener> key()
	{
		return Sections.OPENER;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED);
	}

	@Override
	public Section<Opener> project(KillLog log, ProjectionContext context)
	{
		if (context.contractRules().isRandomAttackStyles())
		{
			return Section.hidden(HiddenReason.CONTRACT);
		}
		Section<GlyphCount> glyphs = context.section(Sections.GLYPHS);
		Section<PhaseTimes> phases = context.section(Sections.PHASES);
		Section<AttackTimeline> attacks = context.section(Sections.ATTACKS);
		Optional<HiddenReason> hidden = Upstream.hidden(glyphs, phases, attacks);
		if (hidden.isPresent())
		{
			return Section.hidden(hidden.get());
		}
		Style expected = glyphs.value().expectedOpener().orElse(null);
		List<Attack> p3 = attacks.value().inPhase(Phase.P3);
		Attack first = p3.isEmpty() ? null : p3.get(0);
		Boolean asExpected = expected == null || first == null || first.getStyle() == null ? null : first.getStyle() == expected;
		AttackResult firstOwn = context.value(Sections.PRAYER_REVIEW)
			.map(PrayerReview::getResults)
			.filter(results -> !results.isEmpty())
			.map(results -> results.get(0))
			.orElse(null);
		return Section.ok(new Opener(glyphs.value(), expected, first, asExpected, firstOwn));
	}
}
