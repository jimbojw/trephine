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
	private boolean destroyed = false;
	
	private Drop drop;
	private Thread thread;
	private Thread executor;
	private List<String> engines = new ArrayList<String>();
	
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
			this.issueErrorCallback();
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
		Pattern hostPattern, webserverPattern;
		{
			String webserver = props.getProperty("trephine.webserver.pattern", "");
			try {
				webserverPattern = Pattern.compile("\\A(" + webserver + ")\\Z");
			} catch (Throwable t) {
				debug(fname, "the properties key 'trephine.webserver.pattern' is not a valid regular expression");
				t.printStackTrace(System.out);
				this.issueErrorCallback();
				return;
			}
			String host = props.getProperty("trephine.host.pattern", "");
			if (host.length()==0) {
				debug(fname, "the properties key 'trephine.host.pattern' is empty, using the webserver pattern instead");
				hostPattern = webserverPattern;
			} else {
				try {
					hostPattern = Pattern.compile("\\A(" + host + ")\\Z");
				} catch (Throwable t) {
					debug(fname, "the properties key 'trephine.host.pattern' is not a valid regular expression");
					t.printStackTrace(System.out);
					this.issueErrorCallback();
					return;
				}
			}
		}
			
		// Check that codebase came from a permitted host domain
		debug(fname, "checking applet codesource location against host pattern...");
		URL csLocation = this.getClass().getProtectionDomain().getCodeSource().getLocation();
		if (!hostPattern.matcher(csLocation.toString()).matches()) {
			debug(fname, "applet codebase location [" + csLocation + "] does not match host pattern, any further applet interaction will fail.");
			this.issueErrorCallback();
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
			pageURL = (String) eval.invoke(window, new Object[] { "window.parent.location + ''" });
		} catch (Exception e) {
			debug(fname, "unable to retrieve window.parent.location from calling page, aborting.");
			e.printStackTrace(System.out);
			this.issueErrorCallback();
			return;
		}
		if (!webserverPattern.matcher(pageURL).matches()) {
			debug(fname, "calling page [" + pageURL + "] does not match webserver pattern, any further applet interaction will fail.");
			this.issueErrorCallback();
			return;
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
				this.issueErrorCallback();
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
				this.issueErrorCallback();
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
	 * Code to execute the provided onerror callback.
	 */
	private void issueErrorCallback() {
		final Launcher applet = this;
		final String onerror = this.onerror;
		if (onerror==null || onerror.length()==0) return;
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				final String fname = "issueErrorCallback:invokeLater";
				try {
					Class<?> jsObject = Class.forName("netscape.javascript.JSObject");
					Method getWindow = jsObject.getMethod("getWindow", Applet.class);
					Method eval = jsObject.getMethod("eval", String.class);
					Object window = getWindow.invoke(jsObject, applet);
					String cbc = "("  + onerror + ")();";
					debug(fname, "issuing callback: " + cbc);
					eval.invoke(window, new Object[] { cbc });
				} catch(Exception e) {
					debug(fname, "problem occurred issuing callback");
					e.printStackTrace(System.out);
				}
			}
		});
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
	
	public static void debug(String fname, Object msg) {
		if (Launcher.debugEnabled) {
			System.out.println(fname + " - " + msg);
			System.out.flush();
		}
	}
	
	public static boolean isDebugEnabled() { return debugEnabled; }
	public static void enableDebug() { Launcher.debugEnabled = true; }
	public static String getVersion() { return version; }

	public boolean isPrivileged() { return privileged; }
	public boolean isDestroyed() { return destroyed; }

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
			if (this.launcher.isDestroyed()) return permissions;
			if (this.inCheck) return permissions;
			this.inCheck = true;
			if (Thread.currentThread().getThreadGroup().parentOf(this.launcher.thread.getThreadGroup())) {
				permissions.add(new AllPermission());
			}
			this.inCheck = false;
			return permissions;
		}

	}
	
}
