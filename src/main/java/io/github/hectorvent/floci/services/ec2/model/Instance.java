package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Instance {

    private String instanceId;
    private String imageId;
    private volatile InstanceState state;
    private transient long stateTransition;
    private transient boolean initialSetup;
    private transient long initialSetupTransition;
    private final transient Object stateGuard = new Object();
    private final transient ReentrantLock lifecycleLock = new ReentrantLock();
    private boolean spot;
    private String stateTransitionReason;
    private String instanceType;
    private Placement placement;
    private String subnetId;
    private String vpcId;
    private String privateIpAddress;
    private String logicalPrivateIpAddress;
    private volatile String publicIpAddress;
    private transient long publicAddressRevision;
    private String privateDnsName;
    private volatile String publicDnsName;
    // Whether this instance's launch asked for an automatic public IP: the launch's
    // AssociatePublicIpAddress, else its subnet's MapPublicIpOnLaunch, and never for an
    // interface the caller created. AutomaticPublicAddress decides from it in every mode.
    private boolean associatePublicIp = false;
    private String keyName;
    private List<GroupIdentifier> securityGroups = new ArrayList<>();
    private List<InstanceNetworkInterface> networkInterfaces = new ArrayList<>();
    private String architecture;
    private String hypervisor = "xen";
    private String virtualizationType = "hvm";
    private String rootDeviceName = "/dev/xvda";
    private String rootDeviceType = "ebs";
    private Instant launchTime;
    private int amiLaunchIndex;
    private String clientToken;
    private String monitoring = "disabled";
    private boolean sourceDestCheck = true;
    private boolean ebsOptimized = false;
    private boolean enaSupport = true;
    private String iamInstanceProfileArn;
    private String iamInstanceProfileId;
    private Instant iamInstanceProfileAssociationTime;
    private String stateReasonCode;
    private String stateReasonMessage;
    private String region;
    private String ownerId;
    private List<Tag> tags = new ArrayList<>();

    private String rootVolumeId;

    // NOTE: disableApiStop and disableApiTermination are stored but ModifyInstanceAttribute
    // does not yet wire them through. Terraform reads these via DescribeInstanceAttribute;
    // without model backing, a modify → plan cycle would show drift. Tracked as a known limitation.
    private boolean disableApiStop = false;
    private boolean disableApiTermination = false;

    // Null for an instance persisted before metadata options were stored; reads fall back to
    // AWS's launch defaults through effectiveMetadataOptions(). Volatile because IMDS reads it on
    // its event loop while ModifyInstanceMetadataOptions replaces it from a request thread.
    private volatile LaunchTemplateData.MetadataOptions metadataOptions;

    // The credit option this instance acquired, at launch from the request or from its burstable
    // family's default, or at a resize onto a burstable type. Stored rather than derived from the
    // current instance type on read, because AWS keeps reporting the unlimited option of an
    // instance that was configured as a T2, T3 or T3a and then resized onto another family. Null
    // for an instance that never acquired one. CreditSpecification is not a member of the Instance
    // shape DescribeInstances returns, so this reaches the wire only through
    // DescribeInstanceCreditSpecifications.
    private String creditSpecificationCpuCredits;

    // Docker backing fields (not serialised to AWS wire format)
    private String dockerContainerId;
    private String containerBridgeIp;
    /**
     * The container's default-bridge address, kept alongside {@code containerBridgeIp} when the
     * instance is also attached to its VPC's Docker network. IMDS identifies a caller by the
     * source address of its request, and that is whichever interface carries the container's
     * default route, the bridge, while the address Floci reports is the VPC one. Both are
     * registered so metadata answers either way.
     */
    private String imdsSourceIp;
    private String userData;
    private Boolean userDataAttempted;
    private String encodedUserData;
    private int sshHostPort;
    private long terminatedAt;

    // Security-group ingress ports published on the host via socat sidecars:
    // container app port -> allocated host port. Not part of the AWS wire format.
    private Map<Integer, Integer> publishedPorts = new LinkedHashMap<>();

    public Instance() {}

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }

    public InstanceState getState() { return state; }
    public void setState(InstanceState state) {
        synchronized (stateGuard) {
            stateTransition++;
            this.state = state;
            stateGuard.notifyAll();
        }
    }

    /** Identifies an admitted operation without persisting executor state across a restart. */
    public long stateTransition() { synchronized (stateGuard) { return stateTransition; } }

    /** Bounded lifecycle publication; never held over Docker, forwarding or reachability probes. */
    public Object stateGuard() { return stateGuard; }

    public ReentrantLock lifecycleLock() { return lifecycleLock; }

    /** Own initial setup before the new pending record becomes visible to StopInstances. */
    public long beginInitialSetup() {
        synchronized (stateGuard) {
            if (!initialSetupPending() && state != null && "pending".equals(state.getName())) {
                initialSetup = true;
                initialSetupTransition = stateTransition;
                if (userDataAttempted == null) {
                    userDataAttempted = false;
                }
            }
            return stateTransition;
        }
    }

    public boolean initialSetupPending() {
        synchronized (stateGuard) {
            return initialSetup && initialSetupTransition == stateTransition;
        }
    }

    /** Record Stop without abandoning the first guest's network, metadata and SSH setup. */
    public boolean stopAfterInitialSetup() {
        synchronized (stateGuard) {
            if (!initialSetupPending()) {
                return false;
            }
            state = InstanceState.stopping();
            return true;
        }
    }

    public boolean completeInitialSetup(long expected) {
        synchronized (stateGuard) {
            if (!initialSetup || initialSetupTransition != expected) {
                return false;
            }
            // Stop either records its intent before this handoff, or sees completed setup and
            // schedules its normal physical operation. No request can fall between the two.
            initialSetup = false;
            stateGuard.notifyAll();
            return stateTransition == expected && state != null && "stopping".equals(state.getName());
        }
    }

    public void awaitInitialSetup() throws InterruptedException {
        synchronized (stateGuard) {
            while (initialSetupPending()) {
                stateGuard.wait();
            }
        }
    }

    /** Wait only for the caller's monotonic budget, retaining ownership on timeout. */
    public boolean awaitInitialSetup(long deadlineNanos) throws InterruptedException {
        synchronized (stateGuard) {
            while (initialSetupPending()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(stateGuard, remaining);
            }
            return true;
        }
    }

    /** A late worker cannot complete a different start, stop or termination. */
    public boolean completeStateTransition(long expected, InstanceState state) {
        synchronized (stateGuard) {
            if (stateTransition != expected) {
                return false;
            }
            this.state = state;
            return true;
        }
    }

    public boolean isSpot() { return spot; }
    public void setSpot(boolean spot) { this.spot = spot; }

    public String getStateTransitionReason() { return stateTransitionReason; }
    public void setStateTransitionReason(String stateTransitionReason) { this.stateTransitionReason = stateTransitionReason; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }

    public Placement getPlacement() { return placement; }
    public void setPlacement(Placement placement) { this.placement = placement; }

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getPrivateIpAddress() { return privateIpAddress; }
    public void setPrivateIpAddress(String privateIpAddress) { this.privateIpAddress = privateIpAddress; }
    public String getLogicalPrivateIpAddress() { return logicalPrivateIpAddress; }
    public void setLogicalPrivateIpAddress(String logicalPrivateIpAddress) {
        this.logicalPrivateIpAddress = logicalPrivateIpAddress;
    }

    public String getPublicIpAddress() { return publicIpAddress; }
    public void setPublicIpAddress(String publicIpAddress) {
        synchronized (stateGuard) {
            this.publicIpAddress = publicIpAddress;
            publicAddressRevision++;
        }
    }
    public long publicAddressRevision() { synchronized (stateGuard) { return publicAddressRevision; } }

    public String getPrivateDnsName() { return privateDnsName; }
    public void setPrivateDnsName(String privateDnsName) { this.privateDnsName = privateDnsName; }

    public String getPublicDnsName() { return publicDnsName; }
    public void setPublicDnsName(String publicDnsName) {
        synchronized (stateGuard) {
            this.publicDnsName = publicDnsName;
            publicAddressRevision++;
        }
    }
    public boolean isAssociatePublicIp() { return associatePublicIp; }
    public void setAssociatePublicIp(boolean associatePublicIp) { this.associatePublicIp = associatePublicIp; }

    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }

    public List<GroupIdentifier> getSecurityGroups() { return securityGroups; }
    public void setSecurityGroups(List<GroupIdentifier> securityGroups) { this.securityGroups = securityGroups; }

    public List<InstanceNetworkInterface> getNetworkInterfaces() { return networkInterfaces; }
    public void setNetworkInterfaces(List<InstanceNetworkInterface> networkInterfaces) { this.networkInterfaces = networkInterfaces; }

    public String getArchitecture() { return architecture; }
    public void setArchitecture(String architecture) { this.architecture = architecture; }

    public String getHypervisor() { return hypervisor; }
    public void setHypervisor(String hypervisor) { this.hypervisor = hypervisor; }

    public String getVirtualizationType() { return virtualizationType; }
    public void setVirtualizationType(String virtualizationType) { this.virtualizationType = virtualizationType; }

    public String getRootDeviceName() { return rootDeviceName; }
    public void setRootDeviceName(String rootDeviceName) { this.rootDeviceName = rootDeviceName; }

    public String getRootDeviceType() { return rootDeviceType; }
    public void setRootDeviceType(String rootDeviceType) { this.rootDeviceType = rootDeviceType; }

    public Instant getLaunchTime() { return launchTime; }
    public void setLaunchTime(Instant launchTime) { this.launchTime = launchTime; }

    public int getAmiLaunchIndex() { return amiLaunchIndex; }
    public void setAmiLaunchIndex(int amiLaunchIndex) { this.amiLaunchIndex = amiLaunchIndex; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }

    public String getMonitoring() { return monitoring; }
    public void setMonitoring(String monitoring) { this.monitoring = monitoring; }

    public boolean isSourceDestCheck() { return sourceDestCheck; }
    public void setSourceDestCheck(boolean sourceDestCheck) { this.sourceDestCheck = sourceDestCheck; }

    public boolean isEbsOptimized() { return ebsOptimized; }
    public void setEbsOptimized(boolean ebsOptimized) { this.ebsOptimized = ebsOptimized; }

    public boolean isEnaSupport() { return enaSupport; }
    public void setEnaSupport(boolean enaSupport) { this.enaSupport = enaSupport; }

    public String getIamInstanceProfileArn() { return iamInstanceProfileArn; }
    public void setIamInstanceProfileArn(String iamInstanceProfileArn) { this.iamInstanceProfileArn = iamInstanceProfileArn; }
    public String getIamInstanceProfileId() { return iamInstanceProfileId; }
    public void setIamInstanceProfileId(String iamInstanceProfileId) { this.iamInstanceProfileId = iamInstanceProfileId; }
    public Instant getIamInstanceProfileAssociationTime() { return iamInstanceProfileAssociationTime; }
    public void setIamInstanceProfileAssociationTime(Instant iamInstanceProfileAssociationTime) { this.iamInstanceProfileAssociationTime = iamInstanceProfileAssociationTime; }

    public String getStateReasonCode() { return stateReasonCode; }
    public void setStateReasonCode(String stateReasonCode) { this.stateReasonCode = stateReasonCode; }

    public String getStateReasonMessage() { return stateReasonMessage; }
    public void setStateReasonMessage(String stateReasonMessage) { this.stateReasonMessage = stateReasonMessage; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }

    public String getDockerContainerId() { return dockerContainerId; }
    public void setDockerContainerId(String dockerContainerId) { this.dockerContainerId = dockerContainerId; }

    public String getEncodedUserData() { return encodedUserData; }
    public void setEncodedUserData(String encodedUserData) { this.encodedUserData = encodedUserData; }
    public Boolean getUserDataAttempted() { return userDataAttempted; }
    public void setUserDataAttempted(Boolean attempted) { userDataAttempted = attempted; }

    /** Legacy rows have no dispatch marker and must not replay a historical script. */
    public boolean claimInitialUserData() {
        synchronized (stateGuard) {
            if (!Boolean.FALSE.equals(userDataAttempted)) {
                return false;
            }
            userDataAttempted = true;
            return true;
        }
    }

    public String getUserData() { return userData; }
    public void setUserData(String userData) { this.userData = userData; }

    public int getSshHostPort() { return sshHostPort; }
    public void setSshHostPort(int sshHostPort) { this.sshHostPort = sshHostPort; }

    public long getTerminatedAt() { return terminatedAt; }
    public void setTerminatedAt(long terminatedAt) { this.terminatedAt = terminatedAt; }

    public String getContainerBridgeIp() { return containerBridgeIp; }
    public void setContainerBridgeIp(String containerBridgeIp) { this.containerBridgeIp = containerBridgeIp; }

    public String getImdsSourceIp() { return imdsSourceIp; }
    public void setImdsSourceIp(String imdsSourceIp) { this.imdsSourceIp = imdsSourceIp; }

    public Map<Integer, Integer> getPublishedPorts() {
        if (publishedPorts == null) {
            publishedPorts = new LinkedHashMap<>();
        }
        return publishedPorts;
    }
    public void setPublishedPorts(Map<Integer, Integer> publishedPorts) { this.publishedPorts = publishedPorts; }

    public String getRootVolumeId() { return rootVolumeId; }
    public void setRootVolumeId(String rootVolumeId) { this.rootVolumeId = rootVolumeId; }

    public boolean isDisableApiStop() { return disableApiStop; }
    public void setDisableApiStop(boolean disableApiStop) { this.disableApiStop = disableApiStop; }

    public boolean isDisableApiTermination() { return disableApiTermination; }
    public void setDisableApiTermination(boolean disableApiTermination) { this.disableApiTermination = disableApiTermination; }

    public LaunchTemplateData.MetadataOptions getMetadataOptions() { return metadataOptions; }
    public void setMetadataOptions(LaunchTemplateData.MetadataOptions metadataOptions) { this.metadataOptions = metadataOptions; }

    public String getCreditSpecificationCpuCredits() { return creditSpecificationCpuCredits; }
    public void setCreditSpecificationCpuCredits(String creditSpecificationCpuCredits) { this.creditSpecificationCpuCredits = creditSpecificationCpuCredits; }

    /** The stored metadata options, or AWS's launch defaults for a record that has none. */
    public LaunchTemplateData.MetadataOptions effectiveMetadataOptions() {
        return metadataOptions != null ? metadataOptions : LaunchTemplateData.MetadataOptions.launchDefaults();
    }
}
