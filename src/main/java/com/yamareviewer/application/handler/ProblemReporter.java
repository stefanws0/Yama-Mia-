package com.yamareviewer.application.handler;

import com.yamareviewer.application.port.ReportRepository;
import com.yamareviewer.domain.diagnosis.Candidate;
import com.yamareviewer.domain.diagnosis.CandidateFinder;
import com.yamareviewer.domain.diagnosis.CheckCandidates;
import com.yamareviewer.domain.diagnosis.ProblemReport;
import com.yamareviewer.domain.diagnosis.Versions;
import com.yamareviewer.domain.health.CheckOutcome;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.text.ReportWriter;
import java.io.IOException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns failed checks and the recording self-check into report files (spec 9). Every method catches its own
 * exceptions (spec 4.5, 11) and returns the file name it wrote. Call it on the plugin executor only.
 */
@Slf4j
public final class ProblemReporter
{
	static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

	private final ReportRepository reports;
	private final IdRegistry ids;
	private final Supplier<Versions> versions;
	private final Clock clock;
	private final Set<Integer> unknownYamaIds = new LinkedHashSet<>();

	public ProblemReporter(ReportRepository reports, IdRegistry ids, Supplier<Versions> versions, Clock clock)
	{
		this.reports = reports;
		this.ids = ids;
		this.versions = versions;
		this.clock = clock;
	}

	public Optional<String> reportFailedChecks(KillLog kill, ProjectionContext context, List<CheckOutcome> outcomes)
	{
		try
		{
			List<CheckCandidates> candidates = new ArrayList<>();
			for (CheckOutcome outcome : outcomes)
			{
				if (outcome.failed())
				{
					Set<Role> roles = outcome.getCheck().reportRoles();
					candidates.add(new CheckCandidates(outcome.name(), roles, candidatesFor(kill, roles)));
				}
			}
			ProblemReport report = ProblemReport.forFailedChecks(versions.get(), kill,
				context.value(Sections.MODE).orElse(null), context.value(Sections.CONTRACT).orElse(null), outcomes, candidates);
			return write(report);
		}
		catch (RuntimeException e)
		{
			log.warn("Could not build the report for kill {}", kill.getHeader().getKillId(), e);
			return Optional.empty();
		}
	}

	/** Spec 5.3: an NPC named "Yama" with an unknown id spawned while armed. One report per new id since startup. */
	public Optional<String> reportUnknownYama(int npcId)
	{
		if (!unknownYamaIds.add(npcId))
		{
			return Optional.empty();
		}
		try
		{
			return write(ProblemReport.forUnknownYama(versions.get(), Set.copyOf(unknownYamaIds), ids.ids(Role.YAMA)));
		}
		catch (RuntimeException e)
		{
			log.warn("Could not build the unknown-Yama report", e);
			return Optional.empty();
		}
	}

	private List<Candidate> candidatesFor(KillLog kill, Set<Role> roles)
	{
		try
		{
			return CandidateFinder.find(kill, ids, roles);
		}
		catch (RuntimeException e)
		{
			log.warn("Candidate finder failed for {}", roles, e);
			return List.of();
		}
	}

	private Optional<String> write(ProblemReport report)
	{
		String name = FILE_TIMESTAMP.withZone(clock.getZone()).format(clock.instant()) + ".txt";
		try
		{
			reports.save(name, ReportWriter.write(report));
			return Optional.of(name);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not write report {}", name, e);
			return Optional.empty();
		}
	}
}
