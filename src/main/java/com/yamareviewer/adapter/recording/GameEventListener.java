package com.yamareviewer.adapter.recording;

import com.yamareviewer.application.command.FightStart;
import com.yamareviewer.application.command.KillSession;
import com.yamareviewer.application.command.SessionState;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.game.NpcUtil;
import net.runelite.client.util.Text;

/**
 * The only receiver of RuneLite game events. Decides when a fight starts and ends and forwards
 * observations; it never classifies and never touches output. All handlers run on the client thread.
 */
@Slf4j
public class GameEventListener
{
	/** How long before the fight a contract item may have left the inventory (spec 6.3). */
	private static final int CONTRACT_LOOKBACK_TICKS = 20;

	private final Client client;
	private final IdRegistry ids;
	private final KillSession session;
	private final EventTranslator translator;
	private final TickSampler sampler;
	private final PositionReader positions;
	private final NpcUtil npcUtil;
	private final Supplier<Future<?>> pendingWrite;

	private final Map<String, Player> players = new HashMap<>();
	private final Set<Integer> pendingWidgetReads = new LinkedHashSet<>();
	private final Map<Integer, Integer> contractItemsLastSeen = new HashMap<>();
	private NPC yama;

	/** The NPC name that marks Yama in any form (the combat NPC and the one on the throne). */
	private static final String YAMA_NAME = "Yama";

	private final Consumer<Integer> unknownYama;
	private final Set<Integer> reportedYamaIds = new HashSet<>();

	public GameEventListener(Client client, IdRegistry ids, KillSession session, EventTranslator translator,
		TickSampler sampler, PositionReader positions, NpcUtil npcUtil, Supplier<Future<?>> pendingWrite,
		Consumer<Integer> unknownYama)
	{
		this.client = client;
		this.ids = ids;
		this.session = session;
		this.translator = translator;
		this.sampler = sampler;
		this.positions = positions;
		this.npcUtil = npcUtil;
		this.pendingWrite = pendingWrite;
		this.unknownYama = unknownYama;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		session.updateRegion(inYamasDomain());
		if (!session.isFighting())
		{
			return;
		}
		record(translator.pollObjectAnimations());
		readPendingWidgets();
		session.record(sampler.sample(session.currentTick(), yama, partner()));
		session.endTick();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		record(translator.gameState(state));
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			session.leave();
			players.clear();
			pendingWidgetReads.clear();
			contractItemsLastSeen.clear();
			yama = null;
			reportedYamaIds.clear();
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		if (ids.is(Role.YAMA, npc.getId()))
		{
			yama = npc;
			startFightIfArmed();
		}
		else
		{
			selfCheck(npc);
		}
		record(translator.npcSpawned(npc));
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		boolean dying = npcUtil.isDying(npc);
		record(translator.npcDespawned(npc, dying));
		if (npc == yama)
		{
			if (dying)
			{
				session.yamaDied();
			}
			yama = null;
		}
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		if (!ids.is(Role.YAMA, event.getNpc().getId()))
		{
			selfCheck(event.getNpc());
		}
		record(translator.npcChanged(event));
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			session.playerDied();
		}
		else if (yama != null && event.getActor() == yama)
		{
			session.yamaDied();
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		NPC npc = event.getMenuEntry().getNpc();
		if (npc != null && ids.is(Role.VOICE_OF_YAMA, npc.getId()))
		{
			EventTranslator.entryChoice(event.getMenuOption()).ifPresent(session::chooseEntry);
		}
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned event)
	{
		Player player = event.getPlayer();
		if (player == client.getLocalPlayer() || player.getName() == null)
		{
			return;
		}
		String name = Text.sanitize(player.getName());
		players.put(name, player);
		session.playerSeen(name);
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event)
	{
		Player player = event.getPlayer();
		if (player == client.getLocalPlayer() || player.getName() == null)
		{
			return;
		}
		String name = Text.sanitize(player.getName());
		players.remove(name, player);
		session.playerLeft(name);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (session.isFighting())
		{
			record(translator.varbitChanged(event));
		}
	}

	/** The contract name may be set after the interface loads, so it is read on the following ticks. */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		for (int componentId : ids.ids(Role.CONTRACT_NAME_WIDGET))
		{
			if (componentId >>> 16 == event.getGroupId())
			{
				pendingWidgetReads.add(componentId);
			}
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		record(translator.gameObjectSpawned(event));
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		record(translator.gameObjectDespawned(event));
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (session.isFighting())
		{
			record(translator.animationChanged(event));
		}
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		if (session.isFighting())
		{
			record(translator.graphicChanged(event));
		}
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated event)
	{
		if (session.isFighting())
		{
			record(translator.graphicsObjectCreated(event));
		}
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		if (session.isFighting())
		{
			record(translator.projectileMoved(event));
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (session.isFighting())
		{
			record(translator.hitsplatApplied(event));
		}
	}

	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		if (session.isFighting())
		{
			record(translator.overheadTextChanged(event));
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (session.state() == SessionState.ARMED && event.getContainerId() == InventoryID.INV)
		{
			rememberContractItems(event.getItemContainer());
		}
		if (session.isFighting())
		{
			record(translator.itemContainerChanged(event));
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (session.isFighting())
		{
			record(translator.chatMessage(event));
		}
	}

	/** Keeps a raw log that is still being written when the client closes. */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		event.waitFor(pendingWrite.get());
	}

	private void startFightIfArmed()
	{
		session.updateRegion(inYamasDomain());
		Player self = client.getLocalPlayer();
		if (self == null || session.isFighting())
		{
			return;
		}
		String selfName = self.getName() == null ? "" : Text.sanitize(self.getName());
		List<String> present = players.keySet().stream().sorted().collect(Collectors.toList());
		session.yamaSpawned(new FightStart(selfName, positions.position(self.getLocalLocation()), present));
		if (session.isFighting())
		{
			Map<Integer, Integer> inventoryNow = currentInventory();
			translator.reset(inventoryNow);
			recordContractConsumedAtChallenge(inventoryNow);
		}
	}

	/**
	 * Spec 5.3: an NPC named "Yama" with an id that has no NPC role, while armed, means the combat Yama was
	 * renumbered and no kill can be recorded. Reported once per id per login; the report is written elsewhere.
	 */
	private void selfCheck(NPC npc)
	{
		String name = npc.getName();
		if (name == null || !YAMA_NAME.equals(Text.removeTags(name)) || ids.roleOf(RoleKind.NPC, npc.getId()).isPresent())
		{
			return;
		}
		session.updateRegion(inYamasDomain());
		if (session.state() == SessionState.ARMED && reportedYamaIds.add(npc.getId()))
		{
			log.debug("Unknown NPC named Yama with id {} while armed", npc.getId());
			unknownYama.accept(npc.getId());
		}
	}

	private void rememberContractItems(ItemContainer inventory)
	{
		for (int itemId : EventTranslator.aggregate(inventory.getItems()).keySet())
		{
			if (isContractItem(itemId))
			{
				contractItemsLastSeen.put(itemId, client.getTickCount());
			}
		}
	}

	private boolean isContractItem(int itemId)
	{
		for (Role role : Role.CONTRACT_ITEMS)
		{
			if (ids.is(role, itemId))
			{
				return true;
			}
		}
		return false;
	}

	/** A contract is consumed at the challenge, possibly before Yama spawns; record it as part of the fight. */
	private void recordContractConsumedAtChallenge(Map<Integer, Integer> inventoryNow)
	{
		int now = client.getTickCount();
		contractItemsLastSeen.forEach((itemId, lastSeen) ->
		{
			if (!inventoryNow.containsKey(itemId) && now - lastSeen <= CONTRACT_LOOKBACK_TICKS)
			{
				session.record(translator.contractConsumed(itemId));
			}
		});
		contractItemsLastSeen.clear();
	}

	private void readPendingWidgets()
	{
		for (Iterator<Integer> it = pendingWidgetReads.iterator(); it.hasNext(); )
		{
			int componentId = it.next();
			Widget widget = client.getWidget(componentId);
			if (widget != null && widget.getText() != null && !widget.getText().isEmpty())
			{
				record(translator.widgetText(componentId, Text.removeTags(widget.getText())));
				it.remove();
			}
		}
	}

	private void record(List<DomainEvent> events)
	{
		events.forEach(session::record);
	}

	private boolean inYamasDomain()
	{
		Player self = client.getLocalPlayer();
		return self != null && ids.is(Role.YAMAS_DOMAIN, positions.regionId(self.getLocalLocation()));
	}

	@Nullable
	private Player partner()
	{
		return session.partnerName().map(players::get).orElse(null);
	}

	private Map<Integer, Integer> currentInventory()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		return inventory == null ? Map.of() : EventTranslator.aggregate(inventory.getItems());
	}
}
