package ch.heap.bukkit.epilog;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryInteractEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import ch.heap.bukkit.epilog.LogEvent;

public class InventoryTracker {
	private Epilog epilog;
	private Map<UUID, String> playerItemInHand = new HashMap <UUID, String>();
	private Map<UUID, InventoryContent> playerInventory = new HashMap <UUID, InventoryContent>();
	private Map<UUID, InventoryContent> playerArmor = new HashMap <UUID, InventoryContent>();
	private Map<UUID, InventoryContent> playerEnderChest = new HashMap <UUID, InventoryContent>();
	private Map<Location, InventoryContent> chestInventory = new HashMap <Location, InventoryContent>();
	private Map<UUID, Map<Location, InventoryContent>> chestInventoryBuffer = new HashMap <UUID, Map<Location, InventoryContent>>();
	
	public InventoryTracker(Epilog el) {
		epilog = el;
		try {
			Collection<String> eventNames = null;
			Method method = null;
			eventNames = Arrays.asList(
					// ItemSpawnEvent for /give command?
					// TODO: remove state for players leaving server
					// player die triggers InventoryCloseEvent
					"inventoryChange", // extern event (e.g. DiviningRod)
					"PlayerRespawnEvent",
					"PlayerItemHeldEvent",
					"PlayerPickupItemEvent",
					"PlayerDropItemEvent",
					"InventoryCloseEvent",
					"PlayerItemBreakEvent",
					"BlockPlaceEvent",
					"PlayerItemConsumeEvent",
					"ProjectileLaunchEvent",
					"PlayerJoinEvent",
					"CraftItemEvent"
			);
			method = this.getClass().getMethod("inventoryHandler", LogEvent.class);
			epilog.addEventObserver(this, method, eventNames);
			eventNames = Arrays.asList(
					"InventoryOpenEvent",
					"InventoryCloseEvent",
					"InventoryClickEvent",
					"InventoryDragEvent",
					"BlockBreakEvent"
			);
			method = this.getClass().getMethod("chestHandler", LogEvent.class);
			epilog.addEventObserver(this, method, eventNames);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	public void onEnable() {
		// TODO: handle log enable/disable
		for (Player p : epilog.getServer().getOnlinePlayers()) {
			inventoryChange(p, System.currentTimeMillis());
		}
	}
	public void inventoryHandler(LogEvent event) {
		Player p = event.player;
		if (p==null) return;
		inventoryChange(p, event.time);
	}
	private void inventoryChange(Player p, long time) {
		checkItemInHand(p, time);
		checkInventory("InventoryContent", p, time, this.playerInventory, p.getInventory().getContents());
		checkInventory("ArmorContent", p, time, this.playerArmor, p.getInventory().getArmorContents());
		checkInventory("EnderChestContent", p, time, this.playerEnderChest, p.getEnderChest().getContents());
	}
	
	public void chestHandler(LogEvent event) {
		Player p = event.player;
		if (p==null) return;
		if (event.event instanceof InventoryOpenEvent) {
			InventoryOpenEvent ioe = (InventoryOpenEvent)event.event;
			checkChest(ioe.getInventory().getHolder(), null, event.time);
		} else if (event.event instanceof BlockBreakEvent) {
			Material material = event.material;
			if (material==Material.CHEST) {
				Block block = ((BlockBreakEvent)event.event).getBlock();
				Location location = block.getLocation();
				checkChest(location, event.player, event.time, null);
				this.chestInventory.remove(location);
			}
		} else if (event.event instanceof InventoryInteractEvent) {
			InventoryInteractEvent iae = (InventoryInteractEvent)event.event;
			checkChest(iae.getInventory().getHolder(), event.player, event.time);
		} else if (event.event instanceof InventoryCloseEvent) {
			flushChestInventoryBuffer(event.player, event.time);
		}
	}
	
	private void checkItemInHand(Player p, long time) {
		// DEBUG: is latency enough to give new item? use getNewSlot()
		ItemStack item = p.getInventory().getItemInMainHand();
		// ItemStack item = p.getItemInHand(); // Old code.
		String itemType = epilog.dataCollector.itemTypeString(item);
		String previousType = playerItemInHand.get(p.getUniqueId());
		if (itemType.equals(previousType)==false) {
			playerItemInHand.put(p.getUniqueId(), itemType);
			LogEvent logEvent = new LogEvent("PlayerItemInHandEvent", time, p);
			logEvent.data.put("material", itemType);
			epilog.postEvent(logEvent);
			// System.out.println("PlayerItemInHandEvent: "+itemType);
		}
	}
	
	private void checkInventory(String eventName, Player player, long time, Map<UUID, InventoryContent> state, ItemStack[] content) {
		UUID uid = player.getUniqueId();
		InventoryContent previous = state.get(uid);
		InventoryContent current = new InventoryContent(content);
		InventoryContent diff = null;
		if (previous!=null) {
			diff = current.diff(previous);
			if (diff.amount.size()==0) return;
		}
		// inventory content has changed
		LogEvent logEvent = new LogEvent(eventName, time, player);
		if (previous==null || previous.amount.size()==0 || current.amount.size()==0) {
			logEvent.data.put("content", current.amount);
		} else {
			logEvent.data.put("delta", diff.amount);
		}
		epilog.postEvent(logEvent);
		state.put(uid, current);
	}
	
	private void checkChest(Location location, Player player, long time, ItemStack[] content) {
		InventoryContent previous = this.chestInventory.get(location);
		InventoryContent current = new InventoryContent(content);
		InventoryContent diff = null;
		if (previous!=null) {
			diff = current.diff(previous);
			if (diff.amount.size()==0) return;
		}
		if (player!=null && content!=null) {
			// buffer diff until inventory close, if chest is not distroyed
			if (diff==null) {
				player = null;
			} else {
				this.chestInventory.put(location, current);
				InventoryContent buffer = getChestInventoryBuffer(player.getUniqueId(), location);
				buffer.add(diff);
				return;
			}
		}
		// chest content has changed
		LogEvent logEvent = new LogEvent("ChestContent", time, player);
		logEvent.data.put("blockX", location.getX());
		logEvent.data.put("blockY", location.getY());
		logEvent.data.put("blockZ", location.getZ());
		logEvent.data.put("worldUUID", location.getWorld().getUID().toString());
		if (previous==null || previous.amount.size()==0 || current.amount.size()==0) {
			logEvent.data.put("content", current.amount);
		} else {
			logEvent.data.put("delta", diff.amount);
		}
		epilog.postEvent(logEvent);
		this.chestInventory.put(location, current);
	}
	private void flushChestInventoryBuffer(Player player, long time) {
		Map<Location, InventoryContent> inventories = chestInventoryBuffer.get(player.getUniqueId());
		if (inventories==null) return;
		for (Entry<Location, InventoryContent> entry : inventories.entrySet()) {
			if (entry.getValue().amount.size()==0) continue;
			Location location = entry.getKey();
			LogEvent logEvent = new LogEvent("ChestContent", time, player);
			logEvent.data.put("blockX", location.getX());
			logEvent.data.put("blockY", location.getY());
			logEvent.data.put("blockZ", location.getZ());
			logEvent.data.put("worldUUID", location.getWorld().getUID().toString());
			logEvent.data.put("delta", entry.getValue().amount);
			epilog.postEvent(logEvent);
		}
		chestInventoryBuffer.remove(player.getUniqueId());
	}
	
	private void checkChest(InventoryHolder ih, Player player, long time) {
		if (ih instanceof BlockState) {
			Block block = ((BlockState)ih).getBlock();
			if (block.getType()==Material.CHEST) {
				checkChest(block.getLocation(), player, time, ih.getInventory().getContents());
			}
		} else if (ih instanceof DoubleChest) {
			DoubleChestInventory dci = (DoubleChestInventory)ih.getInventory();
			Inventory inv1 = dci.getLeftSide();
			Inventory inv2 = dci.getRightSide();
			checkChest(((Chest)inv1.getHolder()).getLocation(), player, time, inv1.getContents());
			checkChest(((Chest)inv2.getHolder()).getLocation(), player, time, inv2.getContents());
		}
	}
	
	private InventoryContent getChestInventoryBuffer(UUID uid, Location location) {
		if (!chestInventoryBuffer.containsKey(uid)) {
			chestInventoryBuffer.put(uid, new HashMap<Location, InventoryContent>());
		}
		if (!chestInventoryBuffer.get(uid).containsKey(location)) {
			chestInventoryBuffer.get(uid).put(location, new InventoryContent());
		}
		return chestInventoryBuffer.get(uid).get(location);
	}
	
	private class InventoryContent {
		private Map<String, Integer> amount;
		
		public InventoryContent() {
			amount = new HashMap<String, Integer>();
		}
		public InventoryContent(Map<String, Integer> amount) {
			this.amount = amount;
		}
		public InventoryContent(ItemStack[] inventory) {
			amount = new HashMap<String, Integer>();
			if (inventory==null) return;
			for (ItemStack item : inventory) {
				if (item==null) continue;
				String key = epilog.dataCollector.itemTypeString(item);
				int n = amount.containsKey(key) ? amount.get(key) : 0;
				n += item.getAmount();
				amount.put(key, n);
			}
		}
		public InventoryContent diff(InventoryContent other) {
			Map<String, Integer> diff = new HashMap<String, Integer>();
			InventoryContent result = new InventoryContent(diff);
			if (this.amount.equals(other.amount)) return result;
			Set<String> allKeys = new HashSet<String>(this.amount.keySet());
			allKeys.addAll(other.amount.keySet());
			for (String key : allKeys) {
				int n1 = this.amount.containsKey(key) ? this.amount.get(key) : 0;
				int n2 = other.amount.containsKey(key) ? other.amount.get(key) : 0;
				if (n1!=n2) diff.put(key, n1-n2);
			}
			return result;
		}
		public void add(InventoryContent other) {
			for (String key : other.amount.keySet()) {
				int n1 = this.amount.containsKey(key) ? this.amount.get(key) : 0;
				int n2 = other.amount.containsKey(key) ? other.amount.get(key) : 0;
				if (n1+n2==0) {
					this.amount.remove(key);
				} else {
					this.amount.put(key, n1+n2);
				}
			}
		} 
	}
}
