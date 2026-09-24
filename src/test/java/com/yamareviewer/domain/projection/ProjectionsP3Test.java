package com.yamareviewer.domain.projection;

import java.util.List;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ProjectionsP3Test
{
	@Test
	public void standardOrderMatchesSpecSixPointOne()
	{
		assertEquals(List.of("phases", "contract", "mode", "glyphs", "attacks", "crashes", "waves", "flares", "specs",
				"prayer-review", "opener", "tick-log", "damage", "supplies", "death-recap"),
			Projections.standard().stream().map(projection -> projection.key().name()).collect(Collectors.toList()));
	}
}
