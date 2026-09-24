package com.yamareviewer.adapter.ui;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.ColorScheme;

/** The navigation button icon, drawn at runtime with Java2D (no binary resource): an orange "Y" of two horns and a stem. */
public final class PanelIcon
{
	static final int SIZE = 16;

	private PanelIcon()
	{
	}

	public static BufferedImage create()
	{
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setColor(ColorScheme.BRAND_ORANGE);
			graphics.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			graphics.draw(new Line2D.Float(3, 2, 8, 8));
			graphics.draw(new Line2D.Float(13, 2, 8, 8));
			graphics.draw(new Line2D.Float(8, 8, 8, 14));
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}
}
