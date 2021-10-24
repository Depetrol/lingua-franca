/*************
 * Copyright (c) 2020, The University of California at Berkeley.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 ***************/

package org.lflang.graph;

import java.util.Collection;
import java.util.Arrays;
import org.lflang.generator.NamedInstance;
import org.lflang.generator.PortInstance;
import org.lflang.generator.ReactionInstance;
import org.lflang.generator.ReactorInstance;
import org.lflang.generator.TriggerInstance;
import org.lflang.lf.Connection;
import org.lflang.lf.Variable;

/**
 * A graph with vertices that are ports or reactions and edges that denote
 * dependencies between them.
 * 
 * @author Marten Lohstroh <marten@berkeley.edu>
 */
public class TopologyGraph extends PrecedenceGraph<NamedInstance<?>> {

    /**
     * Construct a graph with vertices that are reactions or ports and edges
     * that represent (zero-delay) dependencies.
     * 
     * After constructing the graph, run Tarjan's algorithm to detect cyclic
     * dependencies between reactions. It is assumed that no instantiation
     * cycles are present in the program. Checks for instantiation cycles thus
     * must be carried out prior to constructing this graph.
     * 
     * @param reactors The reactor instances to construct the graph for.
     */
    public TopologyGraph(Collection<? extends ReactorInstance> reactors) {
        for (var r : reactors) {
            collectNodesFrom(r);
        }
        this.detectCycles();
    }

    /** See description on other constructor. */
    public TopologyGraph(ReactorInstance... reactors) {
        this(Arrays.asList(reactors));
    }

    /**
     * Build the graph by recursively visiting reactor instances contained in
     * the passed in reactor instance.
     * 
     * @param reactor A reactor instance to harvest dependencies from.
     */
    public void collectNodesFrom(ReactorInstance reactor) {
        ReactionInstance previousReaction = null;
        for (var reaction : reactor.reactions) {
            this.addNode(reaction);

            this.addSources(reaction);
            this.addEffects(reaction);

            // If this is not an unordered reaction, then create a dependency
            // on any previously defined reaction.
            if (!reaction.isUnordered) {
                // If there is an earlier reaction in this same reactor, then
                // create a link in the reaction graph.
                if (previousReaction != null) {
                    this.addEdge(reaction, previousReaction);
                }
                previousReaction = reaction;
            }
        }
        // Recursively add nodes and edges from contained reactors.
        for (var child : reactor.children) {
            collectNodesFrom(child);
        }
    }

    /**
     * Given a reaction instance, record dependencies implied by its effects.
     * 
     * Excluded from the recorded dependencies are those that are broken by
     * physical connections or "after" delays.
     * 
     * @param reaction The reaction to record the dependencies of.
     */
    private void addEffects(ReactionInstance reaction) {
        for (TriggerInstance<? extends Variable> effect : reaction.effects) {
            if (effect instanceof PortInstance) {
                addEdge(effect, reaction);
                PortInstance orig = (PortInstance) effect;
                orig.dependentPorts().forEach(dest -> {
                    recordDependency(reaction, orig, dest);
                });
            }
        }
    }

    /**
     * Given a reaction instance, record dependencies implied by its sources.
     * 
     * Excluded from the recorded dependencies are those that are broken by
     * physical connections or "after" delays.
     * 
     * @param reaction The reaction to record the dependencies of.
     */
    private void addSources(ReactionInstance reaction) {
        for (TriggerInstance<? extends Variable> source : reaction.sources) {
            if (source instanceof PortInstance) {
                addEdge(reaction, source);
                PortInstance dest = (PortInstance) source;
                dest.dependsOnPorts().forEach(orig -> {
                    recordDependency(reaction, orig, dest);
                });
            }
        }
    }

    /**
     * Record a dependency between two port instances, but only if there is a
     * zero-delay path from origin to destination.
     * 
     * @param reaction A reaction that has one of the given port as a source or
     *                 effect.
     * @param orig     The upstream port.
     * @param dest     The downstream port.
     */
    private void recordDependency(ReactionInstance reaction, PortInstance orig,
            PortInstance dest) {
        // Note: a reaction always has a parent, but it might not have a
        // grandparent. Hence, the first argument given to getConnection might
        // be null.
        if (!dependencyBroken(
                getConnection(reaction.parent.parent, orig, dest))) {
            addEdge(dest, orig);
        }
    }

    /**
     * Look up the AST node that describes the connection between the two given
     * port instances and return it if there is one.
     * 
     * @param container The reactor instance in which to perform the look up.
     * @param orig      The upstream port.
     * @param dest      The downstream port.
     * @return The corresponding Connection object or null if there is none.
     */
    private Connection getConnection(ReactorInstance container,
            PortInstance orig, PortInstance dest) {
        if (container == null) {
            return null;
        }
        return container.getConnection(orig, dest);
    }

    /**
     * Report whether or not the given connection breaks dependencies or not.
     * 
     * @param c An AST object that represents a connection.
     * @return true if the connection is physical or has a delay.
     */
    private boolean dependencyBroken(Connection c) {
        if (c != null && (c.isPhysical() || c.getDelay() != null)) {
            return true;
        }
        return false;
    }
}