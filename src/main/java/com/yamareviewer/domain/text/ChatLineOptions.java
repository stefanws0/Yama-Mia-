package com.yamareviewer.domain.text;

import lombok.Value;

/** Which chat lines to print (spec 7.2, config keys chat*). */
@Value
public class ChatLineOptions
{
	boolean phaseLine;
	boolean prayerLine;
	boolean flareLine;
	boolean specLine;
	boolean deathRecap;
}
