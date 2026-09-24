package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class ProjectileObserved implements DomainEvent
{
	int tick;
	int projectileId;
	/** Null when the projectile targets a tile. */
	Actor target;
	int endTick;
}
