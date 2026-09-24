package com.yamareviewer.domain.review;

import java.util.List;
import lombok.Value;

@Value
public class SupplySummary
{
	List<SupplyLine> lines;
	long totalCost;
	PriceMode priceMode;
}
