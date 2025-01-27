/*
 * The MIT License
 *
 * Copyright (c) 2013, Brendan Nolan
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.bstick12.jenkinsci.plugins.leastload;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.init.Initializer;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.LoadBalancer;
import hudson.model.Queue.Task;
import hudson.model.queue.MappingWorksheet;
import hudson.model.queue.MappingWorksheet.ExecutorChunk;
import hudson.model.queue.MappingWorksheet.Mapping;
import hudson.model.queue.SubTask;

import java.io.Serializable;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import hudson.util.ConsistentHash;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import org.apache.tools.ant.taskdefs.Exec;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;

/**
 * A {@link LoadBalancer} implementation that the leastload plugin uses to replace the default
 * Jenkins {@link LoadBalancer}
 * <p>The {@link LeastLoadBalancer} chooses {@link Executor}s that have the least load. An {@link Executor} is defined
 * as having the least load if it is idle or has the most available {@link Executor}s
 * <p>If for any reason we are unsuccessful in creating a {@link Mapping} we fall back on the default Jenkins
 * {@link LoadBalancer#CONSISTENT_HASH} and try to use that.
 *
 * @author brendan.nolan@gmail.com
 */
public class LeastLoadBalancer extends LoadBalancer {

    private static final Logger LOGGER = Logger.getLogger(LeastLoadBalancer.class.getName());
    private final LoadBalancer fallback;
    public static boolean IS_ENABLED = SystemProperties.getBoolean(LeastLoadBalancer.class.getName() + ".IS_ENABLED", true);

    /**
     * Create the {@link LeastLoadBalancer} with a fallback that will be
     * used in case of any failures.
     *
     * @param fallback The {@link LoadBalancer} fallback to use in case of failure
     */
    public LeastLoadBalancer(LoadBalancer fallback) {
        Preconditions.checkNotNull(fallback, "You must provide a fallback implementation of the LoadBalancer");
        this.fallback = fallback;
    }

    @Initializer
    public static void register() {
        var queue = Jenkins.get().getQueue();
        queue.setLoadBalancer(new LeastLoadBalancer(queue.getLoadBalancer()));
    }

    @Override
    @CheckForNull
    public Mapping map(@NonNull Task task, MappingWorksheet ws) {
        try {
            if (IS_ENABLED && !isDisabled(task)) {
                Mapping m = ws.new Mapping();
                assert m.size() == ws.works.size();   // just so that you the reader of the source code don't get confused with the for loop index
                if (assignEvenly(ws, m, task, 0)) {
                    assert m.isCompletelyValid();
                    return m;
                } else {
                    return null; // Maybe there are no free executors.
                }

            } else {
                return getFallBackLoadBalancer().map(task, ws);
            }

        } catch (Exception e) {
            LOGGER.log(WARNING, "Least load balancer failed will use fallback", e);
            return getFallBackLoadBalancer().map(task, ws);
        }
    }
    private boolean assignEvenly(MappingWorksheet ws, Mapping m, Task task, int i) {
        if (i == m.size())
            return true;    // fully assigned
        List<ExecutorChunk> aec = ws.works(i).applicableExecutorChunks();
        Collections.shuffle(aec);
        List<ExecutorChunk> idles = aec.stream().filter(ae -> ae.computer.isIdle()).collect(Collectors.toList());
        List<ExecutorChunk> busies = aec.stream().filter(ae -> ae.computer.isPartiallyIdle()).sorted(Comparator.comparingInt(ae -> ae.computer.countBusy())).collect(Collectors.toList());
        if (assignChunks(ws, m, task, i, 0, idles)) {
            return true;
        }
        if (assignChunks(ws, m, task, i, 0, busies)) {
            return true;
        }
        m.assign(i, null);
        return false;
    }

    private boolean assignChunks(MappingWorksheet ws, Mapping m, Task task, int i, int start, List<ExecutorChunk> aec) {
        ArrayList<ExecutorChunk> ae = new ArrayList(aec);
        int step = 1;//ae.size() / 2 - 1;
        int sz = ae.size();
        for (int j = start; j < sz + start; ++j) {
            ExecutorChunk ec = ae.get((j * step) % sz);
            m.assign(i, ec);
            if (m.isPartiallyValid() && assignEvenly(ws, m, task, i + 1))
                return true;    // successful allocation
            // otherwise 'ec' wasn't a good fit for us. try next.
        }

        return false;
    }

    private boolean isDisabled(Task task) {

        SubTask subTask = task.getOwnerTask();

        if (subTask instanceof Job) {
            Job<?, ?> job = (Job<?, ?>) subTask;
            LeastLoadDisabledProperty property = job.getProperty(LeastLoadDisabledProperty.class);
            // If the job configuration hasn't been saved after installing the plugin, the property will be null. Assume
            // that the user wants to enable functionality by default.
            if (property != null) {
                return property.isLeastLoadDisabled();
            }
            return false;
        } else {
            return true;
        }

    }


    /**
     * Retrieves the fallback {@link LoadBalancer}
     *
     * @return - fallback {@link LoadBalancer}
     */
    public LoadBalancer getFallBackLoadBalancer() {
        return fallback;
    }
}
