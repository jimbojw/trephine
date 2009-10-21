package org.trephine;

import junit.framework.TestCase;

import com.thoughtworks.selenium.DefaultSelenium;

public class SeleniumTest extends TestCase {

    private String url = "http://localhost:8080/";

	public void testFirefox() throws Exception {
	    this.runtest(this.url, "*firefox");
	}
	
	public void testIE() throws Exception {
	    this.runtest(this.url, "*iexplore");
	}
	
	/*
	public void testChrome() throws Exception {
	    this.runtest(this.url, "*chrome");
	}
	
	public void testOpera() throws Exception {
	    this.runtest(this.url, "*opera");
	}
	
	public void testSafari() throws Exception {
	    this.runtest(this.url, "*safari");
	}
	*/
	
	protected void runtest(String url, String browser) {
		DefaultSelenium selenium = new DefaultSelenium("localhost", 4444, browser, url);
		selenium.start();
		selenium.open(url);
		assertEquals("trephine tests", selenium.getTitle());
		selenium.click("id=launch");
		selenium.waitForCondition("selenium.browserbot.getCurrentWindow().trephine.loaded", "10000");
		selenium.stop();
	}

}

