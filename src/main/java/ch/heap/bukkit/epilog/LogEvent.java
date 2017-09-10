package ch.heap.bukkit.epilog;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.json.JSONObject;

public class LogEvent {
	public Event event;
	public long time;
	public Player player = null;
	public String eventName = null;
	public Material material; // cache material for BlockBreakEvent
	public boolean ignore = false;
	public boolean needsData = false;
	public Map <String, Object> data = new HashMap <String, Object>();
	
	public LogEvent() {};
	public LogEvent(String name, long time, Player player) {
		this.eventName = name;
		this.time = time;
		this.player = player;
	}
	
	public JSONObject toJSON() {
		JSONObject data = new JSONObject(this.data);
		Player p = this.player;
		if (p!=null) {
			data.put("player", p.getUniqueId().toString());
			data.put("worldUUID", p.getWorld().getUID().toString());
		}
		data.put("time", this.time);
		data.put("event", this.eventName);
		return data;
	}
	public static LogEvent fromJSON(JSONObject data) {
		LogEvent event = new LogEvent();
		event.eventName = data.optString("event");
		JSONObject d = data.optJSONObject("data");
		if (d!=null) {
			for (String key : d.keySet()) {
				event.data.put(key, d.get(key));
			}
		}
		return event;
	}
}
