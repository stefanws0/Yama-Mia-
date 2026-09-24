package com.yamareviewer.domain.event;

import lombok.Value;

/** A template (real-world) coordinate, also inside the instance. */
@Value
public class Position
{
	int x;
	int y;
	int plane;
}
