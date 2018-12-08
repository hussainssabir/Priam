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

import com.amazonaws.services.autoscaling.model.*;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.ec2.model.Filter;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.netflix.priam.config.IConfiguration;
import com.netflix.priam.cred.ICredential;
import com.netflix.priam.identity.IMembership;
import com.netflix.priam.identity.PriamInstance;
import com.netflix.priam.identity.config.InstanceInfo;
import java.util.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract class represent the generic functions required by both ASG and non-ASG based instances -
 * Number of valid nodes in the cluster - Number of zones - Methods for adding ACLs for the nodes
 * TODO: How ACL is configured?
 */
public abstract class AbstractAWSMembership implements IMembership {
    private static final Logger logger = LoggerFactory.getLogger(AbstractAWSMembership.class);
    protected final IConfiguration config;
    protected final ICredential thisAccountProvider;
    protected final InstanceInfo instanceInfo;
    private final ICredential crossAccountProvider;

    @Inject
    public AbstractAWSMembership(
            IConfiguration config,
            ICredential thisAccountProvider,
            @Named("awsec2roleassumption") ICredential crossAccountProvider,
            InstanceInfo instanceInfo) {
        this.config = config;
        this.thisAccountProvider = thisAccountProvider;
        this.instanceInfo = instanceInfo;
        this.crossAccountProvider = crossAccountProvider;
    }

    protected abstract List<String> getLiveInstances(ICredential provider);

    protected boolean isInstanceStateLive(String lifecycleState) {
        return !(lifecycleState.equalsIgnoreCase("Terminating")
                || lifecycleState.equalsIgnoreCase("shutting-down")
                || lifecycleState.equalsIgnoreCase("Terminated"));
    }

    @Override
    public boolean isInstanceAlive(PriamInstance instance) {
        List<String> instances = getLiveInstances(thisAccountProvider);
        if (config.isDualAccount()) {
            instances = getDualAccountLiveInstances(instances);
        } else {
            logger.info("Single Account cluster");
        }

        return instances.contains(instance.getInstanceId());
    }

    private List<String> getDualAccountLiveInstances(List<String> instances) {
        logger.info("Dual Account cluster");

        List<String> crossAccountInstances = getLiveInstances(crossAccountProvider);

        if (logger.isInfoEnabled()) {
            if (isClassic()) {
                logger.info(
                        "EC2 classic instances (local account): "
                                + Arrays.toString(instances.toArray()));
                logger.info(
                        "VPC Account (cross-account): "
                                + Arrays.toString(crossAccountInstances.toArray()));
            } else {
                logger.info("VPC Account (local account): " + Arrays.toString(instances.toArray()));
                logger.info(
                        "EC2 classic instances (cross-account): "
                                + Arrays.toString(crossAccountInstances.toArray()));
            }
        }

        // Remove duplicates (probably there are not)
        instances.removeAll(crossAccountInstances);

        // Merge the two lists
        instances.addAll(crossAccountInstances);
        logger.info("Combined Instances in the AZ: {}", instances);

        return instances;
    }

    @Override
    public int getRacCount() {
        return config.getRacs().size();
    }

    private boolean isClassic() {
        return instanceInfo.getInstanceEnvironment() == InstanceInfo.InstanceEnvironment.CLASSIC;
    }

    /**
     * Adding peers' IPs as ingress to the running instance SG. The running instance could be in
     * "classic" or "vpc"
     */
    public void addACL(Collection<String> listIPs, int from, int to) {
        AmazonEC2 client = null;
        try {
            client = getEc2Client();
            List<IpPermission> ipPermissions = new ArrayList<>();
            ipPermissions.add(
                    new IpPermission()
                            .withFromPort(from)
                            .withIpProtocol("tcp")
                            .withIpRanges(listIPs)
                            .withToPort(to));

            if (isClassic()) {
                client.authorizeSecurityGroupIngress(
                        new AuthorizeSecurityGroupIngressRequest(
                                config.getACLGroupName(), ipPermissions));
                if (logger.isInfoEnabled()) {
                    logger.info("Done adding ACL to classic: " + StringUtils.join(listIPs, ","));
                }
            } else {
                AuthorizeSecurityGroupIngressRequest sgIngressRequest =
                        new AuthorizeSecurityGroupIngressRequest();
                sgIngressRequest.withGroupId(getVpcGoupId());
                // fetch SG group id for vpc account of the running instance.
                client.authorizeSecurityGroupIngress(
                        sgIngressRequest.withIpPermissions(
                                ipPermissions)); // Adding peers' IPs as ingress to the running
                // instance SG
                if (logger.isInfoEnabled()) {
                    logger.info("Done adding ACL to vpc: " + StringUtils.join(listIPs, ","));
                }
            }

        } finally {
            if (client != null) client.shutdown();
        }
    }

    /*
     * @return SG group id for a group name, vpc account of the running instance.
     */
    protected String getVpcGoupId() {
        AmazonEC2 client = null;
        try {
            client = getEc2Client();
            Filter nameFilter =
                    new Filter().withName("group-name").withValues(config.getACLGroupName()); // SG
            Filter vpcFilter = new Filter().withName("vpc-id").withValues(instanceInfo.getVpcId());

            DescribeSecurityGroupsRequest req =
                    new DescribeSecurityGroupsRequest().withFilters(nameFilter, vpcFilter);
            DescribeSecurityGroupsResult result = client.describeSecurityGroups(req);
            for (SecurityGroup group : result.getSecurityGroups()) {
                logger.debug(
                        "got group-id:{} for group-name:{},vpc-id:{}",
                        group.getGroupId(),
                        config.getACLGroupName(),
                        instanceInfo.getVpcId());
                return group.getGroupId();
            }
            logger.error(
                    "unable to get group-id for group-name={} vpc-id={}",
                    config.getACLGroupName(),
                    instanceInfo.getVpcId());
            return "";
        } finally {
            if (client != null) client.shutdown();
        }
    }

    /** removes a iplist from the SG */
    public void removeACL(Collection<String> listIPs, int from, int to) {
        AmazonEC2 client = null;
        try {
            client = getEc2Client();
            List<IpPermission> ipPermissions = new ArrayList<>();
            ipPermissions.add(
                    new IpPermission()
                            .withFromPort(from)
                            .withIpProtocol("tcp")
                            .withIpRanges(listIPs)
                            .withToPort(to));

            if (isClassic()) {
                client.revokeSecurityGroupIngress(
                        new RevokeSecurityGroupIngressRequest(
                                config.getACLGroupName(), ipPermissions));
                if (logger.isInfoEnabled()) {
                    logger.info(
                            "Done removing from ACL within classic env for running instance: "
                                    + StringUtils.join(listIPs, ","));
                }
            } else {
                RevokeSecurityGroupIngressRequest req = new RevokeSecurityGroupIngressRequest();
                // fetch SG group id for vpc account of the running instance.
                req.withGroupId(getVpcGoupId());
                // Adding peers' IPs as ingress to the running instance SG
                client.revokeSecurityGroupIngress(req.withIpPermissions(ipPermissions));
                if (logger.isInfoEnabled()) {
                    logger.info(
                            "Done removing from ACL within vpc env for running instance: "
                                    + StringUtils.join(listIPs, ","));
                }
            }

        } finally {
            if (client != null) client.shutdown();
        }
    }

    /** List SG ACL's */
    public List<String> listACL(int from, int to) {
        AmazonEC2 client = null;
        try {
            client = getEc2Client();
            List<String> ipPermissions = new ArrayList<>();

            if (isClassic()) {

                DescribeSecurityGroupsRequest req =
                        new DescribeSecurityGroupsRequest()
                                .withGroupNames(
                                        Collections.singletonList(config.getACLGroupName()));
                DescribeSecurityGroupsResult result = client.describeSecurityGroups(req);
                for (SecurityGroup group : result.getSecurityGroups())
                    for (IpPermission perm : group.getIpPermissions())
                        if (perm.getFromPort() == from && perm.getToPort() == to)
                            ipPermissions.addAll(perm.getIpRanges());

                logger.debug("Fetch current permissions for classic env of running instance");
            } else {

                Filter nameFilter =
                        new Filter().withName("group-name").withValues(config.getACLGroupName());
                String vpcid = instanceInfo.getVpcId();
                if (vpcid == null || vpcid.isEmpty()) {
                    throw new IllegalStateException(
                            "vpcid is null even though instance is running in vpc.");
                }

                // only fetch SG for the vpc id of the running instance
                Filter vpcFilter = new Filter().withName("vpc-id").withValues(vpcid);
                DescribeSecurityGroupsRequest req =
                        new DescribeSecurityGroupsRequest().withFilters(nameFilter, vpcFilter);
                DescribeSecurityGroupsResult result = client.describeSecurityGroups(req);
                for (SecurityGroup group : result.getSecurityGroups())
                    for (IpPermission perm : group.getIpPermissions())
                        if (perm.getFromPort() == from && perm.getToPort() == to)
                            ipPermissions.addAll(perm.getIpRanges());

                logger.debug("Fetch current permissions for vpc env of running instance");
            }

            return ipPermissions;
        } finally {
            if (client != null) client.shutdown();
        }
    }

    protected AmazonEC2 getEc2Client() {
        return AmazonEC2ClientBuilder.standard()
                .withCredentials(thisAccountProvider.getAwsCredentialProvider())
                .withRegion(instanceInfo.getRegion())
                .build();
    }
}
