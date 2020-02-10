package com.newegg.ec.redis.service.impl;

import com.google.common.base.Strings;
import com.newegg.ec.redis.client.RedisClient;
import com.newegg.ec.redis.client.RedisClientFactory;
import com.newegg.ec.redis.client.SentinelClient;
import com.newegg.ec.redis.dao.IClusterDao;
import com.newegg.ec.redis.entity.Cluster;
import com.newegg.ec.redis.entity.RedisNode;
import com.newegg.ec.redis.plugin.install.service.AbstractNodeOperation;
import com.newegg.ec.redis.plugin.install.service.impl.DockerNodeOperation;
import com.newegg.ec.redis.plugin.install.service.impl.HumpbackNodeOperation;
import com.newegg.ec.redis.plugin.install.service.impl.MachineNodeOperation;
import com.newegg.ec.redis.service.*;
import com.newegg.ec.redis.util.RedisClusterInfoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import redis.clients.jedis.HostAndPort;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.newegg.ec.redis.client.RedisClient.SERVER;
import static com.newegg.ec.redis.plugin.install.entity.InstallationEnvironment.*;
import static com.newegg.ec.redis.util.RedisNodeInfoUtil.*;
import static com.newegg.ec.redis.util.RedisUtil.*;

/**
 * @author Jay.H.Zou
 * @date 8/2/2019
 */
@Service
public class ClusterService implements IClusterService {

    private static final Logger logger = LoggerFactory.getLogger(ClusterService.class);

    @Autowired
    private IClusterDao clusterDao;

    @Autowired
    private IRedisService redisService;

    @Autowired
    private INodeInfoService nodeInfoService;

    @Autowired
    private IRedisNodeService redisNodeService;

    @Autowired
    private DockerNodeOperation dockerNodeOperation;

    @Autowired
    private MachineNodeOperation machineNodeOperation;

    @Autowired
    private HumpbackNodeOperation humpbackNodeOperation;

    @Autowired
    private ISentinelMastersService sentinelMastersService;

    @Override
    public List<Cluster> getAllClusterList() {
        try {
            return clusterDao.selectAllCluster();
        } catch (Exception e) {
            logger.error("Get all cluster failed.", e);
            return null;
        }
    }

    @Override
    public List<Cluster> getClusterListByGroupId(Integer groupId) {
        try {
            return clusterDao.selectClusterByGroupId(groupId);
        } catch (Exception e) {
            logger.error("Get cluster list by group id failed, group id = " + groupId, e);
            return null;
        }
    }

    @Override
    public Cluster getClusterById(Integer clusterId) {
        try {
            return clusterDao.selectClusterById(clusterId);
        } catch (Exception e) {
            logger.error("Get cluster by id failed, cluster id = " + clusterId, e);
            return null;
        }
    }

    @Override
    public Cluster getClusterByIdAndGroup(Integer groupId, Integer clusterId) {
        try {
            return clusterDao.getClusterByIdAndGroup(groupId, clusterId);
        } catch (Exception e) {
            logger.error("Get cluster failed, cluster id = " + clusterId, e);
            return null;
        }
    }

    @Override
    public Cluster getClusterByName(String clusterName) {
        try {
            return clusterDao.selectClusterByName(clusterName);
        } catch (Exception e) {
            logger.error("Get cluster by name failed, cluster name = " + clusterName, e);
            return new Cluster();
        }
    }


    /**
     * @param cluster
     * @return
     */
    @Transactional
    @Override
    public boolean addCluster(Cluster cluster) {
        boolean fillResult = fillCluster(cluster);
        if (!fillResult) {
            return false;
        }
        String redisMode = cluster.getRedisMode();
        if (Objects.equals(redisMode, STANDALONE) || Objects.equals(redisMode, SENTINEL)) {
            cluster.setClusterState(Cluster.ClusterState.HEALTH);
        }
        int row = clusterDao.insertCluster(cluster);
        if (row == 0) {
            throw new RuntimeException("Save cluster failed.");
        }
        nodeInfoService.createNodeInfoTable(cluster.getClusterId());
        if (Objects.equals(redisMode, SENTINEL)) {
            // TODO: get sentinel masters; save sentinel masters to database;
            sentinelMastersService.addSentinelMaster(cluster);
        }
        return true;
    }

    @Override
    public boolean updateCluster(Cluster cluster) {
        try {
            fillCluster(cluster);
            int row = clusterDao.updateCluster(cluster);
            return row > 0;
        } catch (Exception e) {
            logger.error("Update cluster failed.", e);
            return false;
        }
    }

    @Override
    public boolean updateClusterState(Cluster cluster) {
        try {
            int row = clusterDao.updateClusterState(cluster);
            return row > 0;
        } catch (Exception e) {
            logger.error("Update cluster state failed.", e);
            return false;
        }
    }

    @Override
    public boolean updateNodes(Cluster cluster) {
        Integer clusterId = cluster.getClusterId();
        try {
            clusterDao.updateNodes(clusterId, cluster.getNodes());
            return true;
        } catch (Exception e) {
            logger.error("Update cluster nodes failed, " + cluster, e);
            return false;
        }
    }

    @Override
    public boolean updateClusterRuleIds(Cluster cluster) {
        try {
            clusterDao.updateClusterRuleIds(cluster.getClusterId(), cluster.getRuleIds());
            return true;
        } catch (Exception e) {
            logger.error("Update cluster rule ids failed.", e);
            return false;
        }
    }

    @Override
    public boolean updateClusterChannelIds(Cluster cluster) {
        try {
            clusterDao.updateClusterChannelIds(cluster.getClusterId(), cluster.getChannelIds());
            return true;
        } catch (Exception e) {
            logger.error("Update cluster channel ids failed.", e);
            return false;
        }
    }

    /**
     * drop node_info_{clusterId}
     * delete redis_node in this cluster
     *
     * @param clusterId
     * @return
     */
    @Transactional
    @Override
    public boolean deleteCluster(Integer clusterId) {
        if (clusterId == null) {
            return false;
        }
        clusterDao.deleteClusterById(clusterId);
        nodeInfoService.deleteNodeInfoTable(clusterId);
        redisNodeService.deleteRedisNodeListByClusterId(clusterId);
        return true;
    }

    private boolean fillCluster(Cluster cluster) {
        fillBaseInfo(cluster);
        String redisMode = cluster.getRedisMode();
        if (Strings.isNullOrEmpty(redisMode)) {
            logger.error("Fill base info failed, " + cluster);
            return false;
        }
        fillTotalData(cluster);
        if (Objects.equals(redisMode, CLUSTER)) {
            if (!fillClusterInfo(cluster)) {
                logger.error("Fill cluster info failed, " + cluster);
                return false;
            }
        } else if (Objects.equals(redisMode, STANDALONE)) {
            fillStandaloneInfo(cluster);
        } else if (Objects.equals(redisMode, SENTINEL)) {
            fillSentinelInfo(cluster);
        }
        return true;
    }

    /**
     * @param cluster
     * @return
     */
    private boolean fillClusterInfo(Cluster cluster) {
        try {
            Map<String, String> clusterInfo = redisService.getClusterInfo(cluster);
            if (clusterInfo == null) {
                logger.warn("Cluster info is empty.");
                cluster.setClusterState(Cluster.ClusterState.BAD);
                return true;
            }
            Cluster clusterInfoObj = RedisClusterInfoUtil.parseClusterInfoToObject(clusterInfo);
            cluster.setClusterState(clusterInfoObj.getClusterState());
            cluster.setClusterSlotsAssigned(clusterInfoObj.getClusterSlotsAssigned());
            cluster.setClusterSlotsFail(clusterInfoObj.getClusterSlotsPfail());
            cluster.setClusterSlotsPfail(clusterInfoObj.getClusterSlotsPfail());
            cluster.setClusterSlotsOk(clusterInfoObj.getClusterSlotsOk());
            cluster.setClusterSize(clusterInfoObj.getClusterSize());
            cluster.setClusterKnownNodes(clusterInfoObj.getClusterKnownNodes());
            return true;
        } catch (Exception e) {
            logger.error("Fill cluster info failed, " + cluster, e);
            return false;
        }
    }

    private void fillStandaloneInfo(Cluster cluster) {
        List<RedisNode> redisNodeList = redisService.getRedisNodeList(cluster);
        cluster.setClusterSize(1);
        cluster.setClusterKnownNodes(redisNodeList.size());
    }

    /**
     * sentinel_ok
     * sentinel_masters
     * master_ok
     *
     * @param cluster
     */
    private void fillSentinelInfo(Cluster cluster) {
        try {
            List<RedisNode> redisNodeList = redisService.getRedisNodeList(cluster);
            cluster.setClusterKnownNodes(redisNodeList.size());

            cluster.setSentinelOk(redisNodeList.size());
            Set<HostAndPort> hostAndPorts = nodesToHostAndPortSet(cluster.getNodes());
            SentinelClient sentinelClient = RedisClientFactory.buildSentinelClient(hostAndPorts);
            Map<String, String> sentinelInfo = sentinelClient.getSentinelInfo();
            int sentinelMasters = Integer.parseInt(sentinelInfo.get(SENTINEL_MASTERS));
            cluster.setSentinelMasters(sentinelMasters);

            AtomicInteger masterOk = new AtomicInteger();
            sentinelInfo.forEach((key, val) -> {
                // master0:name=master,status=ok,address=10.16.50.219:16379,slaves=3,sentinels=6
                if (key.startsWith(MASTER_PREFIX) && val.contains("ok")) {
                    masterOk.getAndIncrement();
                }
            });
            cluster.setMasterOk(masterOk.get());
            cluster.setSentinelOk(getSentinelOkNumber(hostAndPorts));
        } catch (Exception e) {
            logger.error("Fill redis base info failed, " + cluster.getClusterName(), e);
        }
    }

    /**
     * 判断 sentinel node 是否 down 掉
     *
     * @param hostAndPorts
     * @return
     */
    private int getSentinelOkNumber(Set<HostAndPort> hostAndPorts) {
        AtomicInteger sentinelOk = new AtomicInteger();
        for (HostAndPort hostAndPort : hostAndPorts) {
            try {
                RedisClientFactory.buildRedisClient(hostAndPort, null);
                sentinelOk.incrementAndGet();
            } catch (Exception e) {
                logger.warn("Sentinel node is down, please check.", e);
            }
        }
        return sentinelOk.get();
    }

    private void fillBaseInfo(Cluster cluster) {
        try {
            String nodes = cluster.getNodes();
            RedisClient redisClient = RedisClientFactory.buildRedisClient(nodesToHostAndPort(nodes), cluster.getRedisPassword());
            Map<String, String> serverInfo = redisClient.getInfo(SERVER);
            // Server
            cluster.setOs(serverInfo.get(OS));
            cluster.setRedisMode(serverInfo.get(REDIS_MODE));
            cluster.setRedisVersion(serverInfo.get(REDIS_VERSION));
        } catch (Exception e) {
            logger.error("Fill redis base info failed, " + cluster.getClusterName(), e);
        }
    }

    private void fillTotalData(Cluster cluster) {
        Map<String, Map<String, Long>> keyspaceInfoMap = redisService.getKeyspaceInfo(cluster);
        cluster.setDbSize(keyspaceInfoMap.size());
        long totalKeys = 0;
        long totalExpires = 0;
        for (Map<String, Long> value : keyspaceInfoMap.values()) {
            totalKeys += value.get(KEYS);
            totalExpires += value.get(EXPIRES);
        }
        cluster.setTotalKeys(totalKeys);
        cluster.setTotalExpires(totalExpires);
        Map<String, Long> totalMemoryInfo = redisService.getTotalMemoryInfo(cluster);
        cluster.setTotalUsedMemory(totalMemoryInfo.get(USED_MEMORY));
    }

    @Override
    public AbstractNodeOperation getNodeOperation(Integer installationEnvironment) {
        switch (installationEnvironment) {
            case DOCKER:
                return dockerNodeOperation;
            case MACHINE:
                return machineNodeOperation;
            case HUMPBACK:
                return humpbackNodeOperation;
            default:
                return null;
        }
    }

}
