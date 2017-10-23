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

package com.vrg.rapid.messaging;

import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.vrg.rapid.MembershipService;
import com.vrg.rapid.SharedResources;
import com.vrg.rapid.pb.BatchedLinkUpdateMessage;
import com.vrg.rapid.pb.ConsensusProposal;
import com.vrg.rapid.pb.ConsensusProposalResponse;
import com.vrg.rapid.pb.JoinMessage;
import com.vrg.rapid.pb.JoinResponse;
import com.vrg.rapid.pb.MembershipServiceGrpc;
import com.vrg.rapid.pb.NodeStatus;
import com.vrg.rapid.pb.ProbeMessage;
import com.vrg.rapid.pb.ProbeResponse;
import com.vrg.rapid.pb.Response;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;


/**
 * gRPC server object. It defers receiving messages until it is ready to
 * host a MembershipService object.
 */
public final class GrpcServer extends MembershipServiceGrpc.MembershipServiceImplBase implements IMessagingServer {
    private final ExecutorService grpcExecutor;
    @Nullable private final EventLoopGroup eventLoopGroup;
    private static final ProbeResponse BOOTSTRAPPING_MESSAGE =
            ProbeResponse.newBuilder().setStatus(NodeStatus.BOOTSTRAPPING).build();
    private final HostAndPort address;
    @Nullable private MembershipService membershipService;
    @Nullable private Server server;
    private final ExecutorService protocolExecutor;
    private final List<ServerInterceptor> interceptors;
    private final boolean useInProcessServer;

    // Used to queue messages in the RPC layer until we are ready with
    // a MembershipService object
    private final DeferredReceiveInterceptor deferringInterceptor = new DeferredReceiveInterceptor();

    public GrpcServer(final HostAndPort address, final SharedResources sharedResources,
                      final List<ServerInterceptor> interceptors, final boolean useInProcessTransport) {
        this.address = address;
        this.protocolExecutor = sharedResources.getProtocolExecutor();
        this.grpcExecutor = sharedResources.getServerExecutor();
        this.eventLoopGroup = useInProcessTransport ? null : sharedResources.getEventLoopGroup();
        this.useInProcessServer = useInProcessTransport;
        this.interceptors = interceptors;
    }

    /**
     * Defined in rapid.proto.
     */
    @Override
    public void receiveLinkUpdateMessage(final BatchedLinkUpdateMessage request,
                                         final StreamObserver<Response> responseObserver) {
        assert membershipService != null;
        protocolExecutor.execute(() -> processLinkUpdateMessage(request));
        responseObserver.onNext(Response.getDefaultInstance());
        responseObserver.onCompleted();
    }

    /**
     * Defined in rapid.proto.
     */
    @Override
    public void receiveConsensusProposal(final ConsensusProposal request,
                                         final StreamObserver<ConsensusProposalResponse> responseObserver) {
        assert membershipService != null;
        protocolExecutor.execute(() -> processConsensusProposal(request));
        responseObserver.onNext(ConsensusProposalResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    /**
     * Defined in rapid.proto.
     */
    @Override
    public void receiveJoinMessage(final JoinMessage joinMessage,
                                   final StreamObserver<JoinResponse> responseObserver) {
        assert membershipService != null;
        protocolExecutor.execute(() -> {
            final ListenableFuture<JoinResponse> result = processJoinMessage(joinMessage);
            Futures.addCallback(result, new JoinResponseCallback(responseObserver), grpcExecutor);
        });
    }

    /**
     * Defined in rapid.proto.
     */
    @Override
    public void receiveJoinPhase2Message(final JoinMessage joinMessage,
                                         final StreamObserver<JoinResponse> responseObserver) {
        protocolExecutor.execute(() -> {
            final ListenableFuture<JoinResponse> result =
                    processJoinPhaseTwoMessage(joinMessage);
            Futures.addCallback(result, new JoinResponseCallback(responseObserver), grpcExecutor);
        });
    }

    /**
     * Defined in rapid.proto.
     */
    @Override
    public void receiveProbe(final ProbeMessage probeMessage,
                             final StreamObserver<ProbeResponse> probeResponseObserver) {
        if (membershipService != null) {
            protocolExecutor.execute(() -> {
                final ListenableFuture<ProbeResponse> result = processProbeMessage(probeMessage);
                Futures.addCallback(result, new ProbeResponseCallback(probeResponseObserver), grpcExecutor);
            });
        }
        else {
            /*
             * This is a special case which indicates that:
             *  1) the system is configured to use a failure detector that relies on Rapid's probe messages
             *  2) the node receiving the probe message has been added to the cluster but has not yet completed
             *     its bootstrap process (has not received its join-confirmation yet).
             *  3) By virtue of 2), the node is "about to be up" and therefore informs the monitor that it is
             *     still bootstrapping. This extra information may or may not be respected by the failure detector,
             *     but is useful in large deployments.
             */
            probeResponseObserver.onNext(BOOTSTRAPPING_MESSAGE);
            probeResponseObserver.onCompleted();
        }
    }

    /**
     * Invoked by the bootstrap protocol when it has a membership service object
     * ready. Until this method is called, the GrpcServer will not have its gRPC service
     * methods invoked.
     *
     * @param service a fully initialized MembershipService object.
     */
    @Override
    public void setMembershipService(final MembershipService service) {
        if (this.membershipService != null) {
            throw new RuntimeException("setMembershipService called more than once");
        }
        this.membershipService = service;
        deferringInterceptor.unblock();
    }

    // IMessaging server interface
    @Override
    public ListenableFuture<ProbeResponse> processProbeMessage(final ProbeMessage probeMessage) {
        assert membershipService != null;
        return membershipService.processProbeMessage(probeMessage);
    }

    // IMessaging server interface
    @Override
    public ListenableFuture<JoinResponse> processJoinMessage(final JoinMessage msg) {
        assert membershipService != null;
        return membershipService.processJoinMessage(msg);
    }

    // IMessaging server interface
    @Override
    public ListenableFuture<JoinResponse> processJoinPhaseTwoMessage(final JoinMessage msg) {
        assert membershipService != null;
        return membershipService.processJoinPhaseTwoMessage(msg);
    }

    // IMessaging server interface
    @Override
    public void processConsensusProposal(final ConsensusProposal msg) {
        assert membershipService != null;
        membershipService.processConsensusProposal(msg);
    }

    // IMessaging server interface
    @Override
    public void processLinkUpdateMessage(final BatchedLinkUpdateMessage msg) {
        assert membershipService != null;
        membershipService.processLinkUpdateMessage(msg);
    }

    // IMessaging server interface
    @Override
    public void shutdown() {
        assert server != null;
        try {
            if (membershipService != null) {
                membershipService.shutdown();
            }
            server.shutdown();
            server.awaitTermination(0, TimeUnit.SECONDS);
            protocolExecutor.shutdownNow();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Starts the RPC server.
     *
     * @throws IOException if a server cannot be successfully initialized
     */
    @Override
    public void start() throws IOException {
        startServer(interceptors);
    }

    private void startServer(final List<ServerInterceptor> interceptors) throws IOException {
        Objects.requireNonNull(interceptors);
        final ImmutableList.Builder<ServerInterceptor> listBuilder = ImmutableList.builder();
        final List<ServerInterceptor> interceptorList = listBuilder.add(deferringInterceptor)
                                                                   .addAll(interceptors) // called first by grpc
                                                                   .build();
        if (useInProcessServer) {
            final ServerBuilder builder = InProcessServerBuilder.forName(address.toString());
            server = builder.addService(ServerInterceptors.intercept(this, interceptorList))
                    .executor(grpcExecutor)
                    .build()
                    .start();
        } else {
            server = NettyServerBuilder.forAddress(new InetSocketAddress(address.getHost(), address.getPort()))
                    .workerEventLoopGroup(eventLoopGroup)
                    .addService(ServerInterceptors.intercept(this, interceptorList))
                    .executor(grpcExecutor)
                    .build()
                    .start();
        }

        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    // Callbacks
    private static class JoinResponseCallback implements FutureCallback<JoinResponse> {
        private final StreamObserver<JoinResponse> responseObserver;

        JoinResponseCallback(final StreamObserver<JoinResponse> responseObserver) {
            this.responseObserver = responseObserver;
        }

        @Override
        public void onSuccess(@Nullable final JoinResponse response) {
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void onFailure(final Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    private static class ProbeResponseCallback implements FutureCallback<ProbeResponse> {
        private final StreamObserver<ProbeResponse> responseObserver;

        ProbeResponseCallback(final StreamObserver<ProbeResponse> responseObserver) {
            this.responseObserver = responseObserver;
        }

        @Override
        public void onSuccess(@Nullable final ProbeResponse response) {
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void onFailure(final Throwable throwable) {
            throwable.printStackTrace();
        }
    }
}