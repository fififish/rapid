/*
 * Copyright © 2016 - 2017 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the “License”); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an “AS IS” BASIS, without warranties or conditions of any kind,
 * EITHER EXPRESS OR IMPLIED. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.vrg.rapid;

import com.google.common.net.HostAndPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A filter that outputs a view change proposal about a node only if:
 * - there are H reports about a node.
 * - there is no other node about which there are more than L but less than H reports.
 */
class WatermarkBuffer {
    private static final int K_MIN = 3;
    private final int H;
    private final int L;
    private final AtomicInteger proposalCount = new AtomicInteger(0);
    private final AtomicInteger updatesInProgress = new AtomicInteger(0);
    private final Map<HostAndPort, HashSet<HostAndPort>> reportsPerHost;
    private final ArrayList<Node> proposal = new ArrayList<>();
    private final Object lock = new Object();
    private static final List<Node> EMPTY_LIST =
            Collections.unmodifiableList(new ArrayList<Node>());

    WatermarkBuffer(final int K, final int H, final int L) {
        if (H > K || L > H || K < K_MIN) {
            throw new IllegalArgumentException("Arguments do not satisfy K > H >= L >= 0:" +
                                               " (K: " + K + ", H: " + H + ", L: " + L);
        }
        this.H = H;
        this.L = L;
        this.reportsPerHost = new HashMap<>();
    }

    int getNumProposals() {
        return proposalCount.get();
    }

    List<Node> receiveLinkUpdateMessage(final LinkUpdateMessage msg) {
        Objects.requireNonNull(msg);

        synchronized (lock) {

            final Set<HostAndPort> reportsForHost = reportsPerHost.computeIfAbsent(msg.getDst(),
                                             (k) -> new HashSet());
            reportsForHost.add(msg.getSrc());
            final int numReportsForHost = reportsForHost.size();

            if (numReportsForHost == L) {
                updatesInProgress.incrementAndGet();
            }

            if (numReportsForHost == H) {
                 // Enough reports about "msg.getDst()" have been received that it is safe to act upon,
                 // provided there are no other nodes with L < #reports < H.
                proposal.add(new Node(msg.getDst()));
                final int updatesInProgressVal = updatesInProgress.decrementAndGet();

                if (updatesInProgressVal == 0) {
                    // No outstanding updates, so all nodes that have crossed the H threshold of reports are
                    // now part of a single proposal.
                    this.proposalCount.incrementAndGet();
                    for (final Node n: proposal) {
                        // The counter below should never be null.
                        final Set reportsSet = reportsPerHost.get(n.address);
                        if (reportsSet == null) {
                            throw new RuntimeException("Node to be delivered not in UpdateCounters map: "
                                                        + n.address);
                        }
                        reportsSet.clear();
                    }
                    final List<Node> ret = Collections.unmodifiableList(new ArrayList<>(proposal));
                    proposal.clear();
                    return ret;
                }
            }

            return EMPTY_LIST;
        }
    }
}