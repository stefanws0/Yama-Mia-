package com.yamareviewer;

import com.yamareviewer.domain.review.PriceMode;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(YamaReviewerConfig.GROUP)
public interface YamaReviewerConfig extends Config
{
	String GROUP = "yamareviewer";

	@ConfigSection(
		name = "Chat summary",
		description = "Which lines of the after-kill summary are printed in the chat",
		position = 10
	)
	String chat = "chat";

	@ConfigSection(
		name = "Review",
		description = "How kills are reviewed and kept",
		position = 20
	)
	String review = "review";

	@ConfigSection(
		name = "Development",
		description = "Settings used while capturing logging kills",
		position = 100,
		closedByDefault = true
	)
	String development = "development";

	@ConfigItem(
		keyName = "chatPhaseLine",
		name = "Phase times",
		description = "Kill time and the time of each phase",
		position = 11,
		section = chat
	)
	default boolean chatPhaseLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatPrayerLine",
		name = "P3 prayers, crashes and waves",
		description = "Prayer accuracy, crash lines and waves dodged",
		position = 12,
		section = chat
	)
	default boolean chatPrayerLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatFlareLine",
		name = "Flares and damage",
		description = "Flares killed and damage taken",
		position = 13,
		section = chat
	)
	default boolean chatFlareLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatSpecLine",
		name = "Specs and supplies",
		description = "Special attacks, drains and supply cost",
		position = 14,
		section = chat
	)
	default boolean chatSpecLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatDeathRecap",
		name = "Death recap",
		description = "After a death, the last ten ticks tick by tick",
		position = 15,
		section = chat
	)
	default boolean chatDeathRecap()
	{
		return false;
	}

	@ConfigItem(
		keyName = "priceSource",
		name = "Supply prices",
		description = "Price supplies at Grand Exchange or high alchemy value",
		position = 21,
		section = review
	)
	default PriceMode priceSource()
	{
		return PriceMode.GRAND_EXCHANGE;
	}

	@ConfigItem(
		keyName = "modeOverride",
		name = "Mode",
		description = "Detect solo, duo host or duo joiner automatically, or force one",
		position = 22,
		section = review
	)
	default ModeOverride modeOverride()
	{
		return ModeOverride.AUTO;
	}

	@Range(min = 10, max = 500)
	@ConfigItem(
		keyName = "historySize",
		name = "History size",
		description = "How many kills are kept per mode and contract",
		position = 23,
		section = review
	)
	default int historySize()
	{
		return 50;
	}

	@Range(min = 1, max = 200)
	@ConfigItem(
		keyName = "rawLogsKept",
		name = "Raw logs kept",
		description = "How many recent kills keep their raw event log, so their reviews can be recalculated",
		position = 24,
		section = review
	)
	default int rawLogsKept()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "captureMode",
		name = "Capture mode",
		description = "Also record other actors and every animated object, for confirming IDs after a game update",
		position = 101,
		section = development
	)
	default boolean captureMode()
	{
		return false;
	}
}
