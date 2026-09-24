package com.yamareviewer;

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
		name = "Development",
		description = "Settings used while capturing logging kills",
		position = 100,
		closedByDefault = true
	)
	String development = "development";

	@Range(min = 1, max = 200)
	@ConfigItem(
		keyName = "rawLogsKept",
		name = "Raw logs kept",
		description = "How many recent kills keep their raw event log, so their reviews can be recalculated",
		position = 1
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
