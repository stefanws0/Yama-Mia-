package com.yamareviewer.domain.review;

import lombok.Value;

/** One consumed item or potion; unit is "x" (items) or "doses". */
@Value
public class SupplyLine
{
	String name;
	int quantity;
	String unit;
	long cost;
	/** Under Familiar Acquisition: food and potions were used but did nothing. */
	boolean noEffect;
}
