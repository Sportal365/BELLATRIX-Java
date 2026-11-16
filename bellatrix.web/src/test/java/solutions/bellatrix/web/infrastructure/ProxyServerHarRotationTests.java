package solutions.bellatrix.web.infrastructure;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import net.lightbody.bmp.core.har.Har;
import net.lightbody.bmp.core.har.HarEntry;
import net.lightbody.bmp.core.har.HarResponse;
import net.lightbody.bmp.filters.HarCaptureFilter;
import net.lightbody.bmp.proxy.CaptureType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("unit")
class ProxyServerHarRotationTests {
    private EmbeddedChannel channel;

    @BeforeEach
    void setup() throws Exception {
        removeProxy();
        channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter()) {
            @Override
            protected SocketAddress remoteAddress0() {
                return new InetSocketAddress("127.0.0.1", 12345);
            }
        };
    }

    @AfterEach
    void cleanup() throws Exception {
        channel.finishAndReleaseAll();
        removeProxy();
    }

    @Test
    void compactionSharesLargeIdenticalBodiesWithoutDroppingHistoryOrChangingResponses() {
        ProxyServer.newHar("class-history");
        var entries = ProxyServer.getCapturedEntries();
        for (int i = 0; i < 3; i++) {
            var entry = new HarEntry("class-history");
            entry.setResponse(new HarResponse(200, "OK", "HTTP/1.1"));
            entry.getResponse().getContent().setText("x".repeat(100000) + (i == 2 ? "different" : "same"));
            entries.add(entry);
        }
        var first = entries.get(0).getResponse().getContent();
        var second = entries.get(1).getResponse().getContent();
        var third = entries.get(2).getResponse().getContent();
        String different = third.getText();
        assertNotSame(first.getText(), second.getText());

        ProxyServer.compactResponseBodies();

        assertEquals(3, entries.size());
        assertSame(first.getText(), second.getText());
        assertEquals("x".repeat(100000) + "same", first.getText());
        assertSame(different, third.getText());
        assertEquals("class-history", ProxyServer.get().getHar().getLog().getPages().get(0).getId());
    }

    @Test
    void compactionPreservesSmallAndPendingResponses() {
        assertDoesNotThrow(ProxyServer::compactResponseBodies);
        ProxyServer.newHar();
        var entry = new HarEntry("class-history");
        entry.setResponse(new HarResponse(0, "", "HTTP/1.1"));
        ProxyServer.getCapturedEntries().add(entry);
        assertDoesNotThrow(ProxyServer::compactResponseBodies);
        entry.getResponse().getContent().setText("{\"cachedConfiguration\":true}");
        ProxyServer.compactResponseBodies();
        assertEquals("{\"cachedConfiguration\":true}", entry.getResponse().getContent().getText());
    }

    @Test
    void unnamedRotationReleasesEntriesRetainedByAnOldFilter() {
        verifyRotation(ProxyServer::newHar);
    }

    @Test
    void namedRotationPreservesTheNewCaptureName() {
        verifyRotation(() -> ProxyServer.newHar("next-test"));
        assertEquals("next-test", ProxyServer.get().getHar().getLog().getPages().get(0).getId());
    }

    @Test
    void clearHistoryReleasesEntriesAndPreservesCaptureTypes() {
        ProxyServer.get().enableHarCaptureTypes(CaptureType.REQUEST_CONTENT, CaptureType.RESPONSE_CONTENT);
        var captureTypes = ProxyServer.get().getHarCaptureTypes().clone();
        verifyRotation(ProxyServer::clearHistory);
        assertEquals(captureTypes, ProxyServer.get().getHarCaptureTypes());
    }

    @Test
    void initialAndEmptyCapturesCanBeRotated() {
        assertNull(ProxyServer.get().getHar());
        assertDoesNotThrow(ProxyServer::clearHistory);
        var initial = ProxyServer.get().getHar();
        assertNotNull(initial);
        assertDoesNotThrow(() -> { ProxyServer.newHar(); });
        assertNotSame(initial, ProxyServer.get().getHar());
        assertTrue(initial.getLog().getEntries().isEmpty());
        assertDoesNotThrow(() -> ProxyServer.newHar("empty"));
        assertTrue(ProxyServer.getCapturedEntries().isEmpty());
    }

    private void verifyRotation(Runnable rotate) {
        ProxyServer.newHar("previous-test");
        Har previous = ProxyServer.get().getHar();
        for (int i = 0; i < 4; i++) {
            var entry = new HarEntry("previous-test");
            entry.setResponse(new HarResponse(200, "OK", "HTTP/1.1"));
            entry.getResponse().getContent().setText("x".repeat(1024 * 1024));
            previous.getLog().addEntry(entry);
        }
        HarCaptureFilter oldFilter = startCapture(previous, "previous-test", "/old-request");
        HarEntry inFlightEntry = previous.getLog().getEntries().get(4);
        assertEquals(5, previous.getLog().getEntries().size());

        rotate.run();

        Har current = ProxyServer.get().getHar();
        assertNotSame(previous, current);
        assertTrue(previous.getLog().getEntries().isEmpty());
        var currentFilter = startCapture(current, current.getLog().getPages().get(0).getId(), "/current-request");
        completeResponse(oldFilter, "{\"previous\":true}");
        completeResponse(currentFilter, "{\"current\":true}");

        assertEquals("{\"previous\":true}", inFlightEntry.getResponse().getContent().getText());
        assertTrue(previous.getLog().getEntries().isEmpty());
        assertEquals(1, current.getLog().getEntries().size());
        assertEquals("{\"current\":true}", ProxyServer.getCapturedEntries().get(0).getResponse().getContent().getText());
        assertTrue(ProxyServer.getCapturedEntries().get(0).getRequest().getUrl().endsWith("/current-request"));
    }

    private HarCaptureFilter startCapture(Har har, String pageId, String path) {
        var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://example.test" + path);
        request.headers().set("Host", "example.test");
        var filter = new HarCaptureFilter(request, channel.pipeline().firstContext(), har, pageId,
                EnumSet.of(CaptureType.REQUEST_CONTENT, CaptureType.RESPONSE_CONTENT));
        filter.clientToProxyRequest(request);
        return filter;
    }

    private void completeResponse(HarCaptureFilter filter, String body) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        response.headers().set("Content-Type", "application/json; charset=UTF-8");
        try {
            filter.serverToProxyResponse(response);
            filter.serverToProxyResponseReceived();
        } finally {
            response.release();
        }
    }

    private void removeProxy() throws Exception {
        var field = ProxyServer.class.getDeclaredField("PROXY_SERVER");
        field.setAccessible(true);
        ((ThreadLocal<?>) field.get(null)).remove();
    }
}
