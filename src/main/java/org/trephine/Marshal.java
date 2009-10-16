package org.trephine;

/**
 * Interface to be implemented in Launcher.js.
 */
public interface Marshal {
	public String getVersion();
	public Object exec(String language, String code);
	public boolean isPrivileged();
	public boolean hasPermission();
	public void askPermission(String callback);
	public boolean isDebugEnabled();
	public void enableDebug();
}

