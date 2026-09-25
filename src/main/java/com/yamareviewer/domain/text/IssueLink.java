package com.yamareviewer.domain.text;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The prefilled GitHub "new issue" URL for a report (spec 9): the summary only, under 6,000 characters, and a
 * request to attach the file. The whole URL is kept under 8,000 characters so browsers and GitHub accept it.
 */
public final class IssueLink
{
	public static final int MAX_BODY_CHARS = 6_000;
	static final int MAX_URL_CHARS = 8_000;
	static final String ATTACH_NOTE = "\n\nThe full report, including the event excerpt, was copied to your clipboard when you clicked "
		+ "\"Report a problem\": paste it here, or attach the newest file from .runelite/plugin-data/yama-reviewer/reports/.";
	private static final String TRUNCATED = "\n[truncated]";
	private static final int TRIM_STEP = 500;

	private IssueLink()
	{
	}

	public static String url(String repositoryUrl, String report)
	{
		String title = title(report);
		String summary = ReportWriter.summary(report).trim();
		int keep = Math.min(summary.length(), MAX_BODY_CHARS - 1 - ATTACH_NOTE.length() - TRUNCATED.length());
		String url = build(repositoryUrl, title, summary.substring(0, keep) + (keep < summary.length() ? TRUNCATED : "") + ATTACH_NOTE);
		while (url.length() > MAX_URL_CHARS && keep > 0)
		{
			keep = Math.max(0, keep - TRIM_STEP);
			url = build(repositoryUrl, title, summary.substring(0, keep) + TRUNCATED + ATTACH_NOTE);
		}
		return url;
	}

	/** The first line of the report without the "Yama Reviewer report: " prefix. */
	static String title(String report)
	{
		int end = report.indexOf('\n');
		String first = end < 0 ? report : report.substring(0, end);
		return first.startsWith(ReportWriter.TITLE_PREFIX) ? first.substring(ReportWriter.TITLE_PREFIX.length()) : first;
	}

	private static String build(String repositoryUrl, String title, String body)
	{
		return repositoryUrl + "/issues/new?title=" + encode(title) + "&body=" + encode(body);
	}

	private static String encode(String text)
	{
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}
}
