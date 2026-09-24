package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.WidgetTextObserved;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Spec 6.3 detection, first match wins: the contract plate text, a contract item leaving the inventory in
 * the first ticks (the recorder writes a contract consumed at the challenge as an InventoryDelta at tick 0),
 * a 7-tick P1 attack cycle (UNKNOWN_CONTRACT), otherwise NONE. No role is required: an uncaptured cue is
 * simply never matched.
 */
public final class ContractProjection implements Projection<Contract>
{
	/** An InventoryDelta of a contract item before this tick counts as the contract being consumed. */
	static final int CONTRACT_ITEM_WINDOW_TICKS = 10;
	/** How many 7-tick gaps between P1 standard attacks are needed before the cycle means a contract. */
	static final int MIN_CONTRACT_CYCLE_GAPS = 3;

	@Override
	public SectionKey<Contract> key()
	{
		return Sections.CONTRACT;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return Set.of();
	}

	@Override
	public Section<Contract> project(KillLog log, ProjectionContext context)
	{
		IdRegistry ids = context.ids();
		for (WidgetTextObserved widget : log.eventsOf(WidgetTextObserved.class))
		{
			if (ids.is(Role.CONTRACT_NAME_WIDGET, widget.getComponentId()))
			{
				Optional<Contract> named = Contract.fromWidgetText(widget.getText());
				if (named.isPresent())
				{
					return Section.ok(named.get());
				}
			}
		}
		for (InventoryDelta delta : log.eventsOf(InventoryDelta.class))
		{
			if (delta.getChange() < 0 && delta.getTick() < CONTRACT_ITEM_WINDOW_TICKS)
			{
				for (Contract contract : Contract.values())
				{
					if (contract.itemRole() != null && ids.is(contract.itemRole(), delta.getItemId()))
					{
						return Section.ok(contract);
					}
				}
			}
		}
		if (hasContractCycleInP1(log, context))
		{
			return Section.ok(Contract.UNKNOWN_CONTRACT);
		}
		return Section.ok(Contract.NONE);
	}

	/** True when P1 standard attacks come every 7 ticks rather than every p1p2AttackCycle ticks. */
	private static boolean hasContractCycleInP1(KillLog log, ProjectionContext context)
	{
		IdRegistry ids = context.ids();
		Optional<PhaseSpan> p1 = context.value(Sections.PHASES).flatMap(times -> times.span(Phase.P1));
		if (p1.isEmpty() || !ids.isCaptured(Role.YAMA_STANDARD_ATTACK))
		{
			return false;
		}
		int contractCycle = ContractRules.CONTRACT_P1P2_ATTACK_CYCLE;
		int normalCycle = context.rules().getP1p2AttackCycle();
		List<Integer> attackTicks = new ArrayList<>();
		for (AnimationObserved animation : log.eventsOf(AnimationObserved.class))
		{
			if (animation.getActor().equals(Actor.YAMA) && ids.is(Role.YAMA_STANDARD_ATTACK, animation.getAnimationId())
				&& animation.getTick() >= p1.get().getStartTick() && animation.getTick() < p1.get().getEndTick())
			{
				attackTicks.add(animation.getTick());
			}
		}
		int contractGaps = 0;
		int normalGaps = 0;
		for (int i = 1; i < attackTicks.size(); i++)
		{
			int gap = attackTicks.get(i) - attackTicks.get(i - 1);
			if (gap == contractCycle)
			{
				contractGaps++;
			}
			else if (gap == normalCycle)
			{
				normalGaps++;
			}
		}
		return contractGaps >= MIN_CONTRACT_CYCLE_GAPS && contractGaps > normalGaps;
	}
}
