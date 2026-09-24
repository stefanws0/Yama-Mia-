package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Position;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/** The one place that turns local points into template coordinates, so positions are never mixed. */
public class PositionReader
{
	private final Client client;

	public PositionReader(Client client)
	{
		this.client = client;
	}

	@Nullable
	public Position position(@Nullable LocalPoint local)
	{
		WorldPoint world = toWorld(local);
		return world == null ? null : new Position(world.getX(), world.getY(), world.getPlane());
	}

	public int regionId(@Nullable LocalPoint local)
	{
		WorldPoint world = toWorld(local);
		return world == null ? -1 : world.getRegionID();
	}

	@Nullable
	private WorldPoint toWorld(@Nullable LocalPoint local)
	{
		return local == null ? null : WorldPoint.fromLocalInstance(client, local);
	}
}
