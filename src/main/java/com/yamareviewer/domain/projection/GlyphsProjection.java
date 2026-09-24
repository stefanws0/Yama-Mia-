package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.ObjectAnimationObserved;
import com.yamareviewer.domain.event.ObjectSpawnObserved;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.GlyphCount;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Counts the fire and shadow glyphs conjured in P2 (spec 6.5): every GLYPH_FIRE or GLYPH_SHADOW object
 * spawned or animated in P2, counted once per object, tile and tick, so a glyph that spawns and animates
 * on the same tick counts once while a second conjure of the same glyph counts again.
 */
public final class GlyphsProjection implements Projection<GlyphCount>
{
	@Override
	public SectionKey<GlyphCount> key()
	{
		return Sections.GLYPHS;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.GLYPH_FIRE, Role.GLYPH_SHADOW);
	}

	@Override
	public Section<GlyphCount> project(KillLog log, ProjectionContext context)
	{
		Section<PhaseTimes> phases = context.section(Sections.PHASES);
		Optional<HiddenReason> hidden = Upstream.hidden(phases);
		if (hidden.isPresent())
		{
			return Section.hidden(hidden.get());
		}
		IdRegistry ids = context.ids();
		Set<String> fire = new HashSet<>();
		Set<String> shadow = new HashSet<>();
		for (ObjectSpawnObserved spawn : log.eventsOf(ObjectSpawnObserved.class))
		{
			count(ids, phases.value(), spawn.getTick(), spawn.getObjectId(), spawn.getPosition(), fire, shadow);
		}
		for (ObjectAnimationObserved animation : log.eventsOf(ObjectAnimationObserved.class))
		{
			count(ids, phases.value(), animation.getTick(), animation.getObjectId(), animation.getPosition(), fire, shadow);
		}
		return Section.ok(new GlyphCount(fire.size(), shadow.size()));
	}

	private static void count(IdRegistry ids, PhaseTimes phases, int tick, int objectId, Position position,
		Set<String> fire, Set<String> shadow)
	{
		if (PhaseLookup.phaseAt(phases, tick) != Phase.P2)
		{
			return;
		}
		String key = objectId + "/" + (position == null ? "?" : position.getX() + "," + position.getY()) + "/" + tick;
		if (ids.is(Role.GLYPH_FIRE, objectId))
		{
			fire.add(key);
		}
		else if (ids.is(Role.GLYPH_SHADOW, objectId))
		{
			shadow.add(key);
		}
	}
}
