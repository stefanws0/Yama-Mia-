package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.client.util.Text;

/** Maps RuneLite actors to domain actors. Not final so tests can mock it. */
public class ActorResolver
{
	private final Client client;
	private final IdRegistry ids;
	private final Supplier<Optional<String>> partnerName;

	public ActorResolver(Client client, IdRegistry ids, Supplier<Optional<String>> partnerName)
	{
		this.client = client;
		this.ids = ids;
		this.partnerName = partnerName;
	}

	public Actor resolve(@Nullable net.runelite.api.Actor actor)
	{
		if (actor == null)
		{
			return Actor.other("none");
		}
		if (actor == client.getLocalPlayer())
		{
			return Actor.SELF;
		}
		if (actor instanceof Player)
		{
			String name = actor.getName() == null ? "" : Text.sanitize(actor.getName());
			return partnerName.get().filter(name::equals).isPresent() ? Actor.PARTNER : Actor.other(name);
		}
		if (actor instanceof NPC)
		{
			NPC npc = (NPC) actor;
			int npcId = npc.getId();
			if (ids.is(Role.YAMA, npcId))
			{
				return Actor.YAMA;
			}
			if (ids.is(Role.JUDGE, npcId))
			{
				return Actor.JUDGE;
			}
			if (ids.is(Role.VOID_FLARE, npcId))
			{
				return Actor.flare(npc.getIndex());
			}
			if (ids.roleOf(RoleKind.NPC, npcId).isPresent())
			{
				return Actor.npc(npcId);
			}
			return Actor.other("npc " + npcId);
		}
		return Actor.other("unknown");
	}
}
