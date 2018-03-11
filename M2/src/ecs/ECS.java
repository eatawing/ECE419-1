package ecs;

import com.google.gson.Gson;
import common.messages.KVServerConfig;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import javax.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class ECS {
    private static Logger logger =  Logger.getRootLogger();
    private static final String SCRIPT_TEXT = "ssh -n %s nohup java -jar /Users/wuqili/Desktop/ECE419/M2/m2-server.jar %s %s %s %s %s %s &";

    private Gson gson;
    private ZooKeeperWatcher zkWatch;
    private TreeSet<IECSNode> serverRepo = new TreeSet<>();
    private TreeSet<IECSNode> serverRepoTaken = new TreeSet<>();
    private HashMap<IECSNode, Integer> serverRepoMapping = new HashMap<>();
    private String configFileName;

    // Zookeeper specific
    private static final int SESSION_TIMEOUT = 10000;
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final String CONNECTION_ADDR = "127.0.0.1:2181";
    private static final String CONNECTION_ADDR_HOST = "127.0.0.1";
    private static final String CONNECTION_ADDR_PORT = "2181";
    private static final String ROOT_PATH = "/ecs";
    private static final String NODE_PATH_SUFFIX = "/ecs/";
    /** if the service is made up of any servers **/
//    private boolean running = false;

    public ECS(String configFileName) {
        gson = new Gson();
        this.configFileName =  configFileName;
        // print heartbeat message
        Logger.getLogger("org.apache.zookeeper").setLevel(Level.ERROR);
    }

    public void initServerRepo() {
        File configFile = new File(configFileName);
        try {
            Scanner scanner = new Scanner(configFile);
            TreeSet<ECSNode> serverRepo = new TreeSet<>();
            String name, host, hashKey;
            int port;
            ECSNode node;
            while (scanner.hasNextLine()) {
                String[] tokens = scanner.nextLine().split(" ");
                name = tokens[0];
                host = tokens[1];
                port = Integer.parseInt(tokens[2]);
                hashKey = host + ":" + String.valueOf(port);
                MessageDigest md = MessageDigest.getInstance("MD5");
                md.update(hashKey.getBytes());
                byte[] digest = md.digest();
                String startingHash = DatatypeConverter.printHexBinary(digest).toUpperCase();
                node = new ECSNode(name, host, port, startingHash);
                serverRepo.add(node);
                this.serverRepoMapping.put(node, 1);
            }
            serverRepo.last().setEndingHashValue(serverRepo.first().getStartingHashValue());
            Iterator itr = serverRepo.iterator();
            ECSNode currentNode, nextNode;
            if (itr.hasNext()){
                currentNode = (ECSNode) itr.next();
                while (itr.hasNext()){
                    nextNode = (ECSNode) itr.next();
                    currentNode.setEndingHashValue(nextNode.getStartingHashValue());
                    currentNode = nextNode;
                }
            }
            this.serverRepo = (TreeSet<IECSNode>) serverRepo.clone();
            initZookeeper();
        } catch (FileNotFoundException e) {
            System.out.println("Error! Unable to open the file!");
            e.printStackTrace();
            System.exit(1);
        } catch (NoSuchAlgorithmException e) {
            logger.error(e.getMessage());
        }
    }

    private void initZookeeper() {
        zkWatch = new ZooKeeperWatcher();
        zkWatch.createConnection(CONNECTION_ADDR, SESSION_TIMEOUT);
        zkWatch.createPath(ROOT_PATH, "");
    }

    public TreeSet<IECSNode> arrangeECSNodes(int count, String cacheStrategy, int cacheSize) {
        TreeSet<IECSNode> serversTaken = new TreeSet<>();
        int availableNodes = 0;
        for (Integer i : serverRepoMapping.values()) {
            availableNodes += i;
        }
        if (availableNodes <= count) {
            return null;
        } else {
            int i = 0;
            for (IECSNode node : serverRepo) {
                if (serverRepoMapping.get(node) == 1) {
                    ((ECSNode) node).setCacheStrategy(cacheStrategy);
                    ((ECSNode) node).setCachesize(cacheSize);
                    serversTaken.add(node);
                    serverRepoMapping.put(node, 0);
                    serverRepoTaken.add(node);
                }
                if (++i == count) {
                    break;
                }
            }
        }
        return serversTaken;
    }

    public void executeScript(ECSNode node) {
        String script = String.format(SCRIPT_TEXT, LOCAL_HOST, node.getNodeName(),CONNECTION_ADDR_HOST,
                CONNECTION_ADDR_PORT, node.getNodePort(), node.getCacheStrategy(), node.getCachesize());
        Process proc;
        Runtime run = Runtime.getRuntime();
//        try {
//            logger.info("Running ... " + script);
//            proc = run.exec(script);
//        } catch (IOException e) {
//            logger.error("Failed to execute script!");
//        }
    }

    public boolean removeECSNodes(Collection<String> nodeNames) {
        boolean ifSuccess = true;
        int removedCount = 0;
        for (Iterator<String> iterator = nodeNames.iterator(); iterator.hasNext();) {
            for (IECSNode node: serverRepoTaken) {
                if (node.getNodeName().equals(iterator.next())){
                    serverRepoTaken.remove(node);
                    serverRepoMapping.put(node, 1);
                    removedCount++;
                }
            }
        }
        if (removedCount != nodeNames.size()) {
            ifSuccess = false;
        }
        return ifSuccess;
    }

    public void awaitNodes(int count, int timeout) {
        zkWatch.awaitNodes(count, timeout);
    }

    public void registerWatchEvent(TreeSet<IECSNode> serversTaken) {
        for(IECSNode node : serversTaken) {
            zkWatch.exists(NODE_PATH_SUFFIX + node.getNodeName(), true);
        }
    }

    public void start() {
        String json = new Gson().toJson(serverRepoTaken);
        for (IECSNode node : serverRepoTaken) {
            zkWatch.writeData(NODE_PATH_SUFFIX + node.getNodeName(), json);
        }
    }
}
