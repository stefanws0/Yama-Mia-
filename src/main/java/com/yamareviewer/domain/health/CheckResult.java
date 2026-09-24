package com.yamareviewer.domain.health;

import java.util.Objects;
import java.util.OptionalInt;

/** Passed, failed with its numbers, or skipped for lack of evidence or applicability (skipped counts as passed). */
public final class CheckResult
{
	private final boolean passed;
	private final boolean skipped;
	private final String detail;
	private final Integer tick;

	private CheckResult(boolean passed, boolean skipped, String detail, Integer tick)
	{
		this.passed = passed;
		this.skipped = skipped;
		this.detail = detail;
		this.tick = tick;
	}

	public static CheckResult pass()
	{
		return new CheckResult(true, false, "", null);
	}

	public static CheckResult fail(String detail)
	{
		return fail(detail, null);
	}

	/** @param tick the tick of the first evidence of the failure, which centres the report's event excerpt; may be null */
	public static CheckResult fail(String detail, Integer tick)
	{
		return new CheckResult(false, false, Objects.requireNonNull(detail), tick);
	}

	public static CheckResult skipped(String reason)
	{
		return new CheckResult(true, true, Objects.requireNonNull(reason), null);
	}

	public boolean passed()
	{
		return passed;
	}

	public boolean skipped()
	{
		return skipped;
	}

	/** The numbers behind a failure, the reason for a skip, empty for a pass. */
	public String detail()
	{
		return detail;
	}

	public OptionalInt tick()
	{
		return tick == null ? OptionalInt.empty() : OptionalInt.of(tick);
	}

	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof CheckResult))
		{
			return false;
		}
		CheckResult that = (CheckResult) other;
		return passed == that.passed && skipped == that.skipped && detail.equals(that.detail) && Objects.equals(tick, that.tick);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(passed, skipped, detail, tick);
	}

	@Override
	public String toString()
	{
		if (skipped)
		{
			return "skipped: " + detail;
		}
		return passed ? "passed" : "failed: " + detail + (tick == null ? "" : " (tick " + tick + ")");
	}
}
