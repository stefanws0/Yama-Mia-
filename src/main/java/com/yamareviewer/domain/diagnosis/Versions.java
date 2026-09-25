package com.yamareviewer.domain.diagnosis;

import lombok.Value;

/** What was running when a report was written: plugin version, RuneLite version, game revision (spec 9). */
@Value
public class Versions
{
	String plugin;
	String runeLite;
	String game;
}
