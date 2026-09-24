package com.yamareviewer.domain.review;

import java.util.List;
import lombok.Value;

@Value
public class DeathRecap
{
	List<RecapTick> ticks;
	boolean outOfRunEnergy;
}
