/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james.imapserver.netty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;

import org.apache.james.imap.api.ImapConstants;
import org.apache.james.imap.api.ImapMessage;
import org.apache.james.imap.api.ImapSessionState;
import org.apache.james.imap.api.process.ImapProcessor;
import org.apache.james.imap.api.process.ImapSession;
import org.apache.james.imap.encode.ImapEncoder;
import org.apache.james.imap.encode.ImapResponseComposer;
import org.apache.james.imap.encode.base.ImapResponseComposerImpl;
import org.apache.james.imap.main.ResponseEncoder;
import org.apache.james.metrics.api.Metric;
import org.apache.james.protocols.api.logger.ContextualLogger;
import org.apache.james.protocols.imap.IMAPSession;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import org.slf4j.Logger;

/**
 * {@link SimpleChannelUpstreamHandler} which handles IMAP
 */
public class ImapChannelUpstreamHandler extends SimpleChannelUpstreamHandler implements NettyConstants{

    private final Logger logger;

    private final String hello;

    private final String[] enabledCipherSuites;

    private final SSLContext context;

    private final boolean compress;

    private final ImapProcessor processor;

    private final ImapEncoder encoder;

    private final ImapHeartbeatHandler heartbeatHandler = new ImapHeartbeatHandler();

    private final boolean plainAuthDisallowed;

    private final Metric imapConnectionsMetric;
    private final Metric imapCommandsMetric;
    
    public ImapChannelUpstreamHandler(String hello, ImapProcessor processor, ImapEncoder encoder, Logger logger, boolean compress,
                                      boolean plainAuthDisallowed, ImapMetrics imapMetrics) {
        this(hello, processor, encoder, logger, compress, plainAuthDisallowed, null, null, imapMetrics);
    }

    public ImapChannelUpstreamHandler(String hello, ImapProcessor processor, ImapEncoder encoder, Logger logger, boolean compress,
                                      boolean plainAuthDisallowed, SSLContext context, String[] enabledCipherSuites,
                                      ImapMetrics imapMetrics) {
        this.logger = logger;
        this.hello = hello;
        this.processor = processor;
        this.encoder = encoder;
        this.context = context;
        this.enabledCipherSuites = enabledCipherSuites;
        this.compress = compress;
        this.plainAuthDisallowed = plainAuthDisallowed;
        this.imapConnectionsMetric = imapMetrics.getConnectionsMetric();
        this.imapCommandsMetric = imapMetrics.getCommandsMetric();
    }

    private Logger getLogger(final ChannelHandlerContext ctx) {
        return new ContextualLogger(
                getUserSupplier(ctx),
                String.valueOf(ctx.getChannel().getId()),
                logger);
    }

    private Supplier<String> getUserSupplier(final ChannelHandlerContext ctx) {
        return () -> {
            Object o = attributes.get(ctx.getChannel());
            if (o instanceof IMAPSession) {
                IMAPSession session = (IMAPSession) o;
                return session.getUser();
            }
            return null;
        };
    }

    @Override
    public void channelBound(final ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        ImapSession imapsession = new NettyImapSession(ctx.getChannel(), toLogSupplier(ctx), context, enabledCipherSuites, compress, plainAuthDisallowed);
        attributes.set(ctx.getChannel(), imapsession);
        super.channelBound(ctx, e);
    }

    private Supplier<Logger> toLogSupplier(final ChannelHandlerContext ctx) {
        return () -> getLogger(ctx);
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        
        InetSocketAddress address = (InetSocketAddress) ctx.getChannel().getRemoteAddress();
        getLogger(ctx).info("Connection closed for " + address.getAddress().getHostAddress());

        // remove the stored attribute for the channel to free up resources
        // See JAMES-1195
        ImapSession imapSession = (ImapSession) attributes.remove(ctx.getChannel());
        if (imapSession != null)
            imapSession.logout();
        imapConnectionsMetric.decrement();

        super.channelClosed(ctx, e);
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        
        InetSocketAddress address = (InetSocketAddress) ctx.getChannel().getRemoteAddress();
        getLogger(ctx).info("Connection established from " + address.getAddress().getHostAddress());
        imapConnectionsMetric.increment();

        ImapResponseComposer response = new ImapResponseComposerImpl(new ChannelImapResponseWriter(ctx.getChannel()));
        ctx.setAttachment(response);

        // write hello to client
        response.untagged().message("OK").message(hello).end();
        super.channelConnected(ctx, e);

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        
        getLogger(ctx).warn("Error while processing imap request", e.getCause());

        if (e.getCause() instanceof TooLongFrameException) {

            // Max line length exceeded
            // See RFC 2683 section 3.2.1
            //
            // "For its part, a server should allow for a command line of at
            // least
            // 8000 octets. This provides plenty of leeway for accepting
            // reasonable
            // length commands from clients. The server should send a BAD
            // response
            // to a command that does not end within the server's maximum
            // accepted
            // command length."
            //
            // See also JAMES-1190
            ImapResponseComposer composer = (ImapResponseComposer) ctx.getAttachment();
            composer.untaggedResponse(ImapConstants.BAD + " failed. Maximum command line length exceeded");
            
        } else {

            // logout on error not sure if that is the best way to handle it
            final ImapSession imapSession = (ImapSession) attributes.get(ctx.getChannel());
            if (imapSession != null)
                imapSession.logout();

            // Make sure we close the channel after all the buffers were flushed out
            Channel channel = ctx.getChannel();
            if (channel.isConnected()) {
                channel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }

        }

    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {

        imapCommandsMetric.increment();
        ImapSession session = (ImapSession) attributes.get(ctx.getChannel());
        ImapResponseComposer response = (ImapResponseComposer) ctx.getAttachment();
        ImapMessage message = (ImapMessage) e.getMessage();
        ChannelPipeline cp = ctx.getPipeline();

        try {
            if (cp.get(NettyConstants.EXECUTION_HANDLER) != null) {
                cp.addBefore(NettyConstants.EXECUTION_HANDLER, NettyConstants.HEARTBEAT_HANDLER, heartbeatHandler);
            } else {
                cp.addBefore(NettyConstants.CORE_HANDLER, NettyConstants.HEARTBEAT_HANDLER, heartbeatHandler);

            }
            final ResponseEncoder responseEncoder = new ResponseEncoder(encoder, response, session);
            processor.process(message, responseEncoder, session);

            if (session.getState() == ImapSessionState.LOGOUT) {
                // Make sure we close the channel after all the buffers were flushed out
                Channel channel = ctx.getChannel();
                if (channel.isConnected()) {
                    channel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            }
            final IOException failure = responseEncoder.getFailure();

            if (failure != null) {
                final Logger logger = session.getLog();
                logger.info(failure.getMessage());
                if (logger.isDebugEnabled()) {
                    logger.debug("Failed to write " + message, failure);
                }
                throw failure;
            }
        } finally {
            ctx.getPipeline().remove(NettyConstants.HEARTBEAT_HANDLER);
        }

        super.messageReceived(ctx, e);

    }

}
