/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.priam.cred;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.ClasspathPropertiesFileCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a basic implementation of ICredentials. User should prefer to implement their own
 * versions for more secured access. This class requires clear AWS key and access.
 *
 * <p>Set the following properties in "conf/awscredntial.properties"
 */
public class ClearCredential implements ICredential {
    private static final Logger logger = LoggerFactory.getLogger(ClearCredential.class);
    private static final String CRED_FILE = "/conf/awscredential.properties";
    private final String AWS_ACCESS_ID;
    private final String AWS_KEY;
    private ClasspathPropertiesFileCredentialsProvider file =
            new ClasspathPropertiesFileCredentialsProvider(CRED_FILE);

    public ClearCredential() {
        AWS_ACCESS_ID = file.getCredentials().getAWSAccessKeyId();
        AWS_KEY = file.getCredentials().getAWSSecretKey();
    }

    public AWSCredentialsProvider getAwsCredentialProvider() {
        return new AWSCredentialsProvider() {
            public AWSCredentials getCredentials() {
                return new BasicAWSCredentials(AWS_ACCESS_ID, AWS_KEY);
            }

            public void refresh() {
                // NOP
            }
        };
    }
}
