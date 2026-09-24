package com.yamareviewer.domain.text;

import com.yamareviewer.domain.history.Trend;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Number, time and trend formatting shared by the text renderers and the panel. */
public final class Formats
{
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
	private static final double STEADY_BAND = 0.02;

	private Formats()
	{
	}

	/** Ticks as m:ss, one tick being 0.6 seconds. */
	public static String duration(int ticks)
	{
		long seconds = Math.round(ticks * 0.6);
		return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	public static String gp(long amount)
	{
		return String.format(Locale.ROOT, "%,d", amount);
	}

	/** 450, 318k, 1.25M. */
	public static String compactGp(long amount)
	{
		if (amount < 1_000)
		{
			return Long.toString(amount);
		}
		if (amount < 1_000_000)
		{
			return Math.round(amount / 1_000.0) + "k";
		}
		return stripZeros(String.format(Locale.ROOT, "%.2f", amount / 1_000_000.0)) + "M";
	}

	public static String percent(double share)
	{
		return Math.round(share * 100) + "%";
	}

	public static String date(long epochMs)
	{
		return DATE.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs));
	}

	/** An arrow and a word comparing the last ten kills with the ten before; "" without two means. */
	public static String trend(Trend trend, boolean lowerIsBetter)
	{
		if (trend == null || trend.getLast10Mean() == null || trend.getPrevious10Mean() == null)
		{
			return "";
		}
		double last = trend.getLast10Mean();
		double previous = trend.getPrevious10Mean();
		if (Math.abs(last - previous) <= Math.abs(previous) * STEADY_BAND)
		{
			return "→ steady";
		}
		boolean lower = last < previous;
		return (lower ? "↓ " : "↑ ") + (lower == lowerIsBetter ? "improving" : "declining");
	}

	private static String stripZeros(String decimal)
	{
		String result = decimal;
		while (result.contains(".") && (result.endsWith("0") || result.endsWith(".")))
		{
			result = result.substring(0, result.length() - 1);
		}
		return result;
	}
}
