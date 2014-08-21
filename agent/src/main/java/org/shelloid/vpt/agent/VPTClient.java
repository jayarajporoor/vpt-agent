/*
 Copyright (c) Shelloid Systems LLP. All rights reserved.
 The use and distribution terms for this software are covered by the
 GNU General Public License 3.0 (http://www.gnu.org/copyleft/gpl.html)
 which can be found in the file LICENSE at the root of this distribution.
 By using this software in any fashion, you are agreeing to be bound by
 the terms of this license.
 You must not remove this notice, or any other, from this software.
 */
package org.shelloid.vpt.agent;

import com.google.gson.internal.LinkedTreeMap;
import org.shelloid.common.ICallback;
import org.shelloid.common.ShelloidUtil;
import org.shelloid.common.exceptions.ShelloidNonRetriableException;
import org.shelloid.common.messages.MessageFields;
import org.shelloid.common.messages.MessageTypes;
import org.shelloid.common.messages.MessageValues;
import org.shelloid.common.messages.ShelloidMessage;
import org.shelloid.ptcp.HelperFunctions;
import org.shelloid.ptcp.PseudoTcp;
import org.shelloid.vpt.agent.common.CallbackMessage;
import org.shelloid.vpt.agent.common.ConnectionInfo;
import org.shelloid.vpt.agent.common.PortMapInfo;
import org.shelloid.vpt.agent.util.AgentReliableMessenger;
import org.shelloid.vpt.agent.util.Configurations;
import org.shelloid.vpt.agent.util.Platform;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import java.awt.TrayIcon;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/* @author Harikrishnan */
public class VPTClient extends SimpleChannelInboundHandler<Object> {

    public static final ConcurrentHashMap<String, String> agentSvcMap = new ConcurrentHashMap();
    public static final ConcurrentHashMap<Integer, PortMapInfo> agentPortMap = new ConcurrentHashMap();
    public static final ConcurrentHashMap<String, ConnectionInfo> agentConnMap = new ConcurrentHashMap();
    public static final AttributeKey<Boolean> HAS_SENT_REMOTE_CLOSE = AttributeKey.valueOf("HAS_SENT_REMOTE_CLOSE");
    public boolean deviceMappingRcvd;

    public VPTClient(WebSocketClientHandshaker handshaker, ICallback<CallbackMessage> callback, AgentReliableMessenger messenger) {
        this.handshaker = handshaker;
        this.callback = callback;
        this.messenger = messenger;
        this.sutils = ShelloidUtil.getInstance();
        this.deviceMappingRcvd = false;
        startClearConnectionsTimer();
    }

    // <editor-fold defaultstate="collapsed" desc="Other codes from the netty web sockets">
    private final ScheduledExecutorService executer = Executors.newSingleThreadScheduledExecutor();
    public final WebSocketClientHandshaker handshaker;
    private final ICallback<CallbackMessage> callback;
    private final AgentReliableMessenger messenger;
    private final ShelloidUtil sutils;
    private ChannelPromise handshakeFuture;
    private Channel websocketChannel;
    private long lastSentAckNum;

    public ChannelFuture handshakeFuture() {
        return handshakeFuture;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        handshakeFuture = ctx.newPromise();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        handshaker.handshake(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        try {
            ctx.channel().close().sync();
            ctx.close().sync();
        } catch (InterruptedException ex) {
            Platform.shelloidLogger.error("InterruptedException while closing channel (channelInactive)");
        }
        onWsDisconnected(null);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel ch = ctx.channel();
        if (!handshaker.isHandshakeComplete()) {
            handshaker.finishHandshake(ch, (FullHttpResponse) msg);
            handshakeFuture.setSuccess();
            onWsAuthenticated();
            Platform.shelloidLogger.debug("Client connected using " + ch + ". Now sending init ACK");
            sendAckMessage(ch, messenger.getLastSendAckNum());
            setChannel(ch);
            return;
        }

        if (msg instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) msg;
            throw new IllegalStateException(
                    "Unexpected FullHttpResponse (getStatus=" + response.getStatus()
                    + ", content=" + response.content().toString(CharsetUtil.UTF_8) + ')');
        }

        WebSocketFrame frame = (WebSocketFrame) msg;
        if (frame instanceof TextWebSocketFrame) {
            TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
            handleShelloidClientMsg(textFrame.text(), ctx.channel());
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.channel().writeAndFlush(new PongWebSocketFrame());
        } else if (frame instanceof PongWebSocketFrame) {
            Platform.shelloidLogger.info("WebSocket Client received pong");
        } else if (frame instanceof CloseWebSocketFrame) {
            Platform.shelloidLogger.info("WebSocket Client received closing");
            try {
                ch.close().sync();
                ctx.close().sync();
            } catch (InterruptedException ex) {
                Platform.shelloidLogger.error("InterruptedException while closing channel (channelInactive)");
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        if (!handshakeFuture.isDone()) {
            handshakeFuture.setFailure(cause);
        }
        try {
            ctx.channel().close().sync();
            ctx.close().sync();
        } catch (InterruptedException ex) {
            Platform.shelloidLogger.error("InterruptedException while closing channel (channelInactive)");
        }
        Platform.shelloidLogger.info("Closing because of an exception");
    }
    // </editor-fold>

    private void handleShelloidClientMsg(String text, Channel channel) {
        try {
            Platform.shelloidLogger.debug("Client Received: " + text);
            ShelloidMessage msg = ShelloidMessage.parse(text);
            String type = msg.getString(MessageFields.type);
            if (type.equals(MessageTypes.URGENT)) {
                switch (msg.getString(MessageFields.subType)) {
                    case MessageTypes.TUNNEL: {
                        handleTunnelMessage(msg, channel);
                        break;
                    }
                    case MessageTypes.ACK: {
                        String msgId = msg.getString(MessageFields.seqNum);
                        messenger.processAckMsg(msgId, channel);
                        break;
                    }
                    case MessageTypes.DEVICE_MAPPINGS: {
                        if (!deviceMappingRcvd) {
                            deviceMappingRcvd = true;
                            handleDeviceMappingsMsg(msg, channel);
                        }
                        break;
                    }
                    case MessageTypes.NO_ROUTE: {
                        String connId = msg.getString(MessageFields.portMapId) + ":" + msg.getString(MessageFields.connTs);
                        ConnectionInfo info = agentConnMap.get(connId);
                        if (info != null) {
                            info.noRouteMsgCount++;
                            if (info.noRouteMsgCount > Configurations.MAX_NO_ROUTE_MSG) {
                                info.getPtcp().close(true);
                                try {
                                    info.getChannel().close().sync();
                                } catch (InterruptedException ex) {
                                    Platform.shelloidLogger.error("InterruptedException while closing channel", ex);
                                }
                                Platform.shelloidLogger.warn("No route found for the other device. So removing from agentConnMap: " + connId);
                                agentConnMap.remove(connId);
                                Platform.shelloidLogger.error("No route found for the device " + msg.getString(MessageFields.remoteDevId) + ": " + msg.getString(MessageFields.msg));
                                Platform.shelloidLogger.warn("No route found for the other device\n" + msg.getString(MessageFields.msg));
                            } else {
                                Platform.shelloidLogger.warn("Ignoring for the device " + msg.getString(MessageFields.remoteDevId) + ": " + msg.getString(MessageFields.msg) + ", count: " + info.noRouteMsgCount);
                            }
                        } else {
                            /* Message may be a non Tunnel message */
                        }
                        break;
                    }
                    default: {
                        System.err.println("Unknown urgent message:" + text);
                        break;
                    }
                }
            } else {
                long currentSeqNum = Long.parseLong(msg.getString(MessageFields.seqNum));
                try {
                    if (currentSeqNum > lastSentAckNum) {
                        lastSentAckNum = currentSeqNum;
                    } else {
                        Platform.shelloidLogger.info("current sequence number(" + currentSeqNum + ") less than or equal to last rcvd seq number(" + lastSentAckNum + ").");
                    }
                        switch (type) {
                            case MessageTypes.START_LISTENING: {
                                handleStartListeningMgs(msg, channel);
                                break;
                            }
                            case MessageTypes.OPEN_PORT: {
                                String portMapId = msg.getString(MessageFields.portMapId);
                                String svcPort = msg.getString(MessageFields.svcPort);
                                handleOpenPortMsg(portMapId, svcPort, channel);
                                break;
                            }
                            case MessageTypes.CLOSE_PORT:
                            case MessageTypes.STOP_LISTEN: {
                                handleFinishOperationMsg(msg, type, channel);
                                break;
                            }
                            default: {
                                System.err.println("Unknown reliable Message: " + text);
                                break;
                            }
                        }
                } finally {
                    sendAckMessage(channel, lastSentAckNum);
                    messenger.setLastSentAckNum(lastSentAckNum);
                }
            }
        } catch (ShelloidNonRetriableException ex) {
            Platform.shelloidLogger.error("Shelloid NRE: ", ex);
        }
    }

    private void handleOpenPortMsg(String portMapId, String svcPort, Channel serverChannel) {
        if (agentSvcMap.containsKey(portMapId) && (svcPort.equals(agentSvcMap.get(portMapId)))) {
            Platform.shelloidLogger.debug("Already received an OPEN_PORT request for port: " + agentSvcMap.get(portMapId));
        } else {
            agentSvcMap.put(portMapId, svcPort);
        }
        String msg = "Your port " + svcPort + " is shared with someone.";
        Platform.shelloidLogger.warn(msg);
        App.showTrayMessage(msg, TrayIcon.MessageType.INFO);
        send(serverChannel, new ShelloidMessage().put(MessageFields.type, MessageTypes.PORT_OPENED).put(MessageFields.portMapId, portMapId));
    }

    private void handleFinishOperationMsg(ShelloidMessage msg, String type, Channel channel) {
        String portMapId = msg.getString(MessageFields.portMapId);
        PortMapInfo info = null;
        int port = -1;
        for (Map.Entry<Integer, PortMapInfo> o : agentPortMap.entrySet()) {
            if (o.getValue().getPortMapId().equals(portMapId)) {
                info = o.getValue();
                port = o.getKey();
                break;
            }
        }
        if (info != null) {
            for (Map.Entry<String, ConnectionInfo> o : agentConnMap.entrySet()) {
                PortMapInfo obj = o.getValue().getPortMapInfo();
                if (obj != null) {
                    if (obj.getPortMapId().equals(info.getPortMapId()) && obj.getChannel() != null) {
                        sendTunnelMessage(obj.getChannel(), info.getPortMapId(), o.getValue().isSvcSide(), o.getValue().getConnTs() + "", null, 0, MessageValues.REMOTE_CLOSE);
                        Platform.shelloidLogger.info("closing from handleFinishOperationMsg.1");
                        try {
                            obj.getChannel().close().sync();
                        } catch (InterruptedException ex) {
                            Platform.shelloidLogger.error("InterruptedException while closing channel", ex);
                        }
                    }
                }
            }
            if (info.getChannel() != null) {
                Platform.shelloidLogger.info("closing from handleFinishOperationMsg.2");
                try {
                    info.getChannel().close().sync();
                } catch (InterruptedException ex) {
                    Platform.shelloidLogger.error("InterruptedException while closing channel", ex);
                }
            }
        }
        if (port == -1){
            try{
                port = Integer.parseInt(agentSvcMap.get(portMapId));
            }
            catch (Exception ex){
                //Platform.shelloidLogger.debug(agentSvcMap.get(portMapId) + " is not an integer (just checking!!).");
                Platform.shelloidLogger.debug(agentSvcMap.get(portMapId) + " is not an integer (Portmap ID does not exists).");
            }
        }
        agentSvcMap.remove(portMapId);
        String trmsg;
        if (type.equals(MessageTypes.CLOSE_PORT)) {
            trmsg = "A sharing on port " + port + " has been removed.";
            send(channel, new ShelloidMessage().put(MessageFields.type, MessageTypes.PORT_CLOSED).put(MessageFields.portMapId, portMapId));
        } else {
            trmsg = "Listening stopped on " + port;
            send(channel, new ShelloidMessage().put(MessageFields.type, MessageTypes.LISTENING_STOPPED).put(MessageFields.portMapId, portMapId));
        }
        if (port != -1){
            App.showTrayMessage(trmsg, TrayIcon.MessageType.INFO);
            Platform.shelloidLogger.warn(trmsg);
        }
    }

    private void handleStartListeningMgs(ShelloidMessage msg, Channel channel) throws NumberFormatException {
        String portMapId = msg.getString(MessageFields.portMapId);
        boolean alreadyMapped = false;
        int port = -1;
        for (Map.Entry<Integer, PortMapInfo> portMap : agentPortMap.entrySet()) {
            if (portMap.getValue().getPortMapId().equals(portMapId)) {
                alreadyMapped = true;
                port = portMap.getKey();
                break;
            }
        }
        if (!alreadyMapped) {
            Channel listeningChannel = listenToAvailablePort();
            if (listeningChannel != null) {
                executeListeningStartedProcedure(portMapId, listeningChannel, channel);
            } else {
                Platform.shelloidLogger.error("Can't find an available port to listen.");
            }
        } else {
            Platform.shelloidLogger.debug("Listening already started on " + port);
            sendListeningStartedMsg(port, portMapId, channel);
        }
    }

    private Channel listenToAvailablePort() {
        final int MAX_PORT_NUMBER = 65535;
        Channel listeningChannel = null;
        int i = Integer.parseInt(Configurations.get(Configurations.ConfigParams.STARTING_PORT_NUMBER));
        LocalLink currentLocalink = new LocalLink(this);
        while (i < MAX_PORT_NUMBER) {
            try {
                listeningChannel = currentLocalink.bind(i);
                break;
            } catch (Exception e) {
                /* Do nothing: this is the bind exception */
                i++;
            }
        }
        return listeningChannel;
    }

    public void executeListeningStartedProcedure(String portMapId, Channel listeningChannel, Channel serverChannel) {
        int port = sutils.getLocalPort(listeningChannel);
        agentPortMap.put(port, new PortMapInfo(portMapId, listeningChannel));
        sendListeningStartedMsg(port, portMapId, serverChannel);
        String msg = "Listening started on port " + port;
        Platform.shelloidLogger.warn(msg);
        App.showTrayMessage(msg, TrayIcon.MessageType.INFO);
    }

    public void sendListeningStartedMsg(int port, String portMapId, Channel serverChannel) {
        ShelloidMessage smsg = new ShelloidMessage();
        smsg.put(MessageFields.type, MessageTypes.LISTENING_STARTED);
        smsg.put(MessageFields.mappedPort, port + "");
        smsg.put(MessageFields.portMapId, portMapId);
        send(serverChannel, smsg);
    }

    private void sendAckMessage(Channel ch, long ackSeqNo) {
        ShelloidMessage ack = new ShelloidMessage();
        ack.put(MessageFields.type, MessageTypes.URGENT);
        ack.put(MessageFields.subType, MessageTypes.ACK);
        ack.put(MessageFields.seqNum, ackSeqNo);
        lastSentAckNum = ackSeqNo;
        send(ch, ack);
    }

    public void setChannel(Channel ch) {
        this.websocketChannel = ch;
    }

    public Channel getChannel() {
        return this.websocketChannel;
    }

    public void send(Channel channel, ShelloidMessage msg) {
        if (messenger == null) {
            channel.writeAndFlush(new TextWebSocketFrame(msg.getJson()));
        } else {
            if (msg.getString(MessageFields.type).equals(MessageTypes.URGENT)) {
                messenger.sendImmediate(msg, channel);
            } else {
                messenger.sendToClient(msg, channel);
            }
        }
    }

    public void clearConnection() {
        Platform.shelloidLogger.debug("Cleaning up connection details...");
        for (Map.Entry<String, ConnectionInfo> obj : agentConnMap.entrySet()) {
            if (obj.getValue().getChannel() != null) {
                try {
                    obj.getValue().getChannel().close().sync();
                } catch (InterruptedException ex) {
                    Platform.shelloidLogger.error("Can't close channel.");
                }
            }
        }
        for (Map.Entry<Integer, PortMapInfo> obj : agentPortMap.entrySet()) {
            if (obj.getValue().getChannel() != null) {
                try {
                    obj.getValue().getChannel().close().sync();
                } catch (InterruptedException ex) {
                    Platform.shelloidLogger.error("Can't close channel.");
                }
            }
        }
        agentConnMap.clear();
        agentPortMap.clear();
        agentSvcMap.clear();
    }

    private void onWsDisconnected(Throwable cause) {
        clearConnection();
        if (callback != null) {
            callback.callback(new CallbackMessage(CallbackMessage.Status.DISCONNECTED, new Object[]{cause}));
        }
    }

    private void onWsAuthenticated() {
        if (callback != null) {
            callback.callback(new CallbackMessage(CallbackMessage.Status.AUTH_SUCCESS, null));
        }
    }

    public void doRemoteClose(Channel remoteChannel, Channel appChannel, String connId, String portMapId, boolean isSvcSide, long connTs, PseudoTcp ptcp, ConnectionInfo info) {
        boolean sendCloseMsg = true;
        if (appChannel != null) {
            Platform.shelloidLogger.info("closing from doRemoteClose.1");
            try {
                appChannel.close().sync();
            } catch (InterruptedException ex) {
                Platform.shelloidLogger.error("InterruptedException while closing channel", ex);
            }
        }
        if (ptcp != null) {
            //System.out.println("SendBufLen: " + ptcp.getSendBufLen());
            if (ptcp.getSendBufLen() > 0) {
                info.setPendingClose(true);
                sendCloseMsg = false;
            } else {
                ptcp.close(true);
            }
        }
        if (sendCloseMsg) {
            if (info == null) {
                sendTunnelMessage(remoteChannel, portMapId, isSvcSide, connTs + "", null, 0, MessageValues.REMOTE_CLOSE);
            } else {
                if (!info.hasReceivedRemoteClose) {
                    sendTunnelMessage(remoteChannel, portMapId, isSvcSide, connTs + "", null, 0, MessageValues.REMOTE_CLOSE);
                }
            }
            Platform.shelloidLogger.info("From doRemoteClose: removing from agentConnMap: " + connId);
            agentConnMap.remove(connId);
        }
    }

    public void sendTunnelMessage(Channel ch, String portMapId, boolean isSvcSide, String connTs, byte[] buffer, int len, String ctrl) {
        if (ch == null) {
            Platform.shelloidLogger.warn("Channel is null. So droping the message");
        } else {
            ShelloidMessage msg = new ShelloidMessage();
            msg.put(MessageFields.type, MessageTypes.URGENT);
            msg.put(MessageFields.subType, MessageTypes.TUNNEL);
            msg.put(MessageFields.portMapId, portMapId);
            msg.put(MessageFields.isSvcSide, isSvcSide + "");
            msg.put(MessageFields.connTs, connTs);
            if (ctrl != null) {
                msg.put(MessageFields.ctrlMsg, ctrl);
            }
            if (buffer != null && len > 0) {
                String hex = HelperFunctions.toHexString(buffer, 0, len);
                msg.put(MessageFields.data, hex);
            }
            send(ch, msg);
        }
    }

    private void startClearConnectionsTimer() {
        final int CLEANUP_TIMER_DURATION = 15 * 1000;
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                long currTime = System.currentTimeMillis();
                for (Map.Entry<String, ConnectionInfo> entry : agentConnMap.entrySet()) {
                    if (currTime - entry.getValue().getLastRcvdTs() > Configurations.CONN_IDLE_THRESHOLD) {
                        Platform.shelloidLogger.warn("ConnInfo timeout - clearing connInfo: lastRcvTs: " + entry.getValue().getLastRcvdTs() + " curr ts: " + currTime + ". Diff: " + (currTime - entry.getValue().getLastRcvdTs()));
                        if (entry.getValue().getPtcp() != null) {
                            entry.getValue().getPtcp().close(true);
                        }
                        if (entry.getValue().getChannel() != null) {
                            try {
                                entry.getValue().getChannel().close().sync();
                            } catch (InterruptedException ex) {
                                Platform.shelloidLogger.error("InterruptedException while closing channel", ex);
                            }
                        }
                        Platform.shelloidLogger.info(" Removing from timer: removing from agentConnMap: " + entry.getKey());
                        agentConnMap.remove(entry.getKey());
                    }
                }
            }
        }, 0, CLEANUP_TIMER_DURATION);
    }

    private void handleDeviceMappingsMsg(ShelloidMessage msg, Channel channel) {
        ArrayList<LinkedTreeMap> guestPorts = msg.getArray(MessageFields.guestPortMappings);
        ArrayList<LinkedTreeMap> hostPorts = msg.getArray(MessageFields.hostPortMappings);
        LocalLink currentLocalink = new LocalLink(this);
        Iterator<LinkedTreeMap> i = guestPorts.iterator();
        while (i.hasNext()) {
            int port = -1;
            LinkedTreeMap portMap = i.next();
            String portStr = portMap.get(MessageFields.port).toString();
            String portMapId = portMap.get(MessageFields.portMapId).toString();
            boolean disabled = Boolean.parseBoolean(portMap.get(MessageFields.disabled).toString());
            if (disabled == true) {
                send(channel, new ShelloidMessage().put(MessageFields.type, MessageTypes.LISTENING_STOPPED).put(MessageFields.portMapId, portMapId));
                i.remove();
            } else {
                Channel listeningChannel = null;
                if (portStr != null) {
                    port = Integer.parseInt(portStr);
                }
                if (port == -1) {
                    listeningChannel = listenToAvailablePort();
                } else {
                    try {
                        listeningChannel = currentLocalink.bind(port);
                    } catch (Exception e) {
                        /* Do nothing: this is the bind exception */
                        if (agentPortMap.get(port) != null) {
                            if (agentPortMap.get(port).getPortMapId().equals(portMapId)) {
                                listeningChannel = agentPortMap.get(port).getChannel();
                            }
                        }
                    }
                }
                if (listeningChannel != null) {
                    i.remove();
                    executeListeningStartedProcedure(portMapId, listeningChannel, channel);
                }
            }
        }
        i = guestPorts.iterator();
        while (i.hasNext()) {
            LinkedTreeMap portMap = i.next();
            String portMapId = portMap.get(MessageFields.portMapId).toString();
            Channel listeningChannel = listenToAvailablePort();
            if (listeningChannel != null) {
                executeListeningStartedProcedure(portMapId, listeningChannel, channel);
            } else {
                Platform.shelloidLogger.error("Can't find an available port to listen.");
            }
        }
        i = hostPorts.iterator();
        while (i.hasNext()) {
            LinkedTreeMap portMap = i.next();
            boolean disabled = Boolean.parseBoolean(portMap.get(MessageFields.disabled).toString());
            String portMapId = portMap.get(MessageFields.portMapId).toString();
            if (disabled == true) {
                send(channel, new ShelloidMessage().put(MessageFields.type, MessageTypes.PORT_CLOSED).put(MessageFields.portMapId, portMapId));
                i.remove();
            } else {
                String portStr = portMap.get(MessageFields.port).toString();
                handleOpenPortMsg(portMapId, portStr, channel);
            }
        }
    }

    public void handleTunnelMessage(ShelloidMessage msg, Channel remoteChannel) {
        long connTs = msg.getLong(MessageFields.connTs);
        String portMapId = msg.getString(MessageFields.portMapId);
        String connId = portMapId + ":" + connTs;
        ConnectionInfo connInfo = agentConnMap.get(connId);
        String ctrl = msg.getString(MessageFields.ctrlMsg);
        if ((ctrl != null) && (ctrl.equals(MessageValues.REMOTE_CLOSE))) {
            if (connInfo != null) {
                connInfo.hasReceivedRemoteClose = true;
                //System.out.println("RCV-BUF-len: " + connInfo.getPtcp().getRcvBufLen());
                if (connInfo.getPtcp().getRcvBufLen() > 0) {
                    connInfo.setPendingClose(true);
                } else {
                    try {
                        connInfo.getChannel().close().sync();
                    } catch (InterruptedException ex) {
                        Platform.shelloidLogger.error("InterruptedException while closing channel", ex);
                    }
                    connInfo.getPtcp().close(true);
                    Platform.shelloidLogger.info("Closing from handleTunnelMessage.REMOTE_CLOSE. So removing from agentConnMap: " + connId);
                    agentConnMap.remove(connId);
                }
            }
            return;
        }
        boolean remoteIsSvcSide = msg.getBoolean(MessageFields.isSvcSide);
        PseudoTcp ptcp = null;
        if (connInfo == null) {
            //this is probably svc-side receiving msg for first time
            if (remoteIsSvcSide) {
                //this is app-side - so we've closed the conn
                Platform.shelloidLogger.debug("Conn Info Null for App Side. So sending Remote Close.");
                Platform.shelloidLogger.info("Sending Remote Close (Reason: 1). ConnID: " + connId);
                doRemoteClose(remoteChannel, null, connId, portMapId, false, connTs, null, null);
                return;
            }
            try {
                //this is host-side receiving msg for first time - so set up our connInfo
                String svcPort = agentSvcMap.get(portMapId);
                if (svcPort != null) {
                    Channel newChannel;
                    LocalLink currentLocalink = new LocalLink(this);
                    ptcp = new PseudoTcp(currentLocalink.new PTCPNotifier(), 0);
                    connInfo = new ConnectionInfo(ptcp, 0, true, connTs, System.currentTimeMillis(), false);
                    Bootstrap b = currentLocalink.getClientBootstrap();
                    b.attr(LocalLink.CONNECTION_MAPPING, connInfo);
                    newChannel = b.connect("localhost", Integer.parseInt(svcPort)).sync().channel();
                    Platform.shelloidLogger.info("Establishing a new Connection (id: " + connId + "): " + newChannel);
                    connInfo.setAgentPort(sutils.getLocalPort(newChannel));
                    connInfo.setChannel(newChannel);
                    connInfo.setPortMapInfo(new PortMapInfo(portMapId, newChannel));
                    ptcp.attach(connInfo);
                    connInfo.setLastRcvdTs(System.currentTimeMillis());
                    agentConnMap.put(connId, connInfo);
                    Platform.shelloidLogger.info("Svc connected with " + ((InetSocketAddress) newChannel.localAddress()).getPort());
                } else {
                    Platform.shelloidLogger.error("Unexpected TUNNEL message arrived.");
                    return;
                }
            } catch (Exception ex) {
                Platform.shelloidLogger.error("Can't get channel: " + ex.getMessage());
                doRemoteClose(remoteChannel, null, connId, portMapId, true, connTs, ptcp, connInfo);
                return;
            }
        } else {
            if (connInfo.getChannel() == null) {
                Platform.shelloidLogger.debug("Can't get channel from connection info. So sending Remote Close");
                Platform.shelloidLogger.info("Sending Remote Close (Reason: 3)");
                doRemoteClose(remoteChannel, null, connId, portMapId, !remoteIsSvcSide, connTs, null, null);
                return;
            } else {
                ptcp = connInfo.getPtcp();
            }
        }
        if (connInfo != null) {
            connInfo.noRouteMsgCount = 0;
        }
        if (ptcp != null) {
            String hex = msg.getString(MessageFields.data);
            byte[] data = HelperFunctions.fromHexString(hex);
            //System.out.println("CALLING ptcp.notifyPacket");
            boolean notifyOk = ptcp.notifyPacket(data, data.length);
            if (!notifyOk || (connInfo.getPendingClose() && ptcp.getSendBufLen() <= 0)) {
                Platform.shelloidLogger.debug("ptcp.notifyPacket returned false. So sending Remote Close");
                Platform.shelloidLogger.info("Sending Remote Close (Reason: 4), notify: " + notifyOk + ", pendigClose: " + connInfo.getPendingClose() + ", sendBufLen: " + ptcp.getSendBufLen());
                doRemoteClose(remoteChannel, (connInfo == null ? null : connInfo.getChannel()), connId, portMapId, !remoteIsSvcSide, connTs, ptcp, connInfo);
            }
            HelperFunctions.adjustClock(executer, ptcp);
        } else {
            throw new IllegalStateException("PTCP is NULL");
        }
    }
}