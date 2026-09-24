package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.SpecResult;
import com.yamareviewer.domain.review.SpecSummary;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spec 8: a spec-energy drop while a known spec weapon is wielded must have a SPEC_* animation on SELF
 * (spec 6.9). Part 3's SpecsProjection already pairs drops with animations: an own SpecResult with a weapon
 * but no animation seen is the failure.
 */
public final class SpecsMatchedCheck implements HealthCheck
{
	@Override
	public String name()
	{
		return "SpecsMatched";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return SpecEnergyDrops.SPEC_ROLES;
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.SPECS);
	}

	@Override
	public boolean applies(ContractRules rules)
	{
		return true;
	}

	@Override
	public CheckResult check(KillLog log, ProjectionContext context)
	{
		Optional<SpecSummary> specs = context.value(Sections.SPECS);
		if (specs.isEmpty())
		{
			return CheckResult.skipped("specs not available");
		}
		List<SpecResult> own = specs.get().ownSpecs();
		List<SpecResult> unmatched = own.stream()
			.filter(spec -> spec.getWeapon() != null && !spec.isAnimationSeen())
			.collect(Collectors.toList());
		if (unmatched.isEmpty())
		{
			return CheckResult.pass();
		}
		SpecResult first = unmatched.get(0);
		return CheckResult.fail(String.format(
			"spec energy dropped by %d%% at tick %d with %s (%s) wielded, but no SPEC_* animation played on SELF at tick %d or %d (%d of %d own specs unmatched)",
			first.getEnergyUsed(), first.getTick(), first.getWeapon().weaponRole(), first.getWeapon().displayName(),
			first.getTick() - 1, first.getTick(), unmatched.size(), own.size()), first.getTick());
	}
}
