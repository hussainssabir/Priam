/**
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.priam.defaultimpl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;

import com.netflix.priam.utils.Sleeper;
import org.apache.commons.lang3.StringUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.netflix.priam.ICassandraProcess;
import com.netflix.priam.IConfiguration;

public class CassandraProcessManager implements ICassandraProcess
{
    private static final Logger logger = LoggerFactory.getLogger(CassandraProcessManager.class);
    private static final String SUDO_STRING = "/usr/bin/sudo";
    private static final int SCRIPT_EXECUTE_WAIT_TIME_MS = 5000;
    protected final IConfiguration config;
    protected final Sleeper sleeper;

    @Inject
    public CassandraProcessManager(IConfiguration config, Sleeper sleeper)
    {
        this.config = config;
        this.sleeper = sleeper;
    }

    protected void setEnv(Map<String, String> env) {   
        env.put("HEAP_NEWSIZE", config.getHeapNewSize());
        env.put("MAX_HEAP_SIZE", config.getHeapSize());
        env.put("DATA_DIR", config.getDataFileLocation());
        env.put("COMMIT_LOG_DIR", config.getCommitLogLocation());
        env.put("LOCAL_BACKUP_DIR", config.getBackupLocation());
        env.put("CACHE_DIR", config.getCacheLocation());
        env.put("JMX_PORT", "" + config.getJmxPort());
        env.put("LOCAL_JMX", config.enableRemoteJMX()?"no":"yes");
        env.put("MAX_DIRECT_MEMORY", config.getMaxDirectMemory());
    }
    
    public void start(boolean join_ring) throws IOException
    {
        logger.info("Starting cassandra server ....Join ring=" + join_ring);

        List<String> command = Lists.newArrayList();
        if (!"root".equals(System.getProperty("user.name")))
        {
            command.add(SUDO_STRING);
            command.add("-n");
            command.add("-E");
        }
        command.addAll(getStartCommand());

        ProcessBuilder startCass = new ProcessBuilder(command);
        
        Map<String, String> env = startCass.environment();
        setEnv(env);
        env.put("cassandra.join_ring", join_ring ? "true" : "false");
        
        startCass.directory(new File("/"));
        startCass.redirectErrorStream(true);
        logger.info("Start cmd: " + startCass.command().toString());
        logger.info("Start env: " + startCass.environment().toString());
        Process starter = startCass.start();
        
        logger.info("Starting cassandra server ....");
		try {
			sleeper.sleepQuietly(SCRIPT_EXECUTE_WAIT_TIME_MS);
			int code = starter.exitValue();
			if (code == 0)
				logger.info("Cassandra server has been started");
			else
				logger.error("Unable to start cassandra server. Error code: {}", code);

			logProcessOutput(starter);
		} catch (Exception e) 
                {
                     logger.warn("Starting Cassandra has an error", e);
		}
    }

    protected List<String> getStartCommand()
    {
        List<String> startCmd = new LinkedList<String>();
        for(String param : config.getCassStartupScript().split(" ")){
            if( StringUtils.isNotBlank(param))
                startCmd.add(param);
        }
        return startCmd;
    }

    void logProcessOutput(Process p)
    {
        try
        {
            final String stdOut = readProcessStream(p.getInputStream());
            final String stdErr = readProcessStream(p.getErrorStream());
            logger.info("std_out: {}", stdOut);
            logger.info("std_err: {}", stdErr);
        }
        catch(IOException ioe)
        {
            logger.warn("Failed to read the std out/err streams", ioe);
        }
    }

    String readProcessStream(InputStream inputStream) throws IOException
    {
        final byte[] buffer = new byte[512];
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(buffer.length);
        int cnt;
        while ((cnt = inputStream.read(buffer)) != -1)
            baos.write(buffer, 0, cnt);
        return baos.toString();
    }


    public void stop() throws IOException
    {
        logger.info("Stopping cassandra server ....");
        List<String> command = Lists.newArrayList();
        if (!"root".equals(System.getProperty("user.name")))
        {
            command.add(SUDO_STRING);
            command.add("-n");
            command.add("-E");
        }
        for(String param : config.getCassStopScript().split(" ")){
            if( StringUtils.isNotBlank(param))
                command.add(param);
        }
        ProcessBuilder stopCass = new ProcessBuilder(command);
        stopCass.directory(new File("/"));
        stopCass.redirectErrorStream(true);
        Process stopper = stopCass.start();

        sleeper.sleepQuietly(SCRIPT_EXECUTE_WAIT_TIME_MS);
        try
        {
            int code = stopper.exitValue();
            if (code == 0)
                logger.info("Cassandra server has been stopped");
            else
            {
                logger.error("Unable to stop cassandra server. Error code: {}", code);
                logProcessOutput(stopper);
            }
        }
        catch(Exception e)
        {
            logger.warn("couldn't shut down cassandra correctly", e);
        }
    }
}
