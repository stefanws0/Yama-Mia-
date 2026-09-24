package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.AttackTimeline;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Spec 8 with the behaviour evidence of spec 6.4: an attack has evidence when SELF prayed against magic or
 * ranged at the check tick and still took more than blockedMaxHit (so the style was not the prayed one). The
 * evidence contradicts the graphic when the prayed style is the graphic's style. At least three
 * contradictions making up at least a quarter of the evidence means the cast or impact graphics are wrong.
 */
public final class GraphicVsBehaviourCheck implements HealthCheck
{
	static final int MIN_CONTRADICTIONS = 3;
	static final double MIN_SHARE = 0.25;

	@Override
	public String name()
	{
		return "GraphicVsBehaviour";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return Set.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED, Role.IMPACT_MAGIC, Role.IMPACT_RANGED);
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.PRAYER_REVIEW, Sections.OPENER);
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
		int evidence = 0;
		int contradictions = 0;
		Integer firstTick = null;
		for (PrayedAttack attack : PrayedAttacks.onSelf(timeline.get().getAttacks(), log, context.rules(), null))
		{
			if (attack.damage() <= max || !attack.prayedAgainstMagicOrRanged())
			{
				continue;
			}
			evidence++;
			if (attack.prayedCorrectly())
			{
				contradictions++;
				if (firstTick == null)
				{
					firstTick = attack.getAttack().getCastTick();
				}
			}
		}
		if (contradictions >= MIN_CONTRADICTIONS && contradictions >= MIN_SHARE * evidence)
		{
			return CheckResult.fail(String.format(
				"%d of %d attacks with behaviour evidence contradict their graphic: SELF prayed against the graphic's style at the prayer-check tick and still took more than %d",
				contradictions, evidence, max), firstTick);
		}
		return CheckResult.pass();
	}
}
