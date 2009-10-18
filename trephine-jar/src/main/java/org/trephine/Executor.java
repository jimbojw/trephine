package org.trephine;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;

public class Executor extends Thread {

	final private Launcher applet;
	final private Drop drop;
	final private HashMap<String,Object> environment;
	
	final private Map<String,String> names = new HashMap<String,String>();
	final private Map<String,ScriptEngine> engines = new HashMap<String,ScriptEngine>();
	
	final private List<ScriptEngineFactory> factories = new ArrayList<ScriptEngineFactory>();

	public Executor( Launcher applet, Drop drop, HashMap<String,Object> environment ) {
		this.applet = applet;
		this.drop = drop;
		this.environment = environment;
	}
	
	public void run() {

		final String fname = "Executor:run()";
		final Thread thread = Thread.currentThread();

		Launcher.debug(fname, "START - " + thread);

		Launcher.debug(fname, "waiting for environment setup...");
		synchronized(this.environment) {
			while (this.environment.get("initialized")==null && !this.applet.isDestroyed()) {
				try {
					this.environment.wait();
				} catch (InterruptedException e) {
					e.printStackTrace(System.out);
				}
			}
		}
		
		if (applet.isDestroyed()) {
			Launcher.debug(fname, "END - applet destroyed before initialization");
			return;
		}
		
		ScriptEngineManager manager = (ScriptEngineManager) this.environment.get("manager");
		ScriptContext context = (ScriptContext) this.environment.get("context");
		
		Launcher.debug(fname, "initializing engine mappings");
		this.setupMappings(manager);
		
		String canonicalName = this.names.get("js");
		Launcher.debug(fname, "pre-setting reference for " + canonicalName + " engine");
		if ( canonicalName!=null ) {
			ScriptEngine engine = (ScriptEngine) this.environment.get("engine");
			this.engines.put(canonicalName, engine);
		}

		Launcher.debug(fname, "preloading selected engines");
		for (String name: this.applet.getEngines()) this.getEngine(manager, context, name); 
			
		Launcher.debug(fname, "waiting for a job to do...");
		Job job = this.drop.take();
		while ( job!=null && job!=Job.DONE ) {
		
			Launcher.debug(fname, "found Job, starting...");

			synchronized (job) {
				try {
					ScriptEngine engine = this.getEngine(manager, context, job.getLanguage());
					if (engine==null) throw new RuntimeException("Unable to procure script engine for language " + job.getLanguage());
					Launcher.debug(fname, "evaluating job code...");
					Object result = engine.eval(job.getCode());
					Launcher.debug(fname, "setting job result");
					job.setResult( result );
				} catch (Exception e) {
					Launcher.debug(fname, "error evaluating code - " + e.getMessage());
					e.printStackTrace(System.out);
					job.setException(e);
				}
			}
		
			Launcher.debug(fname, "finished executing Job!");

			job = this.drop.take();
		}
		
		Launcher.debug(fname, "END");
	}

	private ScriptEngine getEngine(ScriptEngineManager manager, ScriptContext context, String name) {
		final String fname = "Executor:getEngine()";
		name = name.trim();
		Launcher.debug(fname, "START - procuring engine for language " + name + "...");
		if (manager==null) return null;
		String canonicalName = this.names.get(name);
		if (canonicalName==null) {
			this.setupMappings(manager);
			canonicalName = this.names.get(name);
			if (canonicalName==null) {
				Launcher.debug(fname, "could not resolve engine " + name);
				return null;
			}
		}
		ScriptEngine engine = this.engines.get(canonicalName);
		if (engine!=null) return engine;
		engine = manager.getEngineByName(canonicalName);
		if (engine==null) {
			Launcher.debug(fname, "manager lookup failed, checking local factories list...");
			for (ScriptEngineFactory factory: this.factories) {
				if (factory.getLanguageName()==canonicalName) {
					engine = factory.getScriptEngine();
					if (engine!=null) {
						manager.registerEngineName(canonicalName, factory);
						break;
					}
				}
			}
			if (engine==null) {
				Launcher.debug(fname, "could not create instance of engine " + canonicalName);
				return null;
			}
		}
		engine.setContext(context);
		this.engines.put(canonicalName, engine);
		Launcher.debug(fname, "END - loading " + canonicalName + " engine completed successfully");
		return engine;
	}
	
	private void setupMappings(ScriptEngineManager manager) {
		final String fname = "Executor:setupMappings()";
		Launcher.debug(fname, "START");
		Launcher.debug(fname, "creating auto-discovered script engine alias mappings...");
		List<ScriptEngineFactory> factories = manager.getEngineFactories();
		for (ScriptEngineFactory factory: factories) {
			String canonicalName = factory.getLanguageName();
			Launcher.debug(fname, "  Engine: " + factory.getEngineName() + " [" + canonicalName + "]");
			for (String name: factory.getNames()) {
				if (this.names.get(name)==null) this.names.put(name, canonicalName);
			}
		}
		Launcher.debug(fname, "creating script engine alias mappings from local list...");
		for (ScriptEngineFactory factory: this.factories) {
			String canonicalName = factory.getLanguageName();
			Launcher.debug(fname, "  Engine: " + factory.getEngineName() + " [" + canonicalName + "]");
			for (String name: factory.getNames()) {
				if (this.names.get(name)==null) this.names.put(name, canonicalName);
			}
		}
		Launcher.debug(fname, "END");
	}
	
	/**
	 * Attempts to add a jar to the "system" classloader, signified by URL/URI (preferably a file URI)
	 * @param uri The URL of the jar to add (preferably a file URI, but a String, File or URL will also do)
	 * @return Whether the operation was successful.
	 */
	public boolean addSystemJar( Object uri ) {
		final String fname = "Executor:addSystemJar()";
		Launcher.debug(fname, "START");
		
		if (this.applet.isDestroyed()) {
			Launcher.debug(fname, "END - applet is already destroyed, exiting!");
			return false;
		}
		if (!this.applet.isPrivileged()) {
			Launcher.debug(fname, "END - applet is not running in privileged mode, exiting!");
			return false;
		}
		if (!this.applet.hasPermission()) {
			Launcher.debug(fname, "END - privileged access has not been granted, exiting!");
			return false;
		}
		
		URLClassLoader appClassLoader = (URLClassLoader)ClassLoader.getSystemClassLoader();
		try {
			URL url = null;
			if (uri instanceof java.io.File) url = ((java.io.File)uri).toURI().toURL();
			if (uri instanceof java.net.URI) url = ((java.net.URI)uri).toURL();
			if (uri instanceof URL) url = (URL)uri;
			if (url==null) url = new URL(uri.toString());
			Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
			addURL.setAccessible(true);
			Launcher.debug(fname, "adding " + url);
			addURL.invoke(appClassLoader,new Object[]{ url });
		} catch (Throwable t) {
			Launcher.debug(fname, "failed to add jar URL to system classloader");
			t.printStackTrace(System.out);
			Launcher.debug(fname, "END - inhospitable environment");
			return false;
		}

		Launcher.debug(fname, "END - jar successfully added");
		return true;
	}

	/**
	 * Adds a given ScriptEngineFactory to the list of known factories.
	 * @param factory The ScriptEngineFactory to add.
	 * @return Whether the operation was successful.
	 */
	public void addEngineFactory( ScriptEngineFactory factory ) {
		this.factories.add( factory );
	}

}
