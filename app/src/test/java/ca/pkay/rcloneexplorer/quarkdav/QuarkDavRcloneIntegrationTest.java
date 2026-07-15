package ca.pkay.rcloneexplorer.quarkdav;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QuarkDavRcloneIntegrationTest {

    @Test
    public void blankRequestedNameUsesCurrentGeneratedDefault() {
        assertEquals(
                "quarkdav_My_Drive",
                QuarkDavRcloneIntegration.resolveRcloneName("My Drive", "   ")
        );
    }

    @Test
    public void customNameIsTrimmedAndPreserved() {
        assertEquals(
                "My WebDAV 2",
                QuarkDavRcloneIntegration.resolveRcloneName("ignored", "  My WebDAV 2  ")
        );
    }

    @Test
    public void nameValidationMatchesRcloneConfigRulesUsedByTheDialog() {
        assertTrue(QuarkDavRcloneIntegration.isValidRcloneName("WebDAV_远端-2@example.com"));
        assertFalse(QuarkDavRcloneIntegration.isValidRcloneName("-starts-with-dash"));
        assertFalse(QuarkDavRcloneIntegration.isValidRcloneName("contains:colon"));
        assertFalse(QuarkDavRcloneIntegration.isValidRcloneName("ends-with-space "));
    }
}
