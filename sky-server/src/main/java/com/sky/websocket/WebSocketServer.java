package com.sky.websocket;

import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.OnError;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket服务
 */
@Component
@Slf4j
@ServerEndpoint("/ws/{sid}")
public class WebSocketServer {

    //存放会话对象
    private static final Map<String, Session> sessionMap = new ConcurrentHashMap<>();

    /**
     * 连接建立成功调用的方法
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("sid") String sid) {
        log.info("WebSocket 客户端建立连接：{}", sid);
        sessionMap.put(sid, session);
    }

    /**
     * 收到客户端消息后调用的方法
     *
     * @param message 客户端发送过来的消息
     */
    @OnMessage
    public void onMessage(String message, @PathParam("sid") String sid) {
        log.debug("收到 WebSocket 客户端 {} 的消息：{}", sid, message);
    }

    /**
     * 连接关闭调用的方法
     *
     * @param sid
     */
    @OnClose
    public void onClose(Session session, @PathParam("sid") String sid) {
        log.info("WebSocket 连接断开：{}", sid);
        sessionMap.remove(sid, session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.warn("WebSocket 连接异常：{}", session == null ? null : session.getId(), error);
    }

    /**
     * 群发
     *
     * @param message
     */
    public void sendToAllClient(String message) {
        sessionMap.forEach((sid, session) -> {
            try {
                // 支付和催单可能同时发送，串行写入同一会话。
                synchronized (session) {
                    if (session.isOpen()) {
                        session.getBasicRemote().sendText(message);
                    } else {
                        sessionMap.remove(sid, session);
                    }
                }
            } catch (Exception e) {
                log.warn("WebSocket 消息发送失败，客户端：{}", sid, e);
            }
        });
    }

}
