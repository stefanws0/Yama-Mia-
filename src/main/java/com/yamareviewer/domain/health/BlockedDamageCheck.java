package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.AttackTimeline;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Spec 8: more than one P3 attack blocked by the right prayer (spec 6.5 rule 1) still hit for more than
 * blockedMaxHit. Either the impact graphics are swapped or the prayer-check tick is off.
 */
public final class BlockedDamageCheck implements HealthCheck
{
	@Override
	public String name()
	{
		return "BlockedDamage";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return Set.of(Role.IMPACT_MAGIC, Role.IMPACT_RANGED);
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.PRAYER_REVIEW);
	}

	@Override
	public boolean applies(ContractRules rules)
	{
		return rules.getBlockedMaxHit() != null;
	}

	@Override
	public CheckResult check(KillLog log, ProjectionContext context)
	{
		Optional<AttackTimeline> timeline = context.value(Sections.ATTACKS);
		if (timeline.isEmpty())
		{
			return CheckResult.skipped("attacks not available");
		}
		Integer max = context.contractRules().getBlockedMaxHit();
		if (max == null)
		{
			return CheckResult.skipped("blockedMaxHit is not defined under this contract");
		}
		Rules rules = context.rules();
		int blocked = 0;
		int hard = 0;
		Integer firstTick = null;
		for (PrayedAttack attack : PrayedAttacks.onSelf(timeline.get().getAttacks(), log, rules, Phase.P3))
		{
			if (!attack.prayedCorrectly())
			{
				continue;
			}
			blocked++;
			if (attack.damage() > max)
			{
				hard++;
				if (firstTick == null)
				{
					firstTick = attack.getAttack().getCastTick();
				}
			}
		}
		if (hard > 1)
		{
			return CheckResult.fail(String.format(
				"%d of %d P3 attacks blocked by the right prayer still hit for more than %d; the impact graphics may be swapped, or the prayer-check tick (%s, offset %d) may be off",
				hard, blocked, max, rules.getPrayerCheck(), rules.getPrayerCheckOffset()), firstTick);
		}
		return CheckResult.pass();
	}
}
