package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.SupplyItem;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PriceMode;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.SupplyLine;
import com.yamareviewer.domain.review.SupplySummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spec 6.11. Compares the last START and END snapshots; only decreases count. Potions are grouped by their
 * base name (without the dose suffix) and counted in doses; a dose costs the highest-dose START variant
 * divided by its doses. Prices are the snapshot's, in the configured source. Under Familiar Acquisition
 * every consumed line except runes and ammunition is marked "no effect".
 */
public final class SuppliesProjection implements Projection<SupplySummary>
{
	private static final Pattern DOSES = Pattern.compile("^(.*?)\\((\\d)\\)$");
	/** Name fragments of items that still work under Familiar Acquisition (runes and ammunition). */
	private static final List<String> STILL_EFFECTIVE = List.of(" rune", "arrow", "bolt", "dart", "javelin", "knife", "thrownaxe", "scales", "chinchompa");

	@Override
	public SectionKey<SupplySummary> key()
	{
		return Sections.SUPPLIES;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return Set.of();
	}

	@Override
	public Section<SupplySummary> project(KillLog log, ProjectionContext context)
	{
		Optional<SuppliesSnapshot> start = last(log, SnapshotKind.START);
		Optional<SuppliesSnapshot> end = last(log, SnapshotKind.END);
		if (end.isEmpty() && log.endReason() == EndReason.LEFT)
		{
			return Section.hidden(HiddenReason.NOT_APPLICABLE);
		}
		if (start.isEmpty() || end.isEmpty())
		{
			throw new IllegalStateException("Supplies snapshots missing: start=" + start.isPresent() + ", end=" + end.isPresent());
		}
		PriceMode mode = context.settings().getPriceMode();
		boolean noEffect = context.contractRules().isConsumablesHaveNoEffect();

		Map<String, Group> groups = new LinkedHashMap<>();
		for (SupplyItem item : start.get().getItems())
		{
			Matcher potion = DOSES.matcher(item.getName());
			if (potion.matches())
			{
				String base = potion.group(1).trim();
				int doses = Integer.parseInt(potion.group(2));
				Group group = groups.computeIfAbsent("potion:" + base, key -> new Group(base, "doses"));
				group.startUnits += item.getQuantity() * doses;
				if (doses > group.priceDoses)
				{
					group.priceDoses = doses;
					group.unitPrice = price(item, mode) / (double) doses;
				}
			}
			else
			{
				Group group = groups.computeIfAbsent("item:" + item.getItemId(), key -> new Group(item.getName(), "x"));
				group.startUnits += item.getQuantity();
				group.unitPrice = price(item, mode);
			}
		}
		for (SupplyItem item : end.get().getItems())
		{
			Matcher potion = DOSES.matcher(item.getName());
			boolean isPotion = potion.matches();
			Group group = groups.get(isPotion ? "potion:" + potion.group(1).trim() : "item:" + item.getItemId());
			if (group != null)
			{
				group.endUnits += isPotion ? item.getQuantity() * Integer.parseInt(potion.group(2)) : item.getQuantity();
			}
		}

		List<SupplyLine> lines = new ArrayList<>();
		long total = 0;
		for (Group group : groups.values())
		{
			int used = group.startUnits - group.endUnits;
			if (used <= 0)
			{
				continue;
			}
			long cost = Math.round(used * group.unitPrice);
			lines.add(new SupplyLine(group.name, used, group.unit, cost, noEffect && affectedByFamiliar(group)));
			total += cost;
		}
		return Section.ok(new SupplySummary(List.copyOf(lines), total, mode));
	}

	private static Optional<SuppliesSnapshot> last(KillLog log, SnapshotKind kind)
	{
		SuppliesSnapshot last = null;
		for (SuppliesSnapshot snapshot : log.eventsOf(SuppliesSnapshot.class))
		{
			if (snapshot.getKind() == kind)
			{
				last = snapshot;
			}
		}
		return Optional.ofNullable(last);
	}

	private static int price(SupplyItem item, PriceMode mode)
	{
		return mode == PriceMode.GRAND_EXCHANGE ? item.getGePrice() : item.getHaPrice();
	}

	/** Potions and food do nothing under Familiar Acquisition; runes and ammunition still work. */
	private static boolean affectedByFamiliar(Group group)
	{
		if ("doses".equals(group.unit))
		{
			return true;
		}
		String name = group.name.toLowerCase(Locale.ROOT);
		return STILL_EFFECTIVE.stream().noneMatch(name::contains);
	}

	/** One consumed line while the snapshots are compared. */
	private static final class Group
	{
		private final String name;
		private final String unit;
		private int startUnits;
		private int endUnits;
		private int priceDoses;
		private double unitPrice;

		private Group(String name, String unit)
		{
			this.name = name;
			this.unit = unit;
		}
	}
}
