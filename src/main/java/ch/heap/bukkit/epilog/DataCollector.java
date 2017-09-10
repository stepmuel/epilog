package ch.heap.bukkit.epilog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryInteractEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.json.JSONArray;
import org.json.JSONObject;

public class DataCollector {
	Epilog epilog = null;
	
	public ArrayList<ItemTypeStringProvider> itemTypeStringProviders = new ArrayList<ItemTypeStringProvider>();
	
	public DataCollector(Epilog epilog) {
		this.epilog = epilog;
	}
	
	public void addData(LogEvent logEvent) {
		logEvent.needsData = false;
		// check if event is valid
		Event event = logEvent.event;
		if (event==null) {
			logEvent.ignore = true;
			return;
		}
		logEvent.eventName = event.getEventName();
		if (event instanceof Cancellable) {
			if (((Cancellable)event).isCancelled()) {
				logEvent.ignore = true;
				return;
			}
		}
		// add data
		if (event instanceof PlayerMoveEvent) {
			addMovementData(logEvent, (PlayerMoveEvent)event);
		} else if (event instanceof EntityDamageEvent||event instanceof EntityRegainHealthEvent) {
			addDamageData(logEvent, (EntityEvent)event);
		} else if (event instanceof AsyncPlayerChatEvent) {
			AsyncPlayerChatEvent chatEvent = (AsyncPlayerChatEvent)event;
			logEvent.player = chatEvent.getPlayer();
			if (this.epilog.logChats) {
				logEvent.data.put("msg", chatEvent.getMessage());
			}
		} else {
			// add data by introspection
			addGenericData(logEvent, event);
		}
		if (logEvent.player==null) {
			logEvent.ignore = true;
			return;
		}
	}
	
	public void addItemTypeStringProvider(Object obj, Method method) {
		this.itemTypeStringProviders.add(new ItemTypeStringProvider(obj, method));
	}
	
	public String itemTypeString(ItemStack item) {
		String ans = null;
		for (ItemTypeStringProvider sp : this.itemTypeStringProviders) {
			ans = sp.stringForItem(item);
			if (ans!=null) return ans;
		}
		ans = item.getType().toString();
		Map<Enchantment, Integer> enchantments = item.getEnchantments();
		for (Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
			ans += ":" + entry.getKey().getName() + "." + entry.getValue();
		}
		// TODO: potion effects
		return ans;
	}
	
	private static void addMovementData(LogEvent logEvent, PlayerMoveEvent event) {
		logEvent.player = event.getPlayer();
		Map <String, Object> data = logEvent.data;
		Location loc = event.getTo();
		data.put("x", loc.getX());
		data.put("y", loc.getY());
		data.put("z", loc.getZ());
		data.put("pitch", loc.getPitch());
		data.put("yaw", loc.getYaw());
	}
	
	private static void addDamageData(LogEvent logEvent, EntityEvent event) {
		Map <String, Object> data = logEvent.data;
		Entity e1 = event.getEntity();
		double health = -1;
		if (e1 instanceof LivingEntity) {
			health = ((LivingEntity)e1).getHealth() / ((LivingEntity)e1).getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
			data.put("health", health);
		}
		
		boolean e1IsPlayer = e1 instanceof Player;
		if (e1IsPlayer) {
			logEvent.player = (Player)e1;
		}
		
		if (event instanceof EntityRegainHealthEvent) {
			logEvent.eventName = "PlayerRegainHealthEvent";
			EntityRegainHealthEvent ehe = (EntityRegainHealthEvent)event;
			data.put("damage", -ehe.getAmount());
			data.put("cause", ehe.getRegainReason().name());
			return;
		}
		
		EntityDamageEvent ede = (EntityDamageEvent)event;
		data.put("damage", ede.getDamage());
		
		Block block = null;
		
		data.put("cause", ede.getCause().name());
		if (event instanceof EntityDamageByEntityEvent) {
			EntityDamageByEntityEvent evt = (EntityDamageByEntityEvent) event;
			Entity e2 = evt.getDamager();
			Projectile projectile = null;
			if (e2 instanceof Projectile) {
				projectile = (Projectile)e2;
				data.put("projectile", projectile.getType().name());
				ProjectileSource shooter = projectile.getShooter();
				if (shooter instanceof Entity) {
					e2 = (Entity)shooter;
				} else if (shooter instanceof BlockProjectileSource) {
					block = ((BlockProjectileSource)shooter).getBlock();
				}
			}
			if (block==null) {
				boolean e2IsPlayer = e2 instanceof Player;
				if (e1IsPlayer) {
					data.put("entityID", e2.getUniqueId());
					data.put("entity", e2.getType().name());
					if (e2IsPlayer) {
						logEvent.eventName = "PlayerDamageByPlayerEvent";
					} else {
						logEvent.eventName = "PlayerDamageByEntityEvent";
					}
				} else if (e2IsPlayer) {
					logEvent.eventName = "EntityDamageByPlayerEvent";
					logEvent.player = (Player)e2;
					data.put("entityID", e1.getUniqueId());
					data.put("entity", e1.getType().name());
				}
			}
		} else if (event instanceof EntityDamageByBlockEvent) {
			EntityDamageByBlockEvent evt = (EntityDamageByBlockEvent) event;
			block = evt.getDamager();
		} else if (event instanceof EntityDamageEvent) {
			if (e1IsPlayer) logEvent.eventName = "PlayerDamageEvent";
		}
		if (block!=null) {
			if (e1IsPlayer) logEvent.eventName = "PlayerDamageByBlockEvent";
			data.put("material", block.getType().name());
			data.put("blockX", block.getX());
	    	data.put("blockY", block.getY());
	    	data.put("blockZ", block.getZ());
		}
	}
	
	private void addGenericData(LogEvent logEvent, Event event) {
		Map <String, Object> data = logEvent.data;
		
		Player player = null;
		Object entity = null;
		Block block = null;
		Material material = null;
		BlockFace blockFace = null;
		ItemStack itemStack = null;
		boolean doIntrospection = false;
		
		// figure out what kind of data we can get
		if (event instanceof PlayerToggleFlightEvent) {
			data.put("var", ((PlayerToggleFlightEvent)event).isFlying()?1:0);
		} else if (event instanceof PlayerToggleSprintEvent) {
			data.put("var", ((PlayerToggleSprintEvent)event).isSprinting()?1:0);
		} else if (event instanceof PlayerToggleSneakEvent) {
			data.put("var", ((PlayerToggleSneakEvent)event).isSneaking()?1:0);
		} else if (event instanceof PlayerItemHeldEvent) {
			data.put("var", ((PlayerItemHeldEvent)event).getNewSlot());
		} else if (event instanceof PlayerExpChangeEvent) {
			PlayerExpChangeEvent pexcEvent = (PlayerExpChangeEvent) event;
			data.put("var", pexcEvent.getAmount()+pexcEvent.getPlayer().getTotalExperience());
		} else if (event instanceof PlayerInteractEvent) {
			blockFace = ((PlayerInteractEvent)event).getBlockFace();
			block = ((PlayerInteractEvent)event).getClickedBlock();
			data.put("enum", ((PlayerInteractEvent)event).getAction().name());
		} else if (event instanceof FurnaceExtractEvent) {
			material = ((FurnaceExtractEvent)event).getItemType();
			block = ((FurnaceExtractEvent)event).getBlock();
			data.put("var", ((FurnaceExtractEvent)event).getItemAmount());
		} else if (event instanceof PlayerLevelChangeEvent) {
			data.put("var", ((PlayerLevelChangeEvent)event).getNewLevel());
		} else if (event instanceof PlayerTeleportEvent) { // is instance of PlayerMoveEvent
			data.put("enum", ((PlayerTeleportEvent)event).getCause().name());
		} else if (event instanceof FoodLevelChangeEvent) { // entity event
			data.put("var", ((FoodLevelChangeEvent)event).getFoodLevel());
		} else if (event instanceof PlayerCommandPreprocessEvent) {
			String cmd = ((PlayerCommandPreprocessEvent)event).getMessage();
			data.put("cmd", cmd.split(" ", 2)[0]);
		} else if (event instanceof CraftItemEvent) {
			itemStack = ((CraftItemEvent)event).getRecipe().getResult();
			entity = ((CraftItemEvent)event).getWhoClicked();
			doIntrospection = true;
		} else if (event instanceof ProjectileLaunchEvent) {
			Projectile projectile = ((ProjectileLaunchEvent)event).getEntity();
			data.put("enum", projectile.getType().name());
			ProjectileSource shooter = projectile.getShooter();
			if (shooter instanceof Entity) entity = shooter;
			doIntrospection = true;
		} else if (event instanceof InventoryClickEvent || event instanceof InventoryDragEvent) {
			entity = ((InventoryInteractEvent)event).getWhoClicked();
			doIntrospection = true;
			logEvent.ignore = true; // don't send to server
		} else {
			doIntrospection = true;
			for (Method method : event.getClass().getMethods()){
				String methodName = method.getName();
			    try {
			    	if (methodName.equals("getBlock")) {
				    	block = (Block) method.invoke(event);
				    } else if (methodName.equals("getMaterial")) {
				    	material = (Material) method.invoke(event);
				    } else if (methodName.equals("getBlockFace")) {
				    	blockFace = (BlockFace) method.invoke(event);
				    } else if (methodName.equals("getItem")||methodName.equals("getItemStack")||methodName.equals("getItemDrop")) {
				    	Object item = method.invoke(event);
				    	if (item instanceof Item) itemStack = ((Item)item).getItemStack();
				    	else itemStack = (ItemStack) item;
				    } else if (methodName.equals("getPlayer")) {
				    	player = (Player) method.invoke(event);
				    } else if (methodName.equals("getEntity")) {
				    	entity = method.invoke(event);
				    }
			    } catch (Exception e) {
			    	this.epilog.getLogger().warning("unable to get generic data for event: " + event.getEventName());
			    	e.printStackTrace();
			    }
			}
		}
		
		if (doIntrospection==false) {
			try {
				entity = (Player) event.getClass().getMethod("getPlayer", (Class<?>[]) null).invoke(event);
			} catch (Exception e) {}
			try {
				entity = event.getClass().getMethod("getEntity", (Class<?>[]) null).invoke(event);
			} catch (Exception e) {}
		}
		
		// fill out generic data fields
	    if (block!=null) {
	    	data.put("blockX", block.getX());
	    	data.put("blockY", block.getY());
	    	data.put("blockZ", block.getZ());
	    	if (material==null) {
	    		material = block.getType();
	    	}
	    }
	    if (logEvent.material!=null) {
	    	material = logEvent.material;
	    }
	    if (material!=null) {
	    	data.put("material", material.name());
	    }
	    if (blockFace!=null) {
	    	data.put("blockFace", blockFace.name());
	    }
	    if (itemStack!=null) {
	    	data.put("material", this.itemTypeString(itemStack));
	    	data.put("var", itemStack.getAmount());
	    }
	    if (player!=null) {
	    	logEvent.player = player;
	    } else if (entity instanceof Player) {
	    	logEvent.player = (Player) entity;
	    }
//	    if (material!=null) {
//	    	System.out.println(event.getEventName() + ": " + material.name());
//	    }
	}
	
	public JSONArray getOnlinePlayers() {
		return playerArray(this.epilog.getServer().getOnlinePlayers());
	}
	
	// collect server data
	public void addServerMetaData(LogEvent logEvent) {
		Server server = this.epilog.getServer();
		JSONObject data = new JSONObject();
		
		// get loaded plugins 
		JSONArray plugins = new JSONArray();
		for (Plugin plugin : server.getPluginManager().getPlugins()) {
			plugins.put(getPluginMetaData(plugin));
		}
		data.put("plugins", plugins);
		
		// collect some more possibly usefull properties
		data.put("name", server.getName()); // String
		data.put("version", server.getVersion()); // String
		data.put("bukkitVersion", server.getBukkitVersion()); // String
		data.put("maxPlayers", server.getMaxPlayers()); // int
		data.put("port", server.getPort()); // int
		data.put("viewDistance", server.getViewDistance()); // int
		data.put("ip", server.getIp()); // String
		data.put("serverName", server.getServerName()); // String
		data.put("serverId", server.getServerId()); // String
		data.put("worldType", server.getWorldType()); // String
		data.put("generateStructures", server.getGenerateStructures() ? 1 : 0); // boolean
		data.put("allowEnd", server.getAllowEnd() ? 1 : 0); // boolean
		data.put("allowNether", server.getAllowNether() ? 1 : 0); // boolean
		data.put("whitelistedPlayers", playerArray(server.getWhitelistedPlayers())); // Set<OfflinePlayer>
		data.put("connectionThrottle", server.getConnectionThrottle()); // long
		data.put("ticksPerAnimalSpawns", server.getTicksPerAnimalSpawns()); // int
		data.put("ticksPerMonsterSpawns", server.getTicksPerMonsterSpawns()); // int
		data.put("spawnRadius", server.getSpawnRadius()); // int
		data.put("onlineMode", server.getOnlineMode() ? 1 : 0); // boolean
		data.put("allowFlight", server.getAllowFlight() ? 1 : 0); // boolean
		
		data.put("ipBans", server.getIPBans()); // Set<String>
		data.put("bannedPlayers", playerArray(server.getBannedPlayers())); // Set<OfflinePlayer>
		data.put("operators", playerArray(server.getOperators())); // Set<OfflinePlayer>
		
		logEvent.data.put("serverMeta", data);
	}
	
	private static JSONObject getPluginMetaData(Plugin plugin) {
		JSONObject data = new JSONObject();
		PluginDescriptionFile desc = plugin.getDescription();
		data.put("name", desc.getName());
		data.put("version", desc.getVersion());
		if (desc.getCommands()!=null) {
			data.put("commands", desc.getCommands().keySet().toArray());
		}
		return data;
	}
	
	public JSONObject getWorlds(World only) {
		Server server = this.epilog.getServer();
		JSONObject worlds = new JSONObject();
		for (World world : server.getWorlds()) {
			if (only!=null && !world.equals(only)) continue;
			worlds.put(world.getUID().toString(), getWorldMetaData(world));
		}
		return worlds;
	}
	
	private static JSONObject getWorldMetaData(World world) {
		JSONObject data = new JSONObject();
		
		// collect some more possibly usefull properties
//		data.put("entities", world.getEntities()); // List<Entity>
//		data.put("livingEntities", world.getLivingEntities()); // List<LivingEntity>
//		data.put("players", world.getPlayers()); // List<Player>
		data.put("name", world.getName()); // String
		data.put("uuid", world.getUID()); // UUID
		data.put("time", world.getTime()); // long
		data.put("fullTime", world.getFullTime()); // long
//		data.put("weatherDuration", world.getWeatherDuration()); // int
//		data.put("thunderDuration", world.getThunderDuration()); // int
		data.put("environment", world.getEnvironment().name()); // Environment
		data.put("seed", world.getSeed()); // long
		data.put("pvp", world.getPVP() ? 1 : 0); // boolean
//		data.put("generator", world.getGenerator()); // ChunkGenerator
//		data.put("populators", world.getPopulators()); // List<BlockPopulator>
		data.put("allowAnimals", world.getAllowAnimals() ? 1 : 0); // boolean
		data.put("allowMonsters", world.getAllowMonsters() ? 1 : 0); // boolean
		data.put("maxHeight", world.getMaxHeight()); // int
		data.put("seaLevel", world.getSeaLevel()); // int
		data.put("keepSpawnInMemory", world.getKeepSpawnInMemory() ? 1 : 0); // boolean
		data.put("difficulty", world.getDifficulty().name()); // Difficulty
//		data.put("worldFolder", world.getWorldFolder()); // File
		data.put("worldType", world.getWorldType().name()); // WorldType
		data.put("ticksPerAnimalSpawns", world.getTicksPerAnimalSpawns()); // long
		data.put("ticksPerMonsterSpawns", world.getTicksPerMonsterSpawns()); // long
		
		data.put("uuid", world.getUID().toString()); // string
		JSONObject spawnLocation = new JSONObject();
		Location loc = world.getSpawnLocation();
		spawnLocation.put("x", loc.getX());
		spawnLocation.put("y", loc.getY());
		spawnLocation.put("z", loc.getZ());
		spawnLocation.put("pitch", loc.getPitch());
		spawnLocation.put("yaw", loc.getYaw());
		data.put("spawnLocation", spawnLocation);
		
		return data;
	}
	
	private static JSONArray playerArray(Collection<? extends OfflinePlayer> playerSet) {
		JSONArray result = new JSONArray();
		for (OfflinePlayer p : playerSet) {
			result.put(p.getUniqueId().toString());
		}
		return result;
	}
	
	private class ItemTypeStringProvider {
		private Object obj;
		private Method method;
		public ItemTypeStringProvider(Object obj, Method method) {
			this.obj = obj;
			this.method = method;
		}
		public String stringForItem(ItemStack item) {
			String ans = null;
			try {
				ans = (String)this.method.invoke(obj, item);
			} catch (Exception e) {
				e.printStackTrace();
			}
			return ans;
		}
	}
}
