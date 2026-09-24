package com.yamareviewer.adapter.ui;

import java.awt.image.BufferedImage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PanelIconTest
{
	@Test
	public void drawsASixteenPixelIconWithTransparentCornersAndAnOpaqueStem()
	{
		BufferedImage icon = PanelIcon.create();

		assertEquals(16, icon.getWidth());
		assertEquals(16, icon.getHeight());
		assertEquals(0, icon.getRGB(0, 15) >>> 24);
		assertTrue((icon.getRGB(8, 11) >>> 24) > 0);
	}
}
