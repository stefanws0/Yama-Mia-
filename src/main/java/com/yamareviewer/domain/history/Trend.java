package com.yamareviewer.domain.history;

import lombok.Value;

/** The mean of the last 10 kills against the mean of the 10 before; null when there are no values. The UI draws the arrow. */
@Value
public class Trend
{
	Double last10Mean;
	Double previous10Mean;
}
