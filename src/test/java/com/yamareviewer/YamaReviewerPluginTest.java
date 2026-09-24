package com.yamareviewer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class YamaReviewerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(YamaReviewerPlugin.class);
		RuneLite.main(args);
	}
}
