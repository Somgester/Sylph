package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TerminalPageTest {

    @Test
    void pageNeedsNoExternalResourcesAndOnlyAuthorizesItsOwnScripts() {
        String html = TerminalPage.html();
        assertFalse(html.contains("@@"));
        assertFalse(html.contains("<script src="));
        assertFalse(html.contains("<link rel="));
        assertTrue(html.contains("default-src 'none'"));
        assertTrue(html.contains("connect-src 'none'"));
        assertFalse(html.contains("'unsafe-eval'"));
        var policy = Pattern.compile("script-src 'nonce-([^']+)'").matcher(html);
        assertTrue(policy.find());
        String nonce = policy.group(1);
        var scripts = Pattern.compile("<script nonce=\"([^\"]+)\"").matcher(html);
        int count = 0;
        while (scripts.find()) {
            assertEquals(nonce, scripts.group(1));
            count++;
        }
        assertEquals(3, count);
        var other = Pattern.compile("script-src 'nonce-([^']+)'").matcher(TerminalPage.html());
        assertTrue(other.find());
        assertNotEquals(nonce, other.group(1));
    }
}
