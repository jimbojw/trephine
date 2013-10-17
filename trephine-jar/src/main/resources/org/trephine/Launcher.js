/**
 * Launcher.js
 */
 
(function(global) {
	
// Protecting references to globals (since they're shared across languages)
var applet = global.applet, context = global.context, engine = global.engine, manager = global.manager;

var debug = function() {
	if (!applet.isDebugEnabled()) return;
	for (var i=0; i<arguments.length; i++) java.lang.System.out.println("Launcher.js - " + arguments[i]);
}

debug("START");
debug("  applet = " + applet);
debug("  context = " + context);
debug("  engine = " + engine);
debug("  manager = " + manager);

debug("getting window refrence...");
var window = global.window = Packages.netscape.javascript.JSObject.getWindow(applet);
debug("  window = " + window);

debug("setting up trephine object in DOM window and document...");
var trephine = window.eval("window.trephine = { set: function(key,val) { this[key] = val; } };");
debug("  trephine = " + trephine);

debug("implementing Marshal...");
var marshal = global.marshal = new Packages.org.trephine.Marshal({
	getVersion: function() { return applet.getVersion() + ''; },
	exec: function(lang, code) {
		debug("marshal:exec() - START");
		if (applet.isDestroyed()) return null;
		var result = applet.exec(lang, code);
		debug("marshal:exec() - END");
		return result;
	},
	isPrivileged: function(){ return applet.isPrivileged(); },
	isDebugEnabled: function(){ return applet.isDebugEnabled(); },
	enableDebug: function(){ return applet.enableDebug(); }
});
debug("  marshal = " + marshal);

//TODO (maybe): Alter the dynamically created Proxy class (of which marshal is the singleton instance) and
// change the ProtectionDomain's CodeSource to exactly match the DOM window's domain.

debug("setting marshal reference...");
trephine.call("set", ["marshal", marshal]);
debug("  retrieved marshal object: " + window.eval("trephine.marshal"));

debug("END");

})(this);
