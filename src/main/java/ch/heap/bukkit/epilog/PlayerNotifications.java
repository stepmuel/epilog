package ch.heap.bukkit.epilog;

import java.util.Arrays;
import java.util.LinkedHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;


public class PlayerNotifications {
	private final Epilog epilog;
	private final LinkedHashMap<String, String> messages = new LinkedHashMap<String, String>(); 
	
	public PlayerNotifications(Epilog epilog) {
		this.epilog = epilog;
		try {
			epilog.addEventObserver(
				this, 
				this.getClass().getMethod("eventHandler", LogEvent.class), 
				Arrays.asList("PlayerJoinEvent", "inform")
			);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public void updateReady() {
		inform("updateReady", "New plugin versions are ready to install. Type '/reload' or restart your minecraft server.", true);
	}
	
	public void updateAvailable(int n) {
		if (n==0) {
			this.messages.remove("updateAvailable");
		} else {
			inform("updateAvailable", "New plugin versions are available. Type '/el update'.", false);
		}
	}
	
	public void inform(String key, String msg) {
		inform(key, msg, false);
	}
	public void inform(String key, String message, boolean repeat) {
		if (message==null) {
			messages.remove(key);
			return;
		}
		if (repeat==false && message.equals(messages.get(key))) return;
		messages.remove(key); // order messages by age
		messages.put(key, message);
		broadcast(message);
		
	}
	
	private void broadcast(final String message) {
		Bukkit.getScheduler().scheduleSyncDelayedTask(this.epilog, new Runnable() {
		    public void run() {
		    	for (Player p : epilog.getServer().getOnlinePlayers()) {
	    			if (epilog.notifications && p.hasPermission("epilog.notifications")) {
	    				p.sendMessage("[epilog] " + message);
	    			}
	    		}
	    		epilog.getLogger().info(message);
		    }
		});
	}
	
	public void eventHandler(LogEvent event) {
		if (event.eventName.equals("inform")) {
			String key = (String)event.data.get("key");
			if (key==null) return;
			Object msg = event.data.get("msg");
			this.inform(key, msg instanceof String ? (String)msg : null);
			
		} else {
			final Player p = event.player;
			if (p==null) return;
			Bukkit.getScheduler().scheduleSyncDelayedTask(this.epilog, new Runnable() {
			    public void run() {
			    	if (epilog.notifications && p.hasPermission("epilog.notifications")) {
			    		for (String message : messages.values()) {
				    		p.sendMessage("[epilog] " + message);
						}
			    	}
			    	if (epilog.loggingEnabled && epilog.loggingInfo && p.hasPermission("epilog.loggingInfo")) {
			    		p.sendMessage("[epilog] your gameplay is recorded for science");
			    		p.sendMessage("[epilog] visit http://heapcraft.net/ for more information");
			    	}
			    }
			});
		}
	}
}
