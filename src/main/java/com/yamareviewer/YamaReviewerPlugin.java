package com.yamareviewer;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Yama Reviewer",
	description = "Records Yama kills and reviews them after the kill ends. Silent during the fight.",
	tags = {"yama", "pvm", "review", "prayer", "duo"},
	internalName = "yama-reviewer"
)
public class YamaReviewerPlugin extends Plugin
{
	@Provides
	YamaReviewerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(YamaReviewerConfig.class);
	}

	@Override
	protected void startUp()
	{
		log.debug("Yama Reviewer started");
	}

	@Override
	protected void shutDown()
	{
		log.debug("Yama Reviewer stopped");
	}
}
