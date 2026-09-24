package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Section;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/** Runs the projections in order; missing roles → IDS_NOT_CAPTURED; an exception → ERROR (logged). Pure: no clock, no I/O. */
@Slf4j
public final class ReviewBuilder
{
	private final List<Projection<?>> projections;
	private final IdRegistry ids;
	private final Rules rules;

	public ReviewBuilder(List<Projection<?>> projections, IdRegistry ids, Rules rules)
	{
		this.projections = List.copyOf(projections);
		this.ids = ids;
		this.rules = rules;
	}

	public ProjectionContext run(KillLog kill, ReviewSettings settings)
	{
		ProjectionContext context = new ProjectionContext(ids, rules, settings);
		for (Projection<?> projection : projections)
		{
			run(projection, kill, context);
		}
		return context;
	}

	private <T> void run(Projection<T> projection, KillLog kill, ProjectionContext context)
	{
		context.put(projection.key(), sectionOf(projection, kill, context));
	}

	private <T> Section<T> sectionOf(Projection<T> projection, KillLog kill, ProjectionContext context)
	{
		for (Role role : projection.requiredRoles())
		{
			if (!ids.isCaptured(role))
			{
				log.debug("Section {} hidden: role {} is not captured", projection.key().name(), role);
				return Section.hidden(HiddenReason.IDS_NOT_CAPTURED);
			}
		}
		try
		{
			return Objects.requireNonNull(projection.project(kill, context), "projection returned null");
		}
		catch (RuntimeException e)
		{
			log.warn("Projection {} failed for kill {}", projection.key().name(), kill.getHeader().getKillId(), e);
			return Section.hidden(HiddenReason.ERROR);
		}
	}
}
