package ch.heap.bukkit.epilog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.bind.DatatypeConverter;

import org.apache.commons.lang.StringUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.json.JSONArray;
import org.json.JSONObject;

public class Updater {
	private final Epilog epilog;
	private JSONObject availablePlugins = new JSONObject();
	private List<String> pluginNames = null;
	static private Map<Integer, String> bukkitIDs = new HashMap<Integer, String>();
	private Set<String> pluginsToInstall = new HashSet<String>();
	
	static {
		bukkitIDs.put(90348, "Epilog");
		bukkitIDs.put(90196, "DiviningRod");
		bukkitIDs.put(90374, "Classify");
	}
	
	public Updater(Epilog epilog) {
		this.epilog = epilog;
	}
	
	public void update() {
		this.pluginsToInstall.addAll(this.updateablePlugins());
		this.epilog.remote.triggerHeartBeat();
	}
	
	public void sendVersionList(CommandSender sender) {
		List<String> keys = Arrays.asList("installed", "downloaded", "available");
		HashMap<String, Map<String, String>> versions = new HashMap<String, Map<String, String>>(3);
		versions.put(keys.get(0), getCurrentVersions());
		versions.put(keys.get(1), getUpdateVersions());
		versions.put(keys.get(2), getAvailableVersions());
		HashSet<String> all = new HashSet<String>();
		for (String key : keys) all.addAll(versions.get(key).keySet());
		List<String> list = new ArrayList<String>(all);
		java.util.Collections.sort(list);
		for (String name : list) {
			List<String> versionStrings = new ArrayList<String>();
			for (String key : keys) {
				String version = versions.get(key).get(name);
				if (version==null) continue;
				versionStrings.add(key + ": " + version);
			}
			sender.sendMessage(name + " (" + StringUtils.join(versionStrings, ", ") + ")");
		}
	}
	
	// called within Postman thread after sending a heart beat
	public void heartBeatSent() {
		if (epilog.bukkitMode) checkBukkitUpdates();
	}
	
	private void checkBukkitUpdates() {
		String ids = StringUtils.join(bukkitIDs.keySet(), ",");
		String url = "https://api.curseforge.com/servermods/files?projectIds=" + ids;
		JSONArray files = null;
		Map<String, String> headers = new HashMap<String, String>();
		headers.put("User-Agent", "Epilog/v"+epilog.version+" (by stepmuel)");
		String response = epilog.remote.get(url, headers);
		try {
			files = new JSONArray(response);
		} catch (Exception e) {
			this.epilog.getLogger().warning("invalid response from ServerMods API");
		}
		if (files==null) return;
		JSONObject plugins = new JSONObject();
		for (int i=0; i<files.length(); i++) {
			JSONObject file = files.getJSONObject(i);
			int id = file.optInt("projectId");
			String name = bukkitIDs.get(id);
			if (name==null) continue;
			String fileName = name + ".jar";
			if (!file.optString("fileName").equals(fileName)) continue;
			if (!file.optString("releaseType").equals("release")) continue;
			String[] nameParts = file.optString("name").split(" ");
			String version = nameParts.length<2 ? null : nameParts[1];
			JSONObject info = new JSONObject();
			info.put("version", version);
			info.put("url", file.opt("downloadUrl"));
			info.put("md5", file.opt("md5"));
			plugins.put(name, info);
			// System.out.println(file.opt("name") + " " + file.opt("releaseType") + " " + version);
		}
		this.setAvailablePlugins(plugins);
	}
	
	public static PluginDescriptionFile getDescription(File jarFile) {
		PluginDescriptionFile desc = null;
		try {
			ZipFile zipFile = new ZipFile(jarFile);
			ZipEntry entry = zipFile.getEntry("plugin.yml");
			if (entry!=null) {
				desc =  new PluginDescriptionFile(zipFile.getInputStream(entry));
			}
			zipFile.close();
		} catch (Exception e) {
			e.printStackTrace();
		};
		return desc;
	}
	
	private List<String> getPluginNames() {
		if (this.pluginNames==null) {
			List<String> list = new ArrayList<String>(this.availablePlugins.keySet());
			if (!list.contains("Epilog")) list.add("Epilog");
			java.util.Collections.sort(list);
			this.pluginNames = list;
		}
		return this.pluginNames;
	}
	public Map<String, String> getCurrentVersions() {
		Map<String, String> versions = new HashMap<String, String>();
		for (String name : this.getPluginNames()) {
			Plugin plugin = epilog.getServer().getPluginManager().getPlugin(name);
			if (plugin==null) continue;
			versions.put(name, plugin.getDescription().getVersion());
		}
		return versions;
	}
	public Map<String, String> getUpdateVersions() {
		Map<String, String> versions = new HashMap<String, String>();
		File updateFolder = epilog.getServer().getUpdateFolderFile();
		if (updateFolder.isDirectory()==false) return versions;
		for (File file : updateFolder.listFiles()) {
			if (!file.getName().endsWith(".jar")) continue;
			PluginDescriptionFile description = getDescription(file);
			if (description==null) continue;
			String name = description.getName();
			if (this.getPluginNames().contains(name)==false) continue;
			if (!file.getName().equals(name + ".jar")) continue;
			versions.put(name, description.getVersion());
		}
		return versions;
	}
	private Map<String, String> getAvailableVersions() {
		Map<String, String> versions = new HashMap<String, String>();
		for (String name: this.availablePlugins.keySet()) {
			JSONObject info = this.availablePlugins.getJSONObject(name);
			String version = info.optString("version", null);
			if (version==null) continue;
			versions.put(name, version);
		}
		return versions;
	}
	private Set<String> updateablePlugins() {
		HashSet<String> updateable = new HashSet<String>();
		updateable.addAll(getCurrentVersions().keySet());
		updateable.addAll(getUpdateVersions().keySet());
		return updateable;
	}
	public void setAvailablePlugins(JSONObject plugins) {
		// this is set within the remote postman thread
		//System.out.println(plugins.toString(4));
		this.availablePlugins = plugins;
		this.pluginNames = null;
		installPlugins();
	}
	private void installPlugins() {
		Map<String, String> current = getCurrentVersions();
		Map<String, String> update = getUpdateVersions();
		Map<String, String> available = getAvailableVersions();
		HashSet<String> updateable = new HashSet<String>();
		updateable.addAll(current.keySet());
		updateable.addAll(update.keySet());
		HashSet<String> toUpdate = new HashSet<String>(this.pluginsToInstall);
		this.pluginsToInstall.clear();
		if (this.epilog.autoUpdate) {
			toUpdate.addAll(updateable);
		}
		int nInstalls = 0;
		int nUpdateAvailable = 0;
		for (String name : updateable) {
			String newest = available.get(name);
			if (newest==null) continue;
			String version = update.get(name);
			if (version==null) version = current.get(name);
			if (newest.equals(version)) continue;
			if (toUpdate.contains(name)) {
				boolean r = installPlugin(name);
				if (r==false) {
					this.epilog.getLogger().warning("update failed: " + name);
				} else {
					nInstalls += 1;
				}
			} else {
				nUpdateAvailable += 1;
			}
		}
		if (nInstalls>0) {
			this.epilog.informant.updateReady();
		}
		this.epilog.informant.updateAvailable(nUpdateAvailable);
	}
	private boolean installPlugin(String name) {
		JSONObject info = this.availablePlugins.optJSONObject(name);
		if (info==null) return false;
		String url = info.optString("url");
		if (url==null) return false;
		String version = info.optString("version");
		String md5 = info.optString("md5");
		File tmp = null;
		boolean success = true;
		try {
			HttpURLConnection conn = this.epilog.remote.createHttpURLConnection(url);
			MessageDigest md = MessageDigest.getInstance("MD5");
			DigestInputStream dis = new DigestInputStream(conn.getInputStream(), md);
			ReadableByteChannel rbc = Channels.newChannel(dis);
			tmp = File.createTempFile(name, ".jar");
			FileOutputStream fos = new FileOutputStream(tmp);
			fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
			fos.close();
			// check file
			String digest = DatatypeConverter.printHexBinary(md.digest());
			if (md5!=null && !md5.equalsIgnoreCase(digest)) {
				throw new Exception("checksum missmatch");
			}
			// check file version
			PluginDescriptionFile description = getDescription(tmp);
			if (description==null) throw new Exception("not a valid plugin");
			if (!name.equals(description.getName())) throw new Exception("wrong plugin name");
			if (version!=null && !version.equals(description.getVersion())) {
				 throw new Exception("wrong version string");
			}
			// everything ok. install plugin
			File updateFolder = epilog.getServer().getUpdateFolderFile();
			updateFolder.mkdirs();
			File target = new File(updateFolder, name + ".jar");
			copyFile(tmp, target);
			//boolean success = tmp.renameTo(target); // doesn't work on centos; probably tmp file still in use
			//if (!success) throw new Exception("unable to move plugin to update folder");
		} catch (Exception e) {
			e.printStackTrace();
			success = false;
		}
		if (tmp!=null) tmp.delete();
		return success;
	}
	public static void copyFile(File sourceFile, File destFile) throws IOException {
	    if(!destFile.exists()) destFile.createNewFile();
	    FileChannel source = null;
	    FileChannel destination = null;
	    try {
	    	FileInputStream is = new FileInputStream(sourceFile);
	        source = is.getChannel();
	        FileOutputStream os = new FileOutputStream(destFile);
	        destination = os.getChannel();
	        destination.transferFrom(source, 0, source.size());
	        is.close();
	        os.close();
	    } finally {
	        if (source != null) source.close();
	        if (destination != null) destination.close();
	    }
	}
}
