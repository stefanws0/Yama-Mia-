package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.TickLog;
import com.yamareviewer.domain.review.TickLogEntry;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import com.yamareviewer.testing.TestIds;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TickLogProjectionTest
{
	private final TickLogProjection projection = new TickLogProjection();

	private TickLog tickLog(KillLog log, ProjectionContext context)
	{
		context.put(Sections.ATTACKS, new AttacksProjection().project(log, context));
		return projection.project(log, context).value();
	}

	@Test
	public void keyAndRoles()
	{
		assertEquals(Sections.TICK_LOG, projection.key());
		assertEquals(Set.of(Role.YAMA, Role.YAMA_STANDARD_ATTACK), projection.requiredRoles());
	}

	@Test
	public void logsEveryP3AttackWithItsGap()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(7).yamaCasts(Style.RANGED).ticks(9).yamaCasts(Style.MAGIC).ticks(2)
			.end(EndReason.YAMA_DIED);

		TickLog tickLog = tickLog(log, TestContext.withPhases(58));

		assertEquals(List.of(40, 47, 56), tickLog.getEntries().stream().map(TickLogEntry::getTick).collect(Collectors.toList()));
		assertEquals(Arrays.asList(null, 7, 9), tickLog.getEntries().stream().map(TickLogEntry::getGap).collect(Collectors.toList()));
		assertEquals(List.of(Style.MAGIC, Style.RANGED, Style.MAGIC), tickLog.getEntries().stream().map(TickLogEntry::getStyle).collect(Collectors.toList()));
		assertEquals(Actor.SELF, tickLog.getEntries().get(0).getTarget());
		assertEquals(7, tickLog.getCycle());
		assertEquals(Optional.of(0.5), tickLog.cycleShare());
	}

	@Test
	public void offCycleGapsAreAnnotatedWithTheEventsInBetween()
	{
		KillLogBuilder kill = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(3);
		kill.crashLine(3200, 3205).ticks(2);
		kill.waveOn(Actor.SELF).waveAt(3200, 3210).ticks(1);
		kill.yamaAnimates(TestIds.id(Role.YAMA_FLARE_SUMMON)).ticks(1);
		kill.yamaAnimates(TestIds.id(Role.YAMA_MELEE)).ticks(2);
		kill.yamaCasts(Style.RANGED).ticks(1);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		TickLog tickLog = tickLog(log, TestContext.withPhases(50));

		assertEquals(Integer.valueOf(9), tickLog.getEntries().get(1).getGap());
		assertEquals(List.of("crash line at 43", "wave at 45", "flare summon at 46", "melee at 47"), tickLog.getEntries().get(1).getAnnotations());
	}

	@Test
	public void gapsOnTheCycleOrAMultipleOfItAreNotAnnotated()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(3).waveOn(Actor.SELF).ticks(4).yamaCasts(Style.RANGED).ticks(7)
			.waveOn(Actor.SELF).ticks(7).yamaCasts(Style.MAGIC).ticks(1).end(EndReason.YAMA_DIED);

		TickLog tickLog = tickLog(log, TestContext.withPhases(62));

		assertEquals(Arrays.asList(null, 7, 14), tickLog.getEntries().stream().map(TickLogEntry::getGap).collect(Collectors.toList()));
		assertEquals(List.of(), tickLog.getEntries().get(1).getAnnotations());
		assertEquals(List.of(), tickLog.getEntries().get(2).getAnnotations());
		assertEquals(1, tickLog.gapsOnCycle());
	}

	@Test
	public void usesTheConfiguredCycle()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(8).yamaCasts(Style.RANGED).ticks(1).end(EndReason.YAMA_DIED);
		Rules eight = Rules.DEFAULT.toBuilder().p3AttackCycle(8).build();

		assertEquals(1, tickLog(log, TestContext.withPhases(eight, 49)).gapsOnCycle());
		assertEquals(0, tickLog(log, TestContext.withPhases(49)).gapsOnCycle());
	}

	@Test
	public void attacksOutsideP3AreNotLogged()
	{
		KillLog log = KillLogBuilder.kill().ticks(3).yamaCasts(Style.MAGIC).ticks(37).yamaCasts(Style.RANGED).ticks(1).end(EndReason.YAMA_DIED);

		TickLog tickLog = tickLog(log, TestContext.withPhases(41));

		assertEquals(List.of(40), tickLog.getEntries().stream().map(TickLogEntry::getTick).collect(Collectors.toList()));
	}

	@Test
	public void hiddenAttacksHideTheLogWithTheSameReason()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(1).end(EndReason.YAMA_DIED);
		ProjectionContext context = TestContext.withPhases(41);
		context.put(Sections.ATTACKS, Section.<AttackTimeline>hidden(HiddenReason.ERROR));

		assertEquals(Optional.of(HiddenReason.ERROR), projection.project(log, context).hiddenReason());
	}
}
