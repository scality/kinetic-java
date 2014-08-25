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
package com.seagate.kinetic.simulator.internal;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import kinetic.client.KineticException;

import com.google.protobuf.ByteString;
import com.seagate.kinetic.common.lib.KineticMessage;
import com.seagate.kinetic.proto.Kinetic.Command;
import com.seagate.kinetic.proto.Kinetic.Command.PinOperation.PinOpType;
import com.seagate.kinetic.proto.Kinetic.Command.MessageType;
import com.seagate.kinetic.proto.Kinetic.Command.Setup;
import com.seagate.kinetic.proto.Kinetic.Command.Status.StatusCode;
import com.seagate.kinetic.proto.Kinetic.Message.AuthType;
import com.seagate.kinetic.proto.Kinetic.Message;

/**
 * 
 * Pin operation handler.
 *
 */
public abstract class PinOperationHandler {
    
    private final static Logger logger = Logger.getLogger(PinOperationHandler.class
            .getName());

    public static void handleOperation (KineticMessage request,
            KineticMessage respond, SimulatorEngine engine) throws KVStoreException, KineticException {
        
        boolean hasPermission = false;
        
        Message.Builder messageBuilder = (Message.Builder) respond.getMessage();
        // set pin auth
        messageBuilder.setAuthType(AuthType.PINAUTH);

        Command.Builder commandBuilder = (Command.Builder) respond.getCommand();
        
        // set reply type
        commandBuilder.getHeaderBuilder()
        .setMessageType(MessageType.PINOP_RESPONSE);
        
        // set ack sequence
        commandBuilder.getHeaderBuilder()
        .setAckSequence(request.getCommand().getHeader().getSequence());
        
        // check if met TLS requirement
        if (isSecureChannel (request, commandBuilder) == false) {
            
            /**
             * TLS requirement not met, return with INVALID_REQUEST. 
             */
            return;
        }
        
        // request pin
        ByteString requestPin = request.getMessage().getPinAuth().getPin();
        
        // request pin op type
        PinOpType pinOpType = request.getCommand().getBody().getPinOp().getPinOpType();
        
        switch (pinOpType) {
        case LOCK_PINOP:
            // check if has permission
            hasPermission = comparePin (requestPin, engine.getSecurityPin().getLockPin());
            if (hasPermission) {
                logger.info("Device locked ...");
            }
            break;
        case UNLOCK_PINOP:
            hasPermission = comparePin (requestPin, engine.getSecurityPin().getLockPin());
            if (hasPermission) {
                logger.info("Device unlocked ...");
            }
            break;
        case ERASE_PINOP:
            // Both erase operations will return
            // the device to an as manufactured state removing all
            // user data and configuration settings.
            // Erase the device. This may be secure
            // or not. The implication is that it may be faster
            // than the secure operation.
           
            hasPermission = comparePin (requestPin, engine.getSecurityPin().getErasePin());
            if (hasPermission) {
                
                // reset store
                engine.getStore().reset();
                
                //reset setup
                resetSetup (engine);
                
                //reset security
                SecurityHandler.resetSecurity(engine);
            }
            break;
        case SECURE_ERASE_PINOP:
            // Erase the device in a way that will
            // physical access and disassembly of the device
            // will not
            hasPermission = comparePin (requestPin, engine.getSecurityPin().getErasePin());
            if (hasPermission) {
                engine.getStore().reset();
                resetSetup (engine);  
                SecurityHandler.resetSecurity(engine);
            }
            break;
        case INVALID_PINOP:
            hasPermission = false;
            break;
       default: 
           hasPermission = false;
           break;
        }
        
        if (hasPermission == false) {
            commandBuilder.getStatusBuilder().setCode(StatusCode.NOT_AUTHORIZED);
            commandBuilder.getStatusBuilder().setStatusMessage("invalid pin: " + requestPin);
            
            logger.warning("unauthorized pin opeartion request, pin=" + requestPin);
        }
      
    }
    
    private static void resetSetup(SimulatorEngine engine) {
        Setup.Builder sb = Setup.newBuilder();
        sb.setNewClusterVersion(0);
        try {
            SetupHandler.persistSetup(sb.build().toByteArray(), engine.getKineticHome());
            engine.setClusterVersion(0);
        } catch (IOException e) {
            logger.log(Level.WARNING, e.getMessage(), e);
        }
    }
    
    /**
     * compare if request pin is equal to the device pin.
     * 
     * @param requestPin pin in the request message
     * @param devicePin device pin.
     * @return true if the same, otherwise return false
     */
    private static boolean comparePin (ByteString requestPin, ByteString devicePin) {
        
        boolean hasPermission = false;
        
        if (devicePin == null || devicePin.isEmpty()) {
            // if not set, set to true
            hasPermission = true;   
        } else if (devicePin.equals(requestPin)) {
            // if request pin is the same as drive pin
            hasPermission = true;
        }
        
        return hasPermission;
    }
    
    private static boolean isSecureChannel (KineticMessage request, Command.Builder respCommandBuilder) {
        
        boolean hasPermission = request.getIsSecureChannel();
        
        if (hasPermission == false) {
            respCommandBuilder.getStatusBuilder().setCode(StatusCode.INVALID_REQUEST);
            respCommandBuilder.getStatusBuilder().setStatusMessage("TLS channel is required for Pin operation");
        }
        
        return hasPermission;
    }
}