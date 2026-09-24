package com.yamareviewer.domain.review;

/** Why a section is not shown (spec 6.1). Only HEALTH_CHECK_FAILED and ERROR make a review incomplete. */
public enum HiddenReason
{
	IDS_NOT_CAPTURED("IDs not captured"),
	NOT_APPLICABLE("Not applicable"),
	CONTRACT("Not meaningful under this contract"),
	HEALTH_CHECK_FAILED("Health check failed"),
	ERROR("Error while reviewing");

	private final String label;

	HiddenReason(String label)
	{
		this.label = label;
	}

	public String label()
	{
		return label;
	}
}
