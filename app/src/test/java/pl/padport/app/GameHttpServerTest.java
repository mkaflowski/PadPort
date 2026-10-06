package pl.padport.app;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class GameHttpServerTest {
    private static String response(String method,int status,String reason) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        GameHttpServer.writeError(out,method,status,reason);
        return out.toString(StandardCharsets.ISO_8859_1.name());
    }
    @Test public void missingFilesAreReal404sNotOkPages() throws Exception {
        // GeckoView: "200 Not found" made MZ decrypt a 9-byte body (RangeError in the Grinning Beast fight).
        String get=response("GET",404,"Not found");
        assertTrue(get,get.startsWith("HTTP/1.1 404 Not found\r\n"));
        assertTrue(get.contains("Content-Length: 9\r\n"));
        assertTrue(get.endsWith("\r\n\r\nNot found"));
    }
    @Test public void headProbesGetTheStatusWithoutABody() throws Exception {
        // The fs shim's existsSync() is a HEAD request: 404 must mean "missing".
        String head=response("HEAD",404,"Not found");
        assertTrue(head.startsWith("HTTP/1.1 404 "));
        assertTrue(head.endsWith("\r\n\r\n"));
        assertTrue(response("GET",403,"Forbidden").startsWith("HTTP/1.1 403 Forbidden\r\n"));
    }
}
