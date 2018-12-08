/*
 * Copyright 2013 Netflix, Inc.
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
package com.netflix.priam.aws;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.netflix.priam.config.IConfiguration;
import com.netflix.priam.cred.ICredential;
import com.netflix.priam.identity.config.InstanceInfo;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Class to query amazon for running ec2 instances that could be members of the cluster */
public class EC2InstanceMembership extends AbstractAWSMembership {
    private static final Logger logger = LoggerFactory.getLogger(EC2InstanceMembership.class);

    @Inject
    public EC2InstanceMembership(
            IConfiguration config,
            ICredential provider,
            @Named("awsec2roleassumption") ICredential crossAccountProvider,
            InstanceInfo insEnvIdentity) {
        super(config, provider, crossAccountProvider, insEnvIdentity);
    }

    @Override
    /**
     * Method generates the list of all the live instances of this region TODO (Hussain): Should we
     * change this to get only the list of cassandra instances to be added ?
     */
    protected List<String> getLiveInstances(ICredential provider) {
        AmazonEC2 client = null;
        try {
            client = getEc2Client();
            List<String> instanceIds = Lists.newArrayList();
            // NOTE: Don't get confuse with this nextToken variable name with the cassandra token
            // concept,
            // they are not related to each other
            String nextToken = null;

            // Do-while is used so that we will have "res" object to start with
            do {
                DescribeInstancesResult res;
                if (nextToken != null) {
                    res =
                            client.describeInstances(
                                    new DescribeInstancesRequest().withNextToken(nextToken));
                } else {
                    res = client.describeInstances();
                }

                nextToken = res.getNextToken();

                for (Reservation reservation : res.getReservations()) {
                    for (Instance instance : reservation.getInstances())
                        if (isInstanceStateLive(instance.getState().getName()))
                            instanceIds.add(instance.getInstanceId());
                }
            } while (nextToken != null);

            if (logger.isInfoEnabled()) {
                logger.info(
                        String.format(
                                "Querying Amazon returned following instance in the RAC: %s, %s",
                                instanceInfo.getRac(), StringUtils.join(instanceIds, ",")));
            }

            return instanceIds;
        } finally {
            if (client != null) client.shutdown();
        }
    }

    @Override
    public int getRacMembershipSize() {
        throw new RuntimeException(
                "Cannot get rac membership size when running outside of autoscaling group");
    }
}
