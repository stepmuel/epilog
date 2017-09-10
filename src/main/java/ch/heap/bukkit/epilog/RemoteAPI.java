package ch.heap.bukkit.epilog;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.Certificate;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.JSONArray;
import org.json.JSONObject;

public class RemoteAPI {
	private Epilog plugin;
	private String url = null;
	private boolean offline = true;
	private String serverToken = null;
	
	// 10000 events: about 2.5 MB (250 KB compressed); 1000 player seconds
	private int logCacheLimit = 100000; // 25 MB; 2.7 player hours
	// limite sending of large caches; adapts to number of new events
	private int logSendLimit = 5000;
	private int previousLogSize = Integer.MAX_VALUE;
	private boolean logSendRequestPending = false;
	private ArrayDeque<JSONObject> pendingLogs = new ArrayDeque<JSONObject>();
	public int skippedLogs = 0;
	
	private BlockingQueue<JSONObject> logQueue = new LinkedBlockingQueue<JSONObject>();
	private BlockingQueue<Request> requests = new LinkedBlockingQueue<Request>();
	private Postman postman = null;
	
	public RemoteAPI(Epilog plugin) {
		this.plugin = plugin;
	}
	
	public void setURL(String url) {
		// setting url to null enters offline mode
		boolean changed = url==null ? this.url!=null : !url.equals(this.url);
		if (changed) {
			stop();
			this.url = url;
			start();
		}
	}
	
	public void start() {
		if (!this.offline) return;
		if (this.url==null) return;
		if (!this.plugin.isEnabled()) return;
		loadLogCache();
		this.offline = false;
		this.addLogEvent(this.plugin.epilogStateEvent("connect", true));
		if (postman==null) {
			postman = new Postman();
			postman.start();
		}
	}
	
	public void stop() {
		if (this.offline) return;
		if (postman!=null) {
			postman.interrupt();
			try {
				postman.join();
				// no more ongoing web requests now
			} catch (InterruptedException e) {}
			postman = null;
		}
		this.addLogEvent(this.plugin.epilogStateEvent("disconnect", false));
		dispatchLogQueue(false);
		// now send all pending requests in this thread
		sendRequests(new Runnable() {
			@Override public void run() {
				// we are sure now that all log send requests are finished
				saveLogCache();
				serverToken = null;
				offline = true;
			}
		});
	}
	
	// heart beats will trigger updates
	public void triggerHeartBeat() {
		if (this.postman==null) return;
		this.postman.sendHeartBeatAt = 0;
	}
	
	public void addLogEvent(LogEvent event) {
		if (!this.offline && plugin.loggingEnabled) {
			this.logQueue.add(event.toJSON());
		} else {
			this.skippedLogs += 1;
		}
	}
	
	public void addLogData(JSONObject data) {
		if (!this.offline && plugin.loggingEnabled) {
			this.logQueue.add(data);
		} else {
			this.skippedLogs += 1;
		}
	}
	
	public void accessRequest(String email) {
		final JSONObject data = new JSONObject();
		String serverID = this.getPrivateServerID();
		if (serverID==null) return;
    	data.put("epilogServerID", serverID);
    	data.put("email", email);
    	data.put("serverName", this.plugin.getServer().getServerName());
		Request request = new Request("access", data, null);
		this.addRequest(request);
		// TODO: add response handler to provide feedback
	}
	
	public String getPrivateServerID() {
		File keyFile = new File(this.plugin.getDataFolder(), "private_server_id");
		try {
			Scanner ss = new Scanner(keyFile);
			String id = ss.useDelimiter("\\Z").next().trim();
			ss.close();
			if (id.length()==0) return null;
			return id;
		} catch (FileNotFoundException e) {
			return null;
		}
	}
	
	public void setPrivateServerID(String id) {
		File keyFile = new File(this.plugin.getDataFolder(), "private_server_id");
		try {
			PrintWriter writer = new PrintWriter(keyFile, "UTF-8");
			writer.print(id);
			writer.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	private void loadLogCache() {
		File cacheFile = new File(this.plugin.getDataFolder(), "log_cache.json");
		if (!cacheFile.exists()) return;
		try {
			BufferedReader br = new BufferedReader(new FileReader(cacheFile));
			String line;
			while ((line = br.readLine()) != null) {
				JSONObject event = new JSONObject(line);
				this.logQueue.add(event);
			}
			br.close();
		} catch (Exception e) {}
		cacheFile.delete();
	}
	
	private void saveLogCache() {
		if (this.logQueue.size()+this.pendingLogs.size()==0) return;
		File cacheFile = new File(this.plugin.getDataFolder(), "log_cache.json");
		try {
			PrintWriter writer = new PrintWriter(cacheFile, "UTF-8");
			JSONObject log;
			while ((log = this.pendingLogs.poll()) != null) {
				writer.println(log.toString());
			}
			while ((log = this.logQueue.poll()) != null) {
				writer.println(log.toString());
			}
			writer.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	// request stuff
	
	private void dispatchLogQueue(boolean notifyPlugins) {
		if (this.logSendRequestPending) return;
		int n = logQueue.size() + this.pendingLogs.size();
		if (n==0) return;
		this.logSendRequestPending = true;
		
		final int logSize = n;
		Request request = new Request("log", new RequestDelegate() {
			@Override public void response(boolean success, JSONObject answer) {
				if (success) {
					pendingLogs.clear();
				}
				if (answer!=null) {
					skippedLogs -= answer.optInt("skippedLogs", 0);
				}
				logSendRequestPending = false;
			}
		});
		
		int newLogs = logQueue.size() - previousLogSize;
		if (newLogs>this.logSendLimit) {
			// queue is growing faster than we are sending
			this.logSendLimit = newLogs;
		}
		previousLogSize = logQueue.size();
		
		if (logSize>this.logCacheLimit) {
			// add 1 to include new logSkipEvent
			final int toSkip = logSize - this.logCacheLimit + 1;
			int skipped = 0;
			while (toSkip>skipped) {
				JSONObject log = this.pendingLogs.poll();
				if (log==null) break;
				skipped += 1;
			}
			while (toSkip>skipped) {
				JSONObject log = this.logQueue.poll();
				if (log==null) break;
				skipped += 1;
			}
			this.skippedLogs += skipped;
			Map <String, Object> data = new HashMap <String, Object>();
			data.put("skipped", skipped);
			data.put("logSize", logSize);
			this.plugin.postEvent("logSkipEvent", null, data, true);
			this.plugin.getLogger().warning("log cache is full; skipping " + skipped + " events");
		}
		
		if (n>this.logSendLimit) n = this.logSendLimit;
		while (this.pendingLogs.size()<n) {
			JSONObject log = this.logQueue.poll();
			if (log==null) break;
			this.pendingLogs.add(log);
		}
		previousLogSize = this.logQueue.size();
		
		if (this.skippedLogs!=0) {
			request.info.put("skippedLogs", this.skippedLogs);
		}
		request.setData(this.pendingLogs);
		// give connected plugins a chance to add request info
		for (String pluginName : this.plugin.connectedPlugins) {
			if (!notifyPlugins) continue;
			Plugin plugin = this.plugin.getServer().getPluginManager().getPlugin(pluginName);
			if (plugin==null) continue;
			try {
				Method method = plugin.getClass().getMethod("onLogSendRequestPrepare", JSONObject.class);
				method.invoke(plugin, request.info);
			} catch (Exception e) {}
		}
		this.addRequest(request);
	}
	
	public void addRequest(Request request) {
		if (this.offline) {
			request.response(false, null);
		} else {
			this.requests.add(request);
		}
	}
	
	// executes requester in Postman thread
	public boolean offerCustomRequest(final Runnable requester) {
		if (this.offline) return false;
		Request request = new Request(new RequestDelegate() {
			@Override public void response(boolean success, JSONObject answer) {requester.run();}
		});
		request.requiresServerToken = false;
		addRequest(request);
		return true;
	}
	
	public boolean offerWorlds(String cause, World only) {
		if (this.offline) return false;
		LogEvent event = new LogEvent("Worlds", System.currentTimeMillis(), null);
		event.data.put("worlds", plugin.dataCollector.getWorlds(only));
		event.data.put("cause", "token");
		Request request = new Request("worlds", event.toJSON(), null);
		sendRequest(request);
		return true;
	}
	
	private void sendRequests(Runnable then) {
		Request request;
		while ((request = requests.poll()) != null) {
			request.dispatchTime = System.currentTimeMillis();
			sendRequest(request);
		}
		if (then!=null) {
			then.run();
		}
	}
	
	private void sendTokenRequest() {
		LogEvent event = new LogEvent("TokenRequest", System.currentTimeMillis(), null);
		plugin.dataCollector.addServerMetaData(event);
		event.data.put("port", this.plugin.getServer().getPort());
		String id = this.getPrivateServerID();
		event.data.put("epilogServerID", id);
		
		Request request = new Request("token", event.toJSON(), new RequestDelegate () {
			@Override public void response(boolean success, JSONObject answer) {
				if (answer==null) return;
				String token = answer.optString("serverToken", null);
				String id = answer.optString("epilogServerID", null);
				if (token!=null) {
					serverToken = token;
					// trigger first successful log queue dispatch
					postman.sendHeartBeatAt = 0;
				}
				if (id!=null) {
					setPrivateServerID(id);
				}
				skippedLogs -= answer.optInt("skippedLogs", 0);
				// send worlds
				offerWorlds("token", null);
			}
		});
		if (this.skippedLogs!=0) {
			request.info.put("skippedLogs", this.skippedLogs);
		}
		request.requiresServerToken = false;
		sendRequest(request);
	}
	
	private void sendHeartBeat() {
		JSONObject data = new JSONObject();
		data.put("time", System.currentTimeMillis());
		data.put("currentVersions", plugin.updater.getCurrentVersions());
		data.put("updateVersions", plugin.updater.getUpdateVersions());
		data.put("config", plugin.config);
		data.put("port", plugin.getServer().getPort()); // int
		data.put("serverID", this.getPrivateServerID());
		Request request = new Request("heartbeat", data, null);
		request.requiresServerToken = false;
		sendRequest(request);
		this.plugin.updater.heartBeatSent();
	}
	
	// handle events/actions initiated by the server 
	private void handleRequestResponse(JSONObject answer) {
		JSONArray events = answer.optJSONArray("events");
		if (events!=null) {
			long time = System.currentTimeMillis();
			int length = events.length();
			for (int i=0; i<length; i++) {
				LogEvent event = LogEvent.fromJSON(events.getJSONObject(i));
				event.time = time;
				event.ignore = true; // don't send back to logging server
				this.plugin.postEvent(event);
			}
		}
		JSONObject plugins = answer.optJSONObject("plugins");
		if (plugins!=null && !plugin.bukkitMode) {
			this.plugin.updater.setAvailablePlugins(plugins);
		}
	}
	
	public interface RequestDelegate {
		public void response(boolean success, JSONObject answer);
	}
	
	public class Request {
		public String cmd = null;
		public Collection<JSONObject> data = null;
		public RequestDelegate delegate = null;
		public boolean callDelegateInGameLoop = false;
		public boolean requiresServerToken = true;
		public long dispatchTime = 0;
		public JSONObject info = new JSONObject();
		public Request(String cmd, RequestDelegate delegate) {
			this.cmd = cmd;
			this.delegate = delegate;
		}
		public Request(RequestDelegate delegate) {
			// dummy request to allow sending request to other servers
			// within the delegate (no request made if cmd==null)
			this.delegate = delegate;
		}
		public Request(String cmd, JSONObject data, RequestDelegate delegate) {
			this.cmd = cmd;
			this.setData(data);
			this.delegate = delegate;
		}
		public void setData(ArrayDeque<JSONObject> data) {
			this.data = data;
		}
		public void setData(JSONObject data) {
			if (data==null) {
				this.data = null;
			} else {
				ArrayList<JSONObject> dataList = new ArrayList<JSONObject>();
				dataList.add(data);
				this.data = dataList;
			}
		}
		public void addInfo(JSONObject info) {
			if (info==null) return;
			for (String key : info.keySet()) {
				this.info.put(key, info.get(key));
			}
		}
		public void response(final boolean success, final JSONObject answer) {
			if (this.delegate==null) return;
			if (this.callDelegateInGameLoop) {
				if (!plugin.isEnabled()) return;
				new BukkitRunnable() {
		            @Override public void run() {
		            	delegate.response(success, answer);
		            }
		        }.runTask(plugin);
			} else {
				delegate.response(success, answer);
			}
		}
	}
	
	// thread to send web requests
	private class Postman extends Thread {
		public long sendLogsAt = 0;
		public long sendHeartBeatAt = 0;
		public long requestTokenAt = 0;
		public void run() {
			while (!this.isInterrupted()) {
				//System.out.println("postman");
				long time = System.currentTimeMillis();
				if (serverToken==null) {
					if (requestTokenAt<=time) {
						requestTokenAt = time + plugin.heartbeatSendPeriod;
						sendTokenRequest();
					}
				}
				if (sendLogsAt<=time) {
					sendLogsAt = time + plugin.logSendPeriod;
					dispatchLogQueue(true);
				}
				if (sendHeartBeatAt<=time) {
					sendHeartBeatAt = time + plugin.heartbeatSendPeriod;
					sendHeartBeat();
				}
				sendRequests(null);
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					return;
				}
			}
		}
	}
	
	static String convertStreamToString(java.io.InputStream is) {
		java.util.Scanner ss = new java.util.Scanner(is);
		java.util.Scanner s = ss.useDelimiter("\\A");
	    String out = s.hasNext() ? s.next() : "";
	    ss.close();
	    return out;
	}
	
	// http request stuff
	
	private SSLSocketFactory sslFactory = null;
	private SSLSocketFactory getSSLSocketFactory(String path) {
		if (sslFactory==null) {
			try {
				KeyStore keyStore  = KeyStore.getInstance(KeyStore.getDefaultType());
				keyStore.load(null);
				InputStream fis = this.plugin.getResource(path);
				if (fis==null) throw new Error("unable to read certificate");
				BufferedInputStream bis = new BufferedInputStream(fis);
				CertificateFactory cf = CertificateFactory.getInstance("X.509");
				while (bis.available() > 0) {
				    Certificate cert = cf.generateCertificate(bis);
				    keyStore.setCertificateEntry("fiddler"+bis.available(), cert);
				}
				TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
				tmf.init(keyStore);
				SSLContext ctx = SSLContext.getInstance("TLS");
				ctx.init(null, tmf.getTrustManagers(), null);
				sslFactory = ctx.getSocketFactory();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return sslFactory;
	}
	
	public HttpURLConnection createHttpURLConnection(String url) throws Exception {
		// uses custom certificate if needed
		URL obj = new URL(url);
		HttpURLConnection conn = (HttpURLConnection)obj.openConnection();
		if (conn instanceof HttpsURLConnection && obj.getHost().equals("epilog.heapcraft.net")) {
			HttpsURLConnection sslConn = (HttpsURLConnection)conn;
			sslConn.setSSLSocketFactory(getSSLSocketFactory("letsencryptauthorityx3.cer"));
		}
		return conn;
	}
	
	private void sendRequest(Request request) {
		//System.out.print("cmd: " + request.cmd);
		//System.out.print("data: " + request.data);
		if (url==null) {
			request.response(false, null);
			return;
		}
		if (request.cmd==null) {
			// allows running custom request in Postman thread
			request.response(false, null);
			return;
		}
		if (request.requiresServerToken && this.serverToken==null) {
			request.response(false, null);
			return;
		}
		JSONObject answer = null;
		try {
			HttpURLConnection conn = createHttpURLConnection(url);
			conn.setUseCaches(false);
			conn.setRequestProperty("connection", "close"); // disable keep alive
			conn.setRequestMethod("POST");
			if (serverToken!=null) {
				conn.setRequestProperty("X-EPILOG-SERVER-TOKEN", serverToken);
			}
			conn.setRequestProperty("X-EPILOG-COMMAND", request.cmd);
			conn.setRequestProperty("X-EPILOG-VERSION", this.plugin.version);
			conn.setRequestProperty("X-EPILOG-TIME", ""+System.currentTimeMillis());
			JSONObject info = request.info;
			info.put("logSendLimit", this.logSendLimit);
			info.put("logCacheSize", this.logQueue.size() + this.pendingLogs.size());
			info.put("nLogs", request.data==null ? 0 : request.data.size());
			conn.setRequestProperty("X-EPILOG-INFO", info.toString());
			conn.setDoOutput(true);
			
			GZIPOutputStream out = new GZIPOutputStream(conn.getOutputStream());
			if (request.data!=null) {
				for (JSONObject json : request.data) {
					out.write(json.toString().getBytes());
					out.write(0xA); // newline
				}
			}
			out.close();
			
			InputStream in = conn.getInputStream();
			String response = convertStreamToString(in);
			in.close();
			conn.disconnect();
			
			try {
				answer = new JSONObject(response);
				handleRequestResponse(answer);
			} catch (Exception e) {
				this.plugin.getLogger().warning("invalid server response");
				this.plugin.getLogger().log(Level.INFO, response);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		boolean success = answer!=null;
		if (success) {
			String status = answer.optString("status");
			if (!status.equalsIgnoreCase("ok")) {
				success = false;
				this.plugin.getLogger().warning("server error");
				this.plugin.getLogger().log(Level.INFO, answer.toString());
			}
		}
		request.response(success, answer);
	}
	
	public String get(String url, Map<String, String> reqProp) {
		String response = null;
		try {
			HttpURLConnection conn = createHttpURLConnection(url);
			conn.setUseCaches(false);
			conn.setRequestMethod("GET");
			if (reqProp!=null) for (Entry<String, String> entry : reqProp.entrySet()) {
				conn.setRequestProperty(entry.getKey(), entry.getValue());
			}
			InputStream in = conn.getInputStream();
			response = convertStreamToString(in);
			in.close();
			conn.disconnect();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return response;
	}
}
