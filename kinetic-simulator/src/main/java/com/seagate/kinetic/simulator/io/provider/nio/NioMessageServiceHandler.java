/**
 * 
 * Copyright (C) 2014 Seagate Technology.
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 */
package com.seagate.kinetic.simulator.io.provider.nio;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.seagate.kinetic.common.lib.KineticMessage;
import com.seagate.kinetic.proto.Kinetic.Command.MessageType;
import com.seagate.kinetic.simulator.internal.ConnectionInfo;
import com.seagate.kinetic.simulator.internal.FaultInjectedCloseConnectionException;
import com.seagate.kinetic.simulator.internal.SimulatorEngine;
import com.seagate.kinetic.simulator.io.provider.spi.MessageService;

/**
 *
 * @author chiaming
 */
public class NioMessageServiceHandler extends
		SimpleChannelInboundHandler<KineticMessage> {

	private static final Logger logger = Logger
			.getLogger(NioMessageServiceHandler.class.getName());

	private MessageService lcservice = null;

	private boolean enforceOrdering = false;

	private NioQueuedRequestProcessRunner queuedRequestProcessRunner = null;

	private static boolean faultInjectCloseConnection = Boolean
			.getBoolean(FaultInjectedCloseConnectionException.FAULT_INJECT_CLOSE_CONNECTION);

    private boolean isSecureChannel = false;

    public NioMessageServiceHandler(MessageService lcservice2,
            boolean isSecureChannel) {
		this.lcservice = lcservice2;

        this.isSecureChannel = isSecureChannel;

		this.enforceOrdering = lcservice.getServiceConfiguration()
				.getMessageOrderingEnforced();

		if (this.enforceOrdering) {
			this.queuedRequestProcessRunner = new NioQueuedRequestProcessRunner(
					lcservice);
		}
	}
	
	@Override
	public void channelActive (ChannelHandlerContext ctx) throws Exception {
	    super.channelActive(ctx);
	    
	    // register connection info with the channel handler context
        @SuppressWarnings("unused")
        ConnectionInfo info = this.lcservice.registerNewConnection(ctx);
	    
	    //logger.info("***** connection registered., sent UNSOLICITEDSTATUS with cid = " + info.getConnectionId());
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx,
			KineticMessage request)
			throws Exception {

		if (faultInjectCloseConnection) {
			throw new FaultInjectedCloseConnectionException(
					"Fault injected for the simulator");
		}
		
		// set ssl channel flag to false
        request.setIsSecureChannel(isSecureChannel);
		
		// check if conn id is set
		NioConnectionStateManager.checkIfConnectionIdSet(ctx, request);

        boolean shouldContinue = NioBatchOpPreProcessor.processMessage(this,
                ctx, request);

        if (shouldContinue) {
            // process regular request
            processRequest(ctx, request);
        }
    }

    public void processRequest(ChannelHandlerContext ctx,
            KineticMessage request) throws InterruptedException {

        if (enforceOrdering) {
            if (this.shouldProcessRequestAsync(request)) {
                // process request async
                this.processRequestAsync(ctx, request);
            } else {
                // process request sequentially
                queuedRequestProcessRunner.processRequest(ctx, request);
            }
        } else {
            this.processRequestAsync(ctx, request);
        }
	}

    /**
     * Process request asynchronously. The calling thread does not wait for the
     * request to be processed and returns immediately.
     * 
     * @param ctx
     * @param request
     * @throws InterruptedException
     */
    private void processRequestAsync(ChannelHandlerContext ctx,
            KineticMessage request) throws InterruptedException {

        // each request is independently processed
        RequestProcessRunner rpr = null;
        rpr = new RequestProcessRunner(lcservice, ctx, request);
        this.lcservice.execute(rpr);

        logger.info("***** request processed asynchronously ....");
    }

    private boolean shouldProcessRequestAsync(KineticMessage request) {
        boolean flag = false;

        MessageType mtype = request.getCommand().getHeader().getMessageType();

        if (mtype == MessageType.MEDIAOPTIMIZE
                || mtype == MessageType.MEDIASCAN) {
            flag = true;
        }

        return flag;
    }

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		ctx.flush();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
			throws Exception {
		logger.log(Level.WARNING, "Unexpected exception from downstream.",
				cause);

		// close process runner
		if (this.queuedRequestProcessRunner != null) {
			this.queuedRequestProcessRunner.close();
		}

		// close context
		ctx.close();
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
	    
        // remove connection info of the channel handler context from conn info
        // map
        ConnectionInfo info = SimulatorEngine.removeConnectionInfo(ctx);

        logger.info("connection info is removed, id=" + info.getConnectionId()
                + ", is secure channel=" + this.isSecureChannel);
	}

    public MessageService getMessageService() {
        return this.lcservice;
    }

}