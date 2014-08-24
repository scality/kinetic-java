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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.logging.Logger;

import com.seagate.kinetic.common.lib.KineticMessage;
import com.seagate.kinetic.proto.Kinetic.Command;
import com.seagate.kinetic.proto.Kinetic.Message;
import com.seagate.kinetic.proto.Kinetic.Command.MessageType;
import com.seagate.kinetic.proto.Kinetic.Command.Security.ACL;
import com.seagate.kinetic.proto.Kinetic.Command.Security.ACL.Permission;
import com.seagate.kinetic.proto.Kinetic.Command.Setup;
import com.seagate.kinetic.proto.Kinetic.Command.Status.StatusCode;
import com.seagate.kinetic.simulator.lib.SetupInfo;
import com.seagate.kinetic.simulator.persist.Store;

/**
 *
 * Setup info handler
 * <p>
 *
 * @author Chenchong(Emma) Li
 *
 */
public abstract class SetupHandler {
    private final static Logger logger = Logger.getLogger(SetupHandler.class
            .getName());

    public static boolean checkPermission(KineticMessage request,
            KineticMessage respond, Map<Long, ACL> currentMap) {
        
        boolean hasPermission = false;

        Command.Builder commandBuilder = (Command.Builder) respond.getCommand();
        
        // set reply type
        commandBuilder.getHeaderBuilder()
        .setMessageType(MessageType.SETUP_RESPONSE);
        // set ack sequence
        commandBuilder.getHeaderBuilder()
        .setAckSequence(request.getCommand().getHeader().getSequence());

        // check if has permission to set security
        if (currentMap == null) {
            hasPermission = true;
        } else {
            try {
                // check if client has permission
                Authorizer.checkPermission(currentMap, request.getMessage().getHmacAuth().getIdentity(), Permission.SETUP);

                hasPermission = true;
            } catch (KVSecurityException e) {
                commandBuilder.getStatusBuilder()
                .setCode(StatusCode.NOT_AUTHORIZED);
                commandBuilder.getStatusBuilder()
                .setStatusMessage(e.getMessage());
            }
        }

        return hasPermission;
    }

    @SuppressWarnings("rawtypes")
    private static synchronized SetupInfo _handleSetup(KineticMessage request,
            KineticMessage respond, Store store,
            String kineticHome) throws KVStoreException, IOException {
        
        SetupInfo setupInfo = null;
        
        Command.Builder commandBuilder = (Command.Builder) respond.getCommand();
        
        /**
         * XXX protocol-3.0.0
         */
        //byte[] newPin = request.getMessage().getCommand().getBody().getSetup()
        //        .getPin()
        //        .toByteArray();

//        logger.info("parameterPin=" + new String(newPin) + ", internalPin="
//                + new String(myPin));
//
//        if (null == myPin || 0 == myPin.length) {
//            setupInfo = handleSetup(request, respond, myPin, newPin, store,
//                    kineticHome);
//        } else if (Arrays.equals(newPin, myPin)) {
//            setupInfo = handleSetup(request, respond, myPin, newPin, store,
//                    kineticHome);
//        } else {
//            commandBuilder.getStatusBuilder()
//            .setCode(StatusCode.INTERNAL_ERROR);
//            commandBuilder.getStatusBuilder()
//            .setStatusMessage("Pin not match");
//        }

        return setupInfo;
    }

    @SuppressWarnings("rawtypes")
    public static SetupInfo handleSetup(KineticMessage request,
            KineticMessage respond, Store store,
            String kineticHome) throws IOException, KVStoreException {
        
        SetupInfo setupInfo = new SetupInfo();
        
        Command.Builder commandBuilder = (Command.Builder) respond.getCommand();
        
        // persist setupInfo
        SetupHandler.persistSetup(request.getCommand().getBody()
                .getSetup()
                .toByteArray(), kineticHome);

        // modify clusterVersion
        if (request.getCommand().getBody().getSetup()
                .hasNewClusterVersion()) {
            long newClusterVersion = request.getCommand()
                    .getBody().getSetup()
                    .getNewClusterVersion();
            //if (null != newClusterVersion) {
                setupInfo.setClusterVersion(newClusterVersion);
                logger.info("the cluster version is set to: " + newClusterVersion);
            //}
        }

        /**
         * XXX protocol-3.0.0
         */
        // erase the db data
        //if (request.getMessage().getCommand().getBody().getSetup()
        //        .getInstantSecureErase()) {
        //    store.reset();
        //    logger.info("erase db finish!");
        //}

        // set pin
        //if (request.getMessage().getCommand().getBody().getSetup().hasSetPin()) {
        //    myPin = request.getMessage().getCommand().getBody().getSetup()
        //            .getSetPin()
        //            .toByteArray();
        //    setupInfo.setPin(myPin);
        //    logger.info("the drive pin is set: " + new String(myPin));
        //}

        // persist firmware download
        if (request.getCommand().getBody().getSetup()
                .getFirmwareDownload()) {
            if (request.getValue() != null) {
                byte[] firmwareDownloadValue = request.getValue();
                persistFirmwareDownload(firmwareDownloadValue, kineticHome);
            }
        }

        // TODO handle exception
        commandBuilder.getStatusBuilder()
        .setCode(StatusCode.SUCCESS);

        return setupInfo;
    }

     static void persistSetup(byte[] contents, String kineticHome)
            throws IOException {
        String setupPersistFilePath = kineticHome + File.separator + ".setup";
        String setupPersistBakFilePath = setupPersistFilePath + ".bak";

        // delete backup file
        File aclBakFile = new File(setupPersistBakFilePath);
        if (aclBakFile.exists()) {
            aclBakFile.delete();
        }

        // backup file
        File aclFile = new File(setupPersistFilePath);
        aclFile.renameTo(aclBakFile);

        // save new file
        aclFile = new File(setupPersistFilePath);
        FileOutputStream out = new FileOutputStream(aclFile);
        out.write(contents);
        out.close();
    }

    private static void persistFirmwareDownload(byte[] firmwareContent,
            String kineticHome) throws IOException {
        String firmwarePersistFilePath = kineticHome + File.separator;

        Date date = new Date();
        Timestamp timeStamp = new Timestamp(date.getTime());

        // make directory for timestamp
        File file_home = new File(firmwarePersistFilePath + timeStamp);
        if (!file_home.exists()) {
            file_home.mkdirs();
        }

        // save new firmware file
        String file_path = file_home + File.separator + "firmware";
        File firmwareFile = new File(file_path);
        FileOutputStream out = new FileOutputStream(firmwareFile);
        out.write(firmwareContent);
        out.close();
    }

    public static void loadSetup(SimulatorEngine engine) throws IOException {
        
        String setupPersistFilePath = engine.getKineticHome() + File.separator + ".setup";

        File setupFile = new File(setupPersistFilePath);
        
        if (setupFile.exists()) {
            
            Long fileLength = setupFile.length();
            
            if (fileLength != 0) {
                // read info from file
                byte[] fileContent = new byte[fileLength.intValue()];
                FileInputStream in = new FileInputStream(setupFile);
                in.read(fileContent);
                in.close();
                Setup setup = Setup.parseFrom(fileContent);
                
                // set cluster version
                engine.setClusterVersion(setup.getNewClusterVersion());
            }
        }
    }
}
