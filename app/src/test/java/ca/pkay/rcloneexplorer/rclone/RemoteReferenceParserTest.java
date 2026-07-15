package ca.pkay.rcloneexplorer.rclone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RemoteReferenceParserTest {

    private void assertParsed(String input, RemoteReferenceParser.Kind kind, String value) {
        RemoteReferenceParser.Result result = RemoteReferenceParser.parse(input);
        assertEquals(kind, result.getKind());
        assertEquals(value, result.getValue());
    }

    @Test
    public void parsesNamedRemoteTargets() {
        assertParsed("base:path", RemoteReferenceParser.Kind.NAMED_REMOTE, "base");
        assertParsed("base:", RemoteReferenceParser.Kind.NAMED_REMOTE, "base");
        assertParsed(
                "base,server_side_across_configs=true:path",
                RemoteReferenceParser.Kind.NAMED_REMOTE,
                "base");
        assertParsed(
                "base,url=https://example.test:path",
                RemoteReferenceParser.Kind.NAMED_REMOTE,
                "base");
        assertParsed(
                "base,param=/path:with-colon:path",
                RemoteReferenceParser.Kind.NAMED_REMOTE,
                "base");
    }

    @Test
    public void parsesLocalTargets() {
        assertParsed(
                "/storage/emulated/0/crypt",
                RemoteReferenceParser.Kind.LOCAL,
                "/storage/emulated/0/crypt");
        assertParsed(
                "relative/folder",
                RemoteReferenceParser.Kind.LOCAL,
                "relative/folder");
        assertParsed(
                "relative/file:name",
                RemoteReferenceParser.Kind.LOCAL,
                "relative/file:name");
        assertParsed("folder", RemoteReferenceParser.Kind.LOCAL, "folder");
        assertParsed("C:\\Users\\test", RemoteReferenceParser.Kind.LOCAL, "C:\\Users\\test");
    }

    @Test
    public void parsesInlineBackends() {
        assertParsed(
                ":local:/storage/emulated/0",
                RemoteReferenceParser.Kind.INLINE_BACKEND,
                "local");
        assertParsed(
                ":s3,provider=AWS:bucket",
                RemoteReferenceParser.Kind.INLINE_BACKEND,
                "s3");
        assertParsed(
                ":s3,endpoint=https://example.test:bucket",
                RemoteReferenceParser.Kind.INLINE_BACKEND,
                "s3");
    }

    @Test
    public void rejectsEmptyTargets() {
        assertParsed("", RemoteReferenceParser.Kind.UNKNOWN, "");
        assertParsed(":", RemoteReferenceParser.Kind.UNKNOWN, "");
    }
}
