package io.github.vivekdavara.dispatch.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

/**
 * A courier app's HTTP client may open its WebSocket on a keep-alive connection it already used for ordinary
 * requests. Tomcat closes a connection after {@code max-keep-alive-requests} requests (100 by default) by adding
 * {@code Connection: close} to that response, and it does so even when the response is a 101 upgrade, which then
 * carries {@code Connection: upgrade} and {@code Connection: close}. Strict clients (the JDK's, OkHttp) refuse that
 * handshake; the simulator's bots hit it about once per few hundred reconnects. The app turns the limit off, so an
 * upgrade answers with one {@code Connection} header whatever the connection served before.
 *
 * <p>Raw socket on purpose: an HTTP client library would pick its own connection, and the point is to send the
 * upgrade as the 100th request on one connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class KeepAliveUpgradeTest {

    @LocalServerPort
    int port;

    @Test
    void anUpgradeAfterNinetyNineRequestsOnOneConnectionStillHasOneConnectionHeader() throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            byte[] health = "HEAD /api/v1/health HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < 99; i++) {
                out.write(health);
                String head = readHead(in);
                assertThat(head).startsWith("HTTP/1.1 200");
                assertThat(connectionValues(head)).doesNotContain("close");
            }

            out.write(("GET /ws/couriers/" + UUID.randomUUID() + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String upgrade = readHead(in);

            assertThat(upgrade).startsWith("HTTP/1.1 101");
            assertThat(connectionValues(upgrade)).containsExactly("upgrade");
        }
    }

    /** The status line and headers of one response (a HEAD or a 101 has no body to skip). */
    static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("connection closed after: " + head.toString(StandardCharsets.US_ASCII));
            }
            head.write(b);
            matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        return head.toString(StandardCharsets.US_ASCII);
    }

    /** Every value of every {@code Connection} header, lowercased. */
    static List<String> connectionValues(String head) {
        return head.lines()
                .filter(l -> l.toLowerCase(Locale.ROOT).startsWith("connection:"))
                .flatMap(l -> List.of(l.substring("connection:".length()).split(",")).stream())
                .map(v -> v.trim().toLowerCase(Locale.ROOT))
                .toList();
    }
}
