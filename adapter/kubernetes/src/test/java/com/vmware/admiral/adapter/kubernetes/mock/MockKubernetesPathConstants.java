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

package com.vmware.admiral.adapter.kubernetes.mock;

import com.vmware.admiral.adapter.kubernetes.ApiUtil;
import com.vmware.admiral.adapter.kubernetes.KubernetesRemoteApiClient;

/**
 * Constants for mock docker host and services
 */
public interface MockKubernetesPathConstants {
    String BASE_PATH = "/mock/kubernetes";
    String BASE_FAILING_PATH = "/mock/failing-kubernetes";
    String PING = KubernetesRemoteApiClient.pingPath;
    String API_V1 = ApiUtil.API_PREFIX_V1;
    String NAMESPACES = API_V1 + "/namespaces";
    String PODS = "/pods";
    String NODES = API_V1 + "/nodes";

    String DASHBOARD_PROXY_FOR_STATS = API_V1 +
            "/proxy/namespaces/kube-system/services/kubernetes-dashboard/api/v1/node/";
}