package com.gigaspaces.persistency.qa.stest;

import com.gigaspaces.client.ClearModifiers;
import com.gigaspaces.client.CountModifiers;
import com.gigaspaces.cluster.activeelection.SpaceMode;
import com.gigaspaces.persistency.qa.helper.GSAgentController;
import com.gigaspaces.persistency.qa.helper.MongoDBController;
import com.gigaspaces.persistency.qa.utils.IRepetitiveRunnable;
import com.j_spaces.core.IJSpace;
import com.j_spaces.core.admin.StatisticsAdmin;
import com.j_spaces.core.filters.ReplicationStatistics.ChannelState;
import com.j_spaces.core.filters.ReplicationStatistics.OutgoingChannel;
import com.j_spaces.core.filters.ReplicationStatistics.OutgoingReplication;
import org.apache.commons.io.FilenameUtils;
import org.junit.*;
import org.openspaces.admin.Admin;
import org.openspaces.admin.AdminFactory;
import org.openspaces.admin.pu.ProcessingUnit;
import org.openspaces.admin.pu.ProcessingUnitDeployment;
import org.openspaces.admin.space.SpaceInstance;
import org.openspaces.core.GigaSpace;

import java.io.File;
import java.util.concurrent.TimeUnit;


public abstract class AbstractSystemTestUnit {
    private final GSAgentController GS_AGENT_CONTROLLER = new GSAgentController();
    private final MongoDBController MONGO_DB_CONTROLLER = new MongoDBController();

    private static final String QA_GROUP = "qa_group";
    private final static String DEPLOY_DIR = "/mongodb-system-test-deploy";

    private Admin admin;

    protected GigaSpace gigaSpace;
    protected ProcessingUnit testPU;
    protected ProcessingUnit mirrorServicePU;

    @Before
    public void start() {
        startAgentAndMongo();

        drop();

        startWithoutDropDatabase();

    }

    protected void startWithoutDropDatabase() throws AssertionError {
        admin = new AdminFactory().addGroup(QA_GROUP)
                .createAdmin();
        admin.getGridServiceManagers().waitForAtLeastOne(60, TimeUnit.SECONDS);

        if (hasMirrorService())
            deployMirrorService();

        File puArchive = new File(getDeploymentJarPath(DEPLOY_DIR, getPUJar()));

        ProcessingUnitDeployment deployment = new ProcessingUnitDeployment(puArchive);

        try {

            testPU = admin.getGridServiceManagers().deploy(deployment);

            testPU.waitFor(4);

            gigaSpace = testPU.getSpace().getGigaSpace();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }


    @After
    public void afterTest() {
        stop(true);


    }

    public void stop(boolean stopAgentAndMongo) {
        if (mirrorServicePU != null)
            mirrorServicePU.undeployAndWait();

        if (testPU != null)
            testPU.undeployAndWait();

        if (stopAgentAndMongo) {
            stopAgentAndMongo();
        }
        if (admin != null)
            admin.close();
    }

    @Test
    public abstract void test();

    protected String getDeploymentJarPath(String dirName, String jarName) {
        String tmp = System.getProperty("java.io.tmpdir");

        return FilenameUtils.normalize(tmp + dirName + jarName);
    }

    protected boolean hasMirrorService() {
        return getMirrorService() != null;
    }

    protected String getMirrorService() {
        return "/mongodb-qa-mirror.jar";
    }

    protected abstract String getPUJar();

    private void deployMirrorService() {

        File mirrorPuArchive = new File(getDeploymentJarPath(DEPLOY_DIR,
                getMirrorService()));

        ProcessingUnitDeployment deployment = new ProcessingUnitDeployment(mirrorPuArchive);

        try {

            mirrorServicePU = admin.getGridServiceManagers().deploy(deployment);

            mirrorServicePU.waitFor(1);

        } catch (Exception ex) {
            throw new AssertionError(ex);
        }

    }

    protected void say(String message) {
        System.err.println(message);
    }

    protected void waitForActiveReplicationChannelWithMirror(final IJSpace space)
            throws Exception {
        repeat(new IRepetitiveRunnable() {
                   public void run() throws Exception {
                       boolean channelFound = false;

                       for (OutgoingChannel channel : getOutgoingReplication(space).getChannels()) {
                           if (!channel.getTargetMemberName()
                                   .contains("mirror-service")) {
                               continue;
                           }

                           Assert.assertEquals("No replication with mirror",
                                   ChannelState.ACTIVE,
                                   channel.getChannelState());
                           channelFound = true;
                       }

                       if (!channelFound) {
                           Assert.fail("no replication channel with mirror");
                       }
                   }
               },
                60 * 1000);

    }

    protected OutgoingReplication getOutgoingReplication(IJSpace space)
            throws Exception {
        return ((StatisticsAdmin) space.getAdmin()).getHolder()
                .getReplicationStatistics()
                .getOutgoingReplication();
    }

    protected void waitForEmptyReplicationBacklogAndClearMemory(
            GigaSpace gigaSpace) {
        waitForEmptyReplicationBacklog(gigaSpace);
        clearMemory(gigaSpace);
    }

    protected void clearMemory(final GigaSpace gigaSpace) {

        Assert.assertTrue("memory is not 0", repeat(new IRepetitiveRunnable() {

                                                        public void run() throws Exception {
                                                            gigaSpace.clear(null, ClearModifiers.EVICT_ONLY);

                                                            Assert.assertEquals("gigaSpace memory did not clear",
                                                                    0,
                                                                    gigaSpace.count(null,
                                                                            CountModifiers.MEMORY_ONLY_SEARCH));
                                                        }
                                                    },
                10 * 1000));

    }

    protected void waitForEmptyReplicationBacklog(final GigaSpace gigaSpace) {
        if (gigaSpace != null)
            waitForEmptyReplicationBacklog(gigaSpace.getSpace());
    }

    protected void waitForEmptyReplicationBacklog(IJSpace space) {
        Assert.assertTrue("replication backlog is not 0", repeat(new IRepetitiveRunnable() {

                                                                     public void run() throws Exception {
                                                                         long l = -1;

                                                                         l = ((StatisticsAdmin) gigaSpace.getSpace().getAdmin()).getHolder()
                                                                                 .getReplicationStatistics()
                                                                                 .getOutgoingReplication()
                                                                                 .getRedoLogSize();

                                                                         Assert.assertEquals("backlog not empty", 0, l);

                                                                     }
                                                                 },
                10 * 1000));
    }

    protected boolean repeat(IRepetitiveRunnable iRepetitiveRunnable,
                             long repeateInterval) {
        return repeat(iRepetitiveRunnable, repeateInterval, 4);
    }

    protected boolean repeat(IRepetitiveRunnable iRepetitiveRunnable,
                             long repeatInterval, int timesToRepeat) {
        int leftToRepeat = timesToRepeat;
        //boolean needToRepeat = true;
        while (true) {
            try {

                iRepetitiveRunnable.run();

                break;
            } catch (Throwable e) {
                try {
                    Thread.sleep(repeatInterval);
                    leftToRepeat--;
                    if (leftToRepeat == 0)
                        break;
                } catch (InterruptedException e1) {
                    leftToRepeat--;
                    if (leftToRepeat == 0)
                        break;
                }
            }
        }
        return leftToRepeat > 0;
    }

    /**
     * restart the gscs which contain pu.
     *
     * @param pu        - the gscs which contain pu will be restarted
     * @param clustered - if true assumes 2,1 else assumes 1,0
     */
    protected void restartPuGscs(ProcessingUnit pu, boolean clustered) {
        SpaceInstance[] instances = pu.getSpace().getInstances();
        for (SpaceInstance inst : instances) {
            if (inst.getMode().equals(SpaceMode.PRIMARY) || inst.getMode().equals(SpaceMode.BACKUP)) {
                if (inst.getVirtualMachine().getGridServiceContainer() != null)
                    inst.getVirtualMachine().getGridServiceContainer().restart();
            }
        }
        if (clustered) {
            pu.waitFor(2);
            pu.getSpace().waitFor(1, SpaceMode.PRIMARY);
            pu.getSpace().waitFor(1, SpaceMode.BACKUP);
        } else {
            pu.waitFor(1);
        }
    }


    public void startAgentAndMongo() {

        MONGO_DB_CONTROLLER.start(false);

        GS_AGENT_CONTROLLER.start();
    }


    public void stopAgentAndMongo() {
        GS_AGENT_CONTROLLER.stop();

        MONGO_DB_CONTROLLER.stop();
    }


    public void drop() {
        MONGO_DB_CONTROLLER.drop();

    }
}
