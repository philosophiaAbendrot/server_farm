package loadbalancerlab.loadbalancer;

public interface HashRing {
    int findServerId(String resourceName);

    void addAngle(int serverId, int numAngles);

    void removeAngle(int serverId, int numAngles);
}