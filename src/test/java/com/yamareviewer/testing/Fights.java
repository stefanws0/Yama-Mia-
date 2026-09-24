package com.yamareviewer.testing;

import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import java.util.List;

/**
 * A fight skeleton: P1 ticks 0-9, Judge 1 ticks 10-19 (Judge spawns at 10, despawns at 20), P2 ticks 20-29,
 * Judge 2 ticks 30-39, P3 from tick 40. Tests append P3 events from the cursor and end the kill.
 */
public final class Fights
{
	public static final int JUDGE_1_START = 10;
	public static final int P2_START = 20;
	public static final int GLYPH_TICK = 22;
	public static final int JUDGE_2_START = 30;
	public static final int P3_START = 40;

	private Fights()
	{
	}

	public static KillLogBuilder throughToP3()
	{
		return throughToP3(0, 0);
	}

	/** The glyphs are conjured (spawned) at GLYPH_TICK in P2. The cursor is left at P3_START. */
	public static KillLogBuilder throughToP3(int fireGlyphs, int shadowGlyphs)
	{
		KillLogBuilder kill = KillLogBuilder.kill();
		kill.ticks(JUDGE_1_START);
		kill.judgeSpawns();
		kill.ticks(P2_START - JUDGE_1_START);
		kill.judgeDespawns();
		kill.ticks(GLYPH_TICK - P2_START);
		for (int i = 0; i < fireGlyphs; i++)
		{
			kill.glyph(Role.GLYPH_FIRE, 3190 + i, 3210);
		}
		for (int i = 0; i < shadowGlyphs; i++)
		{
			kill.glyph(Role.GLYPH_SHADOW, 3210 + i, 3210);
		}
		kill.ticks(JUDGE_2_START - GLYPH_TICK);
		kill.judgeSpawns();
		kill.ticks(P3_START - JUDGE_2_START);
		kill.judgeDespawns();
		return kill;
	}

	/** The PhaseTimes of this skeleton with P3 ending at endTick, for contexts built by hand. */
	public static PhaseTimes phases(int endTick)
	{
		return new PhaseTimes(List.of(
			new PhaseSpan(Phase.P1, 0, JUDGE_1_START),
			new PhaseSpan(Phase.JUDGE_1, JUDGE_1_START, P2_START),
			new PhaseSpan(Phase.P2, P2_START, JUDGE_2_START),
			new PhaseSpan(Phase.JUDGE_2, JUDGE_2_START, P3_START),
			new PhaseSpan(Phase.P3, P3_START, endTick)), endTick);
	}
}
