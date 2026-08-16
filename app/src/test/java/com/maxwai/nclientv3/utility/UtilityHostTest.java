package com.maxwai.nclientv3.utility;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The host suffix rule behind {@link Utility#isSiteHost}. One OkHttp client carries the session
 * cookies to every destination the app talks to, so this predicate is the only thing keeping them
 * away from third parties - and a wrong answer here is invisible in a build.
 * <p>
 * Tests {@code belongsToSite} rather than {@code isSiteHost} because the latter reads the
 * configured mirror out of {@code Global}, whose static state needs the Android framework.
 */
public class UtilityHostTest {

    @Test
    public void exactHostBelongs() {
        assertTrue(Utility.belongsToSite("nhentai.net", "nhentai.net"));
    }

    @Test
    public void subdomainsBelong() {
        assertTrue(Utility.belongsToSite("i.nhentai.net", "nhentai.net"));
        assertTrue(Utility.belongsToSite("t.nhentai.net", "nhentai.net"));
        assertTrue(Utility.belongsToSite("i3.nhentai.net", "nhentai.net"));
    }

    @Test
    public void matchIsCaseInsensitive() {
        assertTrue(Utility.belongsToSite("I.NHentai.NET", "nhentai.net"));
        assertTrue(Utility.belongsToSite("nhentai.net", "NHentai.Net"));
    }

    @Test
    public void unrelatedHostsDoNotBelong() {
        assertFalse(Utility.belongsToSite("api.github.com", "nhentai.net"));
        assertFalse(Utility.belongsToSite("objects.githubusercontent.com", "nhentai.net"));
    }

    /**
     * The reason this is a suffix test against {@code "." + site} and not a bare
     * {@code endsWith(site)}: an attacker-registered lookalike must not match.
     */
    @Test
    public void lookalikeDomainsDoNotBelong() {
        assertFalse(Utility.belongsToSite("evil-nhentai.net", "nhentai.net"));
        assertFalse(Utility.belongsToSite("nhentai.net.evil.com", "nhentai.net"));
        assertFalse(Utility.belongsToSite("xnhentai.net", "nhentai.net"));
    }

    @Test
    public void anUnsetMirrorMatchesNothing() {
        assertFalse(Utility.belongsToSite("nhentai.net", null));
        assertFalse(Utility.belongsToSite("nhentai.net", ""));
    }
}
