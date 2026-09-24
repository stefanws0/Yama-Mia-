package com.yamareviewer.domain.health;

import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the checks after the projections and before the review is assembled. A failed check hides the
 * sections it protects in the context with HEALTH_CHECK_FAILED, so the assembled review never shows them.
 * A check is skipped when the contract says so, when every section it protects is already hidden as
 * IDS_NOT_CAPTURED, NOT_APPLICABLE or CONTRACT (spec 8), or when it throws (spec 4.5).
 */
@Slf4j
public final class HealthCheckRunner
{
	private static final Set<HiddenReason> ALREADY_HIDDEN = EnumSet.of(
		HiddenReason.IDS_NOT_CAPTURED, HiddenReason.NOT_APPLICABLE, HiddenReason.CONTRACT);

	private HealthCheckRunner()
	{
	}

	public static List<CheckOutcome> apply(KillLog kill, ProjectionContext context, List<HealthCheck> checks)
	{
		List<CheckOutcome> outcomes = new ArrayList<>();
		for (HealthCheck check : checks)
		{
			CheckResult result = run(check, kill, context);
			if (!result.passed())
			{
				for (SectionKey<?> key : check.hides())
				{
					hide(context, key);
				}
			}
			outcomes.add(new CheckOutcome(check, result));
		}
		return List.copyOf(outcomes);
	}

	private static CheckResult run(HealthCheck check, KillLog kill, ProjectionContext context)
	{
		try
		{
			if (!check.applies(context.contractRules()))
			{
				Contract contract = context.value(Sections.CONTRACT).orElse(Contract.NONE);
				return CheckResult.skipped("not applicable under " + contract.displayName());
			}
			if (nothingLeftToProtect(check, context))
			{
				return CheckResult.skipped("every section it protects is already hidden");
			}
			return check.check(kill, context);
		}
		catch (RuntimeException e)
		{
			log.warn("Health check {} failed with an error and was skipped", check.name(), e);
			return CheckResult.skipped("error: " + e);
		}
	}

	private static boolean nothingLeftToProtect(HealthCheck check, ProjectionContext context)
	{
		if (check.hides().isEmpty())
		{
			return false;
		}
		for (SectionKey<?> key : check.hides())
		{
			Optional<HiddenReason> reason = context.section(key).hiddenReason();
			if (reason.isEmpty() || !ALREADY_HIDDEN.contains(reason.get()))
			{
				return false;
			}
		}
		return true;
	}

	/** Only a shown section is hidden; a section hidden for another reason keeps that reason. */
	private static <T> void hide(ProjectionContext context, SectionKey<T> key)
	{
		Section<T> current = context.section(key);
		if (current.isOk())
		{
			context.put(key, Section.hidden(HiddenReason.HEALTH_CHECK_FAILED));
		}
	}
}
