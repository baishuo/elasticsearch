/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.support.replication;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionWriteResponse;
import org.elasticsearch.action.UnavailableShardsException;
import org.elasticsearch.action.WriteConsistencyLevel;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequest.OpType;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.action.support.TransportActions;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateObserver;
import org.elasticsearch.cluster.action.index.MappingUpdatedAction;
import org.elasticsearch.cluster.action.shard.ShardStateAction;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.*;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.index.mapper.Mapping;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.node.NodeClosedException;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Base class for requests that should be executed on a primary copy followed by replica copies.
 * Subclasses can resolve the target shard and provide implementation for primary and replica operations.
 *
 * The action sends a primary request to node with primary copy followed by replica requests to nodes with replica copies of the resolved shard.
 */
public abstract class TransportReplicationAction<Request extends ReplicationRequest, ReplicaRequest extends ReplicationRequest, Response extends ActionWriteResponse> extends TransportAction<Request, Response> {

    public static final String SHARD_FAILURE_TIMEOUT = "action.support.replication.shard.failure_timeout";

    protected final TransportService transportService;
    protected final ClusterService clusterService;
    protected final IndicesService indicesService;
    protected final ShardStateAction shardStateAction;
    protected final WriteConsistencyLevel defaultWriteConsistencyLevel;
    protected final TransportRequestOptions transportOptions;
    protected final MappingUpdatedAction mappingUpdatedAction;
    private final TimeValue shardFailedTimeout;

    final String transportReplicaAction;
    final String transportPrimaryAction;
    final String executor;
    final boolean checkWriteConsistency;

    protected TransportReplicationAction(Settings settings, String actionName, TransportService transportService,
                                         ClusterService clusterService, IndicesService indicesService,
                                         ThreadPool threadPool, ShardStateAction shardStateAction,
                                         MappingUpdatedAction mappingUpdatedAction, ActionFilters actionFilters,
                                         IndexNameExpressionResolver indexNameExpressionResolver, Supplier<Request> request,
                                         Supplier<ReplicaRequest> replicaRequest, String executor) {
        super(settings, actionName, threadPool, actionFilters, indexNameExpressionResolver);
        this.transportService = transportService;
        this.clusterService = clusterService;
        this.indicesService = indicesService;
        this.shardStateAction = shardStateAction;
        this.mappingUpdatedAction = mappingUpdatedAction;

        this.transportPrimaryAction = actionName + "[p]";
        this.transportReplicaAction = actionName + "[r]";
        this.executor = executor;
        this.checkWriteConsistency = checkWriteConsistency();
        transportService.registerRequestHandler(actionName, request, ThreadPool.Names.SAME, new OperationTransportHandler());
        transportService.registerRequestHandler(transportPrimaryAction, request, executor, new PrimaryOperationTransportHandler());
        // we must never reject on because of thread pool capacity on replicas
        transportService.registerRequestHandler(transportReplicaAction, replicaRequest, executor, true, new ReplicaOperationTransportHandler());

        this.transportOptions = transportOptions();

        this.defaultWriteConsistencyLevel = WriteConsistencyLevel.fromString(settings.get("action.write_consistency", "quorum"));
        // TODO: set a default timeout
        shardFailedTimeout = settings.getAsTime(SHARD_FAILURE_TIMEOUT, null);
    }

    @Override
    protected void doExecute(Request request, ActionListener<Response> listener) {
        new ReroutePhase(request, listener).run();
    }

    protected abstract Response newResponseInstance();

    /**
     * Primary operation on node with primary copy
     * @return A tuple containing not null values, as first value the result of the primary operation and as second value
     * the request to be executed on the replica shards.
     */
    protected abstract Tuple<Response, ReplicaRequest> shardOperationOnPrimary(ClusterState clusterState, Request shardRequest) throws Throwable;

    /**
     * Replica operation on nodes with replica copies
     */
    protected abstract void shardOperationOnReplica(ShardId shardId, ReplicaRequest shardRequest);

    /**
     * Resolves target index and shard
     * Note the underlying request's shardId should not be used, unless it was
     * explicitly set in the request (e.g. in flush, bulk and refresh request)
     */
    protected abstract ShardId shardId(ClusterState clusterState, InternalRequest internalRequest);

    protected abstract boolean checkWriteConsistency();

    protected ClusterBlockException checkGlobalBlock(ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.WRITE);
    }

    /**
     * Note the underlying request's shardId should not be used
     */
    protected ClusterBlockException checkRequestBlock(ClusterState state, InternalRequest internalRequest) {
        return state.blocks().indexBlockedException(ClusterBlockLevel.WRITE, internalRequest.concreteIndex);
    }

    protected boolean resolveIndex() {
        return true;
    }

    /**
     * Resolves the request, by default doing nothing. Can be subclassed to do
     * additional processing or validation depending on the incoming request.
     *
     * Note the underlying request's shardId should not be used
     */
    protected void resolveRequest(ClusterState state, InternalRequest internalRequest) {
    }

    protected TransportRequestOptions transportOptions() {
        return TransportRequestOptions.EMPTY;
    }

    protected boolean retryPrimaryException(Throwable e) {
        return e.getClass() == RetryOnPrimaryException.class
                || TransportActions.isShardNotAvailableException(e);
    }

    /**
     * Should an exception be ignored when the operation is performed on the replica.
     */
    protected boolean ignoreReplicaException(Throwable e) {
        if (TransportActions.isShardNotAvailableException(e)) {
            return true;
        }
        // on version conflict or document missing, it means
        // that a new change has crept into the replica, and it's fine
        if (isConflictException(e)) {
            return true;
        }
        return false;
    }

    protected boolean isConflictException(Throwable e) {
        Throwable cause = ExceptionsHelper.unwrapCause(e);
        // on version conflict or document missing, it means
        // that a new change has crept into the replica, and it's fine
        if (cause instanceof VersionConflictEngineException) {
            return true;
        }
        return false;
    }

    protected static class WriteResult<T extends ActionWriteResponse> {

        public final T response;
        public final Translog.Location location;

        public WriteResult(T response, Translog.Location location) {
            this.response = response;
            this.location = location;
        }

        @SuppressWarnings("unchecked")
        public <T extends ActionWriteResponse> T response() {
            // this sets total, pending and failed to 0 and this is ok, because we will embed this into the replica
            // request and not use it
            response.setShardInfo(new ActionWriteResponse.ShardInfo());
            return (T) response;
        }

    }

    class OperationTransportHandler implements TransportRequestHandler<Request> {
        @Override
        public void messageReceived(final Request request, final TransportChannel channel) throws Exception {
            execute(request, new ActionListener<Response>() {
                @Override
                public void onResponse(Response result) {
                    try {
                        channel.sendResponse(result);
                    } catch (Throwable e) {
                        onFailure(e);
                    }
                }

                @Override
                public void onFailure(Throwable e) {
                    try {
                        channel.sendResponse(e);
                    } catch (Throwable e1) {
                        logger.warn("Failed to send response for " + actionName, e1);
                    }
                }
            });
        }
    }

    class PrimaryOperationTransportHandler implements TransportRequestHandler<Request> {
        @Override
        public void messageReceived(final Request request, final TransportChannel channel) throws Exception {
            new PrimaryPhase(request, channel).run();
        }
    }

    class ReplicaOperationTransportHandler implements TransportRequestHandler<ReplicaRequest> {
        @Override
        public void messageReceived(final ReplicaRequest request, final TransportChannel channel) throws Exception {
            new AsyncReplicaAction(request, channel).run();
        }
    }

    public static class RetryOnReplicaException extends ElasticsearchException {

        public RetryOnReplicaException(ShardId shardId, String msg) {
            super(msg);
            setShard(shardId);
        }

        public RetryOnReplicaException(StreamInput in) throws IOException{
            super(in);
        }
    }

    private final class AsyncReplicaAction extends AbstractRunnable {
        private final ReplicaRequest request;
        private final TransportChannel channel;
        // important: we pass null as a timeout as failing a replica is
        // something we want to avoid at all costs
        private final ClusterStateObserver observer = new ClusterStateObserver(clusterService, null, logger);

        AsyncReplicaAction(ReplicaRequest request, TransportChannel channel) {
            this.request = request;
            this.channel = channel;
        }

        @Override
        public void onFailure(Throwable t) {
            if (t instanceof RetryOnReplicaException) {
                logger.trace("Retrying operation on replica", t);
                observer.waitForNextChange(new ClusterStateObserver.Listener() {
                    @Override
                    public void onNewClusterState(ClusterState state) {
                        threadPool.executor(executor).execute(AsyncReplicaAction.this);
                    }

                    @Override
                    public void onClusterServiceClose() {
                        responseWithFailure(new NodeClosedException(clusterService.localNode()));
                    }

                    @Override
                    public void onTimeout(TimeValue timeout) {
                        throw new AssertionError("Cannot happen: there is not timeout");
                    }
                });
            } else {
                try {
                    failReplicaIfNeeded(t);
                } catch (Throwable unexpected) {
                    logger.error("{} unexpected error while failing replica", request.shardId().id(), unexpected);
                } finally {
                    responseWithFailure(t);
                }
            }
        }

        private void failReplicaIfNeeded(Throwable t) {
            String index = request.shardId().getIndex();
            int shardId = request.shardId().id();
            logger.trace("failure on replica [{}][{}]", t, index, shardId);
            if (ignoreReplicaException(t) == false) {
                IndexService indexService = indicesService.indexService(index);
                if (indexService == null) {
                    logger.debug("ignoring failed replica [{}][{}] because index was already removed.", index, shardId);
                    return;
                }
                IndexShard indexShard = indexService.getShardOrNull(shardId);
                if (indexShard == null) {
                    logger.debug("ignoring failed replica [{}][{}] because index was already removed.", index, shardId);
                    return;
                }
                indexShard.failShard(actionName + " failed on replica", t);
            }
        }

        protected void responseWithFailure(Throwable t) {
            try {
                channel.sendResponse(t);
            } catch (IOException responseException) {
                logger.warn("failed to send error message back to client for action [" + transportReplicaAction + "]", responseException);
                logger.warn("actual Exception", t);
            }
        }

        @Override
        protected void doRun() throws Exception {
            try (Releasable shardReference = getIndexShardOperationsCounter(request.shardId())) {
                shardOperationOnReplica(request.internalShardId, request);
            }
            channel.sendResponse(TransportResponse.Empty.INSTANCE);
        }
    }

    public static class RetryOnPrimaryException extends ElasticsearchException {
        public RetryOnPrimaryException(ShardId shardId, String msg) {
            super(msg);
            setShard(shardId);
        }

        public RetryOnPrimaryException(StreamInput in) throws IOException{
            super(in);
        }
    }

    /**
     * Responsible for routing and retrying failed operations on the primary.
     * The actual primary operation is done in {@link PrimaryPhase} on the
     * node with primary copy.
     *
     * Resolves index and shard id for the request before routing it to target node
     */
    final class ReroutePhase extends AbstractRunnable {
        private final ActionListener<Response> listener;
        private final Request request;
        private final ClusterStateObserver observer;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        ReroutePhase(Request request, ActionListener<Response> listener) {
            this.request = request;
            this.listener = listener;
            this.observer = new ClusterStateObserver(clusterService, request.timeout(), logger);
        }

        @Override
        public void onFailure(Throwable e) {
            finishWithUnexpectedFailure(e);
        }

        @Override
        protected void doRun() {
            // adds shardID to request
            if (checkBlocks() == false) {
                return;
            }
            final IndexRoutingTable indexRoutingTable = observer.observedState().getRoutingTable().index(request.shardId().getIndex());
            if (indexRoutingTable == null) {
                logger.trace("index for shard [{}] is not found, scheduling a retry.", request.shardId());
                retryBecauseUnavailable(request.shardId(), "index is not active");
                return;
            }
            final IndexShardRoutingTable shardRoutingTable = indexRoutingTable.shard(request.shardId().getId());
            if (shardRoutingTable == null) {
                logger.trace("routing for shard [{}] is not found, scheduling a retry.", request.shardId());
                retryBecauseUnavailable(request.shardId(), "primary shard is not active");
                return;
            }
            final ShardRouting primary = shardRoutingTable.primaryShard();
            if (primary == null || primary.active() == false) {
                logger.trace("primary shard [{}] is not yet active, scheduling a retry.", request.shardId());
                retryBecauseUnavailable(request.shardId(), "primary shard is not active");
                return;
            }
            if (observer.observedState().nodes().nodeExists(primary.currentNodeId()) == false) {
                logger.trace("primary shard [{}] is assigned to an unknown node [{}], scheduling a retry.", request.shardId(), primary.currentNodeId());
                retryBecauseUnavailable(request.shardId(), "primary shard isn't assigned to a known node.");
                return;
            }
            if (primary.currentNodeId().equals(observer.observedState().nodes().localNodeId())) {
                // perform PrimaryPhase on the local node
                logger.trace("perform primary action for shard [{}] on node [{}]", request.shardId(), primary.currentNodeId());
                performAction(primary, transportPrimaryAction);
            } else {
                // perform ReroutePhase on the node with primary
                logger.trace("reroute primary action for shard [{}] to node [{}]", request.shardId(), primary.currentNodeId());
                performAction(primary, actionName);
            }
        }

        /**
         * checks for any cluster state blocks. Returns true if operation is OK to proceeded.
         * if false is return, no further action is needed. The method takes care of any continuation, by either
         * responding to the listener or scheduling a retry.
         */
        protected boolean checkBlocks() {
            ClusterBlockException blockException = checkGlobalBlock(observer.observedState());
            if (blockException != null) {
                if (blockException.retryable()) {
                    logger.trace("cluster is blocked ({}), scheduling a retry", blockException.getMessage());
                    retry(blockException);
                } else {
                    finishAsFailed(blockException);
                }
                return false;
            }
            final String concreteIndex = resolveIndex() ?
                    indexNameExpressionResolver.concreteSingleIndex(observer.observedState(), request) : request.index();
            // request does not have a shardId yet, we need to resolve the index and pass it along to resolve shardId
            final InternalRequest internalRequest = new InternalRequest(request, concreteIndex);
            resolveRequest(observer.observedState(), internalRequest);
            blockException = checkRequestBlock(observer.observedState(), internalRequest);
            if (blockException != null) {
                if (blockException.retryable()) {
                    logger.trace("cluster is blocked ({}), scheduling a retry", blockException.getMessage());
                    retry(blockException);
                } else {
                    finishAsFailed(blockException);
                }
                return false;
            }
            request.setShardId(shardId(clusterService.state(), internalRequest));
            return true;
        }

        private void performAction(final ShardRouting primary, final String action) {
            DiscoveryNode node = observer.observedState().nodes().get(primary.currentNodeId());
            final boolean isPrimaryAction = action.equals(transportPrimaryAction);
            transportService.sendRequest(node, action, request, transportOptions, new BaseTransportResponseHandler<Response>() {

                @Override
                public Response newInstance() {
                    return newResponseInstance();
                }

                @Override
                public String executor() {
                    return ThreadPool.Names.SAME;
                }

                @Override
                public void handleResponse(Response response) {
                    finishOnSuccess(response);
                }

                @Override
                public void handleException(TransportException exp) {
                    try {
                        // if we got disconnected from the node, or the node / shard is not in the right state (being closed)
                        if (exp.unwrapCause() instanceof ConnectTransportException || exp.unwrapCause() instanceof NodeClosedException ||
                                (isPrimaryAction && retryPrimaryException(exp.unwrapCause()))) {
                            // we already marked it as started when we executed it (removed the listener) so pass false
                            // to re-add to the cluster listener
                            logger.trace("received an error from node the primary was assigned to ({}), scheduling a retry", exp.getMessage());
                            retry(exp);
                        } else {
                            finishAsFailed(exp);
                        }
                    } catch (Throwable t) {
                        finishWithUnexpectedFailure(t);
                    }
                }
            });
        }

        void retry(Throwable failure) {
            assert failure != null;
            if (observer.isTimedOut()) {
                // we running as a last attempt after a timeout has happened. don't retry
                finishAsFailed(failure);
                return;
            }
            observer.waitForNextChange(new ClusterStateObserver.Listener() {
                @Override
                public void onNewClusterState(ClusterState state) {
                    run();
                }

                @Override
                public void onClusterServiceClose() {
                    finishAsFailed(new NodeClosedException(clusterService.localNode()));
                }

                @Override
                public void onTimeout(TimeValue timeout) {
                    // Try one more time...
                    run();
                }
            });
        }

        void finishAsFailed(Throwable failure) {
            if (finished.compareAndSet(false, true)) {
                logger.trace("operation failed", failure);
                listener.onFailure(failure);
            } else {
                assert false : "finishAsFailed called but operation is already finished";
            }
        }

        void finishWithUnexpectedFailure(Throwable failure) {
            logger.warn("unexpected error during the primary phase for action [{}]", failure, actionName);
            if (finished.compareAndSet(false, true)) {
                listener.onFailure(failure);
            } else {
                assert false : "finishWithUnexpectedFailure called but operation is already finished";
            }
        }

        void finishOnSuccess(Response response) {
            if (finished.compareAndSet(false, true)) {
                logger.trace("operation succeeded");
                listener.onResponse(response);
            } else {
                assert false : "finishOnRemoteSuccess called but operation is already finished";
            }
        }

        void retryBecauseUnavailable(ShardId shardId, String message) {
            retry(new UnavailableShardsException(shardId, message + " Timeout: [" + request.timeout() + "], request: " + request.toString()));
        }
    }

    protected class InternalRequest {
        public final Request request;
        public final String concreteIndex;

        private InternalRequest(Request request, String concreteIndex) {
            this.request = request;
            this.concreteIndex = concreteIndex;
        }
    }

    /**
     * Responsible for performing primary operation locally and delegating to replication action once successful
     * <p>
     * Note that as soon as we move to replication action, state responsibility is transferred to {@link ReplicationPhase}.
     */
    final class PrimaryPhase extends AbstractRunnable {
        private final Request request;
        private final TransportChannel channel;
        private final ClusterState clusterState;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private volatile Releasable indexShardReference;

        PrimaryPhase(Request request, TransportChannel channel) {
            this.clusterState = clusterService.state();
            this.request = request;
            this.channel = channel;
        }

        @Override
        public void onFailure(Throwable e) {
            finishAsFailed(e);
        }

        @Override
        protected void doRun() throws Exception {
            // request shardID was set in ReroutePhase
            final ShardId shardId = request.shardId();
            final String writeConsistencyFailure = checkWriteConsistency(shardId);
            if (writeConsistencyFailure != null) {
                finishBecauseUnavailable(shardId, writeConsistencyFailure);
                return;
            }
            final ReplicationPhase replicationPhase;
            try {
                indexShardReference = getIndexShardOperationsCounter(shardId);
                Tuple<Response, ReplicaRequest> primaryResponse = shardOperationOnPrimary(clusterState, request);
                logger.trace("operation completed on primary [{}]", shardId);
                // we cache meta data to resolve settings even if the index gets deleted after primary operation
                IndexMetaData metaData = clusterState.metaData().index(request.shardId().getIndex());
                replicationPhase = new ReplicationPhase(primaryResponse.v2(), primaryResponse.v1(), request.shardId(),
                        metaData, channel, indexShardReference, shardFailedTimeout);
            } catch (Throwable e) {
                if (ExceptionsHelper.status(e) == RestStatus.CONFLICT) {
                    if (logger.isTraceEnabled()) {
                        logger.trace("Failed to execute [" + request + "]", e);
                    }
                } else {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Failed to execute [" + request + "]", e);
                    }
                }
                finishAsFailed(e);
                return;
            }
            finishAndMoveToReplication(replicationPhase);
        }

        /**
         * checks whether we can perform a write based on the write consistency setting
         * returns **null* if OK to proceed, or a string describing the reason to stop
         */
        String checkWriteConsistency(ShardId shardId) {
            if (checkWriteConsistency == false) {
                return null;
            }

            final WriteConsistencyLevel consistencyLevel;
            if (request.consistencyLevel() != WriteConsistencyLevel.DEFAULT) {
                consistencyLevel = request.consistencyLevel();
            } else {
                consistencyLevel = defaultWriteConsistencyLevel;
            }
            final int sizeActive;
            final int requiredNumber;
            IndexRoutingTable indexRoutingTable = clusterState.getRoutingTable().index(shardId.getIndex());
            if (indexRoutingTable != null) {
                IndexShardRoutingTable shardRoutingTable = indexRoutingTable.shard(shardId.getId());
                if (shardRoutingTable != null) {
                    sizeActive = shardRoutingTable.activeShards().size();
                    if (consistencyLevel == WriteConsistencyLevel.QUORUM && shardRoutingTable.getSize() > 2) {
                        // only for more than 2 in the number of shardIt it makes sense, otherwise its 1 shard with 1 replica, quorum is 1 (which is what it is initialized to)
                        requiredNumber = (shardRoutingTable.getSize() / 2) + 1;
                    } else if (consistencyLevel == WriteConsistencyLevel.ALL) {
                        requiredNumber = shardRoutingTable.getSize();
                    } else {
                        requiredNumber = 1;
                    }
                } else {
                    sizeActive = 0;
                    requiredNumber = 1;
                }
            } else {
                sizeActive = 0;
                requiredNumber = 1;
            }

            if (sizeActive < requiredNumber) {
                logger.trace("not enough active copies of shard [{}] to meet write consistency of [{}] (have {}, needed {}), scheduling a retry.",
                        shardId, consistencyLevel, sizeActive, requiredNumber);
                return "Not enough active copies to meet write consistency of [" + consistencyLevel + "] (have " + sizeActive + ", needed " + requiredNumber + ").";
            } else {
                return null;
            }
        }

        /**
         * upon success, finish the first phase and transfer responsibility to the {@link ReplicationPhase}
         */
        void finishAndMoveToReplication(ReplicationPhase replicationPhase) {
            if (finished.compareAndSet(false, true)) {
                replicationPhase.run();
            } else {
                assert false : "finishAndMoveToReplication called but operation is already finished";
            }
        }

        /**
         * upon failure, send failure back to the {@link ReroutePhase} for retrying if appropriate
         */
        void finishAsFailed(Throwable failure) {
            if (finished.compareAndSet(false, true)) {
                Releasables.close(indexShardReference);
                logger.trace("operation failed", failure);
                try {
                    channel.sendResponse(failure);
                } catch (IOException responseException) {
                    logger.warn("failed to send error message back to client for action [" + transportPrimaryAction + "]", responseException);
                }
            } else {
                assert false : "finishAsFailed called but operation is already finished";
            }
        }

        void finishBecauseUnavailable(ShardId shardId, String message) {
            finishAsFailed(new UnavailableShardsException(shardId, message + " Timeout: [" + request.timeout() + "], request: " + request.toString()));
        }
    }

    protected Releasable getIndexShardOperationsCounter(ShardId shardId) {
        IndexService indexService = indicesService.indexServiceSafe(shardId.index().getName());
        IndexShard indexShard = indexService.getShard(shardId.id());
        return new IndexShardReference(indexShard);
    }

    /**
     * Responsible for sending replica requests (see {@link AsyncReplicaAction}) to nodes with replica copy, including
     * relocating copies
     */
    final class ReplicationPhase extends AbstractRunnable {

        private final ReplicaRequest replicaRequest;
        private final Response finalResponse;
        private final ShardIterator shardIt;
        private final TransportChannel channel;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicInteger success = new AtomicInteger(1); // We already wrote into the primary shard
        private final ConcurrentMap<String, Throwable> shardReplicaFailures = ConcurrentCollections.newConcurrentMap();
        private final AtomicInteger pending;
        private final int totalShards;
        private final ClusterState clusterState;
        private final Releasable indexShardReference;
        private final TimeValue shardFailedTimeout;
        private final boolean executeOnReplica;
        private final String indexUUID;

        public ReplicationPhase(ReplicaRequest replicaRequest, Response finalResponse, ShardId shardId,
                                IndexMetaData indexMetaData, TransportChannel channel, Releasable indexShardReference,
                                TimeValue shardFailedTimeout) {
            this.replicaRequest = replicaRequest;
            this.channel = channel;
            this.finalResponse = finalResponse;
            this.indexShardReference = indexShardReference;
            this.shardFailedTimeout = shardFailedTimeout;
            this.executeOnReplica = shouldExecuteReplication(indexMetaData.getSettings());
            this.indexUUID = indexMetaData.getIndexUUID();

            // we get a new state to route replication operations based on the latest shard routings and states
            this.clusterState = clusterService.state();
            this.shardIt = clusterService.operationRouting().shards(clusterState, shardId.getIndex(), shardId.id()).shardsIt();
            // we calculate number of target nodes to send replication operations, including nodes with relocating shards
            int numberOfIgnoredShardInstances = 0;
            int numberOfPendingShardInstances = 0;
            String localNodeId = clusterState.nodes().localNodeId();
            for (ShardRouting shard : shardIt.asUnordered()) {
                if (shard.primary() == false && executeOnReplica == false) {
                    numberOfIgnoredShardInstances++;
                } else if (shard.unassigned()) {
                    numberOfIgnoredShardInstances++;
                } else {
                    if (shard.currentNodeId().equals(localNodeId) == false) {
                        numberOfPendingShardInstances++;
                    }
                    if (shard.relocating() && shard.relocatingNodeId().equals(localNodeId) == false) {
                        numberOfPendingShardInstances++;
                    }
                }
            }
            // one for the local primary copy
            this.totalShards = 1 + numberOfPendingShardInstances + numberOfIgnoredShardInstances;
            this.pending = new AtomicInteger(numberOfPendingShardInstances);
        }

        /**
         * total shard copies
         */
        int totalShards() {
            return totalShards;
        }

        /**
         * total successful operations so far
         */
        int successful() {
            return success.get();
        }

        /**
         * number of pending operations
         */
        int pending() {
            return pending.get();
        }

        @Override
        public void onFailure(Throwable t) {
            logger.error("unexpected error while replicating for action [{}]. shard [{}]. ", t, actionName, shardIt.shardId());
            forceFinishAsFailed(t);
        }

        /**
         * start sending replica requests to target nodes
         */
        @Override
        protected void doRun() {
            if (pending.get() == 0) {
                doFinish();
                return;
            }
            ShardRouting shard;
            shardIt.reset(); // reset the iterator
            while ((shard = shardIt.nextOrNull()) != null) {
                if (shard.primary() == false && executeOnReplica == false) {
                    // If the replicas use shadow replicas, there is no reason to
                    // perform the action on the replica, so skip it and
                    // immediately return

                    // this delays mapping updates on replicas because they have
                    // to wait until they get the new mapping through the cluster
                    // state, which is why we recommend pre-defined mappings for
                    // indices using shadow replicas
                    continue;
                }
                // ignore unassigned shard
                if (shard.unassigned()) {
                    continue;
                }
                // we index on a replica that is initializing as well since we might not have got the event
                // yet that it was started. We will get an exception IllegalShardState exception if its not started
                // and that's fine, we will ignore it

                // we never execute replication operation locally as primary operation has already completed locally
                // hence, we ignore any local shard for replication
                if (clusterState.nodes().localNodeId().equals(shard.currentNodeId()) == false) {
                    performOnReplica(shard, shard.currentNodeId());
                }
                // send operation to relocating shard if not local
                if (shard.relocating() &&  clusterState.nodes().localNodeId().equals(shard.relocatingNodeId()) == false) {
                    performOnReplica(shard, shard.relocatingNodeId());
                }
            }
        }

        /**
         * send replica operation to target node
         */
        void performOnReplica(final ShardRouting shard, final String nodeId) {
            // if we don't have that node, it means that it might have failed and will be created again, in
            // this case, we don't have to do the operation, and just let it failover
            if (!clusterState.nodes().nodeExists(nodeId)) {
                onReplicaFailure(nodeId, null);
                return;
            }

            final DiscoveryNode node = clusterState.nodes().get(nodeId);
            transportService.sendRequest(node, transportReplicaAction, replicaRequest,
                    transportOptions, new EmptyTransportResponseHandler(ThreadPool.Names.SAME) {
                        @Override
                        public void handleResponse(TransportResponse.Empty vResponse) {
                                                                                    onReplicaSuccess();
                                                                                                                                                           }

                        @Override
                        public void handleException(TransportException exp) {
                            logger.trace("[{}] transport failure during replica request [{}] ", exp, node, replicaRequest);
                            if (ignoreReplicaException(exp)) {
                                onReplicaFailure(nodeId, exp);
                            } else {
                                logger.warn("{} failed to perform {} on node {}", exp, shardIt.shardId(), actionName, node);
                                shardStateAction.shardFailed(shard, indexUUID, "failed to perform " + actionName + " on replica on node " + node, exp, shardFailedTimeout, new ReplicationFailedShardStateListener(nodeId, exp));
                            }
                        }
                    }
            );
        }


        void onReplicaFailure(String nodeId, @Nullable Throwable e) {
            // Only version conflict should be ignored from being put into the _shards header?
            if (e != null && ignoreReplicaException(e) == false) {
                shardReplicaFailures.put(nodeId, e);
            }
            decPendingAndFinishIfNeeded();
        }

        void onReplicaSuccess() {
            success.incrementAndGet();
            decPendingAndFinishIfNeeded();
        }

        private void decPendingAndFinishIfNeeded() {
            if (pending.decrementAndGet() <= 0) {
                doFinish();
            }
        }

        private void forceFinishAsFailed(Throwable t) {
            if (finished.compareAndSet(false, true)) {
                Releasables.close(indexShardReference);
                try {
                    channel.sendResponse(t);
                } catch (IOException responseException) {
                    logger.warn("failed to send error message back to client for action [" + transportReplicaAction + "]", responseException);
                    logger.warn("actual Exception", t);
                }
            }
        }

        private void doFinish() {
            if (finished.compareAndSet(false, true)) {
                Releasables.close(indexShardReference);
                final ShardId shardId = shardIt.shardId();
                final ActionWriteResponse.ShardInfo.Failure[] failuresArray;
                if (!shardReplicaFailures.isEmpty()) {
                    int slot = 0;
                    failuresArray = new ActionWriteResponse.ShardInfo.Failure[shardReplicaFailures.size()];
                    for (Map.Entry<String, Throwable> entry : shardReplicaFailures.entrySet()) {
                        RestStatus restStatus = ExceptionsHelper.status(entry.getValue());
                        failuresArray[slot++] = new ActionWriteResponse.ShardInfo.Failure(
                                shardId.getIndex(), shardId.getId(), entry.getKey(), entry.getValue(), restStatus, false
                        );
                    }
                } else {
                    failuresArray = ActionWriteResponse.EMPTY;
                }
                finalResponse.setShardInfo(new ActionWriteResponse.ShardInfo(
                                totalShards,
                                success.get(),
                                failuresArray

                        )
                );
                try {
                    channel.sendResponse(finalResponse);
                } catch (IOException responseException) {
                    logger.warn("failed to send error message back to client for action [" + transportReplicaAction + "]", responseException);
                }
            }
        }

        public class ReplicationFailedShardStateListener implements ShardStateAction.Listener {
            private final String nodeId;
            private Throwable failure;

            public ReplicationFailedShardStateListener(String nodeId, Throwable failure) {
                this.nodeId = nodeId;
                this.failure = failure;
            }

            @Override
            public void onSuccess() {
                onReplicaFailure(nodeId, failure);
            }

            @Override
            public void onShardFailedNoMaster() {
                onReplicaFailure(nodeId, failure);
            }

            @Override
            public void onShardFailedFailure(DiscoveryNode master, TransportException e) {
                if (e instanceof ReceiveTimeoutTransportException) {
                    logger.trace("timeout sending shard failure to master [{}]", e, master);
                }
                onReplicaFailure(nodeId, failure);
            }
        }
    }

    /**
     * Indicated whether this operation should be replicated to shadow replicas or not. If this method returns true the replication phase will be skipped.
     * For example writes such as index and delete don't need to be replicated on shadow replicas but refresh and flush do.
     */
    protected boolean shouldExecuteReplication(Settings settings) {
        return IndexMetaData.isIndexUsingShadowReplicas(settings) == false;
    }

    static class IndexShardReference implements Releasable {

        final private IndexShard counter;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        IndexShardReference(IndexShard counter) {
            counter.incrementOperationCounter();
            this.counter = counter;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                counter.decrementOperationCounter();
            }
        }
    }

    /** Utility method to create either an index or a create operation depending
     *  on the {@link OpType} of the request. */
    private final Engine.Index prepareIndexOperationOnPrimary(IndexRequest request, IndexShard indexShard) {
        SourceToParse sourceToParse = SourceToParse.source(SourceToParse.Origin.PRIMARY, request.source()).index(request.index()).type(request.type()).id(request.id())
                .routing(request.routing()).parent(request.parent()).timestamp(request.timestamp()).ttl(request.ttl());
            return indexShard.prepareIndex(sourceToParse, request.version(), request.versionType(), Engine.Operation.Origin.PRIMARY);

    }

    /** Execute the given {@link IndexRequest} on a primary shard, throwing a
     *  {@link RetryOnPrimaryException} if the operation needs to be re-tried. */
    protected final WriteResult<IndexResponse> executeIndexRequestOnPrimary(IndexRequest request, IndexShard indexShard) throws Throwable {
        Engine.Index operation = prepareIndexOperationOnPrimary(request, indexShard);
        Mapping update = operation.parsedDoc().dynamicMappingsUpdate();
        final ShardId shardId = indexShard.shardId();
        if (update != null) {
            final String indexName = shardId.getIndex();
            mappingUpdatedAction.updateMappingOnMasterSynchronously(indexName, request.type(), update);
            operation = prepareIndexOperationOnPrimary(request, indexShard);
            update = operation.parsedDoc().dynamicMappingsUpdate();
            if (update != null) {
                throw new RetryOnPrimaryException(shardId,
                        "Dynamics mappings are not available on the node that holds the primary yet");
            }
        }
        final boolean created = indexShard.index(operation);

        // update the version on request so it will happen on the replicas
        final long version = operation.version();
        request.version(version);
        request.versionType(request.versionType().versionTypeForReplicationAndRecovery());

        assert request.versionType().validateVersionForWrites(request.version());

        return new WriteResult(new IndexResponse(shardId.getIndex(), request.type(), request.id(), request.version(), created), operation.getTranslogLocation());
    }

    protected final void processAfter(boolean refresh, IndexShard indexShard, Translog.Location location) {
        if (refresh) {
            try {
                indexShard.refresh("refresh_flag_index");
            } catch (Throwable e) {
                // ignore
            }
        }
        if (indexShard.getTranslogDurability() == Translog.Durabilty.REQUEST && location != null) {
            indexShard.sync(location);
        }
        indexShard.maybeFlush();
    }
}
