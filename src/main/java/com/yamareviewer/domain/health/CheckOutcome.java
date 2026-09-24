package com.yamareviewer.domain.health;

import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;

/** What one check said about one kill. */
@Value
public class CheckOutcome
{
	HealthCheck check;
	CheckResult result;

	public String name()
	{
		return check.name();
	}

	public boolean failed()
	{
		return !result.passed();
	}

	public boolean skipped()
	{
		return result.skipped();
	}

	/** Names of the failed checks, in check order. */
	public static List<String> failedNames(List<CheckOutcome> outcomes)
	{
		return outcomes.stream().filter(CheckOutcome::failed).map(CheckOutcome::name).collect(Collectors.toList());
	}

	public static boolean anyFailed(List<CheckOutcome> outcomes)
	{
		return outcomes.stream().anyMatch(CheckOutcome::failed);
	}
}
