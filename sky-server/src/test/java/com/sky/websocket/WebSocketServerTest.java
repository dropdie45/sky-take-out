package com.sky.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import java.io.IOException;

import static org.mockito.Mockito.*;

class WebSocketServerTest {
    private final WebSocketServer server = new WebSocketServer();
    private final Session first = mock(Session.class);
    private final Session second = mock(Session.class);
    private final RemoteEndpoint.Basic firstRemote = mock(RemoteEndpoint.Basic.class);
    private final RemoteEndpoint.Basic secondRemote = mock(RemoteEndpoint.Basic.class);

    private void connect() {
        when(first.isOpen()).thenReturn(true);
        when(second.isOpen()).thenReturn(true);
        when(first.getBasicRemote()).thenReturn(firstRemote);
        when(second.getBasicRemote()).thenReturn(secondRemote);
        server.onOpen(first, "test-first");
        server.onOpen(second, "test-second");
    }

    @AfterEach
    void disconnect() {
        server.onClose(first, "test-first");
        server.onClose(second, "test-second");
    }

    @Test
    void broadcastsToAllConnectedClients() throws Exception {
        connect();
        server.sendToAllClient("{\"type\":1}");
        verify(firstRemote).sendText("{\"type\":1}");
        verify(secondRemote).sendText("{\"type\":1}");
    }

    @Test
    void closedOrFailingClientDoesNotBlockOthers() throws Exception {
        connect();
        doThrow(new IOException("disconnected")).when(firstRemote).sendText("notice");
        server.sendToAllClient("notice");
        verify(secondRemote).sendText("notice");
        when(first.isOpen()).thenReturn(false);
        server.sendToAllClient("next");
        verify(firstRemote, never()).sendText("next");
        verify(secondRemote).sendText("next");
    }

    @Test
    void oldConnectionClosingDoesNotRemoveReplacementWithSameSid() throws Exception {
        connect();
        server.onOpen(second, "test-first");
        server.onClose(first, "test-first");
        server.onClose(second, "test-second");
        server.sendToAllClient("notice");
        verify(secondRemote).sendText("notice");
        server.onClose(second, "test-first");
    }
}
