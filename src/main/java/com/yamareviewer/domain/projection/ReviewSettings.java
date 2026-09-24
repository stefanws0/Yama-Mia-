package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.PriceMode;
import lombok.Value;

/** The user's review settings at the time of the review (spec 10). */
@Value
public class ReviewSettings
{
	public static final ReviewSettings DEFAULT = new ReviewSettings(PriceMode.GRAND_EXCHANGE, null);

	PriceMode priceMode;
	/** Null = detect the mode. */
	Mode modeOverride;
}
