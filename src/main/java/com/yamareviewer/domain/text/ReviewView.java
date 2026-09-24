package com.yamareviewer.domain.text;

import java.util.List;
import lombok.Value;

/** Everything the panel shows for one review; plain strings only. */
@Value
public class ReviewView
{
	String headline;
	String status;
	List<ViewSection> sections;
}
