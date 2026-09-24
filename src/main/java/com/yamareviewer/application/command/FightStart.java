package com.yamareviewer.application.command;

import com.yamareviewer.domain.event.Position;
import java.util.List;
import lombok.Value;

@Value
public class FightStart
{
	String selfName;
	Position selfPosition;
	/** Other players already in the arena when Yama spawns. */
	List<String> playersPresent;
}
