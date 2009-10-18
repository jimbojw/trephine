package org.trephine;

import junit.framework.TestCase;

import com.thoughtworks.selenium.DefaultSelenium;

public class SeleniumTest extends TestCase
{
	protected DefaultSelenium createSeleniumClient(String url) throws Exception {
		return new DefaultSelenium("localhost", 4444, "*firefox", url);
	}
	
	public void testSomethingSimple() throws Exception {
		DefaultSelenium selenium = createSeleniumClient("file:///home/jimbo/code/checkout/trephine/src/it/html/index.html");
		selenium.start();
		selenium.click("link=launch");
		selenium.waitForPageToLoad("30000");
		assertEquals("trephine tests", selenium.getTitle());
		selenium.stop();
	}
}


