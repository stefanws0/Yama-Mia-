package com.yamareviewer.testing;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.ObjectSpawnObserved;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.PhaseSpan;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class KillLogBuilderP3Test
{
	@Test
	public void yamaCastsEmitsTheAnimationAndTheCastGraphicAtTheCursor()
	{
		KillLog log = KillLogBuilder.kill().ticks(3).yamaCasts(Style.MAGIC).build();

		assertEquals(List.of(new AnimationObserved(3, Actor.YAMA, TestIds.id(Role.YAMA_STANDARD_ATTACK))), log.eventsOf(AnimationObserved.class));
		assertEquals(List.of(new GraphicObserved(3, Actor.YAMA, TestIds.id(Role.YAMA_CAST_MAGIC))), log.eventsOf(GraphicObserved.class));
	}

	@Test
	public void crashLineIsThreeFireballsInARow()
	{
		KillLog log = KillLogBuilder.kill().crashLine(3200, 3205).build();

		List<GroundGraphicObserved> fireballs = log.eventsOf(GroundGraphicObserved.class);
		assertEquals(3, fireballs.size());
		assertEquals(3199, fireballs.get(0).getPosition().getX());
		assertEquals(3200, fireballs.get(1).getPosition().getX());
		assertEquals(3201, fireballs.get(2).getPosition().getX());
		assertEquals(TestIds.id(Role.CRASH_FIREBALL), fireballs.get(1).getGraphicId());
	}

	@Test
	public void throughToP3LeavesTheCursorAtTheStartOfP3WithTheGlyphsInP2()
	{
		KillLogBuilder kill = Fights.throughToP3(2, 1);

		assertEquals(Fights.P3_START, kill.tick());
		KillLog log = kill.build();
		List<NpcSpawnObserved> spawns = log.eventsOf(NpcSpawnObserved.class);
		List<NpcDespawnObserved> despawns = log.eventsOf(NpcDespawnObserved.class);
		assertEquals(List.of(10, 30), List.of(spawns.get(0).getTick(), spawns.get(1).getTick()));
		assertEquals(List.of(20, 40), List.of(despawns.get(0).getTick(), despawns.get(1).getTick()));
		List<ObjectSpawnObserved> glyphs = log.eventsOf(ObjectSpawnObserved.class);
		assertEquals(3, glyphs.size());
		assertEquals(22, glyphs.get(0).getTick());
		assertEquals(TestIds.id(Role.GLYPH_SHADOW), glyphs.get(2).getObjectId());
	}

	@Test
	public void phasesMatchTheSkeleton()
	{
		assertEquals(Optional.of(new PhaseSpan(Phase.P3, 40, 100)), Fights.phases(100).span(Phase.P3));
		assertEquals(Optional.of(new PhaseSpan(Phase.P2, 20, 30)), Fights.phases(100).span(Phase.P2));
	}
}
