package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.Section;
import java.util.Set;

/** One metric of the review, computed from the log and the sections produced before it (spec 6.1). */
public interface Projection<T>
{
	SectionKey<T> key();

	/** If any of these roles is uncaptured, ReviewBuilder hides the section with IDS_NOT_CAPTURED without calling project. */
	Set<Role> requiredRoles();

	Section<T> project(KillLog log, ProjectionContext context);
}
