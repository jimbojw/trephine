package org.trephine;

import java.applet.Applet;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessControlException;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.Policy;
import java.security.ProtectionDomain;
import java.security.SecurityPermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public class Launcher extends Applet {
	
	private static final long serialVersionUID = 7018115443869232551L;

	private static final String version = "1.3";
	
	private static boolean debugEnabled = false;

	private boolean privileged = false;
	private boolean permissionGranted = false;
	private boolean destroyed = false;
	
	private Drop drop;
	private Thread thread;
	private Thread executor;
	private List<String> engines = new ArrayList<String>();
	private HashMap<String,Pattern> patterns = new HashMap<String,Pattern>();
	
	private String onload;
	private String onerror;
	
	/**
	 * GO!
	 */
	@Override
	public void init() {
		
		// Check for recognized applet params
		String d = this.getParameter("debug");
		if (Boolean.parseBoolean(d) || "1".equals(d)) Launcher.debugEnabled = true;
		
		final String fname = "Launcher:init()";
		
		debug(fname, "START");

        // Extract recognized applet parameters
		debug(fname, "extracting additional recognized parameters");
		final String onload = this.getParameter("onload");
		if (onload!=null && onload.length()>0) this.onload = onload;
		final String onerror = this.getParameter("onerror");
		if (onerror!=null && onerror.length()>0) this.onerror = onerror;
		
		// Confirming that applet has been initialized with necessary privilege level		
		debug(fname, "checking for applet privileges...");
		try {
			SecurityManager sm = System.getSecurityManager();
			if (sm!=null) {
				sm.checkCreateClassLoader();
				sm.checkPermission(new SecurityPermission("setPolicy"));
			}
			this.privileged = true;
		} catch (SecurityException e) {
			debug(fname, "privilege check failed, further applet interaction will also fail");
		    if (this.onerror!=null && this.onerror.length()>0) {
		        final Launcher applet = this;
		        SwingUtilities.invokeLater(new Runnable() {
			        public void run() {
				        final String ifname = fname + ":invokeLater";
				        try {
					        Class<?> jsObject = Class.forName("netscape.javascript.JSObject");
					        Method getWindow = jsObject.getMethod("getWindow", Applet.class);
					        Method eval = jsObject.getMethod("eval", String.class);
					        Object window = getWindow.invoke(jsObject, applet);
					        String cbc = "("  + onerror + ")();";
					        debug(ifname, "issuing callback: " + cbc);
					        eval.invoke(window, new Object[] { cbc });
				        } catch(Exception e) {
					        debug(ifname, "problem occurred issuing callback");
					        e.printStackTrace(System.out);
				        }
			        }
		        });
	        }
			return;
		}
		
		// Load properties from resource properties file
		Properties props = new Properties();
		debug(fname, "attempting to read trephine.properties file...");
		try {
			props.load(
				new InputStreamReader(
					(new URL(
						"jar:" + 
						this.getClass().getProtectionDomain().getCodeSource().getLocation() +
						"!/org/trephine/trephine.properties"
					)).openStream()
				)
			);
		} catch (Throwable t) {
			debug(fname, "unable to read trephine.properties file: " + t);
			return;
		}
		debug(fname, "trephine.properties loaded successfully, building regular expressions...");
		
		// Attempt to make regular expressions out of properties
		String[] keys = new String[] { "host", "webserver", "trusted" };
		for (int i=0; i<keys.length; i++) {
			String key = keys[i];
			String pattern = props.getProperty("trephine." + key + ".pattern", null);
			try {
				patterns.put(key, Pattern.compile("\\A(" + pattern + ")\\Z"));
			} catch (Throwable t) {
				debug(fname, "the trephine.properties key 'trephine." + key + ".pattern' is not a valid regular expression");
				t.printStackTrace(System.out);
				return;
			}
		}
			
		// Check that codebase came from a permitted host domain
		debug(fname, "checking applet codesource location against host pattern...");
		URL csLocation = this.getClass().getProtectionDomain().getCodeSource().getLocation();
		if (!patterns.get("host").matcher(csLocation.toString()).matches()) {
			debug(fname, "applet codebase location [" + csLocation + "] does not match host pattern, any further applet interaction will fail.");
			return;
		}
		
		// Check that the requesting page matches the webserver pattern
		debug(fname, "checking page domain against webserver pattern...");
		String pageURL = null;
		try {
			Class<?> jsObject = Class.forName("netscape.javascript.JSObject");
			Method getWindow = jsObject.getMethod("getWindow", Applet.class);
			Method eval = jsObject.getMethod("eval", String.class);
			Object window = getWindow.invoke(jsObject, this);
			pageURL = (String) eval.invoke(window, new Object[] { "window.top.location + ''" });
		} catch (Exception e) {
			debug(fname, "unable to retrieve window.top.location from calling page, aborting.");
			e.printStackTrace(System.out);
			return;
		}
		if (!patterns.get("webserver").matcher(pageURL).matches()) {
			debug(fname, "calling page [" + pageURL + "] does not match webserver pattern, any further applet interaction will fail.");
			return;
		}
		
		// Pre-assigning permissions if the page matches the trusted pattern
		if (patterns.get("trusted").matcher(pageURL).matches()) {
			debug(fname, "calling page [" + pageURL + "] matches the trusted pattern - granting permissions.");
			this.setPermission(true);
		}
		
		debug(fname, "grabbing reference to applet thread...");
		this.thread = Thread.currentThread();

		debug(fname, "replacing policy with ThreadOrientedPolicy...");
		try {
			Policy oldPolicy = Policy.getPolicy();
			Policy newPolicy = new Launcher.ThreadOrientedPolicy(oldPolicy, this);
			Policy.setPolicy(newPolicy);
		} catch (AccessControlException e) {
			debug(fname, "policy override failed!");
			e.printStackTrace(System.out);
		}

		String engines = this.getParameter("engines");
		if (engines!=null) for(String engine: engines.split(",")) this.engines.add(engine);

		final Launcher applet = this;
		final HashMap<String,Object> environment = new HashMap<String,Object>();
		
		debug(fname, "checking for Java Scripting implementation...");
		
		try {
			this.thread.getContextClassLoader().loadClass("javax.script.ScriptEngine");
		} catch (ClassNotFoundException e) {
			
			debug(fname, "could not find required classes, attempting to download and add to system classloader...");
			String[] jars = new String[] { "script.jar", "js.jar", "js-engine.jar" };
			
			File trephineDir = new File(new File(System.getProperty("java.io.tmpdir")), "trephine");
			debug(fname, "checking for trephine temp directory (" + trephineDir + ")... ");
			if (!trephineDir.exists()) trephineDir.mkdir();
			
			String base = csLocation.toString().replaceFirst("^(.*/).*$", "$1");
			debug(fname, "downloading dependency jars from " + base + " ...");
			try {
				for (int i=0; i<jars.length; i++) {
					debug(fname, "  copying " + jars[i]);
					URL jarURL = new URL(base + jars[i]);
					BufferedInputStream bis = new BufferedInputStream(jarURL.openStream());
					BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(new File(trephineDir, jars[i])));
					int n;
					byte[] buf = new byte[8192];
					while ((n = bis.read(buf, 0, buf.length)) != -1) out.write(buf, 0, n);
					out.flush();
					bis.close();
					out.close();
				}
			} catch (Throwable t) {
				debug(fname, "failed to download dependency jars");
				t.printStackTrace(System.out);
				debug(fname, "END - could not satisfy dependencies");
				return;
			}
			
			debug(fname, "attempting to inject dependencies into system classloader...");
			URLClassLoader appClassLoader = (URLClassLoader)ClassLoader.getSystemClassLoader();
			try {
				Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
				addURL.setAccessible(true);
				for (int i=0; i<jars.length; i++) {
					URL url = (new File(trephineDir, jars[i])).toURI().toURL();
					debug(fname, "  adding " + url);
					addURL.invoke(appClassLoader,new Object[]{ url });
				}
			} catch (Throwable t) {
				debug(fname, "failed adding URLs to system classloader");
				t.printStackTrace(System.out);
				debug(fname, "END - inhospitable environment");
				return;
			}
		}
		
		debug(fname, "starting executor thread...");
		this.drop = new Drop();
		this.executor = new Executor(applet, drop, environment);
		this.executor.start();

		debug(fname, "starting background initializer thread...");
		(new Thread(new Runnable(){
			public void run() {
				final String ifname = fname + ":initializer";
				final Thread thread = Thread.currentThread();

				debug(ifname, "START - " + thread);
				
				debug(ifname, "creating ScriptEngineManager and procuring JavaScript engine");
				ScriptEngineManager manager = new ScriptEngineManager();
				ScriptEngine engine = manager.getEngineByName("js");
				ScriptContext context = engine.getContext();
				context.setAttribute("com.sun.script.jython.comp.mode", "eval", ScriptContext.ENGINE_SCOPE);
				
				debug(ifname, "setting global references");
				engine.put("applet", applet);
				engine.put("context", context);
				engine.put("engine", engine);
				engine.put("manager", manager);
				
				try {
					URL jarURL = this.getClass().getProtectionDomain().getCodeSource().getLocation();
					URL jsURL = new URL( "jar:" + jarURL.toString() + "!/org/trephine/Launcher.js" );
					debug(ifname, "attempting to load Launcher.js resource - " + jsURL);
					InputStreamReader reader = new InputStreamReader( jsURL.openStream() );
					debug(ifname, "executing JavaScript code in Launcher.js ...");
					engine.eval(reader);
					Object result = engine.eval(reader);
					debug(ifname, "result: " + result);
					jsURL = new URL( "jar:" + jarURL.toString() + "!/org/trephine/JSON.js" );
					debug(ifname, "attempting to load JSON.js resource - " + jsURL);
					reader = new InputStreamReader( jsURL.openStream() );
					debug(ifname, "executing JavaScript code in JSON.js ...");
					engine.eval(reader);
					result = engine.eval(reader);
					debug(ifname, "result: " + result);
				} catch (Exception e) {
					debug(ifname, "Error evaluating code");
					e.printStackTrace(System.out);
				}
				
				debug(ifname, "setting environment references");
				synchronized (environment) {
					environment.put("context",context);
					environment.put("engine",engine);
					environment.put("manager",manager);
					environment.put("initialized",true);
					environment.notifyAll();
				}
				
				if (applet.onload!=null) {
					debug(ifname, "executing onload callback code...");
					try {
						Class<?> jsObject = Class.forName("netscape.javascript.JSObject");
						Method getWindow = jsObject.getMethod("getWindow", Applet.class);
						Method eval = jsObject.getMethod("eval", String.class);
						Object window = getWindow.invoke(jsObject, applet);
						Object result = eval.invoke(window, new Object[] { "("  + applet.onload + ")();" });
						debug(ifname, "  result = " + result);
					} catch (Exception e) {
						debug(ifname, "callback failed");
						e.printStackTrace(System.out);
					}
				}
				
				debug(ifname, "END");
			}
		})).start();
		
		debug(fname, "END");
	}

	@Override
	public synchronized void destroy() {
		final String fname = "Launcher:destroy()";
		debug(fname, "START");
		this.destroyed = true;
		if (this.drop!=null) this.drop.put( Job.DONE );
		this.notifyAll();
		debug(fname, "END");
	}

	/**
	 * 
	 * @param language
	 * @param code
	 * @return
	 */
	public Object exec( String language, String code ) {
		
		final String fname = "Launcher:exec()";
		
		if (!this.privileged) {
			debug(fname, "applet is not running in privileged mode, exiting!");
			return this.listWrap(null, new RuntimeException("Applet is not running in privileged mode."));
		}
		
		debug(fname, "checking for elevated privileges");
		if (!this.hasPermission()) {
			debug(fname, "privileged access has not been granted, exiting!");
			return this.listWrap(null, new RuntimeException("Privileged access has not yet been granted."));
		}

		debug(fname, "creating new Job");
		Job job = new Job( language, code );

		synchronized (job) {
			
			debug(fname, "placing Job in the Drop zone...");
			this.drop.put( job );
			
			debug(fname, "Job placed in Drop, waiting for results...");
			while (!job.isFinished()) {
				try {
					job.wait();
				} catch (InterruptedException e) { }
			}
		}

		Exception e = job.getException();
		if (e!=null) {
			debug(fname, "exception ocurred - " + e.getMessage());
			e.printStackTrace(System.out);
		} else {
			debug(fname, "results received! [" + job.getResult() + "]");
		}

		debug(fname, "returning results");
		return this.listWrap(job.getResult(), e);
		
	}
	
	private ArrayList<Object> listWrap(Object result, Object exception) {
		ArrayList<Object> r = new ArrayList<Object>(3);
		r.add(exception==null);
		r.add(result);
		r.add(exception);
		return r;
	}
	
	/**
	 * Asks user for permission to give scripts privileged access.
	 * @param callback JavaScript function to call when the user has completed the request.
	 */
	public void askPermission( final String callback ) {
		
		final String fname = "Launcher:askPermission()";
		
		if (!this.privileged) {
			debug(fname, "applet is not running in privileged mode, exiting!");
			return;
		}
		
		debug(fname, "START");
		
		final Launcher applet = this;
		final String url = this.getDocumentBase().toString().replace("\\","\\\\").replace("'", "\\'");

		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				final String ifname = fname + ":invokeLater";
				if (!applet.hasPermission()) {
				    int x = (int) Math.floor(Math.random() * 10) + 1;
				    int y = (int) Math.floor(Math.random() * x);
				    int sign = Math.random() < 0.5 ? -1 : 1;
				    String message = (new StringBuffer())
					    .append("A script on this page is requesting access to privileged system resources:\n\n")
					    .append("	" + url + "\n\n")
					    .append("To grant access, answer the following math question and click OK.\n")
					    .append("To deny access, click Cancel.\n\n")
					    .append("What is ")
					    .append(x)
					    .append(sign==-1 ? " minus " : " plus ")
					    .append(y)
					    .append("?")
					    .toString();
				    debug(ifname, "prompting user via JOptionPane.showInputDialog");
				    String response = (String)JOptionPane.showInputDialog(
					    applet,
					    message,
					    "Trephine permission request",
					    JOptionPane.QUESTION_MESSAGE
				    );
				    try {
					    int z = Integer.parseInt(response);
					    boolean correct = (z == x + sign * y);
					    debug(ifname, "user answered " + (correct ? "correctly" : "incorrectly"));
					    applet.setPermission( correct );
				    } catch (NumberFormatException e) {
					    debug(ifname, "null or unparsable response [" + response + "] receieved from user.");
					    applet.setPermission( false );
				    }
				}
				if (callback!=null && callback.length()>0) {
					try {
						Class<?> jsObject = Class.forName("netscape.javascript.JSObject");
						Method getWindow = jsObject.getMethod("getWindow", Applet.class);
						Method eval = jsObject.getMethod("eval", String.class);
						Object window = getWindow.invoke(jsObject, applet);
						String cbc = "("  + callback + ")(" + (applet.hasPermission() ? "true": "false") +  ");";
						debug(ifname, "issuing callback: " + cbc);
						eval.invoke(window, new Object[] { cbc });
					} catch(Exception e) {
						debug(ifname, "problem occurred issuing callback");
						e.printStackTrace(System.out);
					}
				} else {
					debug(ifname, "no callback to issue");
				}
			}
		});
		
		Launcher.debug(fname, "END");

	}

	public static void debug(String fname, Object msg) {
		if (Launcher.debugEnabled) System.out.println(fname + " - " + msg);
	}
	
		
	private synchronized void setPermission(boolean permission) {
		this.permissionGranted = permission;
	}
	
	public static boolean isDebugEnabled() { return debugEnabled; }
	public static void enableDebug() { Launcher.debugEnabled = true; }
	public static String getVersion() { return version; }

	public boolean isPrivileged() { return privileged; }
	public boolean isDestroyed() { return destroyed; }
	public boolean hasPermission() { return this.permissionGranted; }

	public List<String> getEngines() {
		return java.util.Collections.unmodifiableList(this.engines);
	}

	/**
	 * Alternative policy which evaluates code permissions based on the ThreadGroup hierarchy.
	 * Requires parent policy reference to which to defer when ThreadGroup membership test fails. 
	 */
	public class ThreadOrientedPolicy extends Policy {
		
		final private Policy parentPolicy;
		final private Launcher launcher;
		
		private boolean inCheck = false;

		private ThreadOrientedPolicy(Policy policy, Launcher launcher) {
			this.parentPolicy = policy;
			this.launcher = launcher;
			debug("ThreadOrientedPolicy:constructor()", "initialized with parent " + this.parentPolicy);
		}

		@Override
		public PermissionCollection getPermissions(CodeSource codesource) {
			return this.getExtendedPermissions(this.parentPolicy.getPermissions(codesource));
		}

		@Override
		public PermissionCollection getPermissions(ProtectionDomain domain) {
			return this.getExtendedPermissions(this.parentPolicy.getPermissions(domain));
		}
		
		/**
		 * Adds the magical AllPermission to the provided collection if applicable.
		 * @param permissions
		 * @return
		 */
		private PermissionCollection getExtendedPermissions(PermissionCollection permissions) {
			permissions.add(new AllPermission());
			if (this.launcher.isDestroyed()) return permissions;
			if (this.inCheck) return permissions;
			this.inCheck = true;
			if (Thread.currentThread().getThreadGroup().parentOf(this.launcher.thread.getThreadGroup())) {
				if (this.launcher.hasPermission()) permissions.add(new AllPermission());
			}
			this.inCheck = false;
			return permissions;
		}

	}
	
}
