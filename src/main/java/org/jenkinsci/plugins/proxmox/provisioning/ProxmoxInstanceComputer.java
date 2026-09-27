package org.jenkinsci.plugins.proxmox.provisioning;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.slaves.AbstractCloudComputer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Computer of a {@link ProxmoxInstanceAgent}. Records the build it runs in the clone's notes.
 */
public class ProxmoxInstanceComputer extends AbstractCloudComputer<ProxmoxInstanceAgent> {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxInstanceComputer.class.getName());

    public ProxmoxInstanceComputer(ProxmoxInstanceAgent agent) {
        super(agent);
    }

    @Override
    public void taskStarted(Executor executor, Queue.Task task) {
        super.taskStarted(executor, task);
        ProxmoxInstanceAgent agent = getNode();
        if (agent == null || agent.getBuildUrl() != null) {
            return;
        }
        // Jenkins notifies before the executor has created the build, so wait for it without blocking the executor.
        threadPoolForRemoting.submit(() -> {
            for (int i = 0; i < 120; i++) {
                Run<?, ?> run = runOf(executor.getCurrentExecutable());
                if (run != null) {
                    agent.recordBuild(run);
                    return;
                }
                if (executor.getCurrentWorkUnit() == null) {
                    break;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            LOGGER.log(Level.WARNING, "Could not find the build started on {0}", agent.getNodeName());
        });
    }

    /** Finds the build behind an executable, e.g. the pipeline run behind a {@code node} block. */
    @CheckForNull
    static Run<?, ?> runOf(@CheckForNull Queue.Executable executable) {
        Queue.Executable e = executable;
        while (e != null) {
            if (e instanceof Run) {
                return (Run<?, ?>) e;
            }
            e = e.getParentExecutable();
        }
        return null;
    }
}
