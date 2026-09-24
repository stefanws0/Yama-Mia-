package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * The immutable read model of one kill. Section getters never return null, so a review stored before a
 * section existed still loads (the missing section reads as hidden, NOT_APPLICABLE). Part 3 adds its
 * sections here and to allSections().
 */
@Value
@Builder(toBuilder = true)
public class KillReview
{
	public static final int SCHEMA_VERSION = 1;

	int reviewSchemaVersion;
	String killId;
	long startEpochMs;
	long endEpochMs;
	EndReason endReason;
	Mode mode;
	Contract contract;
	ReviewStatus status;
	int skippedEvents;
	Section<PhaseTimes> phases;
	Section<DamageSummary> damage;
	Section<FlareSummary> flares;
	Section<SupplySummary> supplies;
	Section<DeathRecap> deathRecap;

	public Section<PhaseTimes> getPhases()
	{
		return orHidden(phases);
	}

	public Section<DamageSummary> getDamage()
	{
		return orHidden(damage);
	}

	public Section<FlareSummary> getFlares()
	{
		return orHidden(flares);
	}

	public Section<SupplySummary> getSupplies()
	{
		return orHidden(supplies);
	}

	public Section<DeathRecap> getDeathRecap()
	{
		return orHidden(deathRecap);
	}

	/** Every shown section, in display order. */
	public List<Section<?>> allSections()
	{
		return List.of(getPhases(), getDamage(), getFlares(), getSupplies(), getDeathRecap());
	}

	/** INCOMPLETE when any section is hidden for HEALTH_CHECK_FAILED or ERROR, or skippedEvents > 0. */
	public KillReview withRecomputedStatus()
	{
		boolean incomplete = skippedEvents > 0 || allSections().stream()
			.anyMatch(section -> section.hiddenReason()
				.filter(reason -> reason == HiddenReason.HEALTH_CHECK_FAILED || reason == HiddenReason.ERROR)
				.isPresent());
		return toBuilder().status(incomplete ? ReviewStatus.INCOMPLETE : ReviewStatus.COMPLETE).build();
	}

	private static <T> Section<T> orHidden(Section<T> section)
	{
		return section == null ? Section.hidden(HiddenReason.NOT_APPLICABLE) : section;
	}
}
