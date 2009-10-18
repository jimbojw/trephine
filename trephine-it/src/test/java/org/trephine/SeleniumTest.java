package org.trephine;

import junit.framework.TestCase;

import com.thoughtworks.selenium.DefaultSelenium;

public class SeleniumTest extends TestCase
{
	protected DefaultSelenium createSeleniumClient(String url) throws Exception {
		return new DefaultSelenium("localhost", 4444, "*firefox", url);
	}
	
	public void testSomethingSimple() throws Exception {
		DefaultSelenium selenium = createSeleniumClient("http:///localhost:8080/");
		selenium.start();
		selenium.click("link=launch");
		selenium.waitForPageToLoad("300000");
		assertEquals("trephine tests", selenium.getTitle());
		selenium.stop();
	}
}


