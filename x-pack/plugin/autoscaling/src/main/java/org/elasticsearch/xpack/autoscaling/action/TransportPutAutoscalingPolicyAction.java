/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.autoscaling.action;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.AcknowledgedTransportMasterNodeAction;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.autoscaling.AutoscalingMetadata;
import org.elasticsearch.xpack.autoscaling.policy.AutoscalingPolicy;
import org.elasticsearch.xpack.autoscaling.policy.AutoscalingPolicyMetadata;

import java.util.SortedMap;
import java.util.TreeMap;

public class TransportPutAutoscalingPolicyAction extends AcknowledgedTransportMasterNodeAction<PutAutoscalingPolicyAction.Request> {

    private static final Logger logger = LogManager.getLogger(TransportPutAutoscalingPolicyAction.class);

    @Inject
    public TransportPutAutoscalingPolicyAction(
        final TransportService transportService,
        final ClusterService clusterService,
        final ThreadPool threadPool,
        final ActionFilters actionFilters,
        final IndexNameExpressionResolver indexNameExpressionResolver
    ) {
        super(
            PutAutoscalingPolicyAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            PutAutoscalingPolicyAction.Request::new,
            indexNameExpressionResolver,
            ThreadPool.Names.SAME
        );
    }

    @Override
    protected void masterOperation(
        final PutAutoscalingPolicyAction.Request request,
        final ClusterState state,
        ActionListener<AcknowledgedResponse> listener
    ) {
        clusterService.submitStateUpdateTask(
            "put-autoscaling-policy",
            new AckedClusterStateUpdateTask<AcknowledgedResponse>(request, listener) {

                @Override
                protected AcknowledgedResponse newResponse(final boolean acknowledged) {
                    return AcknowledgedResponse.of(acknowledged);
                }

                @Override
                public ClusterState execute(final ClusterState currentState) {
                    return putAutoscalingPolicy(currentState, request.policy(), logger);
                }

            }
        );
    }

    @Override
    protected ClusterBlockException checkBlock(final PutAutoscalingPolicyAction.Request request, final ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }

    static ClusterState putAutoscalingPolicy(final ClusterState currentState, final AutoscalingPolicy policy, final Logger logger) {
        final ClusterState.Builder builder = ClusterState.builder(currentState);
        final AutoscalingMetadata currentMetadata;
        if (currentState.metadata().custom(AutoscalingMetadata.NAME) != null) {
            currentMetadata = currentState.metadata().custom(AutoscalingMetadata.NAME);
        } else {
            currentMetadata = AutoscalingMetadata.EMPTY;
        }
        final SortedMap<String, AutoscalingPolicyMetadata> newPolicies = new TreeMap<>(currentMetadata.policies());
        final AutoscalingPolicyMetadata newPolicyMetadata = new AutoscalingPolicyMetadata(policy);
        final AutoscalingPolicyMetadata oldPolicyMetadata = newPolicies.put(policy.name(), newPolicyMetadata);
        if (oldPolicyMetadata == null) {
            logger.info("adding autoscaling policy [{}]", policy.name());
        } else if (oldPolicyMetadata.equals(newPolicyMetadata)) {
            logger.info("skipping updating autoscaling policy [{}] due to no change in policy", policy.name());
            return currentState;
        } else {
            logger.info("updating autoscaling policy [{}]", policy.name());
        }
        final AutoscalingMetadata newMetadata = new AutoscalingMetadata(newPolicies);
        builder.metadata(Metadata.builder(currentState.getMetadata()).putCustom(AutoscalingMetadata.NAME, newMetadata).build());
        return builder.build();
    }

}
