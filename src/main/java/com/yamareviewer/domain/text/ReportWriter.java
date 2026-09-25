package com.yamareviewer.domain.text;

import com.yamareviewer.domain.diagnosis.Candidate;
import com.yamareviewer.domain.diagnosis.CheckCandidates;
import com.yamareviewer.domain.diagnosis.ProblemReport;
import com.yamareviewer.domain.diagnosis.Versions;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.PlayerSeen;
import com.yamareviewer.domain.health.CheckOutcome;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.SectionKey;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

/** Writes a problem report as plain text (spec 9). Actors are SELF, PARTNER and OTHER; player names never appear. */
public final class ReportWriter
{
	public static final String TITLE_PREFIX = "Yama Reviewer report: ";
	public static final String EVENTS_HEADER = "Events (";
	public static final int EXCERPT_LINES = 200;
	static final int CANDIDATES_PER_ROLE = 10;

	private ReportWriter()
	{
	}

	public static String write(ProblemReport report)
	{
		StringBuilder out = new StringBuilder();
		out.append(TITLE_PREFIX).append(report.getHeadline()).append('\n');
		Versions versions = report.getVersions();
		out.append("Plugin ").append(versions.getPlugin())
			.append(", RuneLite ").append(versions.getRuneLite())
			.append(", game revision ").append(versions.getGame()).append('\n');
		KillLog log = report.getLog();
		if (log != null)
		{
			out.append(String.format("Kill %s: ended %s at tick %d, %d events (%d skipped), mode %s, %s, ids fingerprint %s, recorded by plugin %s",
				log.getHeader().getKillId(), log.endReason(), log.lastTick(), log.getEvents().size(), log.getSkippedEvents(),
				report.getMode() == null ? "unknown" : report.getMode().name(),
				report.getContract() == null ? "contract unknown" : report.getContract().displayName(),
				log.getHeader().getIdsFingerprint(), log.getHeader().getPluginVersion())).append('\n');
		}
		for (String note : report.getNotes())
		{
			out.append(note).append('\n');
		}
		if (!report.getOutcomes().isEmpty())
		{
			out.append("\nChecks\n");
			for (CheckOutcome outcome : report.getOutcomes())
			{
				out.append("  ").append(outcomeLine(outcome)).append('\n');
			}
		}
		if (!report.getCandidates().isEmpty())
		{
			out.append("\nCandidates (ids in this log that the plugin doesn't know, best fit first)\n");
			for (CheckCandidates candidates : report.getCandidates())
			{
				appendCandidates(out, candidates);
			}
		}
		if (log != null)
		{
			appendExcerpt(out, log, firstFailureTick(report.getOutcomes()));
		}
		return out.toString();
	}

	/** Everything before the event excerpt: what "Report a problem" puts in the issue. */
	public static String summary(String report)
	{
		int at = report.indexOf("\n" + EVENTS_HEADER);
		return at < 0 ? report : report.substring(0, at + 1);
	}

	private static String outcomeLine(CheckOutcome outcome)
	{
		if (outcome.failed())
		{
			String hides = outcome.getCheck().hides().stream().map(SectionKey::name).collect(Collectors.joining(", "));
			return "FAILED  " + outcome.name() + ": " + outcome.getResult().detail() + " (hides " + hides + ")";
		}
		if (outcome.skipped())
		{
			return "skipped " + outcome.name() + ": " + outcome.getResult().detail();
		}
		return "passed  " + outcome.name();
	}

	private static void appendCandidates(StringBuilder out, CheckCandidates candidates)
	{
		EnumSet<Role> roles = EnumSet.noneOf(Role.class);
		roles.addAll(candidates.getRoles());
		out.append("  ").append(candidates.getCheckName()).append(": ")
			.append(roles.isEmpty() ? "no report roles" : roles.stream().map(Role::name).collect(Collectors.joining(", ")))
			.append('\n');
		for (Role role : roles)
		{
			List<Candidate> ofRole = candidates.getCandidates().stream()
				.filter(candidate -> candidate.getRole() == role)
				.limit(CANDIDATES_PER_ROLE)
				.collect(Collectors.toList());
			if (ofRole.isEmpty())
			{
				out.append("    ").append(role.name()).append(": none found").append(noneFoundNote(role.kind())).append('\n');
				continue;
			}
			for (Candidate candidate : ofRole)
			{
				out.append("    ").append(candidateLine(candidate)).append('\n');
			}
		}
	}

	private static String noneFoundNote(RoleKind kind)
	{
		switch (kind)
		{
			case NPC:
				return " (unknown NPCs are only recorded in capture mode)";
			case ANIMATION:
			case GRAPHIC:
			case OVERHEAD:
				return "";
			default:
				return " (this kind is only recorded for ids that already have a role)";
		}
	}

	private static String candidateLine(Candidate candidate)
	{
		String what = candidate.getText() != null
			? "text \"" + candidate.getText() + "\""
			: candidate.getRole().kind().name().toLowerCase() + " " + candidate.getId();
		return String.format("%s on %s: %s x%d, ticks %d-%d, fit %.2f, %s", candidate.getRole().name(), candidate.getWhere(), what,
			candidate.getCount(), candidate.getFirstTick(), candidate.getLastTick(), candidate.getScore(), candidate.getEvidence());
	}

	private static int firstFailureTick(List<CheckOutcome> outcomes)
	{
		for (CheckOutcome outcome : outcomes)
		{
			if (outcome.failed() && outcome.getResult().tick().isPresent())
			{
				return outcome.getResult().tick().getAsInt();
			}
		}
		return 0;
	}

	private static void appendExcerpt(StringBuilder out, KillLog log, int aroundTick)
	{
		List<DomainEvent> events = log.getEvents();
		int centre = 0;
		while (centre < events.size() && events.get(centre).getTick() < aroundTick)
		{
			centre++;
		}
		int from = Math.max(0, Math.min(centre - EXCERPT_LINES / 2, events.size() - EXCERPT_LINES));
		int to = Math.min(events.size(), from + EXCERPT_LINES);
		String partner = log.eventsOf(PlayerSeen.class).stream().map(PlayerSeen::getName).findFirst().orElse(null);
		out.append('\n').append(EVENTS_HEADER)
			.append(String.format("lines %d-%d of %d events, around tick %d; actors are SELF, PARTNER, OTHER, YAMA, JUDGE, FLARE(index), NPC(id))",
				from + 1, to, events.size(), aroundTick))
			.append('\n');
		for (int i = from; i < to; i++)
		{
			out.append("  ").append(EventLine.of(events.get(i), partner)).append('\n');
		}
	}
}
