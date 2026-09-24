package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.review.CrashLine;
import com.yamareviewer.domain.review.CrashSummary;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.WaveHit;
import com.yamareviewer.domain.review.WaveSummary;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Damage attribution rules 1, 3 and 4 of spec 6.10, from the Attacks, Crashes and Waves sections.
 * A hidden section simply contributes no matches, so Part 2's rules take over.
 */
public final class P3DamageSources
{
	private final Set<String> standardLandings;
	private final List<CrashLine> crashLines;
	private final List<WaveHit> waveHits;
	private final int window;

	public P3DamageSources(ProjectionContext context)
	{
		this.standardLandings = context.value(Sections.ATTACKS).map(CrashesProjection::standardLandings).orElse(Set.of());
		this.crashLines = context.value(Sections.CRASHES).map(CrashSummary::getLines).orElse(List.of());
		this.waveHits = context.value(Sections.WAVES).map(WaveSummary::getWaves).orElse(List.of());
		this.window = context.rules().getCrashImpactWindow();
	}

	/** Rule 1: the landing hitsplat of a standard attack. */
	public Optional<DamageSource> standard(HitsplatObserved hitsplat)
	{
		return standardLandings.contains(hitsplat.getTick() + "/" + hitsplat.getTarget().getKind())
			? Optional.of(DamageSource.STANDARD)
			: Optional.empty();
	}

	/** Rule 3, then rule 4: part of a crash line hit, else part of a wave hit on the same player. */
	public Optional<DamageSource> crashOrWave(HitsplatObserved hitsplat)
	{
		for (CrashLine line : crashLines)
		{
			if (line.isHit() && line.getPlayer().equals(hitsplat.getTarget()) && Math.abs(line.getTick() - hitsplat.getTick()) <= window)
			{
				return Optional.of(DamageSource.SHADOW_CRASH);
			}
		}
		for (WaveHit wave : waveHits)
		{
			if (wave.isHit() && wave.getPlayer().equals(hitsplat.getTarget()) && Math.abs(wave.getTick() - hitsplat.getTick()) <= window)
			{
				return Optional.of(DamageSource.SHADOW_WAVE);
			}
		}
		return Optional.empty();
	}
}
