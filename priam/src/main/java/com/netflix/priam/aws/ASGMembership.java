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

import com.amazonaws.services.autoscaling.AmazonAutoScaling;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult;
import com.amazonaws.services.autoscaling.model.Instance;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.netflix.priam.config.IConfiguration;
import com.netflix.priam.cred.ICredential;
import com.netflix.priam.identity.config.InstanceInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class to query amazon ASG for its members to provide - Number of valid nodes in the ASG - Number
 * of zones - Methods for adding ACLs for the nodes
 */
public class ASGMembership extends AbstractAWSMembership {
    private static final Logger logger = LoggerFactory.getLogger(ASGMembership.class);
    private final ICredential thisAccountProvider;

    @Inject
    public ASGMembership(
            IConfiguration config,
            ICredential provider,
            @Named("awsec2roleassumption") ICredential crossAccountProvider,
            InstanceInfo instanceInfo) {
        super(config, provider, crossAccountProvider, instanceInfo);
        this.thisAccountProvider = provider;
    }

    @Override
    protected List<String> getLiveInstances(ICredential provider) {
        AmazonAutoScaling client = null;
        try {
            List<String> asgNames = new ArrayList<>();
            asgNames.add(instanceInfo.getAutoScalingGroup());
            asgNames.addAll(Arrays.asList(config.getSiblingASGNames().split("\\s*,\\s*")));
            client = getAutoScalingClient(provider);
            DescribeAutoScalingGroupsRequest asgReq =
                    new DescribeAutoScalingGroupsRequest()
                            .withAutoScalingGroupNames(
                                    asgNames.toArray(new String[asgNames.size()]));
            DescribeAutoScalingGroupsResult res = client.describeAutoScalingGroups(asgReq);

            List<String> instanceIds = Lists.newArrayList();
            for (AutoScalingGroup asg : res.getAutoScalingGroups()) {
                for (Instance ins : asg.getInstances())
                    if (isInstanceStateLive(ins.getLifecycleState()))
                        instanceIds.add(ins.getInstanceId());
            }
            if (logger.isInfoEnabled()) {
                logger.info(
                        String.format(
                                "Querying Amazon returned following instance in the RAC: %s, ASGs: %s --> %s",
                                instanceInfo.getRac(),
                                StringUtils.join(asgNames, ","),
                                StringUtils.join(instanceIds, ",")));
            }
            return instanceIds;
        } finally {
            if (client != null) client.shutdown();
        }
    }

    /** Actual membership AWS source of truth... */
    @Override
    public int getRacMembershipSize() {
        AmazonAutoScaling client = null;
        try {
            client = getAutoScalingClient(thisAccountProvider);
            DescribeAutoScalingGroupsRequest asgReq =
                    new DescribeAutoScalingGroupsRequest()
                            .withAutoScalingGroupNames(instanceInfo.getAutoScalingGroup());
            DescribeAutoScalingGroupsResult res = client.describeAutoScalingGroups(asgReq);
            int size = 0;
            for (AutoScalingGroup asg : res.getAutoScalingGroups()) {
                size += asg.getMaxSize();
            }
            logger.info("Query on ASG returning {} instances", size);
            return size;
        } finally {
            if (client != null) client.shutdown();
        }
    }

    private AmazonAutoScaling getAutoScalingClient(ICredential provider) {
        AmazonAutoScaling client = new AmazonAutoScalingClient(provider.getAwsCredentialProvider());
        client.setEndpoint("autoscaling." + instanceInfo.getRegion() + ".amazonaws.com");
        return client;
    }
}
