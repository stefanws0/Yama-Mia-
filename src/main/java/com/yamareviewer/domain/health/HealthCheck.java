package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import java.util.List;
import java.util.Set;

/**
 * One check of spec section 8. A check needs a minimum of evidence before it can fail; without it, it
 * returns a skipped result. It reads the log and the context and never changes either.
 */
public interface HealthCheck
{
	/** The name of the spec 8 table, for example "P3AttackCount". */
	String name();

	/** The roles a report should look at when this check fails. */
	Set<Role> reportRoles();

	/** The sections hidden with HEALTH_CHECK_FAILED when this check fails. */
	List<SectionKey<?>> hides();

	/** False when the contract makes the check meaningless (spec 8); the runner then skips it. */
	boolean applies(ContractRules rules);

	CheckResult check(KillLog log, ProjectionContext context);
}
