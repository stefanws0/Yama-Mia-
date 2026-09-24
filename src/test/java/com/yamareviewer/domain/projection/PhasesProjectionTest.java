package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.GameStateKind;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class PhasesProjectionTest
{
	private static final int JUDGE_ID = TestIds.id(Role.JUDGE);
	private static final int YAMA_ID = TestIds.id(Role.YAMA);
	private static final int PHASE_VARBIT = TestIds.id(Role.PHASE_VARBIT);
	private static final int TRANSITION_GRAPHIC = TestIds.id(Role.PHASE_TRANSITION_GRAPHIC);

	private static PhaseTimes project(KillLog kill)
	{
		return new PhasesProjection().project(kill, new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT)).value();
	}

	private static List<PhaseSpan> spans(Object... phaseStartEnd)
	{
		List<PhaseSpan> spans = new java.util.ArrayList<>();
		for (int i = 0; i < phaseStartEnd.length; i += 3)
		{
			spans.add(new PhaseSpan((Phase) phaseStartEnd[i], (Integer) phaseStartEnd[i + 1], (Integer) phaseStartEnd[i + 2]));
		}
		return spans;
	}

	/** Judge spawns at 50 and 130, dies at 80 and 160; the cursor ends at 220. */
	private static KillLogBuilder fullKill()
	{
		return KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(60);
	}

	@Test
	public void judgeSpawnsAndDeathsDelimitThePhases()
	{
		PhaseTimes times = project(fullKill().end(EndReason.YAMA_DIED));

		assertEquals(spans(Phase.P1, 0, 50, Phase.JUDGE_1, 50, 80, Phase.P2, 80, 130, Phase.JUDGE_2, 130, 160, Phase.P3, 160, 220), times.getSpans());
		assertEquals(220, times.getTotalTicks());
	}

	@Test
	public void theEarliestSignalStartsTheJudgePhase()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(48).varbit(PHASE_VARBIT, 1)
			.ticks(2).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(20).end(EndReason.LEFT);

		assertEquals(spans(Phase.P1, 0, 48, Phase.JUDGE_1, 48, 80, Phase.P2, 80, 100), project(kill).getSpans());
	}

	@Test
	public void overheadTextAndTheTransitionGraphicAreSignals()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(40).overhead(Actor.YAMA, TestIds.text(Role.PHASE_TRANSITION_TEXT))
			.ticks(10).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(45).graphicOn(Actor.YAMA, TRANSITION_GRAPHIC)
			.ticks(5).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(60).end(EndReason.YAMA_DIED);

		assertEquals(spans(Phase.P1, 0, 40, Phase.JUDGE_1, 40, 80, Phase.P2, 80, 125, Phase.JUDGE_2, 125, 160, Phase.P3, 160, 220),
			project(kill).getSpans());
	}

	@Test
	public void aRepeatedOrResetVarbitIsNotASignal()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(48).varbit(PHASE_VARBIT, 1)
			.ticks(2).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(20).varbit(PHASE_VARBIT, 1)
			.ticks(1).varbit(PHASE_VARBIT, 0)
			.ticks(29).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(60).end(EndReason.YAMA_DIED);

		assertEquals(spans(Phase.P1, 0, 48, Phase.JUDGE_1, 48, 80, Phase.P2, 80, 130, Phase.JUDGE_2, 130, 160, Phase.P3, 160, 220),
			project(kill).getSpans());
	}

	@Test
	public void aSceneReloadInsideAJudgePhaseIsIgnored()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(10).gameState(GameStateKind.LOADING)
			.npcDespawns(Actor.JUDGE, JUDGE_ID, false).npcDespawns(Actor.YAMA, YAMA_ID, false)
			.npcSpawns(Actor.JUDGE, JUDGE_ID).npcSpawns(Actor.YAMA, YAMA_ID)
			.ticks(1).gameState(GameStateKind.LOGGED_IN)
			.ticks(19).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(20).end(EndReason.LEFT);

		assertEquals(spans(Phase.P1, 0, 50, Phase.JUDGE_1, 50, 80, Phase.P2, 80, 100), project(kill).getSpans());
	}

	@Test
	public void aDuoJudgePhaseEndsWhenBothJudgesAreGone()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID, 7).npcSpawns(Actor.JUDGE, JUDGE_ID, 8)
			.ticks(25).npcDespawns(Actor.JUDGE, JUDGE_ID, 7, true)
			.ticks(5).npcDespawns(Actor.JUDGE, JUDGE_ID, 8, true)
			.ticks(20).end(EndReason.LEFT);

		assertEquals(spans(Phase.P1, 0, 50, Phase.JUDGE_1, 50, 80, Phase.P2, 80, 100), project(kill).getSpans());
	}

	@Test
	public void aFightThatEndedEarlyHasOnlyThePhasesReached()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(20).end(EndReason.LEFT);

		PhaseTimes times = project(kill);

		assertEquals(spans(Phase.P1, 0, 50, Phase.JUDGE_1, 50, 80, Phase.P2, 80, 100), times.getSpans());
		assertEquals(100, times.getTotalTicks());
	}

	@Test
	public void aDeathInsideAJudgePhaseEndsThatSpan()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(10).hitsplatOn(Actor.SELF, 99).hp(0).end(EndReason.PLAYER_DIED);

		PhaseTimes times = project(kill);

		assertEquals(spans(Phase.P1, 0, 50, Phase.JUDGE_1, 50, 60), times.getSpans());
		assertEquals(60, times.getTotalTicks());
	}

	@Test
	public void signalsRightAfterAJudgePhaseBelongToTheReturnToTheArena()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(2).graphicOn(Actor.YAMA, TRANSITION_GRAPHIC).varbit(PHASE_VARBIT, 1)
			.ticks(48).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(60).end(EndReason.YAMA_DIED);

		assertEquals(spans(Phase.P1, 0, 50, Phase.JUDGE_1, 50, 80, Phase.P2, 80, 130, Phase.JUDGE_2, 130, 160, Phase.P3, 160, 220),
			project(kill).getSpans());
	}

	@Test
	public void signalsAfterYamaDiedAreIgnored()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(50).npcSpawns(Actor.JUDGE, JUDGE_ID)
			.ticks(30).npcDespawns(Actor.JUDGE, JUDGE_ID, true)
			.ticks(20).npcDespawns(Actor.YAMA, YAMA_ID, true).graphicOn(Actor.YAMA, TRANSITION_GRAPHIC)
			.end(EndReason.YAMA_DIED);

		assertEquals(spans(Phase.P1, 0, 50, Phase.JUDGE_1, 50, 80, Phase.P2, 80, 100), project(kill).getSpans());
	}

	@Test
	public void aLogWithoutFightEndedEndsAtItsLastEvent()
	{
		PhaseTimes times = project(KillLogBuilder.kill().ticks(5).build());

		assertEquals(spans(Phase.P1, 0, 4), times.getSpans());
		assertEquals(4, times.getTotalTicks());
	}

	@Test
	public void needsTheJudgeRole()
	{
		PhasesProjection projection = new PhasesProjection();
		assertEquals(Sections.PHASES, projection.key());
		assertEquals(Set.of(Role.JUDGE), projection.requiredRoles());

		ReviewBuilder builder = new ReviewBuilder(List.of(projection), TestIds.registryWithout(Role.JUDGE), Rules.DEFAULT);

		assertEquals(Section.hidden(HiddenReason.IDS_NOT_CAPTURED),
			builder.run(fullKill().end(EndReason.YAMA_DIED), ReviewSettings.DEFAULT).section(Sections.PHASES));
	}
}
