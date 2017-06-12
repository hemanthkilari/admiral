/*
 * Copyright (c) 2017 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.compute.cluster;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.common.util.QueryUtil;
import com.vmware.admiral.compute.ContainerHostService;
import com.vmware.admiral.compute.ContainerHostService.ContainerHostSpec;
import com.vmware.admiral.compute.ContainerHostUtil;
import com.vmware.admiral.compute.ElasticPlacementZoneConfigurationService;
import com.vmware.admiral.compute.ElasticPlacementZoneConfigurationService.ElasticPlacementZoneConfigurationState;
import com.vmware.admiral.compute.PlacementZoneUtil;
import com.vmware.admiral.compute.container.GroupResourcePlacementService.GroupResourcePlacementState;
import com.vmware.photon.controller.model.adapters.util.Pair;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.photon.controller.model.resources.ResourcePoolService.ResourcePoolState;
import com.vmware.xenon.common.DeferredResult;
import com.vmware.xenon.common.LocalizableValidationException;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.ServiceDocumentQueryResult;
import com.vmware.xenon.common.StatelessService;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;

public class ClusterService extends StatelessService {

    public static final String SELF_LINK = ManagementUriParts.CLUSTERS;

    private static final Pattern PATTERN_CLUSTER_CREATE_LINK = Pattern
            .compile(String.format("^%s\\/?$", SELF_LINK));
    private static final Pattern PATTERN_PROJECT_TENANT_LINK = Pattern
            .compile(String.format("^%s\\/[^\\/]+", ManagementUriParts.PROJECTS));

    public ClusterService() {
        super(ClusterDto.class);
        super.toggleOption(ServiceOption.URI_NAMESPACE_OWNER, true);
    }

    public enum ClusterType {
        DOCKER, VCH
    }

    public enum ClusterStatus {
        ON, OFF, DISABLED, WARNING
    }

    public static class ClusterDto extends ServiceDocument {

        public ClusterDto() {
            nodeLinks = new LinkedList<>();
        }

        /** The name of a given cluster. */
        public String name;

        /** The type of hosts the cluster contains. */
        public ClusterType type;

        /** The status of the cluster. */
        public ClusterStatus status;

        /** (Optional) the address of the VCH cluster. */
        public String address;

        /** The number of containers in the cluster. */
        public int containerCount;

        public long totalMemory;

        public long memoryUsage;

        /** Document links of the {@link ComputeState}s that are part of this cluster */
        public List<String> nodeLinks;

        // TODO do we need that and how do we compute that for docker clusters? (VCH reports this,
        // but docker host doesn't)
        public double totalCpu;

        public double cpuUsage;
    }

    @Override
    public void handlePost(Operation post) {
        if (isCreateClusterPost(post)) {
            createCluster(post);
        } else {
            // TODO add hosts to already existing cluster instead
            post.fail(Operation.STATUS_CODE_NOT_FOUND);
        }
    }

    @Override
    public void handleGet(Operation get) {
        URI elasticPlacementZoneConfigurationUri = UriUtils.buildUri(getHost(),
                ElasticPlacementZoneConfigurationService.SELF_LINK, get.getUri().getQuery());

        sendWithDeferredResult(Operation
                .createGet(UriUtils.buildExpandLinksQueryUri(elasticPlacementZoneConfigurationUri))
                .setReferer(getUri()),
                ServiceDocumentQueryResult.class)
                        .thenCompose(this::getInfoFromHostsWihtinPlacementZone)
                        .thenAccept(clusterDtoList -> {
                            Map<String, ClusterDto> a = clusterDtoList.stream()
                                    .collect(Collectors.toMap(
                                            clusterDto -> clusterDto.documentSelfLink,
                                            Function.identity()));
                            get.setBody(a);
                        })
                        .whenCompleteNotify(get);
        ;
    }

    private boolean isCreateClusterPost(Operation post) {
        return PATTERN_CLUSTER_CREATE_LINK.matcher(post.getUri().getPath()).matches();
    }

    private DeferredResult<List<ClusterDto>> getInfoFromHostsWihtinPlacementZone(
            ServiceDocumentQueryResult queryResult) {
        Map<String, ElasticPlacementZoneConfigurationState> ePZstates = QueryUtil
                .extractQueryResult(
                        queryResult, ElasticPlacementZoneConfigurationState.class);
        List<DeferredResult<ClusterDto>> clusterDtoList = ePZstates.keySet().stream()
                .map(key -> ClusterUtils.getHostsWihtinPlacementZone(
                        ePZstates.get(key).resourcePoolState.documentSelfLink, getHost())
                        .thenApply(computeStates -> {
                            return ClusterUtils.placementZoneAndItsHostsToClusterDto(
                                    ePZstates.get(key).resourcePoolState, computeStates);
                        }))
                .collect(Collectors.toList());
        return DeferredResult.allOf(clusterDtoList);
    }

    private void createCluster(Operation create) {

        try {
            validateCreateClusterPost(create);
        } catch (Throwable ex) {
            logWarning("Failed to verify cluster creation POST: %s", Utils.toString(ex));
            create.fail(ex);
            return;
        }

        // this will contain the IDs of the auto-generated resources
        HashSet<String> generatedResourcesIds = new HashSet<>(2);
        ContainerHostSpec hostSpec = create.getBody(ContainerHostSpec.class);

        generatePlacementZoneAndPlacement(hostSpec, generatedResourcesIds)
                .thenCompose((zoneAndPlacement) -> {
                    return addContainerHost(hostSpec)
                            .thenApply((hostState) -> new Pair<>(zoneAndPlacement.left, hostState));
                })
                .thenAccept((zoneAndHost) -> {
                    LinkedList<ComputeState> a = new LinkedList<ComputeState>();
                    a.add(zoneAndHost.right);
                    create.setBody(
                            ClusterUtils.placementZoneAndItsHostsToClusterDto(zoneAndHost.left, a));
                    create.complete();
                }).exceptionally((ex) -> {
                    create.fail(ex);
                    ContainerHostUtil.cleanupAutogeneratedResources(this, generatedResourcesIds);
                    return null;
                });
    }

    private void validateCreateClusterPost(Operation post) {
        if (!post.hasBody()) {
            throw new LocalizableValidationException(
                    "ContainerHostSpec body is required", "compute.host.spec.is.required");
        }

        ContainerHostSpec hostSpec = post.getBody(ContainerHostSpec.class);
        List<String> hostTenantLinks = hostSpec.getHostTenantLinks();

        if (hostTenantLinks == null || hostTenantLinks.isEmpty()
                || hostTenantLinks.stream().noneMatch(PATTERN_PROJECT_TENANT_LINK.asPredicate())) {
            throw new LocalizableValidationException(
                    "Project context is required", "auth.project.context.required");
        }
    }

    private DeferredResult<ComputeState> addContainerHost(ContainerHostSpec hostSpec) {
        return getHost().sendWithDeferredResult(
                Operation.createPut(getHost(), ContainerHostService.SELF_LINK)
                        .setReferer(getUri())
                        .setBody(hostSpec))
                .thenCompose((op) -> {

                    String computeLink = op.getResponseHeader(Operation.LOCATION_HEADER);
                    return getHost().sendWithDeferredResult(
                            Operation.createGet(getHost(), computeLink).setReferer(getUri()),
                            ComputeState.class);
                });
    }

    /**
     * If needed, automatically generates placement zone and placement for the host specified.
     * Stores the document links of the created documents in the provided set
     */
    private DeferredResult<Pair<ResourcePoolState, GroupResourcePlacementState>> generatePlacementZoneAndPlacement(
            ContainerHostSpec hostSpec, Set<String> generatedResourcesIds) {
        ComputeState hostState = hostSpec.hostState;

        // Honor predefined placement zone if any
        if (ClusterUtils.hasPlacementZone(hostState)) {
            return DeferredResult.completed(null);
        }

        // else, automatically generate placement zone
        return PlacementZoneUtil.generatePlacementZone(getHost(), hostState)
                .thenCompose((generatedZone) -> {
                    // update placement zone in the compute state and generate placement
                    hostState.resourcePoolLink = generatedZone.documentSelfLink;
                    generatedResourcesIds.add(generatedZone.documentSelfLink);
                    return PlacementZoneUtil.generatePlacement(getHost(), generatedZone)
                            .thenApply((generatedPlacement) -> {
                                generatedResourcesIds.add(generatedPlacement.documentSelfLink);
                                return new Pair<>(generatedZone, generatedPlacement);
                            });
                });
    }

}
