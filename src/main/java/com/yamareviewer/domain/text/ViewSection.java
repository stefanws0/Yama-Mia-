package com.yamareviewer.domain.text;

import java.util.List;
import lombok.Value;

/** One rendered section; hiddenReason is null when the section is shown. */
@Value
public class ViewSection
{
	String title;
	List<String> lines;
	String hiddenReason;
}
