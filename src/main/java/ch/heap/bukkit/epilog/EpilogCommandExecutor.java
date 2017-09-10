package ch.heap.bukkit.epilog;

import java.util.HashMap;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.json.JSONObject;

import ch.heap.bukkit.epilog.RemoteAPI.Request;
import ch.heap.bukkit.epilog.RemoteAPI.RequestDelegate;

public class EpilogCommandExecutor implements CommandExecutor {
	public Epilog plugin;
	
	private HashMap<String, String> booleanConfigs; 
	
	public EpilogCommandExecutor(Epilog plugin) {
		this.plugin = plugin;
		booleanConfigs = new HashMap<String, String>();
		booleanConfigs.put("autoUpdate", "auto-update");
		booleanConfigs.put("offlineMode", "offline-mode");
		booleanConfigs.put("logging", "logging-enabled");
		booleanConfigs.put("logChats", "log-chats");
		booleanConfigs.put("worldDownload", "world-download");
		booleanConfigs.put("notifications", "player-notifications");
		booleanConfigs.put("loggingInfo", "logging-info");
		booleanConfigs.put("ingameCommands", "ingame-commands");
	}
	
	@Override
	public boolean onCommand(final CommandSender sender, Command cmd, String label, String[] args) {
		String c = cmd.getName();
		Player p = sender instanceof Player ? (Player)sender : null;
		if (p!=null && plugin.ingameCommands==false) {
			sender.sendMessage("[epilog] ingame-commands are disabled; please use the server console");
			return true;
		}
		if (c.equalsIgnoreCase("el")) {
			if (args.length==0) return false;
			String arg0 = args[0];
			String arg1 = args.length>=2 ? args[1] : "";
			// set/get boolean config parameter
			String configKey = this.booleanConfigs.get(arg0);
			if (configKey!=null) {
				if (!permCheck(p, arg0, "epilog.manage")) return true;
				FileConfiguration config = this.plugin.getConfig();
				if (arg1.length()==0) arg1 = "read";
				if (arg1.equalsIgnoreCase("on")) {
					config.set(configKey, true);
				} else if (arg1.equalsIgnoreCase("off")) {
					config.set(configKey, false);
				} else if (!arg1.equalsIgnoreCase("read")) {
					return false;
				}
				this.plugin.loadConfig(config);
				this.plugin.saveConfig();
				String value = config.isBoolean(configKey) ? config.getBoolean(configKey) ? "on" : "off" : "undefined";
				sender.sendMessage(arg0 + ": " + value);
			} else if (arg0.equalsIgnoreCase("versions")||arg0.equalsIgnoreCase("version")) {
				this.plugin.updater.sendVersionList(sender);
			} else if (arg0.equalsIgnoreCase("update")) {
				if (!permCheck(p, arg0, "epilog.update")) return true;
				this.plugin.updater.update();
				sender.sendMessage("updating plugins");
			} else if (arg0.equalsIgnoreCase("access")) {
				if (!permCheck(p, arg0, "epilog.access")) return true;
				if (arg1.contains("@")) {
					this.plugin.remote.accessRequest(arg1);
					sender.sendMessage("admin interface access key has been sent to " + arg1);
					sender.sendMessage("receiving might take some time; also check your spam folder");
				} else {
					sender.sendMessage("\"" + arg1 + "\" doesn't seem to be a valid email address");
				}
			} else if (arg0.equalsIgnoreCase("help")) {
				for (String name : this.booleanConfigs.keySet()) {
					sender.sendMessage("/el "+name+" [on | off | read]");
				}
				sender.sendMessage("/el versions");
				sender.sendMessage("/el update");
				sender.sendMessage("/el ping");
				sender.sendMessage("/el access <email>");
				sender.sendMessage("more information on http://heapcraft.net/");
			} else if (arg0.equalsIgnoreCase("ping")) {
				RemoteAPI remote = this.plugin.remote;
				final Request request = remote.new Request("ping", null);
				RequestDelegate delegate = new RequestDelegate() {
					@Override public void response(boolean success, JSONObject answer) {
						float dt = System.currentTimeMillis() - request.dispatchTime;
						String pong = success ? answer.optString("pong", "pong") : "server connection failed";
						sender.sendMessage(pong + " (time="+(dt/1000)+"s)");
					}
				};
				request.delegate = delegate;
				request.callDelegateInGameLoop = true;
				remote.addRequest(request);
			} else {
				return false;
			}
			return true;
		}
		return false;
	}
	
	private boolean permCheck(Player p, String cmd, String perm) {
		if (p!=null && !p.hasPermission(perm)) {
			p.sendMessage("command "+cmd+" requires permission "+perm);
			return false;
		}
		return true;
	}
}
