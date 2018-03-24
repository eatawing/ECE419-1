package ecs;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.log4j.Logger;

import java.util.Collection;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


public class ECSWatcher {

    /**
     * zookeeper
     */
    private ZooKeeper zk = null;

    private static final String ROOT_PATH = "/ecs";

    private static final int SESSION_TIMEOUT = 5000;
    /**
     * zk children path
     */
    private static final String CHILDREN_PATH = "/ecs/";
    /**
     * signal to complete zookeeper creation
     */
    private CountDownLatch connectedSemaphore = new CountDownLatch(1);
    ;

    /**
     * signal to complete zookeeper creation
     */
    private CountDownLatch awaitSemaphore;
    /**
     * logger
     */
    private static Logger logger = Logger.getRootLogger();


    /**
     * root watcher
     */
    private Watcher rootWatcher = null;

    private Watcher childrenWatcher = null;


    public void init(String zkHostname, int zkPort) {
        rootWatcher = new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if (event == null) return;


                KeeperState keeperState = event.getState();

                EventType eventType = event.getType();

                logger.info("ROOT watcher triggered " + event.toString());

                if (keeperState == KeeperState.SyncConnected) {
                    switch (eventType) {
                        case None:
                            connectedSemaphore.countDown();
                            logger.info("Successfully connected to zookeeper server");
                            exists(ROOT_PATH, this);
                            break;

                        case NodeChildrenChanged:
                            try {
                                zk.getChildren(ROOT_PATH, this);
                            } catch (Exception e) {
                                logger.error("cannot watcher children");
                            }

                            logger.info("Children Node Changed");
                            awaitSemaphore.countDown();
                        default:
                            logger.info("Change is not related");
                            break;
                    }

                } else {
                    logger.warn("Failed to connect with zookeeper server -> root node");
                }
            }
        };

        childrenWatcher = new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if (event == null) return;


                KeeperState keeperState = event.getState();

                EventType eventType = event.getType();

                logger.info("Children watcher triggered " + event.toString());

                if (keeperState == KeeperState.SyncConnected) {
                    switch (eventType) {

                        case NodeDataChanged:
                            logger.info("Children Node Changed");
                            awaitSemaphore.countDown();

                            break;

                        default:
                            logger.info("Change is not related");
                            break;
                    }

                } else {
                    logger.warn("Failed to connect with zookeeper server -> children node");
                }
            }
        };
        try {
            zk = new ZooKeeper(zkHostname + ":" + zkPort, SESSION_TIMEOUT, rootWatcher);

            connectedSemaphore.await();

            createPath(ROOT_PATH, "", rootWatcher);

        } catch (Exception e) {
            logger.error("Failed to process KVServer Watcher " + e);
        }
    }


    /**
     * Create node with path
     */

    public void createPath(String path, String data, Watcher watcher) {
        try {
            this.zk.create(path, data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            logger.info("Successfully create new node " + path);
        } catch (Exception e) {
            logger.error("Failed to create new node " + path);
        }
    }


    /**
     * update node
     */
    public boolean writeData(String path, String data) {
        try {
            this.zk.setData(path, data.getBytes(), -1);
            logger.info("Successfully update Node at " + path);
        } catch (Exception e) {
            logger.error("Failed to update Node at " + path);
            logger.error(e);
            return false;
        }
        return true;
    }

    /**
     * delete node
     */
    public boolean deleteNode(String path) {
        try {

            if (zk.exists(path, false) != null) {
                this.zk.delete(path, -1);
            }

            return true;

        } catch (Exception e) {
            logger.error("Failed to delete Node at path " + path);
            return false;
        }
    }

    /**
     * Check if the path exists
     */
    public Stat exists(String path, Watcher watch) {
        try {
            return this.zk.exists(path, watch);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }


    public void setSemaphore(int count, Collection<String> list) {
        try {

            zk.getChildren(ROOT_PATH, rootWatcher);

            if (list != null)
                for (String node : list) {
                    exists(CHILDREN_PATH + node, childrenWatcher);
                }

            awaitSemaphore = new CountDownLatch(count);
        } catch (Exception e) {
            logger.error("Cannot watch children");
        }
    }

    public boolean awaitNodes(int timeout) {

        boolean ifNotTimeout = true;

        try {
            ifNotTimeout = awaitSemaphore.await(timeout, TimeUnit.MILLISECONDS);
            logger.info("Finish waiting nodes status is" + ifNotTimeout);
        } catch (InterruptedException e) {
            logger.error("Await Nodes has been interrupted!");
        }
        return ifNotTimeout;
    }


    public boolean deleteAllNodes(TreeSet<IECSNode> serverRepoTaken) {

        logger.info("Deleting all nodes");

        try {
            if(this.zk.exists(ROOT_PATH, false) == null)
                return true;

            for (IECSNode node : serverRepoTaken) {
                deleteNode(CHILDREN_PATH + node.getNodeName() + "/data");
                deleteNode(CHILDREN_PATH + node.getNodeName());
            }

            deleteNode(ROOT_PATH);

            logger.info("Done");

            return true;
        } catch (Exception e) {
            logger.error("Cannot Do ZK operation");
            return false;
        }


    }
}
